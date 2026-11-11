package com.zerostudio.idea.androidtreesitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import x.github.module.editor.view.EditorView

/**
 * Regression for the former ~1500-line rendering cutoff.
 *
 * This deliberately exercises the ordinary contiguous parser/highlighter path:
 * no Native Document Session, partition, viewport source, or large-file helper.
 */
@RunWith(AndroidJUnit4::class)
class TshBeyond1500LinesTest {

    @Test
    fun javaTshProducesSpansAfterLine1500() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        TreeSitter.loadLibrary()
        System.loadLibrary("tree-sitter-java")

        val language = TSLanguageJava.getInstance()
        val engine = WindbellTshEngine.instance
        val dsl = context.assets.open("tsh/java.tsh")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(
            "Java TSH must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("java", dsl, language)
        )

        val lines = (1..2500).map { index ->
            "public class ClassTest$index { int field$index = $index; }"
        }
        val source = lines.joinToString("\n")
        val targetLineIndex = 2099
        val targetLine = lines[targetLineIndex]
        val targetName = "ClassTest2100"
        val lineStart = lines.take(targetLineIndex).sumOf { it.length + 1 }

        val highlighter = TshSyntaxHighlighter(language, "java", engine)
        val editor = EditorView(context)
        try {
            instrumentation.runOnMainSync {
                assertTrue(editor.setText(source))
                editor.setSyntaxHighlighter(highlighter)
            }

            val deadline = SystemClock.uptimeMillis() + 30_000L
            while (!highlighter.isStyleReady(2100, lineStart, 0, targetLine.length) &&
                SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20L)
            }
            assertTrue(
                "TSH must finish the contiguous document parse",
                highlighter.isStyleReady(2100, lineStart, 0, targetLine.length)
            )

            instrumentation.runOnMainSync {
                val styled = highlighter.styleLine(
                    targetLine,
                    2100,
                    lineStart,
                    0,
                    targetLine.length
                )
                assertNotNull(
                    "Line 2100 must receive a Spanned result; captures must not stop near line 1500",
                    styled
                )
                val start = targetLine.indexOf(targetName)
                val end = start + targetName.length
                val colors = styled!!.getSpans(
                    start,
                    end,
                    android.text.style.ForegroundColorSpan::class.java
                )
                assertTrue(
                    "The AST class-name capture must reach line 2100",
                    colors.any {
                        styled.getSpanStart(it) <= start && styled.getSpanEnd(it) >= end
                    }
                )
            }
        } finally {
            instrumentation.runOnMainSync { editor.setSyntaxHighlighter(null) }
            engine.clearAppearanceOverride("java")
        }
    }
}
