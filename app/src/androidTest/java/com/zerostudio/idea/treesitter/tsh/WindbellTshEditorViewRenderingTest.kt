package com.zerostudio.idea.treesitter.tsh

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import com.zerostudio.idea.androidtreesitter.TshSyntaxHighlighter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import x.github.module.editor.view.EditorView

/**
 * End-to-end proof that a real .tsh grammar reaches EditorView's Canvas painter.
 *
 * This exercises the native PieceTree document, viewport capture requests, native TSH
 * execution, Kotlin span adaptation, and the editor's actual software-canvas draw path.
 */
@RunWith(AndroidJUnit4::class)
class WindbellTshEditorViewRenderingTest {
    private val plainColor = Color.rgb(2, 250, 6)
    private val keywordColor = Color.rgb(255, 0, 127)

    private fun dsl() = """
        name: ["Java", ".java"]
        styles: [
            "keyword", #FF007F, #CC7832, @B,
            "string", #007F00, #6A8759,
            "comment", #808080, #808080, @I
        ]
        defines: [
            "ident": /[a-zA-Z_][a-zA-Z0-9_]*/
        ]
        contains: [
            { match: keywordsToRegex("public class static void int if else return"), style: "keyword" },
            { start: { match: '"' }, end: { match: '"' }, style: "string" },
            { match: /\/\/[^\n]*/, style: "comment" }
        ]
    """.trimIndent()

    private fun draw(view: EditorView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun Bitmap.containsColor(expected: Int): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { pixel ->
            Color.alpha(pixel) > 200 &&
                kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 18 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 18 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 18
        }
    }

    @Test
    fun nativeTshHighlightsAreVisibleInTheRealEditorView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        TreeSitter.loadLibrary()
        System.loadLibrary("tree-sitter-java")
        val engine = WindbellTshEngine.instance
        val extension = "java-editor-e2e"
        assertTrue("The .tsh source did not compile", engine.loadTshSource(
            extension, dsl(), TSLanguageJava.getInstance()
        ))
        engine.setAppearance(extension, false)

        lateinit var editor: EditorView
        lateinit var highlighter: TshSyntaxHighlighter
        instrumentation.runOnMainSync {
            editor = EditorView(context)
            editor.setTextSize(48f)
            editor.setTextColor(plainColor)
            editor.setText("public class Demo {\n    String value = \"hello\";\n}")
            highlighter = TshSyntaxHighlighter(
                language = TSLanguageJava.getInstance(),
                extension = extension,
                engine = engine,
                temporaryDirectory = context.cacheDir
            )
            editor.setSyntaxHighlighter(highlighter)
            editor.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY)
            )
            editor.layout(0, 0, 900, 420)
        }

        var rendered = false
        var sawPlainText = false
        val deadline = SystemClock.uptimeMillis() + 20_000L
        try {
            while (SystemClock.uptimeMillis() < deadline && !rendered) {
                instrumentation.runOnMainSync {
                    val bitmap = draw(editor)
                    rendered = bitmap.containsColor(keywordColor)
                    sawPlainText = bitmap.containsColor(plainColor)
                    bitmap.recycle()
                }
                if (!rendered) SystemClock.sleep(40L)
            }
            assertTrue(
                "Native TSH captures never became visible through EditorView's Canvas painter",
                rendered
            )
            assertTrue("Unmatched editor text lost its base color", sawPlainText)
        } finally {
            instrumentation.runOnMainSync {
                editor.setSyntaxHighlighter(null)
            }
        }
    }
}
