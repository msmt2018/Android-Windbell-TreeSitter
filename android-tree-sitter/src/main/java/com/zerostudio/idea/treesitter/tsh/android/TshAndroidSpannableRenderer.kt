package com.zerostudio.idea.treesitter.tsh.android

import android.graphics.Typeface
import android.text.Spannable
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
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

/** Host adapter for an existing editor text object; no Editor/View inheritance is required. */
interface TshAndroidSpannableRenderTarget {
    fun isOnRenderThread(): Boolean
    fun currentRevisions(documentId: String): TshRenderRevisions?
    fun resolveSpannable(documentId: String): Spannable?
    fun resolveTextSizePx(value: TshDslValue.MeasureValue): Float?
    fun invalidate(documentId: String)
}

private interface TshAndroidOwnedSpan {
    val ownerId: String
    val planId: String
}

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
    private val family: String,
    private val style: Int
) : MetricAffectingSpan(), TshAndroidOwnedSpan {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.typeface = Typeface.create(family, style)
    }
    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = Typeface.create(family, style)
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
        if (underline) textPaint.isUnderlineText = true
        if (strikeThrough) textPaint.isStrikeThruText = true
    }
}

/**
 * Android Spannable backend. It only removes spans created by this provider and reports a Drawn
 * receipt after the host Spannable has been mutated and invalidated. Device pixel visibility must
 * still be verified by the host's real renderer tests.
 */
