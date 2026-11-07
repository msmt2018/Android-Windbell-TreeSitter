package com.zerostudio.idea.androidtreesitter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.text.style.ForegroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import x.github.module.editor.view.EditorView
import kotlin.math.abs

/**
 * End-to-end regression: Java parser -> TSH compiler/ATS -> Android spans ->
 * EditorView -> StyledTextPainter -> real software Canvas pixels.
 */
@RunWith(AndroidJUnit4::class)
class TshEditorPipelineRenderingTest {

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

        val source = "class RenderProbe { int value; }"
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
            while (!highlighter.isStyleReady(1, 0, 0, source.length) &&
                SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20L)
            }
            assertTrue("TSH parse/highlight did not finish", highlighter.isStyleReady(1, 0, 0, source.length))

            val nameStart = source.indexOf("RenderProbe")
            val nameEnd = nameStart + "RenderProbe".length
            var styledColor: Int? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val styled = highlighter.styleLine(source, 1, 0, 0, source.length)
                assertNotNull("Editor LineStyler must return an Android Spanned result", styled)
                val colorSpan = styled!!.getSpans(nameStart, nameEnd, ForegroundColorSpan::class.java)
                    .firstOrNull {
                        styled.getSpanStart(it) <= nameStart && styled.getSpanEnd(it) >= nameEnd
                    }
                assertNotNull("ATS class_declaration.name must produce a foreground color span", colorSpan)
                styledColor = colorSpan!!.foregroundColor
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
}
