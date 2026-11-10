package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSPoint
import com.zerostudio.idea.treesitter.TSRange
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Random-access UTF-16 document view. Offsets are UTF-16 code units (not bytes).
 * Implementations may be backed by mmap windows, a piece tree, or chunk storage.
 */
interface Utf16DocumentSource {
    val length: Long
    val version: Long
    operator fun get(offset: Long): Char

    /** Reads a bounded sequential window. Implementations with chunk storage should override. */
    fun readChunk(offset: Long, maxChars: Int): CharSequence {
        require(offset >= 0L && maxChars > 0)
        if (offset >= length) return ""
        val count = minOf(maxChars.toLong(), length - offset).toInt()
        return buildString(count) {
            var index = offset
            repeat(count) { append(get(index++)) }
        }
    }
}

data class DocumentPartition(
    val index: Int,
    val start: Long,
    val endExclusive: Long,
    val firstLine: Long,
    val lineCount: Long,
    val version: Long
) {
    init {
        require(index >= 0 && start >= 0 && endExclusive >= start)
        require(firstLine >= 0 && lineCount >= 0)
    }
}

/** Primitive parallel-array index: no per-checkpoint heap objects or references. */
private class CompactPointCheckpointIndex(initialCapacity: Int = 256) {
    private var offsets32 = IntArray(initialCapacity.coerceAtLeast(1))
    private var rows32 = IntArray(initialCapacity.coerceAtLeast(1))
    private var columns32 = IntArray(initialCapacity.coerceAtLeast(1))
    private var offsets64: LongArray? = null
    private var rows64: LongArray? = null
    private var columns64: LongArray? = null
    var size: Int = 0
        private set

    val uses64BitStorage: Boolean get() = offsets64 != null

    fun add(offset: Long, row: Long, column: Long) {
        if (offset < 0L || row < 0L || column < 0L) {
            throw IllegalArgumentException("Checkpoint coordinates must be non-negative")
        }
        if (offsets64 == null &&
            (offset > Int.MAX_VALUE || row > Int.MAX_VALUE || column > Int.MAX_VALUE)) {
            promoteTo64Bit()
        }
        ensureCapacity()
        val wideOffsets = offsets64
        if (wideOffsets != null) {
            wideOffsets[size] = offset
            rows64!![size] = row
            columns64!![size] = column
        } else {
            offsets32[size] = offset.toInt()
            rows32[size] = row.toInt()
            columns32[size] = column.toInt()
        }
        size++
    }

    private fun ensureCapacity() {
        if (size < offsets32.size) return
        val next = if (size < 1024) size * 2 else size + (size ushr 1)
        val capacity = next.coerceAtLeast(size + 1)
        offsets32 = offsets32.copyOf(capacity)
        rows32 = rows32.copyOf(capacity)
        columns32 = columns32.copyOf(capacity)
        offsets64 = offsets64?.copyOf(capacity)
        rows64 = rows64?.copyOf(capacity)
        columns64 = columns64?.copyOf(capacity)
    }

    private fun promoteTo64Bit() {
        offsets64 = LongArray(offsets32.size) { offsets32[it].toLong() }
        rows64 = LongArray(rows32.size) { rows32[it].toLong() }
        columns64 = LongArray(columns32.size) { columns32[it].toLong() }
        offsets32 = IntArray(0)
        rows32 = IntArray(0)
        columns32 = IntArray(0)
    }

    fun floorIndex(offset: Long): Int {
        var low = 0
        var high = size - 1
        var best = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (offsetAt(middle) <= offset) { best = middle; low = middle + 1 }
            else high = middle - 1
        }
        return best
    }

    fun offsetAt(index: Int): Long = offsets64?.get(index)?.toLong() ?: offsets32[index].toLong()
    fun rowAt(index: Int): Long = rows64?.get(index)?.toLong() ?: rows32[index].toLong()
    fun columnAt(index: Int): Long = columns64?.get(index)?.toLong() ?: columns32[index].toLong()
}

/** Growable primitive buffer; avoids boxed Longs during sparse-index construction. */
private class LongArrayBuilder(initialCapacity: Int = 256) {
    private var values = LongArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set
    fun add(value: Long) {
        if (size == values.size) {
            val next = if (size < 1024) size * 2 else size + (size ushr 1)
            values = values.copyOf(next.coerceAtLeast(size + 1))
        }
        values[size++] = value
    }
    fun toArray(): LongArray = values.copyOf(size)
}

data class ViewportPlan(
    val requestedStart: Long,
    val requestedEndExclusive: Long,
    val partitions: List<DocumentPartition>,
    val documentVersion: Long
)

