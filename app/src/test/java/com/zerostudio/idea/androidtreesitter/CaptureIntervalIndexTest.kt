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
        assertEquals(listOf(1, 2), index.query(5, 6).map { it.styleId })
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

    @Test fun appendQueryMatchesQueryWithoutAllocatingIntermediateResults() {
        val index = CaptureIntervalIndex(listOf(
            span(0, 20, 1),
            span(8, 12, 2),
            span(30, 40, 3)
        ))
        val destination = arrayListOf(span(100, 110, 99))
        index.appendQuery(4, 6, destination)
        assertEquals(listOf(99, 1, 2), destination.map { it.styleId })
        assertEquals(
            index.query(4, 6).map { it.styleId },
            destination.drop(1).map { it.styleId }
        )
    }


    @Test fun unsignedNativeOffsetsAboveSignedIntRangeRemainQueryable() {
        val startByte = 0x8000_0000L
        val endByte = startByte + 32L
        val index = CaptureIntervalIndex(listOf(span(startByte.toInt(), endByte.toInt(), 77)))
        assertEquals(listOf(77), index.query(0x4000_0000, 0x4000_0008).map { it.styleId })
    }


    @Test fun rebaseInsertionDropsIntersectingCaptureAndMovesFollowingCapture() {
        val index = CaptureIntervalIndex(listOf(
            span(0, 8, 1),
            span(10, 16, 2),
            span(20, 28, 3)
        ))
        val result = CaptureDisplayRebaser.rebase(
            CaptureRangeKey(7, 0, 20), index, editStartUtf16 = 6,
            deletedChars = 0, insertedChars = 2
        )!!
        assertEquals(CaptureRangeKey(7, 0, 22), result.key)
        assertEquals(listOf(1, 3), result.index.query(0, 14).map { it.styleId })
        assertEquals(24, result.index.query(11, 14).first().startByte)
    }

    @Test fun rebaseDeletionKeepsPrefixAndShiftsSuffixWithoutSemanticReuse() {
        val index = CaptureIntervalIndex(listOf(
            span(0, 8, 1),
            span(10, 18, 2),
            span(24, 32, 3)
        ))
        val result = CaptureDisplayRebaser.rebase(
            CaptureRangeKey(8, 0, 20), index, editStartUtf16 = 5,
            deletedChars = 5, insertedChars = 0
        )!!
        assertEquals(CaptureRangeKey(8, 0, 15), result.key)
        assertEquals(listOf(1, 3), result.index.query(0, 15).map { it.styleId })
        assertEquals(14, result.index.query(7, 10).first().startByte)
    }

    @Test fun rebaseDropsRangeWhenEntireViewportWasDeleted() {
        val index = CaptureIntervalIndex(listOf(span(10, 20, 1)))
        assertEquals(null, CaptureDisplayRebaser.rebase(
            CaptureRangeKey(9, 5, 10), index, editStartUtf16 = 4,
            deletedChars = 8, insertedChars = 0
        ))
    }

    @Test fun pendingEditDisplayMappingKeepsUnchangedPrefixAndShiftsSuffix() {
        val edits = listOf(
            CaptureDisplayRebaser.PendingEdit(
                startUtf16 = 10, deletedChars = 2, insertedChars = 4
            )
        )
        assertEquals(
            CaptureDisplayRebaser.SourceRange(2, 8),
            CaptureDisplayRebaser.mapCurrentRangeToOriginal(2, 8, edits)
        )
        assertEquals(
            CaptureDisplayRebaser.SourceRange(12, 18),
            CaptureDisplayRebaser.mapCurrentRangeToOriginal(14, 20, edits)
        )
        assertEquals(
            CaptureDisplayRebaser.SourceRange(2, 8),
            CaptureDisplayRebaser.mapOriginalCaptureToCurrent(2, 8, edits)
        )
        assertEquals(
            CaptureDisplayRebaser.SourceRange(14, 20),
            CaptureDisplayRebaser.mapOriginalCaptureToCurrent(12, 18, edits)
        )
        assertEquals(null, CaptureDisplayRebaser.mapCurrentRangeToOriginal(9, 12, edits))
        assertEquals(null, CaptureDisplayRebaser.mapOriginalCaptureToCurrent(8, 12, edits))
    }

    @Test fun pendingEditDisplayMappingComposesBottomUpMultiEditBatch() {
        val edits = listOf(
            CaptureDisplayRebaser.PendingEdit(20, 0, 3),
            CaptureDisplayRebaser.PendingEdit(4, 2, 1)
        )
        val current = CaptureDisplayRebaser.mapCurrentRangeToOriginal(30, 36, edits)
        assertEquals(CaptureDisplayRebaser.SourceRange(28, 34), current)
        assertEquals(
            CaptureDisplayRebaser.SourceRange(30, 36),
            CaptureDisplayRebaser.mapOriginalCaptureToCurrent(28, 34, edits)
        )
    }

    @Test fun pendingDeletionBoundaryMapsSuffixWithoutRepaintingPrefix() {
        val edits = listOf(CaptureDisplayRebaser.PendingEdit(5, 4, 0))
        assertEquals(
            CaptureDisplayRebaser.SourceRange(9, 14),
            CaptureDisplayRebaser.mapCurrentRangeToOriginal(5, 10, edits)
        )
        assertEquals(
            CaptureDisplayRebaser.SourceRange(5, 10),
            CaptureDisplayRebaser.mapOriginalCaptureToCurrent(9, 14, edits)
        )
        assertEquals(null, CaptureDisplayRebaser.mapCurrentRangeToOriginal(3, 7, edits))
    }

}
