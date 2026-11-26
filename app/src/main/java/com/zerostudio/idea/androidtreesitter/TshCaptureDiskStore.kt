package com.zerostudio.idea.androidtreesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.LinkedHashMap

/**
 * Session-scoped cold tier for immutable TSH capture ranges.
 *
 * The in-memory LRU is deliberately small and may evict old viewports. This
 * store keeps already-computed captures on app-private disk so returning to an
 * old viewport restores spans without executing Tree-sitter/Regex again.
 * Entries are discarded on every committed edit because query dependencies
 * are not yet represented by a proven per-range dependency envelope.
 */
internal class TshCaptureDiskStore(
    private val directory: File,
    private val sessionId: Long,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : AutoCloseable {
    internal data class RestoredRange(
        val key: CaptureRangeKey,
        val index: CaptureIntervalIndex
    )

    private data class Entry(val file: File, val bytes: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<CaptureRangeKey, Entry>(16, 0.75f, true)
    private val rangeIndex = CaptureRangeLookupIndex()
    private var bytesUsed = 0L
    private var closed = false

    init {
        require(sessionId >= 0L)
        require(maxBytes > 0L)
        check((directory.isDirectory || directory.mkdirs()) && directory.canWrite()) {
            "Unable to create app-private TSH capture cache directory: $directory"
        }
    }

    fun put(key: CaptureRangeKey, spans: List<WindbellTshEngine.HighlightSpan>) {
        require(key.sessionId == sessionId)
        if (key.startUtf16 >= key.endUtf16) return
        synchronized(lock) {
            if (closed) return
            // Remove the previous index/file before publishing the replacement.
            // Doing this after rename would delete the newly-written file because
            // both generations intentionally use the same deterministic filename.
            removeEntry(key)
            val destination = fileFor(key)
            val temporary = File(directory, destination.name + ".tmp")
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeInt(key.startUtf16)
                    output.writeInt(key.endUtf16)
                    output.writeInt(spans.size)
                    for (span in spans) {
                        output.writeInt(span.startByte)
                        output.writeInt(span.endByte)
                        output.writeInt(span.styleId)
                        output.writeInt(span.fontFlags)
                        output.writeInt(span.priority)
                        output.writeInt(span.declarationOrder)
                        output.writeInt(span.source)
                    }
                    output.flush()
                }
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Unable to replace cached TSH capture page")
                }
                if (!temporary.renameTo(destination)) {
                    throw IllegalStateException("Unable to publish cached TSH capture page")
                }
                val entryBytes = destination.length()
                entries[key] = Entry(destination, entryBytes)
                rangeIndex.add(key)
                bytesUsed += entryBytes
                evictToBudget()
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    /**
     * Restores the smallest gap-free set of old viewport pages covering [key].
     * A partial result is never returned; a missing/corrupt page makes this a
     * cache miss so the caller can safely execute the native pipeline.
     */
    fun restoreCovering(key: CaptureRangeKey): List<RestoredRange>? {
        if (key.sessionId != sessionId || key.startUtf16 >= key.endUtf16) return null
        synchronized(lock) {
            if (closed) return null
            val keys = rangeIndex.findCovering(sessionId, key.startUtf16, key.endUtf16)
                ?: return null
            val restored = ArrayList<RestoredRange>(keys.size)
            for (cachedKey in keys) {
                val entry = entries[cachedKey] ?: run {
                    rangeIndex.remove(cachedKey)
                    return null
                }
                val spans = try {
                    readEntry(entry.file, cachedKey)
                } catch (_: Exception) {
                    removeEntry(cachedKey)
                    return null
                } ?: run {
                    removeEntry(cachedKey)
                    return null
                }
                restored += RestoredRange(cachedKey, CaptureIntervalIndex(spans))
            }
            return restored
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.keys.toList().forEach(::removeEntry)
            bytesUsed = 0L
        }
    }

    private fun readEntry(
        file: File,
        key: CaptureRangeKey
    ): List<WindbellTshEngine.HighlightSpan>? {
        if (!file.isFile || file.length() < HEADER_BYTES) return null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null
            if (input.readInt() != key.startUtf16 || input.readInt() != key.endUtf16) return null
            val count = input.readInt()
            val expectedBytes = HEADER_BYTES.toLong() + count.toLong() * SPAN_BYTES
            if (count < 0 || expectedBytes != file.length()) return null
            val spans = ArrayList<WindbellTshEngine.HighlightSpan>(count)
            repeat(count) {
                spans += WindbellTshEngine.HighlightSpan(
                    startByte = input.readInt(),
                    endByte = input.readInt(),
                    styleId = input.readInt(),
                    fontFlags = input.readInt(),
                    priority = input.readInt(),
                    declarationOrder = input.readInt(),
                    source = input.readInt()
                )
            }
            return spans
        }
    }

    private fun fileFor(key: CaptureRangeKey) =
        File(directory, "${key.startUtf16}-${key.endUtf16}.tshcap")

    private fun removeEntry(key: CaptureRangeKey) {
        val removed = entries.remove(key) ?: return
        bytesUsed = (bytesUsed - removed.bytes).coerceAtLeast(0L)
        rangeIndex.remove(key)
        if (removed.file.exists()) removed.file.delete()
    }

    private fun evictToBudget() {
        val iterator = entries.entries.iterator()
        while (bytesUsed > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            bytesUsed = (bytesUsed - entry.value.bytes).coerceAtLeast(0L)
            rangeIndex.remove(entry.key)
            if (entry.value.file.exists()) entry.value.file.delete()
            iterator.remove()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            clear()
            closed = true
            directory.deleteRecursively()
        }
    }

    val size: Int get() = synchronized(lock) { entries.size }
    val diskBytesUsed: Long get() = synchronized(lock) { bytesUsed }

    private companion object {
        const val MAGIC = 0x54534843 // TSHC
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 20
        const val SPAN_BYTES = 28
        const val DEFAULT_MAX_BYTES = 256L * 1024L * 1024L
    }
}