/**
 * Sparse line index + stable partition metadata. It stores one line checkpoint
 * per stride, not one Long per line, so index memory remains bounded.
 *
 * A document edit invalidates the touched partition and the suffix: lexical and
 * parser state may flow across every later boundary. Callers can later narrow
 * this invalidation only when a grammar-provided convergence checkpoint agrees.
 */
class DocumentPartitionManager(
    private val targetPartitionChars: Long = 64L * 1024L,
    private val lineCheckpointStride: Long = 256L,
    private val prefetchPartitions: Int = 1
) {
    private val lock = ReentrantReadWriteLock()
    private var indexedVersion = Long.MIN_VALUE
    private var indexedLength = 0L
    private var partitions = emptyList<DocumentPartition>()
    private var lineCheckpoints = LongArray(0)
    private var pointCheckpoints = CompactPointCheckpointIndex(0)
    val pointCheckpointCount: Int get() = lock.read { pointCheckpoints.size }

    init {
        require(targetPartitionChars >= 256L)
        require(lineCheckpointStride > 0L)
        require(prefetchPartitions >= 0)
    }

    fun rebuild(source: Utf16DocumentSource) = lock.write {
        val starts = LongArrayBuilder()
        starts.add(0L)
        var line = 0L
        var column = 0L
        var nextLineCheckpoint = lineCheckpointStride
        var nextPointCheckpoint = POINT_CHECKPOINT_STRIDE
        var offset = 0L
        val pointIndex = CompactPointCheckpointIndex()
        pointIndex.add(0L, 0L, 0L)
        while (offset < source.length) {
            val chunk = source.readChunk(
                offset,
                minOf(SOURCE_SCAN_CHUNK_CHARS.toLong(), source.length - offset).toInt()
            )
            require(chunk.isNotEmpty() && chunk.length <= SOURCE_SCAN_CHUNK_CHARS) {
                "Utf16DocumentSource.readChunk must return a non-empty bounded window before EOF"
            }
            for (local in chunk.indices) {
                val ch = chunk[local]
                if (ch == '\n') {
                    line++
                    column = 0L
                    if (line == nextLineCheckpoint) {
                        starts.add(offset + local + 1L)
                        nextLineCheckpoint += lineCheckpointStride
                    }
                } else {
                    column++
                }
                val absolute = offset + local + 1L
                if (absolute >= nextPointCheckpoint) {
                    pointIndex.add(absolute, line, column)
                    nextPointCheckpoint = absolute + POINT_CHECKPOINT_STRIDE
                }
            }
            offset += chunk.length
        }
        val checkpoints = starts.toArray()

        val built = ArrayList<DocumentPartition>()
        var start = 0L
        var firstLine = 0L
        while (start < source.length) {
            val nominal = minOf(source.length, safeAdd(start, targetPartitionChars))
            var end = nominal
            // Prefer a nearby line boundary, but scan through bounded source windows
            // instead of repeatedly indexing a piece-tree snapshot character by character.
            val scanLimit = minOf(source.length, safeAdd(nominal, targetPartitionChars))
            var searchOffset = (nominal - 1L).coerceAtLeast(start)
            var foundLineEnd: Long? = null
            while (searchOffset < scanLimit) {
                val searchChunk = source.readChunk(
                    searchOffset,
                    minOf(SOURCE_SCAN_CHUNK_CHARS.toLong(), scanLimit - searchOffset).toInt()
                )
                require(searchChunk.isNotEmpty() && searchChunk.length <= scanLimit - searchOffset) {
                    "Utf16DocumentSource.readChunk returned an invalid partition-boundary window"
                }
                val newline = searchChunk.indexOf('\n')
                if (newline >= 0) {
                    foundLineEnd = searchOffset + newline + 1L
                    break
                }
                searchOffset += searchChunk.length
            }
            if (foundLineEnd != null) {
                end = foundLineEnd
            } else {
                // A long line: retain a hard size bound and split only at a
                // Unicode scalar boundary. Surrogate pairs are never separated.
                end = nominal.coerceAtLeast(start + 1L)
            }
            if (end < source.length && end > 0L &&
                Character.isHighSurrogate(source[end - 1L]) && Character.isLowSurrogate(source[end])) {
                end++
            }
            if (end <= start) end = minOf(source.length, start + targetPartitionChars)
            val endLine = countNewlines(source, start, end)
            built.add(DocumentPartition(
                index = built.size,
                start = start,
                endExclusive = end,
                firstLine = firstLine,
                lineCount = endLine + if (end > start && source[end - 1L] != '\n') 1L else 0L,
                version = source.version
            ))
            firstLine += endLine + if (end > start && source[end - 1L] != '\n') 1L else 0L
            start = end
        }
        if (source.length == 0L) {
            built.add(DocumentPartition(0, 0L, 0L, 0L, 0L, source.version))
        }
        partitions = built
        lineCheckpoints = checkpoints
        pointCheckpoints = pointIndex
        indexedLength = source.length
        indexedVersion = source.version
    }

    fun snapshot(): List<DocumentPartition> = lock.read { partitions.toList() }

    /** Returns the partition containing a UTF-16 document offset. */
    fun partitionAt(source: Utf16DocumentSource, offset: Long): DocumentPartition {
        require(offset >= 0L && offset <= source.length)
        ensureIndexed(source)
        return lock.read { partitions[findPartition(offset)] }
    }

    fun planViewport(source: Utf16DocumentSource, start: Long, endExclusive: Long): ViewportPlan {
        require(start >= 0L && endExclusive >= start && endExclusive <= source.length)
        ensureIndexed(source)
        return lock.read {
            val first = findPartition(start)
            val last = findPartition(if (endExclusive == start) start else endExclusive - 1L)
            val from = (first - prefetchPartitions).coerceAtLeast(0)
            val through = (last + prefetchPartitions).coerceAtMost(partitions.lastIndex)
            ViewportPlan(start, endExclusive, partitions.subList(from, through + 1).toList(), indexedVersion)
        }
    }

    /**
     * Builds a Tree-sitter range with absolute UTF-16LE byte offsets and byte-based
     * TSPoint columns. Fixed-stride offset checkpoints also bound the scan for
     * extremely long single-line files, where line-only checkpoints are insufficient.
     */
    fun treeSitterRange(source: Utf16DocumentSource, startUtf16: Long, endUtf16: Long): TSRange {
        require(startUtf16 >= 0L && endUtf16 >= startUtf16 && endUtf16 <= source.length)
        require(startUtf16 <= Int.MAX_VALUE / 2L && endUtf16 <= Int.MAX_VALUE / 2L) {
            "TSRange uses signed 32-bit byte offsets in this binding"
        }
        require(isScalarBoundary(source, startUtf16) && isScalarBoundary(source, endUtf16)) {
            "Tree-sitter ranges must not split a UTF-16 surrogate pair"
        }
        ensureIndexed(source)
        val startPoint = pointAt(source, startUtf16)
        val endPoint = pointAt(source, endUtf16)
        return TSRange.create(
            (startUtf16 * 2L).toInt(),
            (endUtf16 * 2L).toInt(),
            startPoint,
            endPoint
        )
    }

    private fun pointAt(source: Utf16DocumentSource, offset: Long): TSPoint {
        var index = 0L
        var row = 0L
        var column = 0L
        lock.read {
            val best = pointCheckpoints.floorIndex(offset)
            index = pointCheckpoints.offsetAt(best)
            row = pointCheckpoints.rowAt(best)
            column = pointCheckpoints.columnAt(best)
        }
        while (index < offset) {
            val chunk = source.readChunk(
                index,
                minOf(SOURCE_SCAN_CHUNK_CHARS.toLong(), offset - index).toInt()
            )
            require(chunk.isNotEmpty() && chunk.length <= offset - index) {
                "Utf16DocumentSource.readChunk returned an invalid point-lookup window"
            }
            for (ch in chunk) {
                if (ch == '\n') {
                    row++
                    column = 0L
                } else {
                    column++
                }
                index++
            }
        }
        require(row <= Int.MAX_VALUE && column * 2L <= Int.MAX_VALUE) {
            "Tree-sitter TSPoint exceeds signed 32-bit coordinates"
        }
        return TSPoint.create(row.toInt(), (column * 2L).toInt())
    }

    private fun isScalarBoundary(source: Utf16DocumentSource, offset: Long): Boolean {
        if (offset <= 0L || offset >= source.length) return true
        return !(Character.isHighSurrogate(source[offset - 1L]) &&
            Character.isLowSurrogate(source[offset]))
    }

    /** Returns the nearest sparse line checkpoint at or before [line]. */
    fun lineCheckpointAtOrBefore(line: Long): Long {
        require(line >= 0L)
        return lock.read {
            if (lineCheckpoints.isEmpty()) return@read 0L
            val index = (line / lineCheckpointStride).coerceAtMost(lineCheckpoints.lastIndex.toLong()).toInt()
            lineCheckpoints[index]
        }
    }

    /**
     * Conservative edit invalidation. Returns the first partition that must be
     * reparsed; every later partition is stale until state convergence is proven.
     */
    fun invalidateEdit(oldStart: Long, oldEndExclusive: Long, newLength: Long): Int = lock.write {
        require(oldStart >= 0L && oldEndExclusive >= oldStart && newLength >= 0L)
        val index = findPartition(oldStart.coerceAtMost(indexedLength))
        val prefix = partitions.take(index)
        partitions = prefix
        indexedVersion = Long.MIN_VALUE
        indexedLength = -1L
        index
    }

    private fun ensureIndexed(source: Utf16DocumentSource) {
        val current = lock.read { indexedVersion == source.version && indexedLength == source.length }
        if (!current) rebuild(source)
    }

    private fun findPartition(offset: Long): Int {
        if (partitions.isEmpty()) return 0
        var low = 0
        var high = partitions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val part = partitions[mid]
            if (offset < part.start) high = mid - 1
            else if (offset >= part.endExclusive && mid != partitions.lastIndex) low = mid + 1
            else return mid
        }
        return low.coerceIn(0, partitions.lastIndex)
    }

    private companion object {
        const val POINT_CHECKPOINT_STRIDE = 64L * 1024L
        const val SOURCE_SCAN_CHUNK_CHARS = 64 * 1024
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private fun countNewlines(source: Utf16DocumentSource, start: Long, end: Long): Long {
        var count = 0L
        var i = start
        while (i < end) {
            val chunk = source.readChunk(
                i,
                minOf(SOURCE_SCAN_CHUNK_CHARS.toLong(), end - i).toInt()
            )
            require(chunk.isNotEmpty() && chunk.length <= end - i) {
                "Utf16DocumentSource.readChunk returned an invalid partition window"
            }
            count += chunk.count { it == '\n' }
            i += chunk.length
        }
        return count
    }
}

