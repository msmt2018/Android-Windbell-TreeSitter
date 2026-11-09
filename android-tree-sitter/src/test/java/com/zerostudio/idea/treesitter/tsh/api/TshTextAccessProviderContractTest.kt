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
        assertEquals("cde", (chunk as TshResult.Success).value.text.toString())
        assertEquals(7L, chunk.value.documentVersion)
        lease.close()
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
