package com.zerostudio.idea.androidtreesitter

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.zerostudio.idea.treesitter.tsh.TshAlphaCharacterStyle
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.LinkedHashMap

/**
 * Cold tier for final immutable Android line styles.
 *
 * It stores only the supported CharacterStyle records emitted by TSH. A hit
 * reconstructs the immutable SpannedString without invoking Regex, capture
 * lookup, palette resolution, or the renderer's style-application loop.
 */
internal class TshRenderedLineDiskStore(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : AutoCloseable {
    private data class Entry(val file: File, val bytes: Long)
    private data class SpanRecord(
        val type: Int,
        val start: Int,
        val end: Int,
        val flags: Int,
        val value: Int
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytesUsed = 0L
    private var closed = false

    init {
        require(maxBytes > 0L)
        if (directory.exists()) {
            check(directory.deleteRecursively()) {
                "Unable to clear stale TSH rendered-line cache directory: $directory"
            }
        }
        check((directory.isDirectory || directory.mkdirs()) && directory.canWrite()) {
            "Unable to create app-private TSH rendered-line cache directory: $directory"
        }
    }

    fun get(
        documentRevision: Long,
        lineStartOffset: Int,
        visibleStart: Int,
        visibleEnd: Int,
        scheme: String,
        dark: Boolean,
        text: String
    ): SpannedString? = synchronized(lock) {
        if (closed) return@synchronized null
        val key = fileKey(documentRevision, lineStartOffset, visibleStart, visibleEnd, scheme, dark)
        val entry = entries[key] ?: return@synchronized null
        try {
            read(entry.file, documentRevision, lineStartOffset, visibleStart, visibleEnd, scheme, dark, text)
                ?: run {
                    remove(key)
                    null
                }
        } catch (_: Exception) {
            remove(key)
            null
        }
    }

    fun contains(
        documentRevision: Long,
        lineStartOffset: Int,
        visibleStart: Int,
        visibleEnd: Int,
        scheme: String,
        dark: Boolean
    ): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val key = fileKey(
            documentRevision, lineStartOffset, visibleStart, visibleEnd, scheme, dark
        )
        entries[key] != null
    }

    fun put(
        documentRevision: Long,
        lineStartOffset: Int,
        visibleStart: Int,
        visibleEnd: Int,
        scheme: String,
        dark: Boolean,
        text: String,
        styled: SpannedString
    ) {
        synchronized(lock) {
            if (closed || visibleStart < 0 || visibleEnd > text.length || visibleEnd <= visibleStart) return
            val key = fileKey(documentRevision, lineStartOffset, visibleStart, visibleEnd, scheme, dark)
            val records = encodeSpans(styled) ?: return
            remove(key)
            val destination = File(directory, "$key.tshr")
            val temporary = File(directory, "$key.tmp")
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeLong(documentRevision)
                    output.writeInt(lineStartOffset)
                    output.writeInt(visibleStart)
                    output.writeInt(visibleEnd)
                    output.writeBoolean(dark)
                    output.writeUTF(scheme)
                    output.writeInt(text.length)
                    output.writeInt(records.size)
                    for (record in records) {
                        output.writeByte(record.type)
                        output.writeInt(record.start)
                        output.writeInt(record.end)
                        output.writeInt(record.flags)
                        output.writeInt(record.value)
                    }
                }
                if (destination.exists() && !destination.delete()) return
                if (!temporary.renameTo(destination)) return
                val size = destination.length()
                if (size > maxBytes) {
                    destination.delete()
                    return
                }
                entries[key] = Entry(destination, size)
                bytesUsed += size
                evictToBudget()
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun encodeSpans(styled: SpannedString): List<SpanRecord>? {
        val spans = styled.getSpans(0, styled.length, CharacterStyle::class.java)
        val records = ArrayList<SpanRecord>(spans.size)
        for (span in spans) {
            val start = styled.getSpanStart(span)
            val end = styled.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val flags = styled.getSpanFlags(span)
            val record = when (span) {
                is ForegroundColorSpan -> SpanRecord(TYPE_FOREGROUND, start, end, flags, span.foregroundColor)
                is BackgroundColorSpan -> SpanRecord(TYPE_BACKGROUND, start, end, flags, span.backgroundColor)
                is StyleSpan -> SpanRecord(TYPE_STYLE, start, end, flags, span.style)
                is UnderlineSpan -> SpanRecord(TYPE_UNDERLINE, start, end, flags, 0)
                is StrikethroughSpan -> SpanRecord(TYPE_STRIKE, start, end, flags, 0)
                is TshAlphaCharacterStyle -> SpanRecord(TYPE_ALPHA, start, end, flags, span.alpha)
                else -> return null
            }
            records += record
        }
        return records
    }

    private fun read(
        file: File,
        revision: Long,
        lineStart: Int,
        visibleStart: Int,
        visibleEnd: Int,
        scheme: String,
        dark: Boolean,
        text: String
    ): SpannedString? {
        if (!file.isFile || file.length() < MIN_FILE_BYTES) return null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null
            if (input.readLong() != revision || input.readInt() != lineStart ||
                input.readInt() != visibleStart || input.readInt() != visibleEnd ||
                input.readBoolean() != dark || input.readUTF() != scheme ||
                input.readInt() != text.length) return null
            val count = input.readInt()
            if (count < 0 || count.toLong() > file.length() / RECORD_BYTES) return null
            val styled = SpannableStringBuilder(text)
            repeat(count) {
                val type = input.readUnsignedByte()
                val start = input.readInt()
                val end = input.readInt()
                val flags = input.readInt()
                val value = input.readInt()
                if (start < 0 || end <= start || end > text.length) return null
                val span: CharacterStyle = when (type) {
                    TYPE_FOREGROUND -> ForegroundColorSpan(value)
                    TYPE_BACKGROUND -> BackgroundColorSpan(value)
                    TYPE_STYLE -> StyleSpan(value)
                    TYPE_UNDERLINE -> UnderlineSpan()
                    TYPE_STRIKE -> StrikethroughSpan()
                    TYPE_ALPHA -> TshAlphaCharacterStyle(value)
                    else -> return null
                }
                styled.setSpan(span, start, end, flags)
            }
            return SpannedString(styled)
        }
    }

    private fun fileKey(
        revision: Long,
        lineStart: Int,
        visibleStart: Int,
        visibleEnd: Int,
        scheme: String,
        dark: Boolean
    ): String = "r${revision}_l${lineStart}_s${visibleStart}_e${visibleEnd}_p${scheme.hashCode()}_d${if (dark) 1 else 0}"

    private fun remove(key: String) {
        val removed = entries.remove(key) ?: return
        bytesUsed = (bytesUsed - removed.bytes).coerceAtLeast(0L)
        if (removed.file.exists()) removed.file.delete()
    }

    private fun evictToBudget() {
        val iterator = entries.entries.iterator()
        while (bytesUsed > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            bytesUsed = (bytesUsed - entry.value.bytes).coerceAtLeast(0L)
            if (entry.value.file.exists()) entry.value.file.delete()
            iterator.remove()
        }
    }

    fun clear() = synchronized(lock) {
        entries.keys.toList().forEach(::remove)
        bytesUsed = 0L
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            clear()
            closed = true
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val MAGIC = 0x54534852 // TSHR
        const val FORMAT_VERSION = 1
        const val MIN_FILE_BYTES = 30L
        const val RECORD_BYTES = 17L
        const val DEFAULT_MAX_BYTES = 128L * 1024L * 1024L
        const val TYPE_FOREGROUND = 1
        const val TYPE_BACKGROUND = 2
        const val TYPE_STYLE = 3
        const val TYPE_UNDERLINE = 4
        const val TYPE_STRIKE = 5
        const val TYPE_ALPHA = 6
    }
}
