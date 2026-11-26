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

    @Test fun continuousAdjacentRangesCoverShiftedViewport() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(7, 0, 100))
        index.add(CaptureRangeKey(7, 100, 200))
        assertEquals(
            listOf(CaptureRangeKey(7, 0, 100), CaptureRangeKey(7, 100, 200)),
            index.findCovering(7, 50, 180)
        )
    }

    @Test fun overlappingRangesCoverViewportAndSkipRedundantNarrowIntervals() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(7, 0, 120))
        index.add(CaptureRangeKey(7, 80, 260))
        index.add(CaptureRangeKey(7, 100, 140))
        assertEquals(
            listOf(CaptureRangeKey(7, 0, 120), CaptureRangeKey(7, 80, 260)),
            index.findCovering(7, 50, 240)
        )
    }

    @Test fun anyGapRejectsPartialCoverage() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(7, 0, 100))
        index.add(CaptureRangeKey(7, 110, 200))
        assertNull(index.findCovering(7, 50, 180))
    }

    @Test fun rangesFromDifferentSessionsAreNeverCombined() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(7, 0, 100))
        index.add(CaptureRangeKey(8, 100, 200))
        assertNull(index.findCovering(7, 50, 180))
    }


    @Test fun coverageUsesFarthestReachToAvoidRedundantCachedRanges() {
        val index = CaptureRangeLookupIndex()
        index.add(CaptureRangeKey(7, 0, 60))
        index.add(CaptureRangeKey(7, 10, 300))
        index.add(CaptureRangeKey(7, 50, 90))
        assertEquals(
            listOf(CaptureRangeKey(7, 10, 300)),
            index.findCovering(7, 40, 250)
        )
    }

    @Test fun randomizedCoverageMatchesLinearUnionOracle() {
        val random = Random(0x434F564552L)
        repeat(100) {
            val index = CaptureRangeLookupIndex()
            val ranges = LinkedHashSet<CaptureRangeKey>()
            repeat(80) {
                val start = random.nextInt(1000)
                val end = start + random.nextInt(180) + 1
                val key = CaptureRangeKey(3, start, end)
                ranges += key
                index.add(key)
            }
            repeat(100) {
                val start = random.nextInt(1000)
                val end = start + random.nextInt(100) + 1
                val sorted = ranges.filter { it.endUtf16 > start && it.startUtf16 < end }
                    .sortedWith(compareBy<CaptureRangeKey> { it.startUtf16 }.thenByDescending { it.endUtf16 })
                var covered = start
                for (range in sorted) {
                    if (range.startUtf16 > covered) break
                    covered = maxOf(covered, range.endUtf16)
                }
                val expected = covered >= end
                assertEquals(expected, index.findCovering(3, start, end) != null)
            }
        }
    }

}
