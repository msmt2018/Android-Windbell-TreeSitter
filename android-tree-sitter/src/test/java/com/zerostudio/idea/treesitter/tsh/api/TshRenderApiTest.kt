package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshRenderApiTest {
    private fun revisions(version: Long = 1L) = TshRenderRevisions(
        TshDocumentId("doc"), version, "config-1", "theme-1", "viewport-1", "renderer-1"
    )

    private fun plan(id: String = "plan-1", version: Long = 1L) = TshRenderPlan(
        TshRenderPlanId(id),
        revisions(version),
        listOf(TshRenderInstruction(
            range = TshSourceRange(
                TshSourcePosition(0L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                TshSourcePosition(4L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                documentVersion = version
            ),
            style = TshResolvedStyle(foregroundArgb = 0xFFFFFFFFu)
        )),
        requiredCapabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
    )

    private class ImmediateProvider : TshRenderProvider {
        override val id = "android-renderer"
        override val apiVersion = 1
        override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
        override fun submit(
            plan: TshRenderPlan,
            acknowledger: TshRenderAcknowledger
        ): TshResult<TshRenderReceipt> {
            assertTrue(acknowledger.isCurrent(plan.id, plan.revisions))
            return TshResult.Success(TshRenderReceipt(
                plan.id, id, plan.revisions, TshRenderReceiptStatus.DRAWN,
                plan.instructions.size
            ))
        }
    }

    private class QueuedProvider : TshRenderProvider {
        override val id = "async-renderer"
        override val apiVersion = 1
        override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
        lateinit var acknowledger: TshRenderAcknowledger
        lateinit var submittedPlan: TshRenderPlan
        override fun submit(
            plan: TshRenderPlan,
            acknowledger: TshRenderAcknowledger
        ): TshResult<TshRenderReceipt> {
            this.acknowledger = acknowledger
            this.submittedPlan = plan
            return TshResult.Success(TshRenderReceipt(
                plan.id, id, plan.revisions, TshRenderReceiptStatus.QUEUED, 0
            ))
        }
    }

    @Test
    fun validatesCapabilitiesAndConfirmsActualDrawOnlyFromReceipt() {
        val coordinator = TshRenderCoordinator(ImmediateProvider())
        coordinator.updateCurrentRevisions(revisions())
        val result = coordinator.submit(plan()) as TshResult.Success<TshRenderReceipt>
        assertTrue(result.value.confirmsActualDraw)
        assertTrue(coordinator.pendingPlanIds().isEmpty())
    }

    @Test
    fun providerLinkageFailureIsReturnedAsDiagnosticAndReleasesPendingPlan() {
        val provider = object : TshRenderProvider {
            override val id = "broken-renderer"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
            override fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger): TshResult<TshRenderReceipt> {
                throw UnsatisfiedLinkError("simulated backend linkage failure")
            }
        }
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val result = coordinator.submit(plan())
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-RENDER-024" })
        assertTrue(coordinator.pendingPlanIds().isEmpty())
    }

    @Test
    fun coordinatorSnapshotsCallerOwnedInstructionCollections() {
        val provider = QueuedProvider()
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val mutableInstructions = mutableListOf(plan().instructions.single())
        val callerPlan = plan().copy(instructions = mutableInstructions)
        coordinator.submit(callerPlan)
        mutableInstructions.clear()
        assertEquals(1, provider.submittedPlan.instructions.size)
        val receipt = provider.acknowledger.acknowledge(TshRenderReceipt(
            callerPlan.id, provider.id, callerPlan.revisions,
            TshRenderReceiptStatus.DRAWN, 1
        ))
        assertTrue(receipt is TshResult.Success)
    }

    @Test
    fun closeInvalidatesPendingPlansAndRejectsLateReceipts() {
        val provider = QueuedProvider()
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val queued = coordinator.submit(plan()) as TshResult.Success<TshRenderReceipt>
        val close = coordinator.close() as TshResult.Success<Unit>
        assertTrue(coordinator.pendingPlanIds().isEmpty())
        assertFalse(provider.acknowledger.isCurrent(queued.value.planId, queued.value.revisions))
        assertTrue(close.diagnostics.isNotEmpty())
        val late = provider.acknowledger.acknowledge(TshRenderReceipt(
            queued.value.planId, provider.id, queued.value.revisions,
            TshRenderReceiptStatus.DRAWN, 1
        ))
        assertTrue(late is TshResult.Failure)
        assertTrue(coordinator.submit(plan("after-close")) is TshResult.Failure)
    }

    @Test
    fun appliedReceiptCanAccountForInstructionsDeferredOutsideViewport() {
        val provider = object : TshRenderProvider {
            override val id = "viewport-renderer"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
            override fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger) =
                TshResult.Success(TshRenderReceipt(
                    plan.id, id, plan.revisions, TshRenderReceiptStatus.APPLIED,
                    appliedInstructionCount = 0,
                    deferredInstructionCount = plan.instructions.size
                ))
        }
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val result = coordinator.submit(plan()) as TshResult.Success<TshRenderReceipt>
        assertEquals(1, result.value.deferredInstructionCount)
        assertTrue(result.value.confirmsHostMutation)
        assertFalse(result.value.confirmsActualDraw)
    }

    @Test
    fun coordinatorBoundsAsynchronousPendingPlans() {
        val provider = QueuedProvider()
        val coordinator = TshRenderCoordinator(provider, maxPendingPlans = 1)
        coordinator.updateCurrentRevisions(revisions())
        assertTrue(coordinator.submit(plan("queued-1")) is TshResult.Success)
        val overflow = coordinator.submit(plan("queued-2"))
        assertTrue(overflow is TshResult.Failure)
        assertTrue(overflow.diagnostics().any { it.code == "TSH-RENDER-022" })
        assertEquals(setOf(TshRenderPlanId("queued-1")), coordinator.pendingPlanIds())
        val final = provider.acknowledger.acknowledge(TshRenderReceipt(
            TshRenderPlanId("queued-1"), provider.id, revisions(),
            TshRenderReceiptStatus.DRAWN, 1
        ))
        assertTrue(final is TshResult.Success)
    }

    @Test
    fun appliedReceiptDoesNotClaimThatPixelsWereDrawn() {
        val provider = object : TshRenderProvider {
            override val id = "span-provider"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
            override fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger) =
                TshResult.Success(TshRenderReceipt(
                    plan.id, id, plan.revisions, TshRenderReceiptStatus.APPLIED,
                    plan.instructions.size
                ))
        }
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val receipt = (coordinator.submit(plan()) as TshResult.Success).value
        assertTrue(receipt.confirmsHostMutation)
        assertFalse(receipt.confirmsActualDraw)
    }

    @Test
    fun rejectsStalePlanBeforeProviderSubmission() {
        val coordinator = TshRenderCoordinator(ImmediateProvider())
        coordinator.updateCurrentRevisions(revisions(2))
        val result = coordinator.submit(plan(version = 1))
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-RENDER-002" })
    }

    @Test
    fun asynchronousReceiptCannotCommitAfterRevisionChanges() {
        val provider = QueuedProvider()
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val queued = coordinator.submit(plan()) as TshResult.Success<TshRenderReceipt>
        assertEquals(TshRenderReceiptStatus.QUEUED, queued.value.status)
        assertTrue(provider.acknowledger.isCurrent(queued.value.planId, queued.value.revisions))
        coordinator.updateCurrentRevisions(revisions(2))
        assertFalse(provider.acknowledger.isCurrent(queued.value.planId, queued.value.revisions))
        val stale = provider.acknowledger.acknowledge(TshRenderReceipt(
            queued.value.planId, provider.id, queued.value.revisions,
            TshRenderReceiptStatus.DRAWN, 1
        ))
        assertTrue(stale is TshResult.Failure)
        assertTrue(stale.diagnostics().any { it.code == "TSH-RENDER-007" })
    }

    @Test
    fun partialReceiptConfirmsDrawOnlyWhenAtLeastOneInstructionWasDrawn() {
        val provider = object : TshRenderProvider {
            override val id = "partial-renderer"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
            override fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger) =
                TshResult.Success(TshRenderReceipt(
                    plan.id, id, plan.revisions, TshRenderReceiptStatus.PARTIAL, 1, droppedInstructionCount = 1
                ))
        }
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val basePlan = plan()
        val twoInstructionPlan = basePlan.copy(
            instructions = basePlan.instructions + basePlan.instructions.single()
        )
        val result = coordinator.submit(twoInstructionPlan) as TshResult.Success<TshRenderReceipt>
        assertTrue(result.value.confirmsActualDraw)
        assertTrue(coordinator.pendingPlanIds().isEmpty())
    }

    @Test
    fun queuedReceiptCannotClaimThatDrawingAlreadyHappened() {
        val provider = object : TshRenderProvider {
            override val id = "invalid-queued-renderer"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
            override fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger) =
                TshResult.Success(TshRenderReceipt(
                    plan.id, id, plan.revisions, TshRenderReceiptStatus.QUEUED, 1
                ))
        }
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val result = coordinator.submit(plan())
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-RENDER-020" })
        assertTrue(coordinator.pendingPlanIds().isEmpty())
    }

    @Test
    fun partialReceiptMustReportAtLeastOneAndFewerThanAllDrawnInstructions() {
        val provider = object : TshRenderProvider {
            override val id = "empty-partial-renderer"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.color", 1))
            override fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger) =
                TshResult.Success(TshRenderReceipt(
                    plan.id, id, plan.revisions, TshRenderReceiptStatus.PARTIAL, 0
                ))
        }
        val coordinator = TshRenderCoordinator(provider)
        coordinator.updateCurrentRevisions(revisions())
        val result = coordinator.submit(plan())
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-RENDER-018" })
        assertTrue(coordinator.pendingPlanIds().isEmpty())
    }

    @Test
    fun pendingRenderPlanReservationIsReleasedAfterTerminalReceipt() {
        val provider = QueuedProvider()
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(4_096, 8_192))
        val coordinator = TshRenderCoordinator(provider, memoryBudget = budget)
        coordinator.updateCurrentRevisions(revisions())
        val queued = coordinator.submit(plan()) as TshResult.Success<TshRenderReceipt>
        assertTrue(budget.snapshot().reservedBytes > 0L)
        assertEquals(1, budget.snapshot().reservations)

        val receipt = provider.acknowledger.acknowledge(TshRenderReceipt(
            queued.value.planId, provider.id, queued.value.revisions,
            TshRenderReceiptStatus.DRAWN, 1
        ))
        assertTrue(receipt is TshResult.Success)
        assertEquals(0L, budget.snapshot().reservedBytes)
        assertEquals(0, budget.snapshot().reservations)
    }

    @Test
    fun pendingRenderPlanReservationIsReleasedWhenCoordinatorCloses() {
        val provider = QueuedProvider()
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(4_096, 8_192))
        val coordinator = TshRenderCoordinator(provider, memoryBudget = budget)
        coordinator.updateCurrentRevisions(revisions())
        assertTrue(coordinator.submit(plan()) is TshResult.Success)
        assertTrue(budget.snapshot().reservedBytes > 0L)
        coordinator.close()
        assertEquals(0L, budget.snapshot().reservedBytes)
    }

    @Test
    fun renderPlanIsRejectedBeforeProviderSubmissionWhenMemoryBudgetIsInsufficient() {
        val provider = QueuedProvider()
        val budget = TshMemoryBudget(TshMemoryBudgetOptions(128, 256))
        val coordinator = TshRenderCoordinator(provider, memoryBudget = budget)
        coordinator.updateCurrentRevisions(revisions())
        val result = coordinator.submit(plan())
        assertTrue(result is TshResult.Failure)
        assertEquals(TshDiagnosticStage.MEMORY, result.diagnostics().single().stage)
        assertTrue(provider::class.java.getDeclaredField("acknowledger").let {
            runCatching { it.isAccessible = true; it.get(provider) }.getOrNull() == null
        })
        assertEquals(0L, budget.snapshot().reservedBytes)
        assertTrue(coordinator.pendingPlanIds().isEmpty())
    }

}