class TshAndroidSpannableRenderer(
    private val target: TshAndroidSpannableRenderTarget,
    override val id: String = "tsh-android-spannable"
) : TshRenderProvider {
    override val apiVersion: Int = 1
    override val capabilities: TshCapabilitySet = TshCapabilitySet.of(
        TshCapability("render.foreground", 1),
        TshCapability("render.background", 1),
        TshCapability("render.font", 1),
        TshCapability("render.decoration", 1),
        TshCapability("render.partial-range", 1)
    )

    override fun submit(
        plan: TshRenderPlan,
        acknowledger: TshRenderAcknowledger
    ): TshResult<TshRenderReceipt> {
        if (!target.isOnRenderThread()) {
            return failure("TSH-ANDROID-RENDER-001", "Spannable rendering must run on the host render thread")
        }
        val documentId = plan.revisions.documentId.value
        if (!acknowledger.isCurrent(plan.id, plan.revisions) ||
            target.currentRevisions(documentId) != plan.revisions) {
            return failure("TSH-ANDROID-RENDER-002", "RenderPlan is stale before Spannable mutation")
        }
        val spannable = target.resolveSpannable(documentId)
            ?: return failure("TSH-ANDROID-RENDER-003", "Host did not provide a Spannable for the document")
        val diagnostics = ArrayList<TshDiagnostic>()
        removeOwnedSpans(spannable)

        var fullyApplied = 0
        for (instruction in plan.instructions) {
            if (!acknowledger.isCurrent(plan.id, plan.revisions) ||
                target.currentRevisions(documentId) != plan.revisions) {
                removePlanSpans(spannable, plan.id.value)
                return failure("TSH-ANDROID-RENDER-004", "RenderPlan became stale during Spannable mutation")
            }
            val start = instruction.range.start.offset
            val end = instruction.range.end.offset
            if (start < 0L || end <= start || end > spannable.length.toLong() ||
                start > Int.MAX_VALUE.toLong()) {
                diagnostics += warning(
                    "TSH-ANDROID-RENDER-005",
                    "Instruction range is empty or outside the current Spannable"
                )
                continue
            }
            val style = instruction.style
            val before = spannable.getSpans(start.toInt(), end.toInt(), CharacterStyle::class.java)
                .count { it is TshAndroidOwnedSpan && it.ownerId == id && it.planId == plan.id.value }
            applyStyle(spannable, start.toInt(), end.toInt(), plan.id.value, style, diagnostics)
            val after = spannable.getSpans(start.toInt(), end.toInt(), CharacterStyle::class.java)
                .count { it is TshAndroidOwnedSpan && it.ownerId == id && it.planId == plan.id.value }
            if (after > before && diagnostics.none { it.severity == TshDiagnosticSeverity.ERROR }) {
                fullyApplied++
            } else if (style.foregroundArgb == null && style.backgroundArgb == null &&
                style.fontFamilies.isEmpty() && style.fontSize == null && style.fontWeight == null &&
                style.italic != true && style.underline != true && style.strikeThrough != true &&
                style.decorations.isEmpty()) {
                diagnostics += warning("TSH-ANDROID-RENDER-006", "Instruction contains no drawable style attributes")
            }
        }

        if (!acknowledger.isCurrent(plan.id, plan.revisions) ||
            target.currentRevisions(documentId) != plan.revisions) {
            removePlanSpans(spannable, plan.id.value)
            return failure("TSH-ANDROID-RENDER-004", "RenderPlan became stale before host invalidation")
        }
        target.invalidate(documentId)
        val status = if (fullyApplied == plan.instructions.size) {
            TshRenderReceiptStatus.DRAWN
        } else {
            TshRenderReceiptStatus.PARTIAL
        }
        val receipt = TshRenderReceipt(
            plan.id, id, plan.revisions, status, fullyApplied, diagnostics.toList()
        )
        return TshResult.Success(receipt, diagnostics.toList())
    }

    private fun applyStyle(
        spannable: Spannable,
        start: Int,
        end: Int,
        planId: String,
        style: com.zerostudio.idea.treesitter.tsh.api.TshResolvedStyle,
        diagnostics: MutableList<TshDiagnostic>
    ) {
        val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        if (style.foregroundArgb != null) {
            spannable.setSpan(TshAndroidForegroundSpan(id, planId, style.foregroundArgb.toInt()), start, end, flags)
        }
        if (style.backgroundArgb != null) {
            spannable.setSpan(TshAndroidBackgroundSpan(id, planId, style.backgroundArgb.toInt()), start, end, flags)
        }
        val decorations = style.decorations
        val family = style.fontFamilies.firstOrNull()
        val supportedDecorations = setOf("bold", "italic", "underline", "strike", "strikethrough")
        val unknownDecorations = decorations - supportedDecorations
        if (unknownDecorations.isNotEmpty()) {
            diagnostics += warning(
                "TSH-ANDROID-RENDER-007",
                "Unsupported decoration attributes: ${unknownDecorations.sorted().joinToString()}"
            )
        }
        val bold = (style.fontWeight ?: 0) >= 600 || "bold" in decorations
        val italic = style.italic == true || "italic" in decorations
        if (family != null || bold || italic) {
            var typefaceStyle = Typeface.NORMAL
            if (bold) typefaceStyle = typefaceStyle or Typeface.BOLD
            if (italic) typefaceStyle = typefaceStyle or Typeface.ITALIC
            spannable.setSpan(
                TshAndroidTypefaceSpan(id, planId, family ?: "sans-serif", typefaceStyle),
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
        val underline = style.underline == true || "underline" in decorations
        val strike = style.strikeThrough == true || "strike" in decorations || "strikethrough" in decorations
        if (underline || strike) {
            spannable.setSpan(TshAndroidDecorationSpan(id, planId, underline, strike), start, end, flags)
        }
    }

    private fun removeOwnedSpans(spannable: Spannable) {
        spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
            .filterIsInstance<TshAndroidOwnedSpan>()
            .filter { it.ownerId == id }
            .forEach { spannable.removeSpan(it as Any) }
    }

    private fun removePlanSpans(spannable: Spannable, planId: String) {
        spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
            .filterIsInstance<TshAndroidOwnedSpan>()
            .filter { it.ownerId == id && it.planId == planId }
            .forEach { spannable.removeSpan(it as Any) }
    }

    private fun warning(code: String, message: String) = TshDiagnostic(
        code, TshDiagnosticSeverity.WARNING, TshDiagnosticStage.RENDER, message
    )

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.RENDER, message
    )))
}
