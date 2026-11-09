package com.zerostudio.idea.treesitter.tsh.android

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import com.zerostudio.idea.treesitter.tsh.api.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var visibleRange: TshAndroidVisibleRange? = null
        override fun visibleRangeUtf16(documentId: String) = visibleRange
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
    fun attachToBindsRendererAndOwnerScopeDetachesWithoutOwningHostObject() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val scope = TshOwnerScope("android-editor")
        val binding = (renderer.attachTo(
            engine, scope,
            requiredCapabilities = TshCapabilitySet.of(TshCapability("render.foreground", 1))
        ) as TshResult.Success<TshAndroidSpannableBinding>).value
        assertTrue(binding.attachment.resolution.selectedProviders.any { it === renderer })
        assertTrue(engine.objects.find(target.hostObject) != null)

        scope.close()

        assertEquals(TshAttachmentStatus.DETACHED, engine.inspectAttachment(binding.attachment.handle)?.status)
        assertTrue(engine.objects.find(target.hostObject) == null)
        assertTrue(engine.providers.all().none { it.id == renderer.id })
        binding.close()
        engine.close()
    }

    @Test
    fun rendererCanBeDiscoveredAsAnEngineHostProvider() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val objects = TshObjectRegistry()
        val providers = TshProviderRegistry()
        providers.register(renderer)
        val descriptor = objects.register(target.hostObject, "editor")
        val resolution = providers.resolveFor(
            target.hostObject,
            descriptor,
            required = TshCapabilitySet.of(TshCapability("render.foreground", 1))
        )
        assertTrue(resolution.isSatisfiable)
        assertTrue(resolution.selectedProviders.any { it === renderer })
    }

    @Test
    fun rendererClipsSpansToTheHostViewport() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val target = Target(text, revisions).apply {
            visibleRange = TshAndroidVisibleRange(1L, 3L)
        }
        val renderer = TshAndroidSpannableRenderer(target)
        val result = renderer.submit(
            plan("viewport-plan", revisions, 0xFFFF0000u), Acknowledger()
        ) as TshResult.Success<TshRenderReceipt>
        assertEquals(TshRenderReceiptStatus.APPLIED, result.value.status)
        assertTrue(result.value.confirmsHostMutation)
        val span = text.getSpans(0, 4, CharacterStyle::class.java).single()
        assertEquals(1, text.getSpanStart(span))
        assertEquals(3, text.getSpanEnd(span))
    }

    @Test
    fun fullyOffscreenPlanIsAcknowledgedAsDeferredWithoutCreatingSpans() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val target = Target(text, revisions).apply {
            visibleRange = TshAndroidVisibleRange(4L, 4L)
        }
        val renderer = TshAndroidSpannableRenderer(target)
        val result = renderer.submit(
            plan("offscreen-plan", revisions, 0xFFFF0000u), Acknowledger()
        ) as TshResult.Success<TshRenderReceipt>
        assertEquals(TshRenderReceiptStatus.APPLIED, result.value.status)
        assertEquals(0, result.value.appliedInstructionCount)
        assertEquals(1, result.value.deferredInstructionCount)
        assertTrue(result.value.confirmsHostMutation)
        assertTrue(text.getSpans(0, 4, CharacterStyle::class.java).isEmpty())
    }

    @Test
    fun decorationSpanResetsUnspecifiedDecorationFlags() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val renderer = TshAndroidSpannableRenderer(Target(text, revisions))
        val plan = TshRenderPlan(
            TshRenderPlanId("decoration"),
            revisions,
            listOf(TshRenderInstruction(
                TshSourceRange(
                    TshSourcePosition(0L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                    TshSourcePosition(4L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                    documentVersion = 1L
                ),
                TshResolvedStyle(underline = true, strikeThrough = false)
            ))
        )
        assertTrue(renderer.submit(plan, Acknowledger()) is TshResult.Success)
        val decoration = text.getSpans(0, 4, CharacterStyle::class.java)
            .single { it !is ForegroundColorSpan }
        val paint = TextPaint().apply { isStrikeThruText = true }
        decoration.updateDrawState(paint)
        assertTrue(paint.isUnderlineText)
        assertFalse(paint.isStrikeThruText)
    }

    @Test
    fun explicitFalseOverridesLowerPriorityDecorationAndTypefaceFlags() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val renderer = TshAndroidSpannableRenderer(Target(text, revisions))
        val plan = TshRenderPlan(
            TshRenderPlanId("explicit-false"),
            revisions,
            listOf(TshRenderInstruction(
                TshSourceRange(
                    TshSourcePosition(0L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                    TshSourcePosition(4L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                    documentVersion = 1L
                ),
                TshResolvedStyle(
                    fontWeight = 400,
                    italic = false,
                    underline = false,
                    strikeThrough = false,
                    decorations = setOf("bold", "italic", "underline", "strike")
                )
            ))
        )
        assertTrue(renderer.submit(plan, Acknowledger()) is TshResult.Success)
        val spans = text.getSpans(0, 4, CharacterStyle::class.java)
        val decoration = spans.single { it !is MetricAffectingSpan }
        val paint = TextPaint().apply {
            isUnderlineText = true
            isStrikeThruText = true
        }
        decoration.updateDrawState(paint)
        assertFalse(paint.isUnderlineText)
        assertFalse(paint.isStrikeThruText)

        val typeface = spans.filterIsInstance<MetricAffectingSpan>().single()
        typeface.updateDrawState(paint)
        assertFalse(paint.typeface.isBold)
        assertFalse(paint.typeface.isItalic)
    }

    @Test
    fun failedReplacementRestoresPreviousTshGenerationAndPreservesHostSpans() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val hostSpan = ForegroundColorSpan(Color.BLUE)
        text.setSpan(hostSpan, 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val target = Target(text, revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val acknowledger = Acknowledger()

        val first = renderer.submit(plan("generation-1", revisions, 0xFFFF0000u), acknowledger)
            as TshResult.Success<TshRenderReceipt>
        assertEquals(TshRenderReceiptStatus.APPLIED, first.value.status)
        assertTrue(first.value.confirmsHostMutation)
        assertTrue(!first.value.confirmsActualDraw)
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
