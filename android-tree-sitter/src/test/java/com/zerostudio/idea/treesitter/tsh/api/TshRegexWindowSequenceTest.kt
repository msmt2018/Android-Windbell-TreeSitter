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
        val sequence = (TshRegexWindowSequence.open(callerLease, constraints()) as TshResult.Success<TshRegexWindowSequence>).value
        val starts = mutableListOf<Long>()
        val ends = mutableListOf<Long>()

        while (true) {
            val result = sequence.nextWindow() as TshResult.Success<TshRegexWindowLease?>
            val window = result.value ?: break
            starts += window.coreStartUtf16
            ends += window.coreEndUtf16
            assertTrue(TshUnicode.isWellFormedUtf16(window.text))
            window.close()
        }

        assertEquals(listOf(0L, 255L, 511L), starts)
        assertEquals(listOf(255L, 511L, source.lengthUtf16), ends)
        assertEquals(source.lengthUtf16, sequence.nextCoreOffsetUtf16)
        assertTrue((sequence.nextWindow() as TshResult.Success<TshRegexWindowLease?>).value == null)
        sequence.close()
        callerLease.close()
    }

    @Test
    fun retainedSequenceSurvivesCallerLeaseCloseAndReleasesOnSequenceClose() {
        val released = AtomicInteger()
        val source = Snapshot("text ".repeat(200))
        val callerLease = TshSharedTextSnapshotLease.acquire(source) { released.incrementAndGet() }
        val sequence = (TshRegexWindowSequence.open(callerLease, constraints()) as TshResult.Success<TshRegexWindowSequence>).value

        callerLease.close()
        assertEquals(0, released.get())
        val first = (sequence.nextWindow() as TshResult.Success<TshRegexWindowLease?>).value!!
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
        val emptySequence = (TshRegexWindowSequence.open(emptyLease, constraints()) as TshResult.Success<TshRegexWindowSequence>).value
        assertEquals(null, (emptySequence.nextWindow() as TshResult.Success<TshRegexWindowLease?>).value)
        emptySequence.close()
        emptyLease.close()
    }

    @Test
    fun failedWindowAdmissionDoesNotAdvanceAndCanBeRetried() {
        val source = Snapshot("q".repeat(1_000))
        val callerLease = TshSharedTextSnapshotLease.acquire(source)
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(2_048, 4_096))
        val blocker = (budget.reserve(TshMemoryCategory.OTHER, 1_000) as TshResult.Success).value
        val sequence = (TshRegexWindowSequence.open(callerLease, constraints(), budget) as TshResult.Success<TshRegexWindowSequence>).value

        val rejected = sequence.nextWindow()
        assertTrue(rejected is TshResult.Failure)
        assertEquals(0L, sequence.nextCoreOffsetUtf16)
        blocker.close()

        val retried = (sequence.nextWindow() as TshResult.Success<TshRegexWindowLease?>).value
        assertNotNull(retried)
        assertEquals(256L, sequence.nextCoreOffsetUtf16)
        retried!!.close()
        sequence.close()
        callerLease.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test
    fun sequenceCloseDoesNotReleaseAnOutstandingCopiedWindow() {
        val source = Snapshot("r".repeat(1_000))
        val callerLease = TshSharedTextSnapshotLease.acquire(source)
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(4_096, 8_192))
        val sequence = (TshRegexWindowSequence.open(callerLease, constraints(), budget) as TshResult.Success<TshRegexWindowSequence>).value
        val window = (sequence.nextWindow() as TshResult.Success<TshRegexWindowLease?>).value!!
        val heldBytes = budget.snapshot().reservedBytes
        assertTrue(heldBytes > 0L)

        sequence.close()
        assertTrue(window.text.isNotEmpty())
        assertEquals(heldBytes, budget.snapshot().reservedBytes)
        window.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
        callerLease.close()
    }

}
