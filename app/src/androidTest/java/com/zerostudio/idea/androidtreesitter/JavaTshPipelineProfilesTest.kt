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
        package com.example.profile;
        import java.util.List;

        /** Java pipeline fixture.
         * @param value input value
         * @return result
         */
        @Deprecated
        public class Demo<T extends Number> {
            private static final int MAX_SIZE = 0x7FFF;
            private String value = "hello";
            public Demo(String value) { this.value = value; }
            public String getValue(int index) {
                // TODO: exercise comment capture
                return value + index;
            }
            public void same(int same) {}
            public void run() {
                Runnable action = this::execute;
                action.run();
                int count = 42;
                double hexRatio = 0x1.8p1;
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
        val extension = "java-profile-pipeline-test"
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
                    when (profile) {
                        0 -> {
                            assertTrue("Regex-only must not declare AST selectors", !source.contains("ast: ["))
                            assertTrue("Regex-only must not declare Tree-sitter SCM queries", !source.contains("treeSitter {"))
                        }
                        1 -> {
                            assertTrue("ATS-only must not declare Regex contains rules", !source.contains("contains: ["))
                            assertTrue("ATS-only must not declare Regex defines", !source.contains("defines: ["))
                            assertTrue("ATS-only must not use regex SCM predicates", !source.contains("#match?"))
                            assertTrue("ATS-only must not use regex editor metadata", !source.contains("wordPattern:") && !source.contains("firstLine:") && !source.contains("onEnterRules:"))
                        }
                        2 -> {
                            assertTrue("Hybrid must include AST selectors", source.contains("ast: ["))
                            assertTrue("Hybrid must include native SCM queries", source.contains("treeSitter {"))
                            assertTrue("Hybrid must include Regex rules", source.contains("contains: ["))
                        }
                    }
                    val isolatedSource = source
                        .replace("id: \"java\"", "id: \"$extension\"")
                        .replace("extensions: [\".java\"]", "extensions: [\".java-profile-test\"]")
                    val compiled = engine.loadTshSource(extension, isolatedSource, language)
                    assertTrue(
                        "$label must compile: ${engine.lastCompileDiagnostics}",
                        compiled
                    )
                    val pipelineTree = if (profile == 0) null else parsedTree
                    val diagnostics = engine.computePipelineDiagnostics(pipelineTree, sample, extension)
                    val captures = diagnostics.captures
                    if (profile != 0) {
                        val expectedQueryPriorities = listOf(
                            145, 140, 138, 136, 135, 132, 130, 128, 127, 125,
                            123, 122, 121, 120, 119, 118, 117, 116, 115, 112,
                            98, 96, 92, 66, 60, 56
                        )
                        expectedQueryPriorities.forEach { priority ->
                            assertTrue(
                                "$label must execute SCM query priority=$priority",
                                captures.any { it.source == 3 && it.priority == priority }
                            )
                        }
                    }
                    if (profile == 2) {
                        val cacheNameStart = sample.indexOf("MAX_CACHE_CAPACITY")
                        assertTrue(
                            "Hybrid must execute the SCM #match? predicate on MAX_CACHE_CAPACITY",
                            captures.any {
                                it.source == 3 && it.priority == 120 &&
                                    it.startByte.toLong() == cacheNameStart.toLong() * 2L
                            }
                        )
                    }
                    val regex = captures.count { it.source == 0 }
                    val regexState = captures.count { it.source == 1 }
                    val regexTotal = regex + regexState
                    val ats = captures.count { it.source == 2 }
                    val query = captures.count { it.source == 3 }
                    assertTrue("$label must not declare a Monarch tokenizer", !source.contains("monarch {"))
                    when (profile) {
                        0 -> {
                            assertTrue("Regex-only must produce Regex / Regex State captures", regexTotal > 0)
                            assertEquals("Regex-only must not produce ATS captures", 0, ats)
                            assertEquals("Regex-only must not produce SCM captures", 0, query)
                        }
                        1 -> {
                            assertEquals("ATS-only must not produce Regex captures", 0, regexTotal)
                            assertTrue("ATS-only must produce AST Selector captures", ats > 0)
                            assertTrue("ATS-only must produce native SCM captures", query > 0)
                        }
                        2 -> {
                            assertTrue("Hybrid must produce Regex / Regex State captures", regexTotal > 0)
                            assertTrue("Hybrid must produce AST Selector captures", ats > 0)
                            assertTrue("Hybrid must produce native SCM captures", query > 0)
                        }
                    }
                    val spans = engine.computeHighlights(pipelineTree, sample, extension)
                    assertTrue("$label must produce final render spans", spans.isNotEmpty())
                    val styled = android.text.SpannableStringBuilder(sample)
                    engine.applyHighlightSpansToSpannable(spans, 0, sample, extension, styled)
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
