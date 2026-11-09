package com.zerostudio.idea.treesitter.tsh.android

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import com.zerostudio.idea.treesitter.tsh.api.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TshAndroidSpannableRendererTest {
    private class Target(
        val text: SpannableStringBuilder,
        val revisions: TshRenderRevisions
    ) : TshAndroidSpannableRenderTarget {
        var failInvalidation = false
        override fun isOnRenderThread() = true
        override fun currentRevisions(documentId: String) = revisions
        override fun resolveSpannable(documentId: String) = text
        override fun resolveTextSizePx(value: TshDslValue.MeasureValue): Float? = 16f
        override fun invalidate(documentId: String) {
            if (failInvalidation) error("host invalidation failure")
        }
    }

    private class Acknowledger : TshRenderAcknowledger {
        override fun isCurrent(planId: TshRenderPlanId, revisions: TshRenderRevisions) = true
        override fun acknowledge(receipt: TshRenderReceipt) = TshResult.Success(receipt)
    }

    private fun plan(id: String, revisions: TshRenderRevisions, color: UInt) = TshRenderPlan(
        TshRenderPlanId(id),
        revisions,
        listOf(TshRenderInstruction(
            TshSourceRange(
                TshSourcePosition(0L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                TshSourcePosition(4L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                documentVersion = revisions.documentVersion
            ),
            TshResolvedStyle(foregroundArgb = color)
        ))
    )

    @Test
    fun failedReplacementRestoresPreviousTshGenerationAndPreservesHostSpans() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val hostSpan = ForegroundColorSpan(Color.BLUE)
        text.setSpan(hostSpan, 0, 4, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        val target = Target(text, revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val acknowledger = Acknowledger()

        assertTrue(renderer.submit(plan("generation-1", revisions, 0xFFFF0000u), acknowledger)
            is TshResult.Success)
        assertEquals(2, text.getSpans(0, 4, CharacterStyle::class.java).size)

        target.failInvalidation = true
        val failed = renderer.submit(
            plan("generation-2", revisions, 0xFF00FF00u), acknowledger
        )
        assertTrue(failed is TshResult.Failure)

        val spans = text.getSpans(0, 4, CharacterStyle::class.java)
        assertEquals("host span and previous TSH span must survive rollback", 2, spans.size)
        assertTrue(spans.any { it === hostSpan })
    }
}
