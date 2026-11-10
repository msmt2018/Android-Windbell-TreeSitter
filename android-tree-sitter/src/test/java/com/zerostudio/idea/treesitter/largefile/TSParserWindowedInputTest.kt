package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TSParserWindowedInputTest {

    @Test fun windowedCallbackKeepsUtf16SurrogatePairsInsideChunkBoundaries() {
        val callback = CharSequenceParseWindowCallback("aaaaa😀b")
        val first = callback.read(0L, 12)
        assertEquals("aaaaa", String(first, Charsets.UTF_16LE))
        val second = callback.read(first.size.toLong(), 12)
        assertEquals("😀b", String(second, Charsets.UTF_16LE))
    }

    @Test fun windowedReaderHandlesThreeHundredMegabyteVirtualSourceWithoutFlattening() {
        val virtual = object : CharSequence {
            override val length = 300 * 1024 * 1024
            override fun get(index: Int): Char {
                if (index !in 0 until length) throw IndexOutOfBoundsException()
                return 'x'
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                require(startIndex >= 0 && endIndex >= startIndex && endIndex <= length)
                return "x".repeat(endIndex - startIndex)
            }
        }
        val source = CharSequenceUtf16DocumentSource(virtual, 42L)
        assertEquals(300L * 1024L * 1024L, source.length)
        assertEquals('x', source[source.length - 1L])

        val callback = CharSequenceParseWindowCallback(virtual)
        val lastHundredChars = callback.read((virtual.length - 100L) * 2L, 64 * 1024)
        assertEquals(200, lastHundredChars.size)
        assertEquals("x".repeat(100), String(lastHundredChars, Charsets.UTF_16LE))
    }

    @Test fun windowedReaderPreservesUnpairedUtf16CodeUnits() {
        val bytes = CharSequenceParseWindowCallback("a\uD800b").read(0L, 64)
        val codeUnit = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        assertEquals(0xD800, codeUnit)
    }

    @Test fun windowedCallbackUsesPieceTreeChunkReaderWithoutFlattening() {
        val text = "class A { val emoji = \"😀\" }"
        var reads = 0
        val source = object : CharSequence {
            override val length: Int get() = text.length
            override fun get(index: Int): Char = text[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                error("source.subSequence must not be called")
        }
        val callback = CharSequenceParseWindowCallback(source) { start, count ->
            reads++
            text.substring(start, minOf(text.length, start + count))
        }
        assertEquals(text, String(callback.read(0L, 64), Charsets.UTF_16LE))
        assertEquals(1, reads)
    }

    @Test fun windowedNativeParserConsumesBoundedUtf16ChunksWithoutAstErrors() {
        val text = ("class A { int value; String emoji = \"😀\"; }\n").repeat(12000)
        val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
        var maxRead = 0
        val callback = CharSequenceParseWindowCallback(text) { start, maxChars ->
            val end = minOf(text.length, start + maxChars)
            val chunkEnd = if (end < text.length && end > start &&
                Character.isHighSurrogate(text[end - 1]) &&
                Character.isLowSurrogate(text[end])) end - 1 else end
            text.substring(start, chunkEnd).also {
                maxRead = maxOf(maxRead, it.length * 2)
            }
        }
        try {
            val tree = parser.parseWindowed(null, text.length.toLong() * 2L, emptyArray(), callback)
            assertNotNull(tree)
            assertTrue(maxRead <= 64 * 1024)
            assertFalse(tree!!.rootNode.hasErrors(),
                "A valid windowed full-document parse must not introduce ERROR/MISSING nodes")
            tree.close()
        } finally {
            parser.close()
        }
    }


    @Test fun largeStringAndByteArrayEntryPointsKeepOneCompleteAstAcrossReparse() {
        val declarationCount = 30_000
        val source = buildString(declarationCount * 24) {
            repeat(declarationCount) { index ->
                append("class DenseType")
                append(index)
                append(" {}\n")
            }
        }
        assertTrue(source.length.toLong() * 2L >= 1024L * 1024L)

        val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
        var first: com.zerostudio.idea.treesitter.TSTree? = null
        var reparsed: com.zerostudio.idea.treesitter.TSTree? = null
        var fromBytes: com.zerostudio.idea.treesitter.TSTree? = null
        try {
            first = parser.parseString(source)
            assertNotNull(first)
            assertFalse(first!!.rootNode.hasErrors(),
                "Large String parse must retain a valid complete ATS tree")

            reparsed = parser.parseString(first, source)
            assertNotNull(reparsed)
            assertFalse(reparsed!!.rootNode.hasErrors(),
                "Incremental String reparse must not damage the existing ATS tree")

            val wrapped = ("x" + source + "y").toByteArray(Charsets.UTF_16LE)
            fromBytes = parser.parseBytes(wrapped, 2, source.length * 2)
            assertNotNull(fromBytes)
            assertFalse(fromBytes!!.rootNode.hasErrors(),
                "Large UTF-16LE byte-slice parse must preserve all syntax")
        } finally {
            fromBytes?.close()
            reparsed?.close()
            first?.close()
            parser.close()
        }
    }

    @Test fun windowedCallbackJoinsSurrogatePairSplitAcrossPieceTreePieces() {
        val source = "😀x"
        val callback = CharSequenceParseWindowCallback(source) { start, _ ->
            source.substring(start, start + 1)
        }
        val bytes = callback.read(0L, 64)
        assertEquals("😀", String(bytes, Charsets.UTF_16LE))
        assertEquals("x", String(callback.read(bytes.size.toLong(), 64), Charsets.UTF_16LE))
    }

    @Test fun windowedCallbackRefusesToSplitPairWhenOnlyOneCodeUnitFits() {
        val callback = CharSequenceParseWindowCallback("😀") { start, _ ->
            "😀".substring(start, start + 1)
        }
        assertThrows(IllegalStateException::class.java) { callback.read(0L, 2) }
    }
}
