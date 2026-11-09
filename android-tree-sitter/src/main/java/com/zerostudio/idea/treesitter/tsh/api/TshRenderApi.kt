package com.zerostudio.idea.treesitter.tsh.api

import java.util.Collections

@JvmInline
value class TshRenderPlanId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class TshResolvedStyle(
    val foregroundArgb: UInt? = null,
    val backgroundArgb: UInt? = null,
    val fontFamilies: List<String> = emptyList(),
    val fontSize: TshDslValue.MeasureValue? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikeThrough: Boolean? = null,
    val decorations: Set<String> = emptySet()
) {
    init {
        require(fontFamilies.none { it.isBlank() })
        require(fontWeight == null || fontWeight in 1..1000)
        require(decorations.none { it.isBlank() })
    }
}

data class TshRenderInstruction(
    val range: TshSourceRange,
    val style: TshResolvedStyle,
    val zIndex: Int = 0,
    val priority: Int = 0,
    val sourceMatchIds: Set<TshMatchId> = emptySet()
) {
    init {
        require(range.start.coordinateSystem == TshCoordinateSystem.UTF16_CODE_UNIT)
    }
}

data class TshRenderRevisions(
    val documentId: TshDocumentId,
    val documentVersion: Long,
    val configRevision: String,
    val themeRevision: String,
    val viewportRevision: String,
    val rendererRevision: String,
    val treeSitterRevision: String? = null,
    val regexRevision: String? = null
) {
    init {
        require(documentVersion >= 0L)
        require(configRevision.isNotBlank())
        require(themeRevision.isNotBlank())
        require(viewportRevision.isNotBlank())
        require(rendererRevision.isNotBlank())
        require(treeSitterRevision == null || treeSitterRevision.isNotBlank())
        require(regexRevision == null || regexRevision.isNotBlank())
    }
}

data class TshRenderPlan(
    val id: TshRenderPlanId,
    val revisions: TshRenderRevisions,
    val instructions: List<TshRenderInstruction>,
    val requiredCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY
) {
    init {
        require(instructions.all {
            it.range.documentVersion == null || it.range.documentVersion == revisions.documentVersion
        })
    }
}

enum class TshRenderReceiptStatus { QUEUED, DRAWN, PARTIAL, APPLIED, PARTIALLY_APPLIED, REJECTED, FAILED }

data class TshRenderReceipt(
    val planId: TshRenderPlanId,
    val providerId: String,
    val revisions: TshRenderRevisions,
    val status: TshRenderReceiptStatus,
    val appliedInstructionCount: Int,
    val diagnostics: List<TshDiagnostic> = emptyList()
) {
    init {
        require(providerId.isNotBlank())
        require(appliedInstructionCount >= 0)
    }

    /** Compatibility accessor; for APPLIED statuses this is not a pixel-draw count. */
    @Deprecated("Use appliedInstructionCount; host mutation does not prove pixels were drawn")
    val drawnInstructionCount: Int get() = appliedInstructionCount

    /** True only when the host confirms an actual drawing operation, not just Span mutation. */
    val confirmsActualDraw: Boolean
        get() = (status == TshRenderReceiptStatus.DRAWN || status == TshRenderReceiptStatus.PARTIAL) &&
            appliedInstructionCount > 0

    /** True when the host display object was updated, even if a later View draw has not occurred. */
    val confirmsHostMutation: Boolean
        get() = (status == TshRenderReceiptStatus.APPLIED ||
            status == TshRenderReceiptStatus.PARTIALLY_APPLIED) && appliedInstructionCount > 0
}

interface TshRenderAcknowledger {
    /** Providers must check this immediately before every actual UI draw/commit. */
    fun isCurrent(planId: TshRenderPlanId, revisions: TshRenderRevisions): Boolean
    fun acknowledge(receipt: TshRenderReceipt): TshResult<TshRenderReceipt>
}

/** Renderer owns all View/Canvas/Span/Compose objects and must only mutate TSH-owned draw state. */
interface TshRenderProvider {
    val id: String
    val apiVersion: Int
    val capabilities: TshCapabilitySet

    /**
     * QUEUED may be acknowledged asynchronously after this method returns. Synchronous providers
     * return a final receipt directly and must not invoke acknowledger before returning.
     */
    fun submit(plan: TshRenderPlan, acknowledger: TshRenderAcknowledger): TshResult<TshRenderReceipt>

