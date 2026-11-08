package com.zerostudio.idea.androidtreesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntervalIndexTest {
    private fun span(start: Int, end: Int, id: Int = start) =
        WindbellTshEngine.HighlightSpan(start, end, id, 0, 0, 0)

    @Test fun queryFindsOverlapsAndExcludesTouchingBoundaries() {
        val index = CaptureIntervalIndex(listOf(
            span(0, 20, 1),
            span(8, 12, 2),
            span(30, 40, 3),
            span(50, 100, 4)
        ))
        assertEquals(listOf(1, 2), index.query(4, 6).map { it.styleId })
        assertEquals(listOf(2), index.query(5, 6).map { it.styleId })
        assertEquals(listOf(3), index.query(15, 18).map { it.styleId })
        assertTrue(index.query(20, 25).isEmpty())
        assertTrue(index.query(0, 0).isEmpty())
    }

    @Test fun nestedAndLongIntervalsRemainDiscoverableAfterPrefixPruning() {
        val index = CaptureIntervalIndex(listOf(
            span(0, 200, 1),
            span(10, 12, 2),
            span(20, 22, 3),
            span(30, 32, 4),
            span(40, 42, 5)
        ))
        assertEquals(listOf(1, 5), index.query(20, 21).map { it.styleId })
        assertEquals(5, index.size)
    }
}
