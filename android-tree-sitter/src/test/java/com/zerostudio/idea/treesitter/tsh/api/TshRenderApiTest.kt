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
                    plan.id, id, plan.revisions, TshRenderReceiptStatus.PARTIAL, 1
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
}
