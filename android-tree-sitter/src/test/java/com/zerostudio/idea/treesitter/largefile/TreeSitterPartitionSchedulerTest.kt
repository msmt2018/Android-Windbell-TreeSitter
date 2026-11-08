package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TreeSitterPartitionSchedulerTest {
    private class Source(val text: String, override val version: Long = 7L) : Utf16DocumentSource {
        override val length = text.length.toLong()
        override fun get(offset: Long) = text[offset.toInt()]
    }

    @Test fun viewportRequestsAreScheduledAndCached() {
        val source = Source(("class A { int value; }\n").repeat(40))
        val manager = DocumentPartitionManager(256, prefetchPartitions = 1)
        val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
        val cache = PartitionAstCache(1024 * 1024) { length -> maxOf(1024L, length * 8L) }
        val scheduler = TreeSitterPartitionScheduler(manager, cache, contextChars = 32)
        val latch = CountDownLatch(1)
        val requests = scheduler.requestViewport(source, 300, 500, 1) { request ->
            val included = manager.treeSitterRange(
                source, request.contextStartUtf16, request.contextEndUtf16
            )
            val tree = parser.parseWindowed(null, source.text, arrayOf(included))
            latch.countDown()
            tree
        }
        assertTrue(requests.isNotEmpty())
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.size == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        // At least one tree has been admitted; the scheduler may still be filling prefetch.
        assertTrue(cache.size > 0)
        scheduler.close()
        parser.close()
    }
    @Test fun windowedCallbackKeepsUtf16SurrogatePairsInsideChunkBoundaries() {
        val text = "aaaaa😀b"
        val callback = CharSequenceParseWindowCallback(text)
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
        val callback = CharSequenceParseWindowCallback("a\uD800b")
        val bytes = callback.read(0L, 64)
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
        val bytes = callback.read(0L, 64)
        assertEquals(text, String(bytes, Charsets.UTF_16LE))
        assertEquals(1, reads)
    }

    @Test fun windowedNativeParserConsumesBoundedUtf16Chunks() {
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
        val tree = parser.parseWindowed(null, text.length.toLong() * 2L, emptyArray(), callback)
        assertNotNull(tree)
        assertTrue(maxRead <= 64 * 1024)
        tree?.close()
        parser.close()
    }

}
