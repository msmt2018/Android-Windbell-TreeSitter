package com.zerostudio.idea.androidtreesitter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.text.SpannableStringBuilder
import android.text.Spanned
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
                        light [ outer: #202020 @bold, inner: #FF0000 ]
                        dark [ outer: #A0A0A0, inner: #FF5555 ]
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
