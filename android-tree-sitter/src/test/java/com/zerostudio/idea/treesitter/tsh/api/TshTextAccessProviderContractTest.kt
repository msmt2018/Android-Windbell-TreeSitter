package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshTextAccessProviderContractTest {
    private class Snapshot : TshTextSnapshot {
        override val documentId = "doc"
        override val version = 7L
        private val content = "abcdef"
        override val lengthUtf16 = content.length.toLong()
        override fun readUtf16(start: Long, end: Long): CharSequence {
            require(start >= 0 && end >= start && end <= lengthUtf16)
            return content.substring(start.toInt(), end.toInt())
        }
    }

    private class Provider : TshTextAccessProvider {
        override val id = "test"
        override fun supports(document: Any) = true
        override fun snapshot(document: Any) = TshResult.Success(Snapshot())
    }

    @Test
    fun readsBoundedChunksWithoutFlatteningContract() {
        val provider = Provider()
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val chunk = provider.readChunk(lease, 2L, 3)
        val chunkValue = (chunk as TshResult.Success).value
        assertEquals("cde", chunkValue.text.toString())
        assertEquals(7L, chunkValue.documentVersion)
        lease.close()
    }

    @Test
    fun chunkReadsNeverSplitSurrogatePairsAndRejectMalformedEdits() {
        val snapshot = object : TshTextSnapshot {
            override val documentId = "unicode"
            override val version = 1L
            private val content = "ab😀cd"
            override val lengthUtf16 = content.length.toLong()
            override fun readUtf16(start: Long, end: Long): CharSequence =
                content.substring(start.toInt(), end.toInt())
        }
        val provider = ProviderWithSnapshot(snapshot)
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val tooSmall = provider.readChunk(lease, 2L, 1)
        assertTrue(tooSmall is TshResult.Failure)
        val emoji = provider.readChunk(lease, 2L, 2) as TshResult.Success
        assertEquals("😀", emoji.value.text.toString())
        val splitStart = provider.readChunk(lease, 3L, 2)
        assertTrue(splitStart is TshResult.Failure)
        assertThrows(IllegalArgumentException::class.java) {
            TshTextEdit(0, 0, "\\uD800")
        }
        lease.close()
    }

    private class ProviderWithSnapshot(private val value: TshTextSnapshot) : TshTextAccessProvider {
        override val id = "unicode"
        override fun supports(document: Any) = true
        override fun snapshot(document: Any) = TshResult.Success(value)
    }

    @Test
    fun rejectsOverlappingOrUnorderedEdits() {
        assertThrows(IllegalArgumentException::class.java) {
            TshTextChange("doc", 1, 2, listOf(
                TshTextEdit(3, 5, "x"), TshTextEdit(4, 6, "y")
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TshTextChange("doc", 1, 2, listOf(
                TshTextEdit(5, 6, "x"), TshTextEdit(1, 2, "y")
            ))
        }
    }
}
