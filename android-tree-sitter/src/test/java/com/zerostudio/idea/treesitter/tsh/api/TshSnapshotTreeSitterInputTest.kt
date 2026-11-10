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
        val first = input.read(0L, 4)
        assertArrayEquals(byteArrayOf(0x41, 0x00), first)

        val emoji = input.read(2L, 4)
        assertArrayEquals(byteArrayOf(0x3D, 0xD8.toByte(), 0x00, 0xDE.toByte()), emoji)

        val finalChar = input.read(6L, 4)
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x00), finalChar)
        assertArrayEquals(byteArrayOf(), input.read(8L, 4))
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
            input.read(0L, 4)
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
            input.read(0L, 8)
        }
        lease.close()
    }

    @Test
    fun rejectsOddNativeByteOffsets() {
        val provider = Provider()
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val input = TshSnapshotTreeSitterInput(provider, lease)
        assertThrows(TshTextInputException::class.java) {
            input.read(1L, 8)
        }
        lease.close()
    }
}
