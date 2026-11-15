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
}
