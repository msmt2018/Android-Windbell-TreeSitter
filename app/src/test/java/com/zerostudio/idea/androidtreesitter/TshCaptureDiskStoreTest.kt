package com.zerostudio.idea.androidtreesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class TshCaptureDiskStoreTest {
    private fun directory(): File = File(
        System.getProperty("java.io.tmpdir"),
        "tsh-capture-disk-test-${System.nanoTime()}"
    )

    private fun span(start: Int, end: Int, style: Int) =
        WindbellTshEngine.HighlightSpan(start, end, style, 0, 0, 1)

    @Test fun restoresPreviouslyCapturedRangeAfterHotCacheEviction() {
        val directory = directory()
        val store = TshCaptureDiskStore(directory, 41)
        try {
            val key = CaptureRangeKey(41, 0, 100)
            store.put(key, listOf(span(20, 80, 7)))
            val restored = store.restoreCovering(CaptureRangeKey(41, 30, 60))
            assertNotNull(restored)
            assertEquals(listOf(7), restored!!.single().index.query(15, 35).map { it.styleId })
        } finally {
            store.close()
        }
    }

    @Test fun restoresContinuousUnionOfAdjacentRanges() {
        val directory = directory()
        val store = TshCaptureDiskStore(directory, 42)
        try {
            store.put(CaptureRangeKey(42, 0, 50), listOf(span(0, 100, 1)))
            store.put(CaptureRangeKey(42, 50, 100), listOf(span(100, 200, 2)))
            val restored = store.restoreCovering(CaptureRangeKey(42, 25, 75))
            assertNotNull(restored)
            assertEquals(2, restored!!.size)
        } finally {
            store.close()
        }
    }

    @Test fun rejectsCoverageWithAnyGap() {
        val directory = directory()
        val store = TshCaptureDiskStore(directory, 43)
        try {
            store.put(CaptureRangeKey(43, 0, 40), listOf(span(0, 80, 1)))
            store.put(CaptureRangeKey(43, 50, 100), listOf(span(100, 200, 2)))
            assertNull(store.restoreCovering(CaptureRangeKey(43, 20, 80)))
        } finally {
            store.close()
        }
    }

    @Test fun committedEditClearRemovesAllReusableRanges() {
        val directory = directory()
        val store = TshCaptureDiskStore(directory, 44)
        try {
            store.put(CaptureRangeKey(44, 0, 100), listOf(span(0, 200, 1)))
            assertNotNull(store.restoreCovering(CaptureRangeKey(44, 10, 20)))
            store.clear()
            assertNull(store.restoreCovering(CaptureRangeKey(44, 10, 20)))
            assertEquals(0, store.size)
        } finally {
            store.close()
        }
    }

    @Test fun replacingSameRangePublishesNewPageAndRemainsReadable() {
        val directory = directory()
        val store = TshCaptureDiskStore(directory, 45)
        try {
            val key = CaptureRangeKey(45, 0, 100)
            store.put(key, listOf(span(0, 20, 1)))
            store.put(key, listOf(span(20, 40, 2)))
            val restored = store.restoreCovering(CaptureRangeKey(45, 10, 20))
            assertNotNull(restored)
            assertEquals(listOf(2), restored!!.single().index.query(10, 20).map { it.styleId })
            assertEquals(1, store.size)
        } finally {
            store.close()
        }
    }

}
