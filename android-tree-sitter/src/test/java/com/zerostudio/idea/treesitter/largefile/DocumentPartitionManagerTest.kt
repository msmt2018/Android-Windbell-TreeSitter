package com.zerostudio.idea.treesitter.largefile

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentPartitionManagerTest {
    private class Source(private val text: String, override val version: Long = 1L) : Utf16DocumentSource {
        override val length: Long get() = text.length.toLong()
        override fun get(offset: Long): Char = text[offset.toInt()]
    }

    @Test fun partitionsAreOrderedAndNeverSplitSurrogatePairs() {
        val text = ("a".repeat(250) + "😀" + "\n").repeat(8)
        val source = Source(text)
        val manager = DocumentPartitionManager(targetPartitionChars = 256, prefetchPartitions = 1)
        manager.rebuild(source)
        val parts = manager.snapshot()
        assertTrue(parts.size > 1)
        assertEquals(0L, parts.first().start)
        assertEquals(text.length.toLong(), parts.last().endExclusive)
        parts.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endExclusive, b.start)
            if (b.start < text.length) {
                assertFalse(Character.isHighSurrogate(text[(b.start - 1).toInt()]) &&
                    Character.isLowSurrogate(text[b.start.toInt()]))
            }
        }
    }

    @Test fun viewportPlanIncludesOnlyVisibleAndConfiguredPrefetch() {
        val source = Source(("x".repeat(100) + "\n").repeat(20))
        val manager = DocumentPartitionManager(256, prefetchPartitions = 1)
        val plan = manager.planViewport(source, 600, 700)
        assertTrue(plan.partitions.isNotEmpty())
        assertTrue(plan.partitions.first().start <= 600)
        assertTrue(plan.partitions.last().endExclusive >= 700)
    }

    @Test fun lruEvictsToByteBudgetAndClosesEvictedValues() {
        val closed = mutableListOf<String>()
        val cache = BoundedLruCache<String, String>(5, { it.length.toLong() }) { closed += it }
        cache.put("a", "aaa")
        cache.put("b", "bbb")
        assertNull(cache.get("a"))
        assertEquals(3L, cache.bytesUsed)
        assertEquals(listOf("aaa"), closed)
        cache.close()
        assertEquals(0L, cache.bytesUsed)
        assertTrue(closed.contains("bbb"))
    }


    @Test fun treeSitterRangeUsesAbsoluteUtf16LeOffsetsAndCrLfRows() {
        val text = "first\r\n😀second\r\nthird"
        val source = Source(text)
        val manager = DocumentPartitionManager(targetPartitionChars = 256)
        val start = text.indexOf("second").toLong()
        val end = (text.indexOf("third") + 5).toLong()
        val range = manager.treeSitterRange(source, start, end)
        assertEquals(start * 2L, range.startByte.toLong())
        assertEquals(end * 2L, range.endByte.toLong())
        assertEquals(1, range.startPoint.row)
        assertEquals(4, range.startPoint.column)
        assertEquals(2, range.endPoint.row)
        assertEquals(10, range.endPoint.column)
    }

    @Test fun treeSitterRangeRejectsSurrogatePairSplit() {
        val source = Source("a😀b")
        val manager = DocumentPartitionManager()
        assertThrows(IllegalArgumentException::class.java) {
            manager.treeSitterRange(source, 2L, 3L)
        }
    }

    @Test fun checkpointStoreRestoresOnlyMatchingVersionAndInvalidatesSuffix() {
        val store = TokenizerCheckpointStore(8)
        store.put(TokenizerCheckpoint(4, 10, 100, listOf("root", "string")))
        store.put(TokenizerCheckpoint(4, 20, 200, listOf("root")))
        assertEquals(10L, store.nearest(15, 4)?.line)
        assertNull(store.nearest(15, 5))
        store.invalidateFrom(15)
        assertEquals(10L, store.nearest(30, 4)?.line)
    }
    @Test fun longSingleLinePointLookupUsesBoundedSparseCheckpoints() {
        val text = "x".repeat(2 * 1024 * 1024) + "\nend"
        val source = Source(text)
        val manager = DocumentPartitionManager(targetPartitionChars = 64 * 1024)
        val offset = 1_500_000L
        val range = manager.treeSitterRange(source, offset, offset + 1L)
        assertEquals(0, range.startPoint.row)
        assertEquals(offset * 2L, range.startPoint.column.toLong())
        assertTrue(manager.pointCheckpointCount in 2..40)
    }

    @Test fun partitionIndexUsesBoundedSequentialSourceWindows() {
        val text = ("line-".repeat(100) + "\n").repeat(4000)
        var chunkReads = 0
        var randomReads = 0
        val source = object : Utf16DocumentSource {
            override val length = text.length.toLong()
            override val version = 12L
            override fun get(offset: Long): Char {
                randomReads++
                return text[offset.toInt()]
            }
            override fun readChunk(offset: Long, maxChars: Int): CharSequence {
                chunkReads++
                val start = offset.toInt()
                val end = minOf(text.length, start + maxChars)
                return text.subSequence(start, end)
            }
        }
        val manager = DocumentPartitionManager(targetPartitionChars = 64 * 1024)
        manager.rebuild(source)
        assertTrue(manager.snapshot().isNotEmpty())
        assertTrue(chunkReads < 100)
        assertTrue(randomReads < 100)
    }

}
