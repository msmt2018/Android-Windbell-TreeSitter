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
        var onInvalidate: (() -> Unit)? = null
        var visibleRange: TshAndroidVisibleRange? =
            TshAndroidVisibleRange(0L, text.length.toLong())
        var provideViewport = true
        override fun visibleRangeUtf16(documentId: String) =
            if (provideViewport) visibleRange else null
        override fun isOnRenderThread() = true
        override fun currentRevisions(documentId: String) = revisions
        override fun resolveSpannable(documentId: String) = text
        override fun resolveTextSizePx(value: TshDslValue.MeasureValue): Float? = 16f
        override fun invalidate(documentId: String) {
            if (failInvalidation) error("host invalidation failure")
            onInvalidate?.invoke()
        }
    }

    private class Acknowledger : TshRenderAcknowledger {
        var current = true
        override fun isCurrent(planId: TshRenderPlanId, revisions: TshRenderRevisions) = current
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
    fun rendererRejectsMissingViewportInsteadOfAllocatingWholeDocumentSpans() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions).apply {
            provideViewport = false
        }
        val renderer = TshAndroidSpannableRenderer(target)
        val result = renderer.submit(plan("no-viewport", revisions, Color.RED.toUInt()), Acknowledger())
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-ANDROID-RENDER-018" })
        assertEquals(0, target.text.getSpans(0, target.text.length, CharacterStyle::class.java).size)
    }

    @Test
    fun attachToBindsRendererAndOwnerScopeDetachesWithoutOwningHostObject() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val hostSpan = ForegroundColorSpan(Color.BLUE)
        text.setSpan(hostSpan, 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val target = Target(text, revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val scope = TshOwnerScope("android-editor")
        val binding = (renderer.attachTo(
            engine, scope,
            requiredCapabilities = TshCapabilitySet.of(TshCapability("render.foreground", 1))
        ) as TshResult.Success<TshAndroidSpannableBinding>).value
        assertTrue(binding.attachment.resolution.selectedProviders.any { it === renderer })
        assertTrue(engine.objects.find(target.hostObject) != null)
        binding.renderCoordinator.updateCurrentRevisions(revisions)
        assertTrue(binding.renderCoordinator.submit(
            plan("binding-render", revisions, 0xFFFF0000u)
        ) is TshResult.Success)
        assertEquals(2, text.getSpans(0, 4, CharacterStyle::class.java).size)

        scope.close()

        assertEquals(1, text.getSpans(0, 4, CharacterStyle::class.java).size)
        assertTrue(text.getSpans(0, 4, CharacterStyle::class.java).any { it === hostSpan })
        assertEquals(TshAttachmentStatus.DETACHED, engine.inspectAttachment(binding.attachment.handle)?.status)
        assertTrue(engine.objects.find(target.hostObject) == null)
        assertTrue(engine.providers.all().none { it.id == renderer.id })
        assertTrue(binding.closeDiagnostics.isEmpty())
        binding.close()
        assertTrue(binding.closeDiagnostics.isEmpty())
        engine.close()
    }

    @Test
    fun explicitAttachmentDetachClosesRendererBindingAndUnregistersProvider() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val scope = TshOwnerScope("explicit-detach")
        val binding = (renderer.attachTo(engine, scope) as TshResult.Success<TshAndroidSpannableBinding>).value

        assertTrue(engine.detach(binding.attachment.handle))

        assertTrue(binding.isClosed)
        assertTrue(engine.providers.all().none { it.id == renderer.id })
        assertTrue(binding.closeDiagnostics.isEmpty())
        scope.close()
        engine.close()
    }

    @Test
    fun multipleEditorsCanOwnIndependentAndroidRendererBindings() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val firstTarget = Target(SpannableStringBuilder("one"), revisions)
        val secondTarget = Target(SpannableStringBuilder("two"), revisions)
        val firstRenderer = TshAndroidSpannableRenderer(firstTarget)
        val secondRenderer = TshAndroidSpannableRenderer(secondTarget)
        assertTrue(firstRenderer.id != secondRenderer.id)
        val firstScope = TshOwnerScope("first-editor")
        val secondScope = TshOwnerScope("second-editor")
        firstRenderer.attachTo(engine, firstScope)
        secondRenderer.attachTo(engine, secondScope)
        assertEquals(2, engine.providers.all().size)

        firstScope.close()
        assertEquals(listOf(secondRenderer.id), engine.providers.all().map { it.id })
        secondScope.close()
        assertTrue(engine.providers.all().isEmpty())
        engine.close()
    }

    @Test
    fun failedBindingSetupRollsBackProviderRegistration() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val result = renderer.attachTo(engine, TshOwnerScope("invalid"), objectType = "")
        assertTrue(result is TshResult.Failure)
        assertTrue(engine.providers.all().none { it.id == renderer.id })
        assertEquals(0, engine.objects.size())
        engine.close()
    }

    @Test
    fun engineShutdownClosesRendererCoordinatorEvenWhenOwnerScopeRemainsOpen() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val scope = TshOwnerScope("engine-shutdown")
        val binding = (renderer.attachTo(engine, scope) as TshResult.Success<TshAndroidSpannableBinding>).value

        engine.close()

        assertTrue(binding.isClosed)
        assertTrue(binding.renderCoordinator.submit(plan("after-engine-close", revisions, 0xFFFF0000u))
            is TshResult.Failure)
        scope.close()
    }

    @Test
    fun scopedRendererBindingDoesNotUnregisterAnExternallyOwnedHostObject() {
        val engine = TshEngine.create()
        engine.initialize()
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val target = Target(SpannableStringBuilder("code"), revisions)
        val descriptor = engine.objects.register(target.hostObject, "editor")
        val renderer = TshAndroidSpannableRenderer(target)
        val scope = TshOwnerScope("externally-owned-editor")
        val binding = (renderer.attachTo(engine, scope) as TshResult.Success<TshAndroidSpannableBinding>).value

        scope.close()

        assertEquals(descriptor.id, engine.objects.find(target.hostObject)?.id)
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
    fun planCancelledDuringHostInvalidationRollsBackNewGeneration() {
        val revisions = TshRenderRevisions(
            TshDocumentId("doc"), 1L, "config", "theme", "viewport", "renderer"
        )
        val text = SpannableStringBuilder("code")
        val target = Target(text, revisions)
        val renderer = TshAndroidSpannableRenderer(target)
        val acknowledger = Acknowledger()
        assertTrue(renderer.submit(plan("old-generation", revisions, 0xFFFF0000u), acknowledger)
            is TshResult.Success)

        target.onInvalidate = { acknowledger.current = false }
        val cancelled = renderer.submit(
            plan("cancelled-generation", revisions, 0xFF00FF00u), acknowledger
        )
        assertTrue(cancelled is TshResult.Failure)
        val remaining = text.getSpans(0, 4, CharacterStyle::class.java)
        assertEquals(1, remaining.size)
        assertTrue(text.getSpanStart(remaining.single()) == 0)
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
