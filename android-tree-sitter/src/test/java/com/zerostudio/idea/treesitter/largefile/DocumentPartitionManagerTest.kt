package com.zerostudio.idea.treesitter.largefile

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentPartitionManagerTest {
    private class Source(private val text: String, override val version: Long = 1L) : Utf16DocumentSource {
        override val length: Long get() = text.length.toLong()
        override fun get(offset: Long): Char = text[offset.toInt()]
    }

    @Test fun compactOffsetIndexSupportsEmptyCapacityGrowthAndMonotonicity() {
        val index = CompactOffsetIndex(0)
        index.add(0L)
        repeat(600) { index.add((it + 1L) * 64L * 1024L) }
        assertEquals(601, index.size)
        assertEquals(0L, index[0])
        assertEquals(600L * 64L * 1024L, index[600])
        assertThrows(IllegalArgumentException::class.java) { index.add(1L) }
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

    @Test fun viewportPlanIncludesEveryPartitionIntersectingALongLine() {
        val source = object : Utf16DocumentSource {
            override val length = 220_000L
            override val version = 19L
            override fun get(offset: Long): Char = 'x'
            override fun readChunk(offset: Long, maxChars: Int): CharSequence =
                "x".repeat(minOf(maxChars.toLong(), length - offset).toInt())
        }
        val manager = DocumentPartitionManager(
            targetPartitionChars = 64L * 1024L,
            prefetchPartitions = 0
        )
        manager.rebuild(source)

        val plan = manager.planViewport(source, 0L, source.length)
        assertTrue(plan.partitions.size >= 3)
        assertEquals(0L, plan.partitions.first().start)
        assertEquals(source.length, plan.partitions.last().endExclusive)
        for (index in 1 until plan.partitions.size) {
            assertEquals(
                plan.partitions[index - 1].endExclusive,
                plan.partitions[index].start
            )
        }
    }


    @Test fun sparseLineOffsetsRemainCorrectWithCompactPrimitiveStorage() {
        val source = Source("x\\n".repeat(300))
        val manager = DocumentPartitionManager(targetPartitionChars = 1024)
        manager.rebuild(source)
        assertEquals(0L, manager.lineCheckpointAtOrBefore(255L))
        assertEquals(512L, manager.lineCheckpointAtOrBefore(256L))
        assertEquals(512L, manager.lineCheckpointAtOrBefore(300L))
    }


    @Test fun pointCheckpointIndexWidensOnlyWhenRequiredAndKeepsGrowing() {
        val index = CompactPointCheckpointIndex(1)
        index.add(0L, 0L, 0L)
        assertFalse(index.uses64BitStorage)
        index.add(Int.MAX_VALUE.toLong() + 1L, 2L, 3L)
        assertTrue(index.uses64BitStorage)
        index.add(Int.MAX_VALUE.toLong() + 65_536L, 4L, 5L)
        assertEquals(1, index.floorIndex(Int.MAX_VALUE.toLong() + 10L))
        assertEquals(Int.MAX_VALUE.toLong() + 65_536L, index.offsetAt(2))
        assertEquals(4L, index.rowAt(2))
        assertEquals(5L, index.columnAt(2))
    }



    @Test fun tokenizerCheckpointStoreSnapshotsMutableStateBeforeWeighing() {
        val mutableStates = mutableListOf("root")
        val store = TokenizerCheckpointStore(maxEntries = 8, maxBytes = 512L)
        store.put(TokenizerCheckpoint(1L, 4L, 40L, mutableStates))
        val chargedBytes = store.bytesUsed

        mutableStates += "a".repeat(10_000)
        val stored = store.nearest(4L, 1L)
        assertEquals(listOf("root"), stored?.stateStack)
        assertEquals(chargedBytes, store.bytesUsed)
    }

    @Test fun tokenizerCheckpointCacheEvictsByBytesNotOnlyEntryCount() {
        val store = TokenizerCheckpointStore(maxEntries = 100, maxBytes = 200L)
        store.put(TokenizerCheckpoint(1L, 1L, 10L, listOf("root")))
        store.put(TokenizerCheckpoint(1L, 2L, 20L, listOf("string")))
        assertTrue(store.bytesUsed in 1L..200L)
        assertEquals(2L, store.nearest(10L, 1L)?.line)
        store.invalidateFrom(2L)
        assertEquals(0L, store.bytesUsed)
        assertNull(store.nearest(10L, 1L))
    }

}
