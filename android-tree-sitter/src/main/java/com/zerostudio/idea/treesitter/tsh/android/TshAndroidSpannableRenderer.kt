package com.zerostudio.idea.treesitter.tsh.android

import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage
import com.zerostudio.idea.treesitter.tsh.api.TshRenderAcknowledger
import com.zerostudio.idea.treesitter.tsh.api.TshRenderPlan
import com.zerostudio.idea.treesitter.tsh.api.TshRenderProvider
import com.zerostudio.idea.treesitter.tsh.api.TshRenderReceipt
import com.zerostudio.idea.treesitter.tsh.api.TshRenderReceiptStatus
import com.zerostudio.idea.treesitter.tsh.api.TshRenderRevisions
import com.zerostudio.idea.treesitter.tsh.api.TshCapability
import com.zerostudio.idea.treesitter.tsh.api.TshCapabilitySet
import com.zerostudio.idea.treesitter.tsh.api.TshDslValue
import com.zerostudio.idea.treesitter.tsh.api.TshResult

/** UTF-16 half-open visible range supplied by the host editor's viewport. */
data class TshAndroidVisibleRange(val startUtf16: Long, val endUtf16: Long) {
    init {
        require(startUtf16 >= 0L && endUtf16 >= startUtf16)
    }
}

/** Host adapter for an existing editor text object; no Editor/View inheritance is required. */
interface TshAndroidSpannableRenderTarget {
    /** Exact host object this adapter serves; override when the adapter is a separate wrapper. */
    val hostObject: Any get() = this
    fun isOnRenderThread(): Boolean
    fun currentRevisions(documentId: String): TshRenderRevisions?
    fun resolveSpannable(documentId: String): Spannable?
    /** Return a bounded host viewport in absolute UTF-16 code units; null rejects rendering. */
    fun visibleRangeUtf16(documentId: String): TshAndroidVisibleRange?
    fun resolveTextSizePx(value: TshDslValue.MeasureValue): Float?
    fun invalidate(documentId: String)
    /** Optional asynchronous dispatch used to remove TSH-owned Spans during off-thread detach. */
    fun postToRenderThread(action: () -> Unit): Boolean = false
}

private val NEXT_ANDROID_RENDERER_ID = AtomicLong(0L)

private interface TshAndroidOwnedSpan {
    val ownerId: String
    val planId: String
}

private data class TshAndroidSpanSnapshot(
    val span: Any,
    val start: Int,
    val end: Int,
    val flags: Int
)

private class TshAndroidForegroundSpan(
    override val ownerId: String,
    override val planId: String,
    private val color: Int
) : CharacterStyle(), TshAndroidOwnedSpan {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.color = color
    }
}

private class TshAndroidBackgroundSpan(
    override val ownerId: String,
    override val planId: String,
    private val color: Int
) : CharacterStyle(), TshAndroidOwnedSpan {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.bgColor = color
    }
}

private class TshAndroidTypefaceSpan(
    override val ownerId: String,
    override val planId: String,
    private val family: String?,
    private val weight: Int?,
    private val style: Int
) : MetricAffectingSpan(), TshAndroidOwnedSpan {
    private fun resolve(base: Typeface): Typeface {
        if (Build.VERSION.SDK_INT >= 28) {
            val baseTypeface = family?.let { Typeface.create(it, Typeface.NORMAL) } ?: base
            return Typeface.create(
                baseTypeface,
                weight ?: baseTypeface.weight,
                (style and Typeface.ITALIC) != 0
            )
        }
        return family?.let { Typeface.create(it, style) } ?: Typeface.create(base, style)
    }

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.typeface = resolve(textPaint.typeface)
    }
    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = resolve(textPaint.typeface)
    }
}

private class TshAndroidTextSizeSpan(
    override val ownerId: String,
    override val planId: String,
    private val sizePx: Float
) : MetricAffectingSpan(), TshAndroidOwnedSpan {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.textSize = sizePx
    }
    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.textSize = sizePx
    }
}

private class TshAndroidDecorationSpan(
    override val ownerId: String,
    override val planId: String,
    private val underline: Boolean,
    private val strikeThrough: Boolean
) : CharacterStyle(), TshAndroidOwnedSpan {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.isUnderlineText = underline
        textPaint.isStrikeThruText = strikeThrough
    }
}

/**
 * Android Spannable backend. It only removes spans created by this provider and reports an APPLIED
 * receipt after the host Spannable has been mutated and invalidated. Device pixel visibility must
 * still be verified by the host's real renderer tests.
 */
