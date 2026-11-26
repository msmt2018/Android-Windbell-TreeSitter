package com.zerostudio.idea.androidtreesitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JavaTshPipelineProfilesTest {
    private val sample = """
        /** Java pipeline fixture.
         * @param value input value
         * @return result
         */
        @Deprecated
        public class Demo {
            private static final int MAX_SIZE = 0x7FFF;
            private String value = "hello";
            public Demo(String value) { this.value = value; }
            public String getValue(int index) {
                // TODO: exercise comment capture
                return value + index;
            }
            public void run() {
                Runnable action = this::execute;
                action.run();
                int count = 42;
                if (count > 0) execute();
            }
            private void execute() { System.out.println(value); }
        }
        record Point(int x, int y) {}
        enum State { READY, FAILED }
    """.trimIndent()

    @Test
    fun allThreeProfilesCompileAndKeepTheirProducerPipelinesIsolated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val parser = TSParser.create().apply { setLanguage(language) }
        try {
            val tree = parser.parseString(sample)
            assertTrue("Java fixture must parse", tree != null)
            tree!!.use { parsedTree ->
                val profiles = listOf(
                    Triple("java-regex-only.tsh", "Regex Only", 0),
                    Triple("java-ats-only.tsh", "Tree-sitter ATS Only", 1),
                    Triple("java-hybrid.tsh", "Hybrid", 2)
                )
                profiles.forEach { (asset, label, profile) ->
                    val source = context.assets.open("tsh/$asset")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    assertTrue(
                        "$label must compile: ${engine.lastCompileDiagnostics}",
                        engine.loadTshSource("java", source, language)
                    )
                    val diagnostics = engine.computePipelineDiagnostics(parsedTree, sample, "java")
                    val captures = diagnostics.captures
                    val regex = captures.count { it.source == 0 }
                    val monarch = captures.count { it.source == 1 }
                    val ats = captures.count { it.source == 2 }
                    val query = captures.count { it.source == 3 }
                    assertEquals("$label must not activate Monarch", 0, monarch)
                    when (profile) {
                        0 -> {
                            assertTrue("Regex-only must produce Regex captures", regex > 0)
                            assertEquals("Regex-only must not produce ATS captures", 0, ats)
                            assertEquals("Regex-only must not produce SCM captures", 0, query)
                        }
                        1 -> {
                            assertEquals("ATS-only must not produce Regex captures", 0, regex)
                            assertTrue("ATS-only must produce AST Selector captures", ats > 0)
                            assertTrue("ATS-only must produce native SCM captures", query > 0)
                        }
                        2 -> {
                            assertTrue("Hybrid must produce Regex captures", regex > 0)
                            assertTrue("Hybrid must produce AST Selector captures", ats > 0)
                            assertTrue("Hybrid must produce native SCM captures", query > 0)
                        }
                    }
                    val spans = engine.computeHighlights(parsedTree, sample, "java")
                    assertTrue("$label must produce final render spans", spans.isNotEmpty())
                    val styled = android.text.SpannableStringBuilder(sample)
                    engine.applyHighlightSpansToSpannable(spans, 0, sample, "java", styled)
                    assertTrue(
                        "$label must resolve captures into Android foreground spans",
                        styled.getSpans(
                            0, styled.length,
                            android.text.style.ForegroundColorSpan::class.java
                        ).isNotEmpty()
                    )
                }
            }
        } finally {
            parser.close()
        }
    }
}
