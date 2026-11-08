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
    private val source: CharSequence,
    private val chunkReader: (startUtf16: Int, maxChars: Int) -> CharSequence = { start, count ->
        source.subSequence(start, minOf(source.length, start + count))
    }
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
        val maxChars = (maxBytes / 2).coerceAtLeast(1)
        val chunk = chunkReader(start, maxChars)
        require(chunk.isNotEmpty() && chunk.length <= maxChars &&
            start.toLong() + chunk.length <= source.length.toLong()) {
            "chunkReader must return a non-empty bounded window before EOF"
        }
        var safeLength = chunk.length
        val nextOffset = start + safeLength
        val pairCrossesPieceBoundary = nextOffset < source.length &&
            Character.isHighSurrogate(chunk[safeLength - 1]) &&
            Character.isLowSurrogate(source[nextOffset])
        if (pairCrossesPieceBoundary && safeLength > 1) safeLength--

        // A piece-tree piece boundary may itself sit between a surrogate pair.
        // In that case the reader can return just the high surrogate; join the
        // next code unit into this bounded window instead of returning zero bytes.
        val joinedPair = pairCrossesPieceBoundary && safeLength == 1 &&
            chunk.length == 1 && maxChars >= 2
        val outputLength = if (joinedPair) 2 else safeLength
        check(outputLength > 0) {
            "The requested window is too small to keep a UTF-16 surrogate pair together"
        }
        val output = if (joinedPair) charArrayOf(chunk[0], source[nextOffset]).concatToString() else chunk
        // Encode UTF-16 code units directly. String.getBytes may replace an
        // isolated surrogate, while editor buffers must preserve exact code units.
        val bytes = ByteArray(outputLength * 2)
        for (index in 0 until outputLength) {
            val codeUnit = output[index].code
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
    includedRanges: Array<TSRange>? = null,
    chunkReader: ((startUtf16: Int, maxChars: Int) -> CharSequence)? = null
): TSTree? {
    val byteLength = source.length.toLong() * 2L
    return parseWindowed(
        oldTree,
        byteLength,
        includedRanges,
        if (chunkReader == null) CharSequenceParseWindowCallback(source)
        else CharSequenceParseWindowCallback(source, chunkReader)
    )
}
