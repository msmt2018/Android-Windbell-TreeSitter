package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSRange
import com.zerostudio.idea.treesitter.TSTree

/**
 * Random-access UTF-16LE adapter for immutable editor snapshots and other
 * CharSequences. Each native read copies at most one Tree-sitter input window;
 * the entire document is never flattened into a byte array or direct buffer.
 */
class CharSequenceParseWindowCallback(
    private val source: CharSequence
) : TSParser.ParseWindowCallback {
    private val byteLength = source.length.toLong() * 2L

    override fun read(byteOffset: Long, maxBytes: Int): ByteArray {
        require(byteOffset >= 0L && (byteOffset and 1L) == 0L) {
            "UTF-16LE read offset must be a non-negative even byte offset"
        }
        require(maxBytes >= 2) { "maxBytes must hold at least one UTF-16 code unit" }
        if (byteOffset >= byteLength) {
            if (byteOffset == byteLength) return ByteArray(0)
            throw IndexOutOfBoundsException("byteOffset=$byteOffset, byteLength=$byteLength")
        }

        val start = (byteOffset / 2L).toInt()
        val maxChars = (maxBytes / 2).coerceAtLeast(1).toLong()
        var end = minOf(source.length.toLong(), start.toLong() + maxChars).toInt()

        if (end < source.length && end > start &&
            Character.isHighSurrogate(source[end - 1]) &&
            Character.isLowSurrogate(source[end])) {
            end--
        }
        check(end > start) {
            "maxBytes is too small to return the next UTF-16 surrogate pair"
        }
        val chunk = source.subSequence(start, end)
        // Encode UTF-16 code units directly. String.getBytes may replace an
        // isolated surrogate, while editor buffers must preserve exact code units.
        val bytes = ByteArray(chunk.length * 2)
        for (index in 0 until chunk.length) {
            val codeUnit = chunk[index].code
            bytes[index * 2] = codeUnit.toByte()
            bytes[index * 2 + 1] = (codeUnit ushr 8).toByte()
        }
        return bytes
    }
}

/** Stable random-access source for partitioning immutable editor snapshots. */
class CharSequenceUtf16DocumentSource(
    private val source: CharSequence,
    override val version: Long,
    private val chunkReader: (startUtf16: Int, maxChars: Int) -> CharSequence = { start, count ->
        source.subSequence(start, start + count)
    }
) : Utf16DocumentSource {
    override val length: Long get() = source.length.toLong()

    override fun readChunk(offset: Long, maxChars: Int): CharSequence {
        require(offset >= 0L && maxChars > 0)
        if (offset >= length) return ""
        val start = offset.toInt()
        val count = minOf(maxChars.toLong(), length - offset).toInt()
        return chunkReader(start, count)
    }

    override fun get(offset: Long): Char {
        if (offset < 0L || offset >= source.length.toLong()) {
            throw IndexOutOfBoundsException("offset=$offset, length=${source.length}")
        }
        return source[offset.toInt()]
    }
}

/** Parses a CharSequence snapshot without allocating a document-sized byte buffer. */
fun TSParser.parseWindowed(
    oldTree: TSTree?,
    source: CharSequence,
    includedRanges: Array<TSRange>? = null
): TSTree? {
    val byteLength = source.length.toLong() * 2L
    return parseWindowed(
        oldTree,
        byteLength,
        includedRanges,
        CharSequenceParseWindowCallback(source)
    )
}