/** LRU cache with an explicit byte budget and deterministic close-on-eviction. */
class ByteBudgetLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val weigh: (V) -> Long,
    private val onEvict: (V) -> Unit = {}
) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var usedBytes = 0L
    init { require(maxBytes >= 0L) }

    val size: Int get() = lock.read { entries.size }
    val bytesUsed: Long get() = lock.read { usedBytes }

    fun get(key: K): V? = lock.write { entries[key] }

    fun put(key: K, value: V) = lock.write {
        val weight = weigh(value).coerceAtLeast(0L)
        val previous = entries.remove(key)
        if (previous != null) {
            usedBytes -= weigh(previous).coerceAtLeast(0L)
            if (previous !== value) onEvict(previous)
        }
        if (weight > maxBytes) {
            onEvict(value)
            return@write
        }
        entries[key] = value
        usedBytes += weight
        val iterator = entries.entries.iterator()
        while (usedBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next().value
            usedBytes -= weigh(eldest).coerceAtLeast(0L)
            iterator.remove()
            onEvict(eldest)
        }
    }

    fun remove(key: K): V? = lock.write {
        val value = entries.remove(key) ?: return@write null
        usedBytes -= weigh(value).coerceAtLeast(0L)
        value
    }

    fun invalidate(predicate: (K, V) -> Boolean) = lock.write {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key, entry.value)) {
                usedBytes -= weigh(entry.value).coerceAtLeast(0L)
                iterator.remove()
                onEvict(entry.value)
            }
        }
    }

    /** Compatibility helpers for callers using the original weighted-cache API. */
    fun weight(): Long = bytesUsed

    fun removeIf(predicate: (K) -> Boolean) = invalidate { key, _ -> predicate(key) }

    override fun close() = lock.write {
        entries.values.forEach(onEvict)
        entries.clear()
        usedBytes = 0L
    }
}

