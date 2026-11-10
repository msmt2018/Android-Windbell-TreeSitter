package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TshRegexWindowSequenceTest {
    private class Snapshot(private val value: String) : TshTextSnapshot {
        override val documentId = "regex-sequence-doc"
        override val version = 5L
        override val lengthUtf16 = value.length.toLong()
        override fun readUtf16(start: Long, end: Long): CharSequence =
            value.substring(start.toInt(), end.toInt())
    }

    private fun constraints() = TshRegexWindowConstraints(
        coreWindowUtf16 = 256,
        maxWindowUtf16 = 512,
        maxMatchLengthUtf16 = 32
    )

    @Test
    fun partitionsSourceIntoContiguousNonOverlappingCodePointSafeCores() {
        val source = Snapshot("a".repeat(255) + "😀" + "b".repeat(500))
        val callerLease = TshSharedTextSnapshotLease.acquire(source)
        val sequence = (TshRegexWindowSequence.open(callerLease, constraints()) as TshResult.Success).value
        val starts = mutableListOf<Long>()
        val ends = mutableListOf<Long>()

        while (true) {
            val result = sequence.nextWindow() as TshResult.Success
            val window = result.value ?: break
            starts += window.coreStartUtf16
            ends += window.coreEndUtf16
            assertTrue(TshUnicode.isWellFormedUtf16(window.text))
            window.close()
        }

        assertEquals(listOf(0L, 255L, 511L), starts)
        assertEquals(listOf(255L, 511L, source.lengthUtf16), ends)
        assertEquals(source.lengthUtf16, sequence.nextCoreOffsetUtf16)
        assertTrue((sequence.nextWindow() as TshResult.Success).value == null)
        sequence.close()
        callerLease.close()
    }

    @Test
    fun retainedSequenceSurvivesCallerLeaseCloseAndReleasesOnSequenceClose() {
        val released = AtomicInteger()
        val source = Snapshot("text ".repeat(200))
        val callerLease = TshSharedTextSnapshotLease.acquire(source) { released.incrementAndGet() }
        val sequence = (TshRegexWindowSequence.open(callerLease, constraints()) as TshResult.Success).value

        callerLease.close()
        assertEquals(0, released.get())
        val first = (sequence.nextWindow() as TshResult.Success).value!!
        assertTrue(first.text.isNotEmpty())
        first.close()
        sequence.close()
        sequence.close()
        assertEquals(1, released.get())
        assertTrue(sequence.nextWindow() is TshResult.Failure)
    }

    @Test
    fun rejectsUnboundedPatternAndHandlesEmptyDocument() {
        val unboundedLease = TshSharedTextSnapshotLease.acquire(Snapshot("abc"))
        val unbounded = TshRegexWindowSequence.open(
            unboundedLease,
            TshRegexWindowConstraints(
                coreWindowUtf16 = 256,
                maxWindowUtf16 = 512,
                maxMatchLengthUtf16 = null
            )
        )
        assertTrue(unbounded is TshResult.Failure)
        unboundedLease.close()

        val emptyLease = TshSharedTextSnapshotLease.acquire(Snapshot(""))
        val emptySequence = (TshRegexWindowSequence.open(emptyLease, constraints()) as TshResult.Success).value
        assertEquals(null, (emptySequence.nextWindow() as TshResult.Success).value)
        emptySequence.close()
        emptyLease.close()
    }
}
