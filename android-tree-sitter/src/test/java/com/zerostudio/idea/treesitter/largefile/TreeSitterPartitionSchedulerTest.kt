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
            parser.includedRanges = listOf(included)
            // Stream bounded UTF-16LE windows instead of allocating a direct buffer
            // proportional to the entire document (which defeats large-file mode).
            val tree = parser.parse(null, readCallback = { byteOffset, _ ->
                val start = (byteOffset.toLong() / 2L).coerceAtMost(source.length)
                if (start >= source.length) {
                    ByteArray(0)
                } else {
                    var end = minOf(source.length, start + 4096L)
                    if (end < source.length &&
                        Character.isHighSurrogate(source.text[(end - 1L).toInt()]) &&
                        Character.isLowSurrogate(source.text[end.toInt()])) {
                        end--
                    }
                    source.text.substring(start.toInt(), end.toInt()).toByteArray(Charsets.UTF_16LE)
                }
            })
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
}