/** A cache identity must include every input capable of changing captures. */
data class CaptureCacheKey(
    val documentVersion: Long,
    val partitionIndex: Int,
    val startUtf16: Long,
    val endUtf16: Long,
    val grammarRevision: Long,
    val themeRevision: Long
)

data class TokenizerCheckpoint(
    val documentVersion: Long,
    val line: Long,
    val utf16Offset: Long,
    val stateStack: List<String>
) {
    init { require(documentVersion >= 0L && line >= 0L && utf16Offset >= 0L) }
}

/**
 * Per-line Monarch continuation snapshots. A checkpoint is reusable only for
 * the exact document version; edits must invalidate from the preceding checkpoint.
 */
class TokenizerCheckpointStore(private val maxEntries: Int = 4096) {
    private val entries = object : LinkedHashMap<Long, TokenizerCheckpoint>(16, 0.75f, true) {}
    init { require(maxEntries > 0) }

    @Synchronized fun put(checkpoint: TokenizerCheckpoint) {
        entries[checkpoint.line] = checkpoint
        while (entries.size > maxEntries) entries.remove(entries.entries.iterator().next().key)
    }

    @Synchronized fun nearest(line: Long, documentVersion: Long): TokenizerCheckpoint? {
        // Avoid allocating a filtered intermediate list on every checkpoint lookup.
        var best: TokenizerCheckpoint? = null
        for (checkpoint in entries.values) {
            if (checkpoint.line <= line && checkpoint.documentVersion == documentVersion &&
                (best == null || checkpoint.line > best.line)) best = checkpoint
        }
        return best
    }

    @Synchronized fun invalidateFrom(line: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().key >= line) iterator.remove()
    }

    @Synchronized fun clear() = entries.clear()
}