    fun cancel(planId: TshRenderPlanId): TshResult<Unit> = TshResult.Failure(listOf(TshDiagnostic(
        code = "TSH-RENDER-013",
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.RENDER,
        message = "Renderer provider does not support cancellation"
    )))
}

/** Version-safe render submission coordinator. It never draws; it validates revisions and receipts. */
class TshRenderCoordinator(
    private val provider: TshRenderProvider
) : TshRenderAcknowledger {
    init {
        require(provider.id.isNotBlank())
        require(provider.apiVersion > 0)
    }

    private val lock = Any()
    private val pending = LinkedHashMap<TshRenderPlanId, TshRenderPlan>()
    private var currentRevisions: TshRenderRevisions? = null

    fun updateCurrentRevisions(revisions: TshRenderRevisions) {
        synchronized(lock) { currentRevisions = revisions }
    }

    fun submit(plan: TshRenderPlan): TshResult<TshRenderReceipt> {
        // Snapshot caller-owned collections before storing or handing the plan to third-party code.
        val stablePlan = snapshotPlan(plan)
        val current = synchronized(lock) { currentRevisions }
            ?: return failure("TSH-RENDER-001", "Current render revisions have not been installed")
        if (stablePlan.revisions != current) {
            return failure("TSH-RENDER-002", "RenderPlan revisions are stale")
        }
        val missing = stablePlan.requiredCapabilities.all().filterNot { required ->
            provider.capabilities.satisfies(required)
        }
        if (missing.isNotEmpty()) {
            return failure(
                "TSH-RENDER-003",
                "Renderer '${provider.id}' lacks required capabilities: ${missing.joinToString { it.id }}"
            )
        }
        synchronized(lock) {
            if (currentRevisions != stablePlan.revisions) {
                return failure("TSH-RENDER-002", "RenderPlan became stale before submission")
            }
            if (pending.containsKey(stablePlan.id)) {
                return failure("TSH-RENDER-004", "RenderPlan id is already pending")
            }
            pending[stablePlan.id] = stablePlan
        }

        val submitted = try {
            provider.submit(stablePlan, this)
        } catch (error: Exception) {
            synchronized(lock) { pending.remove(stablePlan.id) }
            return failure("TSH-RENDER-005", "Renderer provider threw during submission", error)
        }
        return when (submitted) {
            is TshResult.Failure -> {
                synchronized(lock) { pending.remove(stablePlan.id) }
                submitted
            }
            is TshResult.Success -> {
                val receipt = submitted.value
                validateReceipt(stablePlan, receipt)?.let { problem ->
                    synchronized(lock) { pending.remove(stablePlan.id) }
                    return TshResult.Failure(listOf(problem))
                }
                if (receipt.status == TshRenderReceiptStatus.QUEUED) {
                    TshResult.Success(receipt, submitted.diagnostics + receipt.diagnostics)
                } else {
                    acknowledge(receipt)
                }
            }
        }
    }

    override fun isCurrent(planId: TshRenderPlanId, revisions: TshRenderRevisions): Boolean =
        synchronized(lock) {
            pending.containsKey(planId) && currentRevisions == revisions &&
                pending[planId]?.revisions == revisions
        }

    /** Called by the renderer after asynchronous drawing completes. */
    override fun acknowledge(receipt: TshRenderReceipt): TshResult<TshRenderReceipt> {
        val plan = synchronized(lock) { pending[receipt.planId] }
            ?: return failure("TSH-RENDER-006", "Receipt refers to an unknown or already completed RenderPlan")
        validateReceipt(plan, receipt)?.let { problem ->
            synchronized(lock) { pending.remove(receipt.planId) }
            return TshResult.Failure(listOf(problem))
        }
        val current = synchronized(lock) { currentRevisions }
        if (current != plan.revisions) {
            synchronized(lock) { pending.remove(receipt.planId) }
            return failure("TSH-RENDER-007", "Renderer acknowledged a stale RenderPlan")
        }
        if (receipt.status != TshRenderReceiptStatus.QUEUED) {
            synchronized(lock) { pending.remove(receipt.planId) }
        }
        when (receipt.status) {
            TshRenderReceiptStatus.REJECTED, TshRenderReceiptStatus.FAILED -> {
                val errors = receipt.diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR }
                TshResult.Failure(errors.ifEmpty { listOf(diagnostic(
                    "TSH-RENDER-014", "Renderer reported ${receipt.status}"
                )) })
            }
            TshRenderReceiptStatus.PARTIAL, TshRenderReceiptStatus.PARTIALLY_APPLIED -> TshResult.Success(
                receipt,
                receipt.diagnostics + if (receipt.diagnostics.none { it.severity == TshDiagnosticSeverity.WARNING }) {
                    listOf(TshDiagnostic(
                        "TSH-RENDER-015", TshDiagnosticSeverity.WARNING,
                        TshDiagnosticStage.RENDER, "Renderer completed only part of the RenderPlan"
                    ))
                } else emptyList()
            )
            else -> TshResult.Success(receipt, receipt.diagnostics)
        }
    }

    fun cancelPending(planId: TshRenderPlanId): TshResult<Unit> {
        val plan = synchronized(lock) { pending[planId] }
            ?: return failure("TSH-RENDER-016", "RenderPlan is not pending")
        val result = try {
            provider.cancel(planId)
        } catch (error: Exception) {
            return failure("TSH-RENDER-017", "Renderer cancellation failed", error)
        }
        if (result is TshResult.Success) {
            synchronized(lock) { pending.remove(planId) }
        }
        return result
    }

    fun pendingPlanIds(): Set<TshRenderPlanId> = synchronized(lock) { pending.keys.toSet() }

    private fun snapshotPlan(plan: TshRenderPlan): TshRenderPlan = plan.copy(
        instructions = Collections.unmodifiableList(plan.instructions.map { instruction ->
            instruction.copy(
                style = instruction.style.copy(
                    fontFamilies = Collections.unmodifiableList(instruction.style.fontFamilies.toList()),
                    decorations = Collections.unmodifiableSet(instruction.style.decorations.toSet())
                ),
                sourceMatchIds = Collections.unmodifiableSet(instruction.sourceMatchIds.toSet())
            )
        }.toList())
    )

    private fun validateReceipt(plan: TshRenderPlan, receipt: TshRenderReceipt): TshDiagnostic? {
        if (receipt.planId != plan.id) return diagnostic("TSH-RENDER-008", "Receipt plan id does not match submitted plan")
        if (receipt.providerId != provider.id) return diagnostic("TSH-RENDER-009", "Receipt provider id does not match renderer")
        if (receipt.revisions != plan.revisions) return diagnostic("TSH-RENDER-010", "Receipt revisions do not match submitted plan")
        if (receipt.appliedInstructionCount > plan.instructions.size) {
            return diagnostic("TSH-RENDER-011", "Receipt reports more drawn instructions than the plan contains")
        }
        if (receipt.status == TshRenderReceiptStatus.QUEUED && receipt.appliedInstructionCount != 0) {
            return diagnostic("TSH-RENDER-020", "QUEUED receipt cannot claim instructions were drawn")
        }
        if ((receipt.status == TshRenderReceiptStatus.DRAWN ||
                receipt.status == TshRenderReceiptStatus.APPLIED) &&
            receipt.appliedInstructionCount != plan.instructions.size) {
            return diagnostic("TSH-RENDER-012", "DRAWN/APPLIED receipt must confirm every instruction")
        }
        if (receipt.status == TshRenderReceiptStatus.PARTIAL &&
            (receipt.appliedInstructionCount == 0 ||
                receipt.appliedInstructionCount >= plan.instructions.size)) {
            return diagnostic("TSH-RENDER-018", "PARTIAL draw receipt must report at least one but fewer than all instructions")
        }
        // PARTIAL may mean only some style attributes were applied even when every range was touched.
        if ((receipt.status == TshRenderReceiptStatus.REJECTED ||
                receipt.status == TshRenderReceiptStatus.FAILED) && receipt.drawnInstructionCount != 0) {
            return diagnostic("TSH-RENDER-019", "Rejected/failed receipt cannot report drawn instructions")
        }
        return null
    }

    private fun failure(code: String, message: String, cause: Throwable? = null) =
        TshResult.Failure(listOf(diagnostic(code, message, cause)))

    private fun diagnostic(code: String, message: String, cause: Throwable? = null) =
        TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.RENDER,
            message = message,
            cause = cause
        )
}
