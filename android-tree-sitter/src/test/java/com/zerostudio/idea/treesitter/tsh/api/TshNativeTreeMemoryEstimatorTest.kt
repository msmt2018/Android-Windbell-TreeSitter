package com.zerostudio.idea.treesitter.tsh.api

import org.junit.Assert.*
import org.junit.Test

class TshNativeTreeMemoryEstimatorTest {
    private fun request(lengthUtf16: Long = 200L) = TshNativeTreeMemoryRequest(
        documentId = "doc-1",
        documentVersion = 4L,
        treeRevision = 9L,
        sourceLengthUtf16 = lengthUtf16,
        grammarLanguageId = "java",
        grammarFingerprint = "java-test-fingerprint",
        incremental = false
    )

    @Test fun computesLogicalUtf16LeSourceSizeWithoutClaimingNativeUsage() {
        assertEquals(400L, request().sourceBytesUtf16Le)
    }

    @Test fun noEstimatorDoesNotCreateAFakeNativeTreeReservation() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(1024, 2048))
        val result = TshNativeTreeMemoryReservations.reserve(budget, null, request())
        assertTrue(result is TshResult.Success)
        assertNull((result as TshResult.Success).value)
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test fun reservesCallerEstimateForSyntaxTreeLifetime() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(1024, 2048))
        val estimator = TshNativeTreeMemoryEstimator { input ->
            assertEquals(200L, input.sourceLengthUtf16)
            assertFalse(input.incremental)
            768L
        }
        val result = TshNativeTreeMemoryReservations.reserve(budget, estimator, request())
        assertTrue(result is TshResult.Success)
        val reservation = (result as TshResult.Success).value!!
        assertEquals(768L, reservation.bytes)
        assertEquals(768L, budget.snapshot().bytesByCategory[TshMemoryCategory.SYNTAX_TREE])
        reservation.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test fun budgetRejectionPreventsNativeTreeReservation() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(100, 200))
        val result = TshNativeTreeMemoryReservations.reserve(
            budget, TshNativeTreeMemoryEstimator { 201L }, request()
        )
        assertTrue(result is TshResult.Failure)
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test fun invalidOrThrowingEstimatorReturnsMemoryDiagnostic() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(1024, 2048))
        val invalid = TshNativeTreeMemoryReservations.reserve(
            budget, TshNativeTreeMemoryEstimator { 0L }, request()
        )
        assertTrue(invalid is TshResult.Failure)
        assertEquals("TSH-MEMORY-006", (invalid as TshResult.Failure).diagnostics.single().code)

        val thrown = TshNativeTreeMemoryReservations.reserve(
            budget, TshNativeTreeMemoryEstimator { throw IllegalStateException("estimator fault") }, request()
        )
        assertTrue(thrown is TshResult.Failure)
        assertEquals("TSH-MEMORY-007", (thrown as TshResult.Failure).diagnostics.single().code)
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test fun rejectsSourceLengthThatWouldOverflowUtf16ByteCount() {
        assertThrows(IllegalArgumentException::class.java) {
            request(Long.MAX_VALUE)
        }
    }
}
