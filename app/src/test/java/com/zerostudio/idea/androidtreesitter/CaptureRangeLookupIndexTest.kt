package com.zerostudio.idea.androidtreesitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections
import java.util.Random

class CaptureRangeLookupIndexTest {
    @Test fun findsContainingRangeAcrossNestedIntervals() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(1, 0, 1000))
        index.add(CaptureRangeKey(1, 100, 900))
        index.add(CaptureRangeKey(1, 200, 800))
        index.add(CaptureRangeKey(1, 300, 700))
        index.add(CaptureRangeKey(1, 350, 450))
        assertNotNull(index.findContaining(1, 360, 440))
        assertNotNull(index.findContaining(1, 320, 680))
        assertEquals(CaptureRangeKey(1, 0, 1000), index.findContaining(1, 0, 1000))
    }

    @Test fun isolatesDocumentSessions() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(10, 0, 500))
        index.add(CaptureRangeKey(11, 0, 100))
        assertEquals(CaptureRangeKey(11, 0, 100), index.findContaining(11, 20, 80))
        assertNull(index.findContaining(12, 20, 80))
    }

    @Test fun removingWideIntervalRecomputesSubtreeMaximum() {
        val index = CaptureRangeLookupIndex()
        val wide = CaptureRangeKey(1, 0, 1000)
        val narrow = CaptureRangeKey(1, 200, 300)
        index.add(wide)
        index.add(narrow)
        assertEquals(wide, index.findContaining(1, 10, 900))
        index.remove(wide)
        assertNull(index.findContaining(1, 10, 900))
        assertEquals(narrow, index.findContaining(1, 210, 290))
        assertEquals(1, index.size)
    }

    @Test fun disjointIntervalsDoNotProduceFalseHits() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(1, 0, 20))
        index.add(CaptureRangeKey(1, 100, 120))
        assertNull(index.findContaining(1, 30, 40))
        assertNull(index.findContaining(1, 10, 10))
    }

    @Test fun randomizedInsertRemoveAndLookupMatchesLinearOracle() {
        val random = Random(0x545348L)
        val index = CaptureRangeLookupIndex()
        val keys = LinkedHashSet<CaptureRangeKey>()
        repeat(1200) {
            val session = random.nextInt(5).toLong()
            val start = random.nextInt(20_000)
            val end = start + random.nextInt(4000) + 1
            val key = CaptureRangeKey(session, start, end)
            keys += key
            index.add(key)
        }
        repeat(500) {
            val session = random.nextInt(5).toLong()
            val start = random.nextInt(20_000)
            val end = start + random.nextInt(500) + 1
            val expected = keys.any {
                it.sessionId == session && it.startUtf16 <= start && it.endUtf16 >= end
            }
            assertEquals(expected, index.findContaining(session, start, end) != null)
        }
        val removals = keys.toMutableList()
        Collections.shuffle(removals, random)
        removals.take(600).forEach {
            index.remove(it)
            keys.remove(it)
        }
        assertEquals(keys.size, index.size)
        repeat(500) {
            val session = random.nextInt(5).toLong()
            val start = random.nextInt(20_000)
            val end = start + random.nextInt(500) + 1
            val expected = keys.any {
                it.sessionId == session && it.startUtf16 <= start && it.endUtf16 >= end
            }
            assertEquals(expected, index.findContaining(session, start, end) != null)
        }
    }

    @Test
    fun overlappingRangesAreOrderedAndScopedToTheRequestedSession() {
        val index = CaptureRangeLookupIndex()
        val expected = listOf(
            CaptureRangeKey(7, 5, 20),
            CaptureRangeKey(7, 12, 18)
        )

        // Deliberately insert out of order and interleave other sessions.
        index.add(CaptureRangeKey(9, 10, 40))
        index.add(expected[1])
        index.add(CaptureRangeKey(7, 25, 30))
        index.add(CaptureRangeKey(3, 0, 100))
        index.add(expected[0])
        index.add(CaptureRangeKey(7, 0, 10))

        assertEquals(expected, index.findOverlappingRanges(7, 15, 25))
    }

    @Test
    fun touchingBoundariesAreNotOverlaps() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(1, 0, 10))
        index.add(CaptureRangeKey(1, 20, 30))

        assertEquals(emptyList<CaptureRangeKey>(), index.findOverlappingRanges(1, 10, 20))
        assertEquals(emptyList<CaptureRangeKey>(), index.findOverlappingRanges(2, 0, 30))
    }

    @Test
    fun coveringAndUncoveredQueriesUseTheSameOrderedIntervals() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(4, 20, 30))
        index.add(CaptureRangeKey(4, 0, 10))
        index.add(CaptureRangeKey(4, 10, 20))

        assertEquals(
            listOf(CaptureRangeKey(4, 0, 10), CaptureRangeKey(4, 10, 20), CaptureRangeKey(4, 20, 30)),
            index.findCoveringRanges(4, 5, 25)
        )
        assertEquals(emptyList<IntRange>(), index.findUncoveredRanges(4, 5, 25))
        assertNull(index.findCoveringRanges(4, 5, 35))
        assertEquals(listOf(30 until 35), index.findUncoveredRanges(4, 5, 35))
    }
}
