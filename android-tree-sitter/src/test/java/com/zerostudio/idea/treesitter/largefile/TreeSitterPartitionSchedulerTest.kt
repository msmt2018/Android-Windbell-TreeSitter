package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun partitionContextExpandsAcrossVeryLongLinesWhenNoNewlineIsFound() {
        val source = Source("x".repeat(2_000))
        val manager = DocumentPartitionManager(256, prefetchPartitions = 0)
        val cache = PartitionAstCache(1024) { 1L }
        val scheduler = TreeSitterPartitionScheduler(manager, cache, contextChars = 32)
        val completed = CountDownLatch(1)
        var captured: PartitionParseRequest? = null

        val keys = scheduler.requestViewport(source, 400, 450, 1) { request ->
            captured = request
            completed.countDown()
            null
        }

        assertTrue(keys.isNotEmpty())
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(0L, captured?.contextStartUtf16)
        assertEquals(source.length, captured?.contextEndUtf16)
        scheduler.close()
    }

    @Test fun cancelledViewportPlanningDoesNotLeaveUnsubmittedTasksPending() {
        val text = ("line\n").repeat(600)
        val cancel = AtomicBoolean(true)
        val reads = AtomicInteger()
        val source = object : Utf16DocumentSource {
            override val length = text.length.toLong()
            override val version = 81L
            override fun get(offset: Long) = text[offset.toInt()]
            override fun readChunk(offset: Long, maxChars: Int): CharSequence {
                if (cancel.get() && reads.incrementAndGet() >= 2) {
                    throw CancellationException("cancel viewport planning")
                }
                if (offset >= length) return ""
                return text.substring(offset.toInt(), minOf(text.length, offset.toInt() + maxChars))
            }
        }
        val manager = DocumentPartitionManager(256, prefetchPartitions = 0)
        val cache = PartitionAstCache(1024) { 1L }
        val scheduler = TreeSitterPartitionScheduler(manager, cache, contextChars = 8)

        assertTrue(scheduler.requestViewport(source, 100, 700, 1) { null }.isEmpty())

        cancel.set(false)
        reads.set(0)
        val parsed = CountDownLatch(1)
        val keys = scheduler.requestViewport(source, 100, 700, 1) {
            parsed.countDown()
            null
        }
        assertTrue(keys.isNotEmpty())
        assertTrue(parsed.await(2, TimeUnit.SECONDS), "rolled-back tasks must be schedulable again")
        scheduler.close()
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

    @Test fun windowedCallbackJoinsSurrogatePairSplitAcrossPieceTreePieces() {
        val text = "😀x"
        val source = text
        val callback = CharSequenceParseWindowCallback(source) { start, _ ->
            source.substring(start, start + 1)
        }
        val bytes = callback.read(0L, 64)
        assertEquals("😀", String(bytes, Charsets.UTF_16LE))
        val rest = callback.read(bytes.size.toLong(), 64)
        assertEquals("x", String(rest, Charsets.UTF_16LE))
    }

    @Test fun windowedCallbackRefusesToSplitPairWhenOnlyOneCodeUnitFits() {
        val callback = CharSequenceParseWindowCallback("😀") { start, _ ->
            "😀".substring(start, start + 1)
        }
        assertThrows(IllegalStateException::class.java) { callback.read(0L, 2) }
    }


    @Test fun supersededSourceReadIsSilentAndDoesNotQueuePartialViewportWork() {
        var cancelReads = false
        val source = object : Utf16DocumentSource {
            private val text = ("line\n").repeat(300)
            override val length = text.length.toLong()
            override val version = 19L
            override fun get(offset: Long) = text[offset.toInt()]
            override fun readChunk(offset: Long, maxChars: Int): CharSequence {
                if (cancelReads) throw java.util.concurrent.CancellationException(
                    "Large-file partition indexing superseded"
                )
                if (offset >= length) return ""
                return text.substring(offset.toInt(), minOf(text.length, offset.toInt() + maxChars))
            }
        }
        val manager = DocumentPartitionManager(256, prefetchPartitions = 0)
        manager.rebuild(source)
        val cache = PartitionAstCache(1024) { 1L }
        var failures = 0
        val scheduler = TreeSitterPartitionScheduler(
            manager, cache, contextChars = 32,
            onFailure = { _, _ -> failures++ }
        )
        cancelReads = true
        val requests = scheduler.requestViewport(source, 100, 200, 1) {
            fail("A cancelled viewport must not submit parse work")
        }
        assertTrue(requests.isEmpty())
        assertEquals(0, failures)
        scheduler.close()
    }
    @Test fun asyncViewportRequestDoesNotReadOrIndexOnCallerThread() {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val text = ("line\n").repeat(200)
        val source = object : Utf16DocumentSource {
            override val length = text.length.toLong()
            override val version = 77L
            override fun get(offset: Long) = text[offset.toInt()]
            override fun readChunk(offset: Long, maxChars: Int): CharSequence {
                enteredRead.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                if (offset >= length) return ""
                return text.substring(offset.toInt(), minOf(text.length, offset.toInt() + maxChars))
            }
        }
        val manager = DocumentPartitionManager(256, prefetchPartitions = 0)
        val cache = PartitionAstCache(1024) { 1L }
        val scheduler = TreeSitterPartitionScheduler(manager, cache, contextChars = 16)
        val callbackCalled = AtomicBoolean(false)
        scheduler.requestViewportAsync(source, 100, 200, 1, {
            fail("Test source should not need native parsing")
        }) { callbackCalled.set(true) }
        assertTrue(enteredRead.await(2, TimeUnit.SECONDS), "indexing should begin on worker")
        assertFalse(callbackCalled.get())
        releaseRead.countDown()
        scheduler.close()
    }

    @Test fun cancellationFromParseCallbackIsNotReportedAsFailure() {
        val source = Source(("class A { int value; }\n").repeat(80))
        val manager = DocumentPartitionManager(256, prefetchPartitions = 0)
        val cache = PartitionAstCache(1024) { 1L }
        val failures = AtomicInteger()
        val parseStarted = CountDownLatch(1)
        val scheduler = TreeSitterPartitionScheduler(
            manager, cache, contextChars = 16,
            onFailure = { _, _ -> failures.incrementAndGet() }
        )
        val requests = scheduler.requestViewport(source, 100, 200, 1) {
            parseStarted.countDown()
            throw java.util.concurrent.CancellationException("superseded parse")
        }
        assertTrue(requests.isNotEmpty())
        assertTrue(parseStarted.await(2, TimeUnit.SECONDS))
        scheduler.close()
        assertEquals(0, failures.get())
    }
}
