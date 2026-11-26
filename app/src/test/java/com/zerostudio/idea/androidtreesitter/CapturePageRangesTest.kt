package com.zerostudio.idea.androidtreesitter

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePageRangesTest {
    @Test
    fun requestsWithinOnePageShareTheSameStableKey() {
        val first = CaptureRangeKey(7L, 120, 180)
        val second = CaptureRangeKey(7L, 900, 1_200)

        val canonicalFirst = CapturePageRanges.canonicalize(first, 100_000)
        val canonicalSecond = CapturePageRanges.canonicalize(second, 100_000)

        assertEquals(canonicalFirst, canonicalSecond)
        assertEquals(0, canonicalFirst.startUtf16)
        assertEquals(CapturePageRanges.PAGE_SIZE_UTF16, canonicalFirst.endUtf16)
    }

    @Test
    fun finalPageIsClippedToDocumentEnd() {
        val start = CapturePageRanges.PAGE_SIZE_UTF16 + 10
        val key = CaptureRangeKey(3L, start, start + 20)

        val result = CapturePageRanges.canonicalize(key, start + 50)

        assertEquals(CapturePageRanges.PAGE_SIZE_UTF16, result.startUtf16)
        assertEquals(start + 50, result.endUtf16)
    }

    @Test
    fun crossPageAndOversizedRequestsRemainExact() {
        val boundary = CapturePageRanges.PAGE_SIZE_UTF16
        val crossing = CaptureRangeKey(2L, boundary - 4, boundary + 8)
        val oversized = CaptureRangeKey(2L, 0, boundary + 1)

        assertEquals(crossing, CapturePageRanges.canonicalize(crossing, boundary * 3))
        assertEquals(oversized, CapturePageRanges.canonicalize(oversized, boundary * 3))
    }

    @Test
    fun emptyAndOutOfDocumentRangesAreNotExpanded() {
        val empty = CaptureRangeKey(1L, 50, 50)
        val outside = CaptureRangeKey(1L, 200, 220)

        assertEquals(empty, CapturePageRanges.canonicalize(empty, 100))
        assertEquals(outside, CapturePageRanges.canonicalize(outside, 100))
    }
}
