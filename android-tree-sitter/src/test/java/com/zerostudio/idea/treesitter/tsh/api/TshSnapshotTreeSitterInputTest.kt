package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshSnapshotTreeSitterInputTest {
    private class Snapshot : TshTextSnapshot {
        override val documentId = "streamed-doc"
        override val version = 9L
        private val content = "A😀é"
        override val lengthUtf16 = content.length.toLong()
        override fun readUtf16(start: Long, end: Long): CharSequence =
            content.substring(start.toInt(), end.toInt())
    }

    private class Provider : TshTextAccessProvider {
        override val id = "piece-tree-provider"
        override val threading = TshTextAccessThreading.ANY_THREAD
        override val supportsConcurrentReads = true
        private val value = Snapshot()
        override fun supports(document: Any) = true
        override fun snapshot(document: Any): TshResult<TshTextSnapshot> =
            TshResult.Success(value)
    }

    @Test
    fun streamsUtf16leInBoundedChunksWithoutSplittingSurrogatePairs() {
        val provider = Provider()
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val input = TshSnapshotTreeSitterInput(provider, lease)
        val buffer = ByteArray(4)

        val first = input.read(0L, 0, 0, buffer)
        assertEquals(2, first)
        assertArrayEquals(byteArrayOf(0x41, 0x00, 0x00, 0x00), buffer)

        val emoji = input.read(2L, 0, 1, buffer)
        assertEquals(4, emoji)
        assertArrayEquals(byteArrayOf(0x3D, 0xD8.toByte(), 0x00, 0xDE.toByte()), buffer)

        val finalChar = input.read(6L, 0, 3, buffer)
        assertEquals(2, finalChar)
        assertEquals(0xE9.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0, input.read(8L, 0, 4, buffer))
        lease.close()
    }

    @Test
    fun rejectsProviderChunksOutsideSnapshotAndRequestedBounds() {
        val snapshot = Snapshot()
        val provider = object : TshTextAccessProvider {
            override val id = "malformed-provider"
            override val threading = TshTextAccessThreading.ANY_THREAD
            override fun supports(document: Any) = true
            override fun snapshot(document: Any): TshResult<TshTextSnapshot> = TshResult.Success(snapshot)
            override fun readChunk(
                lease: TshTextSnapshotLease,
                startUtf16: Long,
                maxUtf16Length: Int
            ): TshResult<TshTextChunk> = TshResult.Success(
                TshTextChunk(snapshot.documentId, snapshot.version, startUtf16, snapshot.lengthUtf16 + 1L, "A😀é!")
            )
        }
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val input = TshSnapshotTreeSitterInput(provider, lease)
        assertThrows(TshTextInputException::class.java) {
            input.read(0L, 0, 0, ByteArray(4))
        }
        lease.close()
    }

    @Test
    fun rejectsMalformedUtf16FromOverriddenChunkReader() {
        val snapshot = Snapshot()
        val provider = object : TshTextAccessProvider {
            override val id = "malformed-unicode-provider"
            override val threading = TshTextAccessThreading.ANY_THREAD
            override fun supports(document: Any) = true
            override fun snapshot(document: Any): TshResult<TshTextSnapshot> = TshResult.Success(snapshot)
            override fun readChunk(
                lease: TshTextSnapshotLease,
                startUtf16: Long,
                maxUtf16Length: Int
            ): TshResult<TshTextChunk> = TshResult.Success(
                TshTextChunk(snapshot.documentId, snapshot.version, startUtf16, startUtf16 + 1L, "\uD800")
            )
        }
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val input = TshSnapshotTreeSitterInput(provider, lease)
        assertThrows(TshTextInputException::class.java) {
            input.read(0L, 0, 0, ByteArray(8))
        }
        lease.close()
    }

    @Test
    fun rejectsOddNativeByteOffsets() {
        val provider = Provider()
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val input = TshSnapshotTreeSitterInput(provider, lease)
        assertThrows(TshTextInputException::class.java) {
            input.read(1L, 0, 0, ByteArray(8))
        }
        lease.close()
    }

    @Test
    fun reservesTemporaryParserInputChunkAndReleasesAfterCallback() {
        val provider = Provider()
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(128, 256))
        val input = TshSnapshotTreeSitterInput(provider, lease, budget)
        val buffer = ByteArray(4)

        assertEquals(2, input.read(0L, 0, 0, buffer))
        assertEquals(0L, budget.snapshot().reservedBytes)
        assertEquals(68L, budget.snapshot().highWatermarkBytes)
        assertEquals(0, budget.snapshot().reservations)
        lease.close()
    }

    @Test
    fun rejectsParserInputChunkBeforeProviderReadWhenBudgetIsInsufficient() {
        val snapshot = Snapshot()
        var reads = 0
        val provider = object : TshTextAccessProvider {
            override val id = "counting-provider"
            override val threading = TshTextAccessThreading.ANY_THREAD
            override fun supports(document: Any) = true
            override fun snapshot(document: Any): TshResult<TshTextSnapshot> = TshResult.Success(snapshot)
            override fun readChunk(
                lease: TshTextSnapshotLease,
                startUtf16: Long,
                maxUtf16Length: Int
            ): TshResult<TshTextChunk> {
                reads++
                return super<TshTextAccessProvider>.readChunk(lease, startUtf16, maxUtf16Length)
            }
        }
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(32, 64))
        val input = TshSnapshotTreeSitterInput(provider, lease, budget)
        val error = assertThrows(TshTextInputException::class.java) {
            input.read(0L, 0, 0, ByteArray(4))
        }
        assertEquals(0, reads)
        assertEquals(TshDiagnosticStage.MEMORY, error.diagnostics.single().stage)
        assertEquals(0L, budget.snapshot().reservedBytes)
        lease.close()
    }


    @Test
    fun reservesReusableStreamingJniBufferForWholeParseLifetime() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(2_048, 4_096))
        val reservation = (TshParserInputMemoryReservations.reserveChunkBuffer(
            budget, "doc", 3L, 1_024
        ) as TshResult.Success).value!!

        assertEquals(1_088L, reservation.bytes)
        assertEquals(1_088L, budget.snapshot().bytesByCategory[TshMemoryCategory.PARSER_INPUT])
        reservation.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test
    fun rejectsInvalidOrOverBudgetStreamingJniBufferBeforeNativeAllocation() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(1_024, 2_048))
        val tooLarge = TshParserInputMemoryReservations.reserveChunkBuffer(
            budget, "doc", 3L, 1_024
        )
        assertTrue(tooLarge is TshResult.Failure)
        assertEquals(0L, budget.snapshot().reservedBytes)

        val invalid = TshParserInputMemoryReservations.reserveChunkBuffer(
            budget, "doc", 3L, 1_025
        )
        assertTrue(invalid is TshResult.Failure)
        assertEquals("TSH-MEMORY-009", invalid.diagnostics().single().code)
    }

}