class TshAndroidSpannableRenderer(
    private val target: TshAndroidSpannableRenderTarget,
    override val id: String = "tsh-android-spannable-${NEXT_ANDROID_RENDERER_ID.incrementAndGet()}"
) : TshRenderProvider {
    override val apiVersion: Int = 1
    init { require(id.isNotBlank()) }

    private val closed = AtomicBoolean(false)
    private val renderedDocumentIds = LinkedHashSet<String>()
    @Volatile var cleanupDiagnostics: List<TshDiagnostic> = emptyList()
        private set

    override val capabilities: TshCapabilitySet = TshCapabilitySet.of(
        TshCapability("render.foreground", 1),
        TshCapability("render.background", 1),
        TshCapability("render.font", 1),
        TshCapability("render.decoration", 1),
        TshCapability("render.partial-range", 1)
    )

    override fun supports(instance: Any, descriptor: com.zerostudio.idea.treesitter.tsh.api.TshObjectDescriptor): Boolean =
        instance === target.hostObject

    internal fun hostObjectForBinding(): Any = target.hostObject

    /** Registers this renderer and attaches the exact host object for the OwnerScope lifetime. */
    fun attachTo(
        engine: com.zerostudio.idea.treesitter.tsh.api.TshEngine,
        ownerScope: com.zerostudio.idea.treesitter.tsh.api.TshOwnerScope,
        objectType: String = "editor",
        objectCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
        metadata: Map<String, String> = emptyMap(),
        requiredCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
        optionalCapabilities: TshCapabilitySet? = null
    ): TshResult<TshAndroidSpannableBinding> = TshAndroidSpannableBinding.attach(
        engine, ownerScope, this, objectType, objectCapabilities, metadata,
        requiredCapabilities, optionalCapabilities ?: TshCapabilitySet.from(
            capabilities.all().filterNot { requiredCapabilities.contains(it.id) }
        )
    )

    override fun submit(
        plan: TshRenderPlan,
        acknowledger: TshRenderAcknowledger
    ): TshResult<TshRenderReceipt> {
        if (closed.get()) return failure("TSH-ANDROID-RENDER-039", "Android Spannable renderer is closed")
        val onRenderThread = try {
            target.isOnRenderThread()
        } catch (error: Exception) {
            return failure("TSH-ANDROID-RENDER-024", "Cannot verify host render thread: ${error.message}")
        } catch (error: LinkageError) {
            return failure("TSH-ANDROID-RENDER-025", "Host render-thread check failed with a linkage error")
        }
        if (!onRenderThread) {
            return failure("TSH-ANDROID-RENDER-001", "Spannable rendering must run on the host render thread")
        }
        val documentId = plan.revisions.documentId.value
        val initiallyCurrent = try {
            !closed.get() && acknowledger.isCurrent(plan.id, plan.revisions) &&
                target.currentRevisions(documentId) == plan.revisions
        } catch (error: Exception) {
            return failure("TSH-ANDROID-RENDER-026", "Cannot verify initial render revisions: ${error.message}")
        } catch (error: LinkageError) {
            return failure("TSH-ANDROID-RENDER-027", "Initial revision check failed with a linkage error")
        }
        if (!initiallyCurrent) {
            return failure("TSH-ANDROID-RENDER-002", "RenderPlan is stale before Spannable mutation")
        }
        val spannable = try {
            target.resolveSpannable(documentId)
        } catch (error: Exception) {
            return failure("TSH-ANDROID-RENDER-028", "Host Spannable resolution failed: ${error.message}")
        } catch (error: LinkageError) {
            return failure("TSH-ANDROID-RENDER-029", "Host Spannable resolution failed with a linkage error")
        } ?: return failure("TSH-ANDROID-RENDER-003", "Host did not provide a Spannable for the document")
        val viewport = try {
            target.visibleRangeUtf16(documentId)
        } catch (error: Exception) {
            return failure("TSH-ANDROID-RENDER-030", "Host viewport lookup failed: ${error.message}")
        } catch (error: LinkageError) {
            return failure("TSH-ANDROID-RENDER-031", "Host viewport lookup failed with a linkage error")
        } ?: return failure(
            "TSH-ANDROID-RENDER-018",
            "Host must provide a bounded UTF-16 viewport; whole-document Span rendering is disabled by default"
        )
        if (viewport.endUtf16 > spannable.length.toLong()) {
            return failure(
                "TSH-ANDROID-RENDER-015",
                "Host viewport range exceeds the current Spannable length"
            )
        }
        val trackedForCleanup = synchronized(renderedDocumentIds) {
            if (closed.get()) false else {
                renderedDocumentIds += documentId
                true
            }
        }
        if (!trackedForCleanup) return failure(
            "TSH-ANDROID-RENDER-039",
            "Android Spannable renderer closed while preparing the document viewport"
        )
        val diagnostics = ArrayList<TshDiagnostic>()
        // Keep the last committed TSH spans until the replacement plan is fully applied.
        val previousSpans = try {
            captureOwnedSpans(spannable)
        } catch (error: Exception) {
            return failure("TSH-ANDROID-RENDER-032", "Cannot snapshot existing host Spans: ${error.message}")
        } catch (error: LinkageError) {
            return failure("TSH-ANDROID-RENDER-033", "Host Span snapshot failed with a linkage error")
        }

        var appliedInstructionCount = 0
        var deferredInstructionCount = 0
        var droppedInstructionCount = 0
        var visibleInstructionCount = 0
        var fullyApplied = 0
        for (instruction in plan.instructions) {
            val currentDuringMutation = try {
                !closed.get() && acknowledger.isCurrent(plan.id, plan.revisions) &&
                    target.currentRevisions(documentId) == plan.revisions
            } catch (error: Exception) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-019", "Host revision check failed during Span mutation: ${error.message}")
            } catch (error: LinkageError) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-020", "Host revision check failed with a linkage error during Span mutation")
            }
            if (!currentDuringMutation) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-004", "RenderPlan became stale during Spannable mutation")
            }
            val rawStart = instruction.range.start.offset
            val rawEnd = instruction.range.end.offset
            if (rawStart < 0L || rawEnd <= rawStart || rawEnd > spannable.length.toLong() ||
                rawStart > Int.MAX_VALUE.toLong()) {
                diagnostics += warning(
                    "TSH-ANDROID-RENDER-005",
                    "Instruction range is empty or outside the current Spannable"
                )
                droppedInstructionCount++
                continue
            }
            val start = maxOf(rawStart, viewport.startUtf16)
            val end = minOf(rawEnd, viewport.endUtf16)
            if (end <= start) {
                diagnostics += TshDiagnostic(
                    "TSH-ANDROID-RENDER-016", TshDiagnosticSeverity.INFO,
                    TshDiagnosticStage.RENDER,
                    "Instruction is outside the current host viewport and was deferred"
                )
                deferredInstructionCount++
                continue
            }
            visibleInstructionCount++
            if (instruction.zIndex !in 0..255) {
                diagnostics += warning(
                    "TSH-ANDROID-RENDER-012",
                    "Android Spannable supports zIndex values from 0 to 255; this instruction was skipped"
                )
                droppedInstructionCount++
                continue
            }
            val clippedToViewport = start != rawStart || end != rawEnd
            val style = instruction.style
            val diagnosticsBefore = diagnostics.size
            val before = try {
                spannable.getSpans(start.toInt(), end.toInt(), CharacterStyle::class.java)
                    .count { it is TshAndroidOwnedSpan && it.ownerId == id && it.planId == plan.id.value }
            } catch (error: Exception) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-034", "Cannot inspect existing spans in the target range: ${error.message}")
            } catch (error: LinkageError) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-035", "Host Span inspection failed with a linkage error")
            }
            val spanPriority = instruction.zIndex shl Spanned.SPAN_PRIORITY_SHIFT
            val spanFlags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or spanPriority
            try {
                applyStyle(spannable, start.toInt(), end.toInt(), plan.id.value, style, spanFlags, diagnostics)
            } catch (error: Exception) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-010", "Applying a Spannable style failed: ${error.message}")
            } catch (error: LinkageError) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-021", "Applying a Spannable style failed with a linkage error")
            }
            val after = try {
                spannable.getSpans(start.toInt(), end.toInt(), CharacterStyle::class.java)
                    .count { it is TshAndroidOwnedSpan && it.ownerId == id && it.planId == plan.id.value }
            } catch (error: Exception) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-036", "Cannot verify applied host spans: ${error.message}")
            } catch (error: LinkageError) {
                removeNewOwnedSpans(spannable, previousSpans)
                return failure("TSH-ANDROID-RENDER-037", "Host Span verification failed with a linkage error")
            }
            val hadWarning = diagnostics.size > diagnosticsBefore
            if (after > before) {
                appliedInstructionCount++
                if (!hadWarning) fullyApplied++
                if (clippedToViewport) diagnostics += TshDiagnostic(
                    "TSH-ANDROID-RENDER-017", TshDiagnosticSeverity.INFO,
                    TshDiagnosticStage.RENDER,
                    "Instruction was clipped to the current host viewport"
                )
            } else {
                if (!hadWarning) diagnostics += warning(
                    "TSH-ANDROID-RENDER-006", "Instruction contains no drawable style attributes"
                )
                droppedInstructionCount++
            }
        }

        val currentBeforeCommit = try {
            !closed.get() && acknowledger.isCurrent(plan.id, plan.revisions) &&
                target.currentRevisions(documentId) == plan.revisions
        } catch (error: Exception) {
            removeNewOwnedSpans(spannable, previousSpans)
            return failure("TSH-ANDROID-RENDER-022", "Host revision check failed before Span commit: ${error.message}")
        } catch (error: LinkageError) {
            removeNewOwnedSpans(spannable, previousSpans)
            return failure("TSH-ANDROID-RENDER-023", "Host revision check failed with a linkage error before Span commit")
        }
        if (!currentBeforeCommit) {
            removeNewOwnedSpans(spannable, previousSpans)
            if (closed.get()) removeOwnedSpans(spannable)
            return failure("TSH-ANDROID-RENDER-004", "RenderPlan became stale before host invalidation")
        }
        // Commit atomically from the host object's point of view: only now remove the
        // previous TSH generation. If invalidation fails, restore it and discard the new one.
        try {
            previousSpans.forEach { spannable.removeSpan(it.span) }
            target.invalidate(documentId)
        } catch (error: Exception) {
            val rollbackFailure = runCatching {
                removeNewOwnedSpans(spannable, previousSpans)
                if (closed.get()) removeOwnedSpans(spannable)
                else restoreSpans(spannable, previousSpans)
                target.invalidate(documentId)
            }.exceptionOrNull()
            val detail = rollbackFailure?.let { "; rollback also failed: ${it.message}" }.orEmpty()
            return failure(
                "TSH-ANDROID-RENDER-009",
                "Host Span commit/invalidation failed: ${error.message}${detail}"
            )
        } catch (error: LinkageError) {
            val rollbackFailure = runCatching {
                removeNewOwnedSpans(spannable, previousSpans)
                restoreSpans(spannable, previousSpans)
                target.invalidate(documentId)
            }.exceptionOrNull()
            val detail = rollbackFailure?.let { "; rollback also failed: ${it.message}" }.orEmpty()
            return failure(
                "TSH-ANDROID-RENDER-011",
                "Host Span commit/invalidation failed with a linkage error${detail}"
            )
        }
        val currentAfterCommit = try {
            !closed.get() && acknowledger.isCurrent(plan.id, plan.revisions) &&
                target.currentRevisions(documentId) == plan.revisions
        } catch (error: Exception) {
            false
        } catch (error: LinkageError) {
            false
        }
        if (!currentAfterCommit) {
            val rollbackFailure = runCatching {
                removeNewOwnedSpans(spannable, previousSpans)
                if (closed.get()) removeOwnedSpans(spannable)
                else restoreSpans(spannable, previousSpans)
                target.invalidate(documentId)
            }.exceptionOrNull()
            val detail = rollbackFailure?.let { "; rollback also failed: ${it.message}" }.orEmpty()
            return failure(
                "TSH-ANDROID-RENDER-038",
                "RenderPlan became stale or was cancelled during host invalidation${detail}"
            )
        }
        val status = if (fullyApplied == visibleInstructionCount && droppedInstructionCount == 0) {
            TshRenderReceiptStatus.APPLIED
        } else {
            TshRenderReceiptStatus.PARTIALLY_APPLIED
        }
        val receipt = TshRenderReceipt(
            plan.id, id, plan.revisions, status, appliedInstructionCount, diagnostics.toList(),
            deferredInstructionCount, droppedInstructionCount
        )
        return TshResult.Success(receipt, diagnostics.toList())
    }

    private fun applyStyle(
        spannable: Spannable,
        start: Int,
        end: Int,
        planId: String,
        style: com.zerostudio.idea.treesitter.tsh.api.TshResolvedStyle,
        flags: Int,
        diagnostics: MutableList<TshDiagnostic>
    ) {
        if (style.foregroundArgb != null) {
            spannable.setSpan(TshAndroidForegroundSpan(id, planId, style.foregroundArgb.toInt()), start, end, flags)
        }
        if (style.backgroundArgb != null) {
            spannable.setSpan(TshAndroidBackgroundSpan(id, planId, style.backgroundArgb.toInt()), start, end, flags)
        }
        val decorations = style.decorations
        val family = style.fontFamilies.firstOrNull()
        if (style.fontFamilies.size > 1) {
            diagnostics += warning(
                "TSH-ANDROID-RENDER-013",
                "This backend currently applies only the first font family; host fallback resolution is not available"
            )
        }
        val supportedDecorations = setOf("bold", "italic", "underline", "strike", "strikethrough")
        val unknownDecorations = decorations - supportedDecorations
        if (unknownDecorations.isNotEmpty()) {
            diagnostics += warning(
                "TSH-ANDROID-RENDER-007",
                "Unsupported decoration attributes: ${unknownDecorations.sorted().joinToString()}"
            )
        }
        val bold = style.fontWeight?.let { it >= 600 } ?: ("bold" in decorations)
        val italic = style.italic ?: ("italic" in decorations)
        if (family != null || style.fontWeight != null || style.italic != null || bold || italic) {
            var typefaceStyle = Typeface.NORMAL
            if (bold) typefaceStyle = typefaceStyle or Typeface.BOLD
            if (italic) typefaceStyle = typefaceStyle or Typeface.ITALIC
            if (Build.VERSION.SDK_INT < 28 && style.fontWeight != null &&
                style.fontWeight != 400 && style.fontWeight != 700) {
                diagnostics += warning(
                    "TSH-ANDROID-RENDER-014",
                    "This Android API level approximates custom font weights to normal/bold"
                )
            }
            spannable.setSpan(
                TshAndroidTypefaceSpan(
                    id, planId, family,
                    style.fontWeight ?: if (bold) 700 else null,
                    typefaceStyle
                ),
                start, end, flags
            )
        }
        if (style.fontSize != null) {
            val sizePx = target.resolveTextSizePx(style.fontSize)
            if (sizePx == null || !sizePx.isFinite() || sizePx <= 0f) {
                diagnostics += warning(
                    "TSH-ANDROID-RENDER-008",
                    "Host could not resolve the requested font-size measure to pixels"
                )
            } else {
                spannable.setSpan(TshAndroidTextSizeSpan(id, planId, sizePx), start, end, flags)
            }
        }
        val underline = style.underline ?: ("underline" in decorations)
        val strike = style.strikeThrough ?: ("strike" in decorations || "strikethrough" in decorations)
        val hasDecorationDeclaration = style.underline != null || style.strikeThrough != null ||
            "underline" in decorations || "strike" in decorations || "strikethrough" in decorations
        if (hasDecorationDeclaration) {
            spannable.setSpan(TshAndroidDecorationSpan(id, planId, underline, strike), start, end, flags)
        }
    }

    private fun captureOwnedSpans(spannable: Spannable): List<TshAndroidSpanSnapshot> =
        spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
            .filterIsInstance<TshAndroidOwnedSpan>()
            .filter { it.ownerId == id }
            .map { span ->
                val value = span as Any
                TshAndroidSpanSnapshot(
                    value,
                    spannable.getSpanStart(value),
                    spannable.getSpanEnd(value),
                    spannable.getSpanFlags(value)
                )
            }

    private fun restoreSpans(
        spannable: Spannable,
        snapshots: List<TshAndroidSpanSnapshot>
    ) {
        snapshots.forEach { snapshot ->
            if (snapshot.start >= 0 && snapshot.end > snapshot.start &&
                snapshot.end <= spannable.length) {
                spannable.setSpan(snapshot.span, snapshot.start, snapshot.end, snapshot.flags)
            }
        }
    }

    private fun removeNewOwnedSpans(
        spannable: Spannable,
        previous: List<TshAndroidSpanSnapshot>
    ) {
        val previousObjects = previous.map { it.span }.toSet()
        spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
            .filterIsInstance<TshAndroidOwnedSpan>()
            .filter { it.ownerId == id && it !in previousObjects }
            .forEach { spannable.removeSpan(it as Any) }
    }

    private fun warning(code: String, message: String) = TshDiagnostic(
        code, TshDiagnosticSeverity.WARNING, TshDiagnosticStage.RENDER, message
    )

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.RENDER, message
    )))
}
