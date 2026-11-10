package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

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

    private fun lease(text: String, onRelease: () -> Unit = {}) =
        TshSharedTextSnapshotLease.acquire(Snapshot(text), onRelease)

    @Test
    fun convertsAcrossUtf8Utf16AndCodePointsWithoutFlatteningSnapshot() {
        val sourceLease = lease("A𠮷Bé")
        val index = TshSparseCoordinateIndex.build(
            sourceLease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        )
        assertTrue(index is TshResult.Success)
        val value = (index as TshResult.Success).value

        assertEquals(3L, (value.convert(5L, TshCoordinateSystem.UTF8_BYTE,
            TshCoordinateSystem.UTF16_CODE_UNIT) as TshResult.Success).value)
        assertEquals(3L, (value.convert(2L, TshCoordinateSystem.UNICODE_CODE_POINT,
            TshCoordinateSystem.UTF16_CODE_UNIT) as TshResult.Success).value)
        assertEquals(6L, (value.convert(4L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.UTF8_BYTE) as TshResult.Success).value)
        value.close()
        sourceLease.close()
    }

    @Test
    fun retainsSnapshotIndependentlyFromCallersLease() {
        val releases = AtomicInteger()
        val callerLease = lease("A𠮷B", releases::incrementAndGet)
        val index = (TshSparseCoordinateIndex.build(
            callerLease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value

        callerLease.close()
        assertEquals(0, releases.get())
        assertEquals(3L, (index.convert(
            5L, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
        ) as TshResult.Success).value)

        index.close()
        index.close()
        assertEquals(1, releases.get())
        assertTrue(index.convert(
            0L, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UTF8_BYTE
        ) is TshResult.Failure)
    }

    @Test
    fun rejectsClosedLeaseAndLeaseWithoutIndependentRetention() {
        val closed = lease("abc")
        closed.close()
        assertTrue(TshSparseCoordinateIndex.build(closed) is TshResult.Failure)

        val snapshot = Snapshot("abc")
        val nonRetainable = object : TshTextSnapshotLease {
            override val snapshot: TshTextSnapshot = snapshot
            override val isClosed: Boolean = false
            override fun close() = Unit
        }
        assertTrue(TshSparseCoordinateIndex.build(nonRetainable) is TshResult.Failure)
    }

    @Test
    fun rejectsOffsetsInsideSurrogateAndUtf8Sequences() {
        val sourceLease = lease("A𠮷B")
        val result = TshSparseCoordinateIndex.build(
            sourceLease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success
        val index = result.value
        assertTrue(index.convert(2L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.UTF8_BYTE) is TshResult.Failure)
        assertTrue(index.convert(2L, TshCoordinateSystem.UTF8_BYTE,
            TshCoordinateSystem.UTF16_CODE_UNIT) is TshResult.Failure)
        assertTrue(index.convert(2L, TshCoordinateSystem.UTF8_BYTE,
            TshCoordinateSystem.UTF8_BYTE) is TshResult.Failure)
        index.close()
        sourceLease.close()
    }

    @Test
    fun supportsConfiguredStrideLargerThanDefaultScanWindow() {
        val text = buildString { repeat(80_000) { append('a') } }
        val sourceLease = lease(text)
        val index = (TshSparseCoordinateIndex.build(
            sourceLease, chunkSizeUtf16 = 32 * 1024, checkpointStrideUtf16 = 64 * 1024
        ) as TshResult.Success).value

        assertEquals(60_000L, (index.convert(
            60_000L, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UTF8_BYTE
        ) as TshResult.Success).value)
        assertEquals(60_000L, (index.convert(
            60_000L, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
        ) as TshResult.Success).value)
        index.close()
        sourceLease.close()
    }

    @Test
    fun usesSparseCheckpointsForLargeSnapshotAndKeepsEndBoundary() {
        val text = buildString { repeat(20_000) { append("a𠮷") } }
        val sourceLease = lease(text)
        val index = (TshSparseCoordinateIndex.build(
            sourceLease, chunkSizeUtf16 = 4096, checkpointStrideUtf16 = 1024
        ) as TshResult.Success).value
        val endUtf8 = 20_000L * 5L
        assertEquals(text.length.toLong(), (index.convert(
            endUtf8, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
        ) as TshResult.Success).value)
        assertEquals(40_000L, (index.convert(
            60_000L, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UNICODE_CODE_POINT
        ) as TshResult.Success).value)
        index.close()
        sourceLease.close()
    }

    @Test
    fun rejectsLayoutAndGraphemeCoordinatesWithoutSpecializedProvider() {
        val sourceLease = lease("abc")
        val index = (TshSparseCoordinateIndex.build(
            sourceLease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        assertTrue(index.convert(1L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.LINE_COLUMN) is TshResult.Failure)
        assertTrue(index.convert(1L, TshCoordinateSystem.UTF16_CODE_UNIT,
            TshCoordinateSystem.GRAPHEME_CLUSTER) is TshResult.Failure)
        index.close()
        sourceLease.close()
    }
}
