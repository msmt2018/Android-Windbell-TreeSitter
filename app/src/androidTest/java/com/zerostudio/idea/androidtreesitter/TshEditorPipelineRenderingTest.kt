package com.zerostudio.idea.androidtreesitter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import com.zerostudio.idea.piecetable.PieceTreeTextBufferBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import x.github.module.editor.render.StyledTextPainter
import x.github.module.editor.view.EditorView
import kotlin.math.abs

/**
 * End-to-end regression: Java parser -> TSH compiler/ATS -> Android spans ->
 * EditorView -> StyledTextPainter -> real software Canvas pixels.
 */
@RunWith(AndroidJUnit4::class)
class TshEditorPipelineRenderingTest {


    @Test
    fun nestedTshCapturePriorityReachesFinalCanvasColor() {
        val engine = WindbellTshEngine.instance
        val dsl = """
            tsh 2 {
                language { id: "canvas-priority-regression" extensions: [".cpr"] }
                matching { multiline: true }
                styles {
                    outer { scope: "comment.block" }
                    inner { scope: "keyword.other.todo" }
                }
                schemes {
                    plain {
                        light [ outer: #202020 @bold @bg #203040, inner: #FF0000 ]
                        dark [ outer: #A0A0A0 @bg #304050, inner: #FF5555 ]
                    }
                }
                defaultScheme: "plain"
                contains: [
                    {
                        start: { match: /BEGIN/ }
                        end: { match: /END/ }
                        name: "outer"
                        contentName: "outer"
                        contains: [
                            { match: /TOKEN/, style: "inner", priority: 100 }
                        ]
                    }
                ]
            }
        """.trimIndent()
        val compiled = engine.loadTshSource("canvas-priority-regression", dsl, null)
        assertTrue(
            "Nested-priority TSH DSL must compile: ${engine.lastCompileDiagnostics}",
            compiled
        )
        assertTrue(engine.ensurePalette("canvas-priority-regression"))
        engine.setAppearance("canvas-priority-regression", false)

        val source = "BEGIN TOKEN END"
        val captures = engine.computeHighlights(null, source, "canvas-priority-regression")
        val innerId = engine.getStyleCatalog("canvas-priority-regression")
            .first { it.name == "inner" }.styleId
        assertTrue(
            "Native ATS/regex pipeline must emit the nested high-priority capture",
            captures.any { it.styleId == innerId && it.priority == 100 }
        )

        // The editor's interval index returns captures in source-offset order,
        // which intentionally differs from native priority order for nested spans.
        val viewportCaptures = CaptureIntervalIndex(captures).query(0, source.length)
        val styled = SpannableStringBuilder(source)
        engine.applyHighlightSpansToSpannable(
            spans = viewportCaptures,
            documentOffset = 0,
            text = source,
            extension = "canvas-priority-regression",
            spannable = styled
        )
        val tokenStart = source.indexOf("TOKEN")
        val tokenEnd = tokenStart + "TOKEN".length
        val tokenStyles = styled.getSpans(tokenStart, tokenEnd, StyleSpan::class.java)
        assertTrue("Outer TSH font style must be represented on nested token", tokenStyles.isNotEmpty())
        val outerCapture = viewportCaptures.first {
            it.styleId == engine.getStyleCatalog("canvas-priority-regression")
                .first { style -> style.name == "outer" }.styleId
        }
        val expectedOuterPriority = viewportCaptures.asSequence()
            .map { it.priority to it.declarationOrder }
            .distinct()
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .indexOf(outerCapture.priority to outerCapture.declarationOrder)
            .let { (255 - it).coerceAtLeast(1) }
        assertTrue(
            "Font StyleSpan must carry the same native rule precedence",
            tokenStyles.any {
                (styled.getSpanFlags(it) and Spanned.SPAN_PRIORITY) ==
                    (expectedOuterPriority shl Spanned.SPAN_PRIORITY_SHIFT)
            }
        )
        val tokenBackgrounds = styled.getSpans(
            tokenStart, tokenEnd, BackgroundColorSpan::class.java
        ).filter {
            styled.getSpanStart(it) <= tokenStart && styled.getSpanEnd(it) >= tokenEnd
        }
        assertTrue("The outer TSH background must cover the nested token", tokenBackgrounds.isNotEmpty())
        assertEquals(
            "The DSL background color must survive native palette decoding",
            engine.getStylePalette("canvas-priority-regression", "outer")?.lightBackground,
            tokenBackgrounds.maxByOrNull {
                styled.getSpanFlags(it) and Spanned.SPAN_PRIORITY
            }?.backgroundColor
        )
        val tokenColors = styled.getSpans(
            tokenStart, tokenEnd, ForegroundColorSpan::class.java
        ).filter {
            styled.getSpanStart(it) <= tokenStart && styled.getSpanEnd(it) >= tokenEnd
        }
        assertTrue("Both outer and nested styles must cover TOKEN", tokenColors.size >= 2)
        val winningColor = tokenColors.maxByOrNull {
            styled.getSpanFlags(it) and Spanned.SPAN_PRIORITY
        }?.foregroundColor
        assertEquals(
            "Android span priority must preserve the native high-priority capture",
            engine.getStylePalette("canvas-priority-regression", "inner")?.lightColor,
            winningColor
        )

        val bitmap = Bitmap.createBitmap(640, 100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val paint = android.text.TextPaint(android.text.TextPaint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 42f
            }
            StyledTextPainter().draw(
                canvas = canvas,
                text = source,
                spans = styled,
                start = 0,
                end = styled.length,
                x = 8f,
                baseline = 64f,
                rowTop = 0f,
                rowBottom = 100f,
                basePaint = paint
            )
            val expected = engine.getStylePalette("canvas-priority-regression", "inner")!!.lightColor
            val expectedBackground = engine.getStylePalette(
                "canvas-priority-regression", "outer"
            )!!.lightBackground
            val tokenX = 8f + paint.measureText(source.substring(0, tokenStart))
            assertEquals(
                "The .tsh background must reach the final Canvas pixel",
                expectedBackground,
                bitmap.getPixel((tokenX + 2f).toInt(), 5)
            )
            var matchingPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 8 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) == 0) continue
                    val distance =
                        abs(Color.red(pixel) - Color.red(expected)) +
                        abs(Color.green(pixel) - Color.green(expected)) +
                        abs(Color.blue(pixel) - Color.blue(expected))
                    if (distance <= 72) matchingPixels++
                }
            }
            assertTrue(
                "Nested TSH style must win at the final Canvas pixel stage; pixels=$matchingPixels",
                matchingPixels > 0
            )
        } finally {
            bitmap.recycle()
            engine.clearAppearanceOverride("canvas-priority-regression")
        }
    }

    private fun blendArgbForTest(background: Int, foreground: Int): Int {
        val alpha = Color.alpha(foreground) / 255f
        fun channel(backgroundChannel: Int, foregroundChannel: Int): Int =
            (foregroundChannel * alpha + backgroundChannel * (1f - alpha)).toInt()
        return Color.rgb(
            channel(Color.red(background), Color.red(foreground)),
            channel(Color.green(background), Color.green(foreground)),
            channel(Color.blue(background), Color.blue(foreground))
        )
    }

    @Test
    fun configurableForegroundAndBackgroundAlphaReachCanvasPixels() {
        val engine = WindbellTshEngine.instance
        val dsl = """
            tsh 2 {
                language { id: "canvas-alpha-regression" extensions: [".car"] }
                styles {
                    translucent { scope: "constant.numeric" }
                }
                schemes {
                    plain {
                        light [ translucent: #FF0000 @alpha 0.5 @bg #0000FF @bg-alpha 0.25 ]
                        dark [ translucent: #FF7777 @alpha 0.75 @bg #7777FF @bg-alpha 0.5 ]
                    }
                }
                defaultScheme: "plain"
                contains: [
                    { match: /42/, style: "translucent" }
                ]
            }
        """.trimIndent()
        assertTrue(
            "Alpha TSH DSL must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("canvas-alpha-regression", dsl, null)
        )
        assertTrue(engine.ensurePalette("canvas-alpha-regression"))
        engine.setAppearance("canvas-alpha-regression", false)

        val palette = engine.getStylePalette("canvas-alpha-regression", "translucent")
        assertNotNull("The alpha style must be present in the decoded Palette", palette)
        val light = palette!!
        assertEquals(128, Color.alpha(light.lightColor))
        assertEquals(64, Color.alpha(light.lightBackground))
        val darkPalette = engine.getSchemePalette("canvas-alpha-regression", "plain")
            .values.first { it.styleId == light.styleId }
        assertEquals(191, Color.alpha(darkPalette.darkColor))
        assertEquals(128, Color.alpha(darkPalette.darkBackground))

        val source = "42"
        val captures = engine.computeHighlights(null, source, "canvas-alpha-regression")
        assertTrue("Regex capture must reach the Android span adapter", captures.isNotEmpty())
        val styled = SpannableStringBuilder(source)
        engine.applyHighlightSpansToSpannable(
            spans = captures,
            documentOffset = 0,
            text = source,
            extension = "canvas-alpha-regression",
            spannable = styled
        )
        val foreground = styled.getSpans(0, source.length, ForegroundColorSpan::class.java)
            .maxByOrNull { styled.getSpanFlags(it) and Spanned.SPAN_PRIORITY }
        val background = styled.getSpans(0, source.length, BackgroundColorSpan::class.java)
            .maxByOrNull { styled.getSpanFlags(it) and Spanned.SPAN_PRIORITY }
        assertNotNull(foreground)
        assertNotNull(background)
        assertEquals(light.lightColor, foreground!!.foregroundColor)
        assertEquals(light.lightBackground, background!!.backgroundColor)

        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val paint = android.text.TextPaint(android.text.TextPaint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 48f
            }
            StyledTextPainter().draw(
                canvas = canvas,
                text = source,
                spans = styled,
                start = 0,
                end = styled.length,
                x = 8f,
                baseline = 58f,
                rowTop = 0f,
                rowBottom = 80f,
                basePaint = paint
            )
            val expectedBackground = blendArgbForTest(Color.WHITE, light.lightBackground)
            val actualBackground = bitmap.getPixel(9, 2)
            assertTrue(
                "Background alpha must be composited by the actual Canvas painter: " +
                    "expected=$expectedBackground actual=$actualBackground",
                abs(Color.red(actualBackground) - Color.red(expectedBackground)) <= 1 &&
                    abs(Color.green(actualBackground) - Color.green(expectedBackground)) <= 1 &&
                    abs(Color.blue(actualBackground) - Color.blue(expectedBackground)) <= 1
            )
            // Remove the background and render onto transparent pixels to verify
            // that ForegroundColorSpan alpha survives the custom Canvas painter too.
            styled.removeSpan(background)
            bitmap.eraseColor(Color.TRANSPARENT)
            StyledTextPainter().draw(
                canvas = Canvas(bitmap),
                text = source,
                spans = styled,
                start = 0,
                end = styled.length,
                x = 8f,
                baseline = 58f,
                rowTop = 0f,
                rowBottom = 80f,
                basePaint = paint
            )
            var translucentForegroundPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) in 1..128 && Color.red(pixel) > 0 &&
                        Color.green(pixel) == 0 && Color.blue(pixel) == 0) {
                        translucentForegroundPixels++
                    }
                }
            }
            assertTrue(
                "Foreground alpha must survive the actual Canvas text draw; " +
                    "pixels=$translucentForegroundPixels",
                translucentForegroundPixels > 0
            )
        } finally {
            bitmap.recycle()
            engine.clearAppearanceOverride("canvas-alpha-regression")
        }
    }

    @Test
    fun styleParentFontAttributesAreInheritedIndependentlyOfForeground() {
        val engine = WindbellTshEngine.instance
        val dsl = """
            tsh 2 {
                language { id: "style-parent-paint-regression" extensions: [".spr"] }
                styles {
                    base { scope: "entity.name" }
                    child { scope: "entity.name.type" parent: "base" }
                }
                schemes {
                    plain {
                        light [ base: #FF0000 @bold @italic, child: #0000FF ]
                        dark [ base: #AA0000 @bold @italic, child: #0000AA ]
                    }
                }
                defaultScheme: "plain"
            }
        """.trimIndent()
        assertTrue(
            "Style-parent paint DSL must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("style-parent-paint-regression", dsl, null)
        )
        val child = engine.getStylePalette("style-parent-paint-regression", "child")
        assertNotNull("Child style palette must be available", child)
        assertEquals("Child foreground must remain its own color", Color.BLUE, child!!.lightColor)
        assertEquals("Parent bold must be inherited even with child foreground", 1, child.lightFontFlags and 0x01)
        assertEquals("Parent italic must be inherited even with child foreground", 2, child.lightFontFlags and 0x02)
        assertEquals("Dark parent bold must be inherited", 1, child.darkFontFlags and 0x01)
        assertEquals("Dark parent italic must be inherited", 2, child.darkFontFlags and 0x02)
    }

    @Test
    fun childStyleCanExplicitlyDisableInheritedFontAttributes() {
        val engine = WindbellTshEngine.instance
        val dsl = """
            tsh 2 {
                language { id: "font-override-regression" extensions: [".for"] }
                styles {
                    base { scope: "entity.name" }
                    child { scope: "entity.name.type" parent: "base" }
                }
                schemes {
                    plain {
                        light [ base: #FF0000 @bold @italic @underline, child: #0000FF @no-bold @no-underline ]
                        dark [ base: #AA0000 @bold @italic @underline, child: #0000AA @no-bold @no-underline ]
                    }
                }
                defaultScheme: "plain"
            }
        """.trimIndent()
        assertTrue(
            "Explicit font override DSL must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("font-override-regression", dsl, null)
        )
        val child = engine.getStylePalette("font-override-regression", "child")
        assertNotNull("Child palette must exist", child)
        assertEquals("Child bold must be explicitly disabled", 0, child!!.lightFontFlags and 0x01)
        assertEquals("Parent italic must still be inherited", 0x02, child.lightFontFlags and 0x02)
        assertEquals("Child underline must be explicitly disabled", 0, child.lightFontFlags and 0x04)
        assertEquals("Dark child bold must be explicitly disabled", 0, child.darkFontFlags and 0x01)
        assertEquals("Dark parent italic must still be inherited", 0x02, child.darkFontFlags and 0x02)
        assertEquals("Dark child underline must be explicitly disabled", 0, child.darkFontFlags and 0x04)
    }

    @Test
    fun repeatedSchemeEntriesOverlayOnlyExplicitPaintAttributes() {
        val engine = WindbellTshEngine.instance
        val dsl = """
            tsh 2 {
                language { id: "repeated-paint-overlay-regression" extensions: [".rpo"] }
                styles {
                    token { scope: "constant.numeric" }
                }
                schemes {
                    plain {
                        light [
                            token: #FF0000 @bg #00FF00 @bold @italic,
                            token: #0000FF
                        ]
                        dark [
                            token: #AA0000 @bg #00AA00 @bold @italic,
                            token: #0000AA
                        ]
                    }
                }
                defaultScheme: "plain"
            }
        """.trimIndent()
        assertTrue(
            "Repeated Scheme paint entries must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("repeated-paint-overlay-regression", dsl, null)
        )
        val token = engine.getStylePalette("repeated-paint-overlay-regression", "token")
        assertNotNull("Repeated-entry palette must exist", token)
        assertEquals("Last explicit foreground wins", Color.BLUE, token!!.lightColor)
        assertEquals("Earlier background remains present", true, token.hasLightBackground)
        assertEquals("Earlier background value survives", Color.GREEN, token.lightBackground)
        assertEquals("Earlier bold attribute survives", 0x01, token.lightFontFlags and 0x01)
        assertEquals("Earlier italic attribute survives", 0x02, token.lightFontFlags and 0x02)
        assertEquals("Dark foreground uses last declaration", Color.BLUE and 0x00FFFFFF or (0xAA shl 24), token.darkColor)
        assertEquals("Dark background survives", Color.rgb(0, 170, 0), token.darkBackground)
        assertEquals("Dark bold survives", 0x01, token.darkFontFlags and 0x01)
        assertEquals("Dark italic survives", 0x02, token.darkFontFlags and 0x02)
    }

    @Test
    fun schemeExtensionPreservesExplicitFontAttributeClears() {
        val engine = WindbellTshEngine.instance
        val dsl = """
            tsh 2 {
                language { id: "scheme-font-override-regression" extensions: [".sfo"] }
                styles {
                    base { scope: "entity.name" }
                    child { scope: "entity.name.type" parent: "base" }
                }
                schemes {
                    foundation {
                        light [ base: #FF0000 @bold @italic @underline ]
                        dark [ base: #AA0000 @bold @italic @underline ]
                    }
                    derived {
                        extends: "foundation"
                        light [ child: #0000FF @no-bold @no-underline ]
                        dark [ child: #0000AA @no-bold @no-underline ]
                    }
                }
                defaultScheme: "derived"
            }
        """.trimIndent()
        assertTrue(
            "Scheme font override DSL must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("scheme-font-override-regression", dsl, null)
        )
        val palette = engine.getSchemePalette("scheme-font-override-regression", "derived")
        val baseId = engine.getStylePalette("scheme-font-override-regression", "base")!!.styleId
        val childId = engine.getStylePalette("scheme-font-override-regression", "child")!!.styleId
        val base = palette.values.first { it.styleId == baseId }
        val child = palette.values.first { it.styleId == childId }
        assertEquals("Base bold survives Scheme inheritance", 0x01, base.lightFontFlags and 0x01)
        assertEquals("Child explicitly clears inherited bold", 0, child.lightFontFlags and 0x01)
        assertEquals("Child inherits unspecified italic", 0x02, child.lightFontFlags and 0x02)
        assertEquals("Child explicitly clears inherited underline", 0, child.lightFontFlags and 0x04)
        assertEquals("Dark child explicitly clears inherited bold", 0, child.darkFontFlags and 0x01)
        assertEquals("Dark child inherits unspecified italic", 0x02, child.darkFontFlags and 0x02)
        assertEquals("Dark child explicitly clears inherited underline", 0, child.darkFontFlags and 0x04)
    }

    @Test
    fun javaAstSymbolColorReachesEditorCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TreeSitter.loadLibrary()
        System.loadLibrary("tree-sitter-java")

        val language = TSLanguageJava.getInstance()
        assertTrue("Java Tree-sitter language must be registered", language.nativeObject != 0L)

        val tshSource = context.assets.open("tsh/java.tsh")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val engine = WindbellTshEngine.instance
        assertTrue(
            "Java TSH must compile against the registered Java parser: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("java", tshSource, language)
        )
        assertTrue(engine.selectScheme("java", "darcula"))
        engine.setAppearance("java", false)

        val source = "class OtherProbe { int first; }\nclass RenderProbe { int value; }"
        val targetLineStart = source.indexOf("class RenderProbe")
        val targetLine = source.substring(targetLineStart)
        val highlighter = TshSyntaxHighlighter(language, "java")
        val editor = EditorView(context)
        val bitmap = Bitmap.createBitmap(1200, 180, Bitmap.Config.ARGB_8888)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editor.setBackgroundColor(Color.WHITE)
                editor.setTextColor(Color.DKGRAY)
                editor.setTextSize(48f)
                editor.setGutterEnabled(false)
                assertTrue(editor.setText(source))
                editor.setSyntaxHighlighter(highlighter)
                editor.measure(
                    View.MeasureSpec.makeMeasureSpec(bitmap.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bitmap.height, View.MeasureSpec.EXACTLY)
                )
                editor.layout(0, 0, bitmap.width, bitmap.height)
            }

            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (!highlighter.isStyleReady(2, targetLineStart, 0, targetLine.length) &&
                SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20L)
            }
            assertTrue("TSH parse/highlight did not finish", highlighter.isStyleReady(2, targetLineStart, 0, targetLine.length))

            val nameStart = targetLine.indexOf("RenderProbe")
            val nameEnd = nameStart + "RenderProbe".length
            var styledColor: Int? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val styled = highlighter.styleLine(targetLine, 2, targetLineStart, 0, targetLine.length)
                assertNotNull("Editor LineStyler must return an Android Spanned result", styled)
                val colorSpan = styled!!.getSpans(nameStart, nameEnd, ForegroundColorSpan::class.java)
                    .firstOrNull {
                        styled.getSpanStart(it) <= nameStart && styled.getSpanEnd(it) >= nameEnd
                    }
                assertNotNull("ATS class_declaration.name must produce a foreground color span", colorSpan)
                styledColor = colorSpan!!.foregroundColor

                // Deliberately supply a line key whose bucket contains unrelated
                // captures. The document-range fallback must still find this line's ATS span.
                val mismatchedKey = highlighter.styleLine(
                    targetLine, 1, targetLineStart, 0, targetLine.length
                )
                assertNotNull("A stale line-index bucket must not suppress valid ATS captures", mismatchedKey)
                val fallbackColor = mismatchedKey!!.getSpans(
                    nameStart, nameEnd, ForegroundColorSpan::class.java
                ).firstOrNull {
                    mismatchedKey.getSpanStart(it) <= nameStart &&
                        mismatchedKey.getSpanEnd(it) >= nameEnd
                }
                assertNotNull("Range fallback must recover the class-name foreground span", fallbackColor)
                assertEquals(styledColor, fallbackColor!!.foregroundColor)
            }

            val expected = engine.getStylePalette("java", "type")
            assertNotNull("The selected Scheme must provide a type palette", expected)
            assertEquals(expected!!.lightColor, styledColor)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editor.draw(Canvas(bitmap))
            }

            val target = expected.lightColor
            var matchingPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) == 0) continue
                    val distance =
                        abs(Color.red(pixel) - Color.red(target)) +
                        abs(Color.green(pixel) - Color.green(target)) +
                        abs(Color.blue(pixel) - Color.blue(target))
                    if (distance <= 72) matchingPixels++
                }
            }
            assertTrue(
                "The selected ATS type color must be painted into the EditorView Canvas; pixels=$matchingPixels",
                matchingPixels > 0
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editor.setSyntaxHighlighter(null)
            }
            bitmap.recycle()
            engine.clearAppearanceOverride("java")
        }
    }

    @Test
    fun largePieceTreeDocumentStylesAndDrawsAViewportBeyondTheFirstPartition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TreeSitter.loadLibrary()
        System.loadLibrary("tree-sitter-java")
        val language = TSLanguageJava.getInstance()
        assertTrue("Java Tree-sitter language must be registered", language.nativeObject != 0L)

        val engine = WindbellTshEngine.instance
        val tshSource = context.assets.open("tsh/java.tsh")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(
            "Java TSH must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("java", tshSource, language)
        )
        assertTrue(engine.selectScheme("java", "darcula"))
        engine.setAppearance("java", false)

        // Build a real chunk-backed document larger than the 1 Mi UTF-16
        // small/large threshold. The target declaration is in a distant partition.
        val fillerLine = "// partition filler 0123456789 abcdefghijklmnopqrstuvwxyz\n"
        val fillerChunk = buildString {
            repeat(1024) { append(fillerLine) }
        }
        val builder = PieceTreeTextBufferBuilder()
        val chunkCount = 24
        repeat(chunkCount) { builder.acceptChunk(fillerChunk) }
        val targetStart = fillerChunk.length * chunkCount
        val targetLine = "class DistantRenderProbe { int value; }"
        builder.acceptChunk(targetLine)
        val targetLineNumber = chunkCount * 1024 + 1

        val highlighter = TshSyntaxHighlighter(language, "java", engine)
        val editor = EditorView(context)
        val bitmap = Bitmap.createBitmap(1200, 180, Bitmap.Config.ARGB_8888)
        val laidOut = CountDownLatch(1)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editor.setBackgroundColor(Color.WHITE)
                editor.setTextColor(Color.DKGRAY)
                editor.setTextSize(16f)
                editor.setGutterEnabled(false)
                editor.setSyntaxHighlighter(highlighter)
                assertTrue(
                    "Large Piece Tree buffer must be accepted",
                    editor.setBuffer(builder.build()) { laidOut.countDown() }
                )
                editor.measure(
                    View.MeasureSpec.makeMeasureSpec(bitmap.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bitmap.height, View.MeasureSpec.EXACTLY)
                )
                editor.layout(0, 0, bitmap.width, bitmap.height)
            }
            assertTrue("Large document layout did not finish", laidOut.await(60, TimeUnit.SECONDS))

            val deadline = SystemClock.uptimeMillis() + 30_000L
            while (!highlighter.isStyleReady(
                    targetLineNumber, targetStart, 0, targetLine.length
                ) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20L)
            }
            assertTrue(
                "Distant TSH partition did not become ready",
                highlighter.isStyleReady(targetLineNumber, targetStart, 0, targetLine.length)
            )

            val nameStart = targetLine.indexOf("DistantRenderProbe")
            val nameEnd = nameStart + "DistantRenderProbe".length
            var styledColor: Int? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val styled = highlighter.styleLine(
                    targetLine, targetLineNumber, targetStart, 0, targetLine.length
                )
                assertNotNull("Distant line must receive Android spans", styled)
                val span = styled!!.getSpans(
                    nameStart, nameEnd, ForegroundColorSpan::class.java
                ).firstOrNull {
                    styled.getSpanStart(it) <= nameStart && styled.getSpanEnd(it) >= nameEnd
                }
                assertNotNull("Distant class declaration must receive its TSH type color", span)
                styledColor = span!!.foregroundColor

                // Exercise the same EditorView -> TextRenderer -> StyledTextPainter path
                // used by the application after jumping to a distant line.
                editor.scrollTo(0, (targetLineNumber - 1) * editor.getLineHeight())
                editor.draw(Canvas(bitmap))
            }

            val expected = engine.getStylePalette("java", "type")
            assertNotNull("The selected scheme must provide a type palette", expected)
            assertEquals(expected!!.lightColor, styledColor)
            var matchingPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) == 0) continue
                    val distance =
                        abs(Color.red(pixel) - Color.red(expected.lightColor)) +
                        abs(Color.green(pixel) - Color.green(expected.lightColor)) +
                        abs(Color.blue(pixel) - Color.blue(expected.lightColor))
                    if (distance <= 72) matchingPixels++
                }
            }
            assertTrue(
                "Distant large-file TSH type color must reach actual Canvas pixels; pixels=$matchingPixels",
                matchingPixels > 0
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editor.setSyntaxHighlighter(null)
            }
            bitmap.recycle()
            engine.clearAppearanceOverride("java")
        }
    }

}
