package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            // Use bounded direct segments. The JNI SegmentedTextSource reads them
            // independently; no single direct buffer scales with the document size.
            val segments = ArrayList<ByteBuffer>()
            var offset = 0
            while (offset < source.text.length) {
                var end = minOf(source.text.length, offset + 32 * 1024)
                if (end < source.text.length &&
                    Character.isHighSurrogate(source.text[end - 1]) &&
                    Character.isLowSurrogate(source.text[end])) {
                    end--
                }
                val segment = ByteBuffer.allocateDirect((end - offset) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (index in offset until end) segment.putChar(source.text[index])
                segment.flip()
                segments += segment
                offset = end
            }
            val tree = parser.parseBuffers(null, segments.toTypedArray(), arrayOf(included))
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
