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

    @Test fun reservationResizeIsAtomicAndPreservesCategoryAccounting() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(100, 200))
        val token = (budget.reserve(TshMemoryCategory.SYNTAX_TREE, 40, "tree") as TshResult.Success).value

        assertTrue(token.resize(70) is TshResult.Success)
        assertEquals(70L, token.bytes)
        assertEquals(70L, budget.snapshot().reservedBytes)
        assertEquals(70L, budget.snapshot().bytesByCategory[TshMemoryCategory.SYNTAX_TREE])
        assertEquals(70L, budget.snapshot().highWatermarkBytes)

        val rejected = token.resize(101)
        assertTrue(rejected is TshResult.Failure)
        assertEquals(70L, token.bytes)
        assertEquals(70L, budget.snapshot().reservedBytes)

        assertTrue(token.resize(15) is TshResult.Success)
        assertEquals(15L, budget.snapshot().reservedBytes)
        budget.close()
        assertTrue("Shrinking retained memory remains legal after shutdown", token.resize(10) is TshResult.Success)
        assertTrue("Growing after shutdown must be rejected", token.resize(11) is TshResult.Failure)
        token.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
        assertTrue(token.resize(1) is TshResult.Failure)
    }

    @Test fun runtimeMemoryPressureLowersAdmissionWithoutInvalidatingRetainedReservations() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(targetBytes = 100, hardLimitBytes = 200))
        val retained = (budget.reserve(TshMemoryCategory.TEXT_SNAPSHOT, 8) as TshResult.Success).value

        assertTrue(budget.updateAvailableMemory(16) is TshResult.Success)
        assertEquals(4L, budget.limitBytes)
        assertEquals(8L, budget.snapshot().reservedBytes)
        assertTrue("New work must be rejected while retained memory exceeds the new limit",
            budget.reserve(TshMemoryCategory.QUERY_RESULT, 1) is TshResult.Failure)

        assertTrue("Existing owners must still be able to shrink retained allocations",
            retained.resize(4) is TshResult.Success)
        assertTrue(budget.reserve(TshMemoryCategory.QUERY_RESULT, 1) is TshResult.Success)

        assertTrue("Null restores the configured baseline", budget.updateAvailableMemory(null) is TshResult.Success)
        assertEquals(100L, budget.limitBytes)
        retained.close()
    }

    @Test fun runtimeMemoryPressureRejectsInvalidSamplesAndClosedBudgets() {
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(targetBytes = 100, hardLimitBytes = 200))
        assertTrue(budget.updateAvailableMemory(0) is TshResult.Failure)
        budget.close()
        assertTrue(budget.updateAvailableMemory(16) is TshResult.Failure)
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

    @Test fun activeReservationCountIsBoundedAndReleasedSlotsCanBeReused() {
        val budget = TshMemoryBudget(
            TshMemoryBudgetOptions(targetBytes = 100, hardLimitBytes = 200, maxActiveReservations = 2)
        )
        val first = (budget.reserve(TshMemoryCategory.OTHER, 1) as TshResult.Success).value
        val second = (budget.reserve(TshMemoryCategory.OTHER, 1) as TshResult.Success).value
        val rejected = budget.reserve(TshMemoryCategory.OTHER, 1)
        assertTrue(rejected is TshResult.Failure)
        assertEquals("TSH-MEMORY-010", (rejected as TshResult.Failure).diagnostics.single().code)
        first.close()
        val third = budget.reserve(TshMemoryCategory.OTHER, 1) as TshResult.Success
        third.value.close()
        second.close()
        assertEquals(0, budget.snapshot().reservations)
    }

    @Test fun validatesReservationCountLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            TshMemoryBudgetOptions(maxActiveReservations = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TshMemoryBudgetOptions(maxActiveReservations = TshMemoryBudgetOptions.ABSOLUTE_MAX_ACTIVE_RESERVATIONS + 1)
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
