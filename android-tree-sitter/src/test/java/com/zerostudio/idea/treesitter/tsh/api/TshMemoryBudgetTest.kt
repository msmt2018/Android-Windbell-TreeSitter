package com.zerostudio.idea.treesitter.tsh.api

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TshMemoryBudgetTest {
    @Test fun defaultsAndAdaptiveLimitAreBounded() {
        val defaults = TshMemoryBudget()
        assertEquals(100L * TshMemoryBudgetOptions.MIB, defaults.limitBytes)
        assertEquals(512L * TshMemoryBudgetOptions.MIB, defaults.hardLimitBytes)
        val adapted = TshMemoryBudgetOptions(availableMemoryBytes = 120L * TshMemoryBudgetOptions.MIB)
        assertEquals(30L * TshMemoryBudgetOptions.MIB, adapted.effectiveLimitBytes)
    }

    @Test fun rejectsInvalidAndOverBudgetReservations() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(10, 20))
        val invalid = budget.reserve(TshMemoryCategory.OTHER, 0)
        assertTrue(invalid is TshResult.Failure)
        assertEquals(TshDiagnosticStage.MEMORY, (invalid as TshResult.Failure).diagnostics.single().stage)
        assertTrue(budget.reserve(TshMemoryCategory.OTHER, 8) is TshResult.Success)
        assertTrue(budget.reserve(TshMemoryCategory.QUERY_RESULT, 3) is TshResult.Failure)
        assertEquals(8L, budget.snapshot().reservedBytes)
    }

    @Test fun categoryStatisticsAndReleaseAreIdempotent() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(100, 200))
        val a = (budget.reserve(TshMemoryCategory.QUERY_RESULT, 20, "q") as TshResult.Success).value
        val b = (budget.reserve(TshMemoryCategory.RENDER_PLAN, 30) as TshResult.Success).value
        assertEquals(20L, budget.snapshot().bytesByCategory[TshMemoryCategory.QUERY_RESULT])
        a.close()
        a.close()
        assertTrue(a.isClosed)
        assertEquals(30L, budget.snapshot().reservedBytes)
        b.close()
        assertEquals(50L, budget.snapshot().highWatermarkBytes)
        assertEquals(0, budget.snapshot().reservations)
    }

    @Test fun closingBudgetRejectsNewWorkButAllowsExistingRelease() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(100, 200))
        val token = (budget.reserve(TshMemoryCategory.TEXT_SNAPSHOT, 40) as TshResult.Success).value
        budget.close()
        assertTrue(budget.reserve(TshMemoryCategory.OTHER, 1) is TshResult.Failure)
        token.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test fun concurrentReservationsNeverExceedLimit() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(100, 200))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(100) {
                    val result = budget.reserve(TshMemoryCategory.MATCH_SET, 3)
                    if (result is TshResult.Success) result.value.close()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0L, budget.snapshot().reservedBytes)
        assertTrue(budget.snapshot().highWatermarkBytes <= budget.limitBytes)
    }

    @Test fun reservationCountIsBoundedIndependentlyFromReservedBytes() {
        val budget = TshMemoryBudget(
            TshMemoryBudgetOptions(targetBytes = 10, hardLimitBytes = 20, maxReservations = 2)
        )
        val first = (budget.reserve(TshMemoryCategory.OTHER, 1) as TshResult.Success).value
        val second = (budget.reserve(TshMemoryCategory.OTHER, 1) as TshResult.Success).value

        val rejected = budget.reserve(TshMemoryCategory.OTHER, 1)
        assertTrue(rejected is TshResult.Failure)
        assertEquals("TSH-MEMORY-010", (rejected as TshResult.Failure).diagnostics.single().code)
        assertEquals(2, budget.snapshot().reservations)
        assertEquals(2, budget.snapshot().maxReservations)

        first.close()
        val replacement = budget.reserve(TshMemoryCategory.OTHER, 1)
        assertTrue(replacement is TshResult.Success)
        second.close()
        (replacement as TshResult.Success).value.close()
        assertEquals(0, budget.snapshot().reservations)
    }

    @Test fun reservationCountOptionCannotExceedAbsoluteSafetyLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            TshMemoryBudgetOptions(
                targetBytes = 10,
                hardLimitBytes = 20,
                maxReservations = TshMemoryBudgetOptions.ABSOLUTE_MAX_RESERVATIONS + 1
            )
        }
    }

    @Test fun validatesAbsoluteHardCapAndTargetOrdering() {
        assertThrows(IllegalArgumentException::class.java) {
            TshMemoryBudgetOptions(600L * TshMemoryBudgetOptions.MIB, 600L * TshMemoryBudgetOptions.MIB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TshMemoryBudgetOptions(21, 20)
        }
    }
}
