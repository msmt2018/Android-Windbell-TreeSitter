package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshSparseCoordinateIndexTest {
    private class Snapshot(private val text: String) : TshTextSnapshot {
        override val documentId: String = "coordinate-test"
        override val version: Long = 7L
        override val lengthUtf16: Long = text.length.toLong()

        override fun readUtf16(start: Long, end: Long): CharSequence {
            require(start >= 0 && end >= start && end <= lengthUtf16)
            return text.substring(start.toInt(), end.toInt())
        }
    }

    @Test
    fun convertsAcrossUtf8Utf16AndCodePointsWithoutFlatteningSnapshot() {
        val snapshot = Snapshot("A𠮷Bé")
        val index = TshSparseCoordinateIndex.build(
            snapshot, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        )
        assertTrue(index is TshResult.Success)
        val value = (index as TshResult.Success).value

        assertEquals(3L, (value.convert(5L, TshCoordinateSystem.UTF8_BYTE,
            TshCoordinateSystem.UTF16_CODE_UNIT) as TshResult.Success).value)
        assertEquals(2L, (value.convert(2L, TshCoordinateSystem.UNICODE_CODE_POINT,
            TshCoordinateSystem.UTF16_CODE_UNIT) as TshResult.Success).value)
        assertEquals(6L, (value.convert(4L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.UTF8_BYTE) as TshResult.Success).value)
    }

    @Test
    fun rejectsOffsetsInsideSurrogateAndUtf8Sequences() {
        val result = TshSparseCoordinateIndex.build(
            Snapshot("A𠮷B"), chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success
        assertTrue(result.value.convert(2L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.UTF8_BYTE) is TshResult.Failure)
        assertTrue(result.value.convert(2L, TshCoordinateSystem.UTF8_BYTE,
            TshCoordinateSystem.UTF16_CODE_UNIT) is TshResult.Failure)
    }

    @Test
    fun usesSparseCheckpointsForLargeSnapshotAndKeepsEndBoundary() {
        val text = buildString {
            repeat(20_000) { append("a𠮷") }
        }
        val result = TshSparseCoordinateIndex.build(
            Snapshot(text), chunkSizeUtf16 = 4096, checkpointStrideUtf16 = 1024
        )
        assertTrue(result is TshResult.Success)
        val index = (result as TshResult.Success).value
        val endUtf8 = 20_000L * 5L
        assertEquals(text.length.toLong(), (index.convert(
            endUtf8, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
        ) as TshResult.Success).value)
        assertEquals(40_000L, (index.convert(
            60_000L, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UNICODE_CODE_POINT
        ) as TshResult.Success).value)
    }

    @Test
    fun rejectsLayoutAndGraphemeCoordinatesWithoutSpecializedProvider() {
        val index = (TshSparseCoordinateIndex.build(
            Snapshot("abc"), chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        assertTrue(index.convert(1L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.LINE_COLUMN) is TshResult.Failure)
        assertTrue(index.convert(1L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.GRAPHEME_CLUSTER) is TshResult.Failure)
    }
}
