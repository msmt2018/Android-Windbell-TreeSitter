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
    fun copiesBorrowedChunkBeforeBoundaryLookaheadReusesProviderBuffer() {
        class ReusingBufferSnapshot : TshTextSnapshot {
            override val documentId = "borrowed-window-doc"
            override val version = 3L
            private val content = "A😀B"
            private val backing = CharArray(2)
            override val lengthUtf16 = content.length.toLong()

            override fun readUtf16(start: Long, end: Long): CharSequence {
                val count = (end - start).toInt()
                require(count in 0..backing.size)
                java.util.Arrays.fill(backing, '\u0000')
                for (index in 0 until count) {
                    backing[index] = content[start.toInt() + index]
                }
                return object : CharSequence {
                    override val length: Int = count
                    override fun get(index: Int): Char = backing[index]
                    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                        String(backing, startIndex, endIndex - startIndex)
                }
            }
        }

        val snapshot = ReusingBufferSnapshot()
        val provider = object : TshTextAccessProvider {
            override val id = "reusing-window-provider"
            override val threading = TshTextAccessThreading.ANY_THREAD
            override fun supports(document: Any) = true
            override fun snapshot(document: Any): TshResult<TshTextSnapshot> =
                TshResult.Success(snapshot)

            override fun readChunk(
                lease: TshTextSnapshotLease,
                startUtf16: Long,
                maxUtf16Length: Int
            ): TshResult<TshTextChunk> {
                val end = minOf(snapshot.lengthUtf16, startUtf16 + maxUtf16Length)
                val borrowed = snapshot.readUtf16(startUtf16, end)
                return TshResult.Success(TshTextChunk(
                    snapshot.documentId, snapshot.version, startUtf16, end, borrowed
                ))
            }
        }
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val input = TshSnapshotTreeSitterInput(provider, lease)

        // The chunk read returns a borrowed view of the shared two-unit buffer. The previous
        // and next boundary checks overwrite that buffer with "A" and "B" respectively.
        assertArrayEquals(
            byteArrayOf(0x3D, 0xD8.toByte(), 0x00, 0xDE.toByte()),
            input.read(2L, 4)
        )
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
