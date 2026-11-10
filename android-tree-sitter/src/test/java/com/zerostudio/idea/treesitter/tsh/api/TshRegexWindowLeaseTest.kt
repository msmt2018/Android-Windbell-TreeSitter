package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TshRegexWindowLeaseTest {
    private class Snapshot(private val content: String) : TshTextSnapshot {
        override val documentId = "regex-window-doc"
        override val version = 12L
        override val lengthUtf16 = content.length.toLong()
        var largestRead = 0L

        override fun readUtf16(start: Long, end: Long): CharSequence {
            require(start >= 0L && end >= start && end <= lengthUtf16)
            largestRead = maxOf(largestRead, end - start)
            return content.substring(start.toInt(), end.toInt())
        }
    }

    private fun lease(snapshot: Snapshot, released: (() -> Unit)? = null) =
        TshSharedTextSnapshotLease.acquire(snapshot) { released?.invoke() }

    private fun constraints(
        maxMatch: Int? = 32,
        lookbehind: Int = 8,
        lookahead: Int = 4
    ) = TshRegexWindowConstraints(
        coreWindowUtf16 = 256,
        maxWindowUtf16 = 512,
        maxMatchLengthUtf16 = maxMatch,
        maxLookbehindUtf16 = lookbehind,
        maxLookaheadUtf16 = lookahead
    )

    @Test
    fun acquiresOnlyTheCoreAndRequiredFiniteContext() {
        val snapshot = Snapshot("a".repeat(2_000))
        val sourceLease = lease(snapshot)
        val window = (TshRegexWindowLease.acquire(
            sourceLease, 300L, 256, constraints()
        ) as TshResult.Success).value

        assertEquals(2_000L, window.documentLengthUtf16)
        assertFalse(window.isDocumentStart)
        assertFalse(window.isDocumentEnd)
        assertEquals(292L, window.windowStartUtf16)
        assertEquals(591L, window.windowEndUtf16)
        assertEquals(299, window.lengthUtf16)
        assertEquals(8, window.coreStartInWindow)
        assertEquals(264, window.coreEndInWindow)
        assertTrue(window.ownsMatchStart(300L))
        assertTrue(window.ownsMatchStart(555L))
        assertFalse(window.ownsMatchStart(556L))
        assertEquals(300L, window.absoluteUtf16Offset(8))
        assertEquals(299L, snapshot.largestRead)

        window.close()
        sourceLease.close()
    }

    @Test
    fun expandsContextEdgesToAvoidSplittingSurrogatePairs() {
        val content = "a".repeat(248) + "😀" + "b".repeat(292) + "😀" + "c".repeat(500)
        val snapshot = Snapshot(content)
        val sourceLease = lease(snapshot)
        val window = (TshRegexWindowLease.acquire(
            sourceLease, 256L, 256, constraints(lookahead = 0)
        ) as TshResult.Success).value

        // The lookbehind start (248) is exactly before the first surrogate pair. The right
        // context boundary (543) falls between the second pair's code units and is extended.
        assertEquals(248L, window.windowStartUtf16)
        assertEquals(544L, window.windowEndUtf16)
        assertTrue(TshUnicode.isWellFormedUtf16(window.text))
        window.close()
        sourceLease.close()
    }

    @Test
    fun rejectsCoreBoundariesInsideSurrogatePairs() {
        val snapshot = Snapshot("a".repeat(300) + "😀" + "b".repeat(500))
        val sourceLease = lease(snapshot)
        val result = TshRegexWindowLease.acquire(
            sourceLease, 301L, 256, constraints()
        )
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-REGEX-WINDOW-007", result.diagnostics().single().code)
        sourceLease.close()
    }

    @Test
    fun rejectsUnboundedPatternsInsteadOfFlatteningTheDocument() {
        val snapshot = Snapshot("a".repeat(2_000_000))
        val sourceLease = lease(snapshot)
        val result = TshRegexWindowLease.acquire(
            sourceLease, 0L, 256, constraints(maxMatch = null)
        )
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-REGEX-WINDOW-002", result.diagnostics().single().code)
        assertEquals(0L, snapshot.largestRead)
        sourceLease.close()
    }

    @Test
    fun memoryReservationIsHeldUntilWindowCloseAndReleasedIdempotently() {
        val snapshot = Snapshot("x".repeat(2_000))
        val sourceLease = lease(snapshot)
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(2_048, 4_096))
        val window = (TshRegexWindowLease.acquire(
            sourceLease, 300L, 256, constraints(), budget
        ) as TshResult.Success).value

        assertEquals(TshMemoryCategory.REGEX_WINDOW, budget.snapshot().bytesByCategory
            .filterValues { it > 0L }.keys.single())
        assertEquals(1_260L, budget.snapshot().reservedBytes)
        window.close()
        window.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
        assertThrows(IllegalStateException::class.java) { window.text }
        sourceLease.close()
    }

    @Test
    fun insufficientBudgetIsRejectedBeforeReadingTheContiguousWindow() {
        val snapshot = Snapshot("x".repeat(2_000))
        val sourceLease = lease(snapshot)
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(64, 128))
        val result = TshRegexWindowLease.acquire(
            sourceLease, 300L, 256, constraints(), budget
        )
        assertTrue(result is TshResult.Failure)
        assertEquals(TshDiagnosticStage.MEMORY, result.diagnostics().single().stage)
        assertTrue(snapshot.largestRead <= 2L)
        assertEquals(0L, budget.snapshot().reservedBytes)
        sourceLease.close()
    }

    @Test
    fun closingCallerSnapshotLeaseDoesNotInvalidateCopiedWindow() {
        val released = AtomicInteger()
        val snapshot = Snapshot("source text".repeat(100))
        val sourceLease = lease(snapshot) { released.incrementAndGet() }
        val window = (TshRegexWindowLease.acquire(
            sourceLease, 0L, 256,
            TshRegexWindowConstraints(
                coreWindowUtf16 = 256,
                maxWindowUtf16 = 512,
                maxMatchLengthUtf16 = 32
            )
        ) as TshResult.Success).value

        sourceLease.close()
        assertEquals(1, released.get())
        assertTrue(window.text.isNotEmpty())
        window.close()
    }

    @Test
    fun onlyFinalWindowOwnsZeroWidthMatchAtDocumentEnd() {
        val snapshot = Snapshot("z".repeat(300))
        val sourceLease = lease(snapshot)
        val first = (TshRegexWindowLease.acquire(
            sourceLease, 0L, 256,
            TshRegexWindowConstraints(256, 512, 32)
        ) as TshResult.Success).value
        val last = (TshRegexWindowLease.acquire(
            sourceLease, 256L, 256,
            TshRegexWindowConstraints(256, 512, 32)
        ) as TshResult.Success).value

        assertTrue(first.isDocumentStart)
        assertFalse(first.isDocumentEnd)
        assertFalse(first.ownsMatchStart(256L))
        assertTrue(last.isDocumentEnd)
        assertTrue(last.ownsMatchStart(300L))
        first.close()
        last.close()
        sourceLease.close()
    }

}
