package com.zerostudio.idea.treesitter.tsh

import android.text.SpannableStringBuilder
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class JavaTshFullTest {

    companion object {
        private const val TSH_RESOURCE_PATH = "src/main/assets/tsh/java.tsh"

        @JvmStatic
        @BeforeAll
        fun setup() {
            // The grammar lives in the app's asset source set; this test belongs
            // to the android-tree-sitter module, so resolve it from either Gradle
            // working-directory convention before falling back to classpath assets.
            val candidates = listOf(
                File(TSH_RESOURCE_PATH),
                File("../app/src/main/assets/tsh/java.tsh"),
                File("app/src/main/assets/tsh/java.tsh")
            )
            val tshContent = candidates.firstOrNull { it.isFile }
                ?.readText(Charsets.UTF_8)
                ?: JavaTshFullTest::class.java.classLoader.getResourceAsStream("tsh/java.tsh")
                    ?.bufferedReader()?.use { it.readText() }
                ?: error("Could not find app/src/main/assets/tsh/java.tsh; checked ${candidates.map { it.absolutePath }}")

            // 直接驱动动态 DSL 编译器完成 Native 装配（零 SCM 硬编码）
            val loadSuccess = WindbellTshEngine.instance.loadTshSource(
                "java",
                tshContent,
                TSLanguageJava.getInstance()
            )
            assertTrue(loadSuccess, "Windbell-TSH DSL compilation and registration must succeed.")
        }
    }

    @Test
    fun testBlockScopeAndSurrogatePairStability() {
        // 覆盖：现代 Text Block、Javadoc 嵌套块、Emoji 👨‍👩‍👧‍👦 (4 个 Code Units) 与罕见汉字 𠮷
        val textBlockDelimiter = "\"\"\""
        val testSource = """
            package com.zerostudio.test;

            /**
             * Javadoc block for test
             * @param targetUser special name: 𠮷野家
             * @return execution status
             */
            public record UserProfile(String targetUser, int userLevel) {
                public static final int MAX_CACHE_CAPACITY = 0x1000;

                public void renderEmoji() {
                    var multiline = $textBlockDelimiter
                        First line of text block
                        Multi-byte Emoji: 👨‍👩‍👧‍👦
                        End of block.
                        $textBlockDelimiter;
                    System.out.println(targetUser + ": " + MAX_CACHE_CAPACITY);
                }
            }
        """.trimIndent()

        val parser = TSParser.create()
        parser.language = TSLanguageJava.getInstance()
        val tree = parser.parseString(testSource)
        assertNotNull(tree, "Tree-sitter must generate non-null syntax tree.")

        // 1. 混合分词测试 (AST 寄存器比对 + 正则下推自动机)
        assertTrue(WindbellTshEngine.instance.isGrammarLoaded("java"))
        val spans = WindbellTshEngine.instance.computeHighlights(tree, testSource, "java")
        assertNotNull(spans)
        assertTrue(spans.isNotEmpty(), "Highlight spans must not be empty.")
        assertTrue(spans.any { it.source == 0 }, "Oniguruma regex rules must emit identifiable spans.")
        assertTrue(spans.any { it.source == 2 }, "ATS selectors must emit identifiable spans.")
        val atsTexts = spans.filter { it.source == 2 }.map { span ->
            testSource.substring(
                (span.startByte shr 1).coerceIn(0, testSource.length),
                (span.endByte shr 1).coerceIn(0, testSource.length)
            )
        }
        assertTrue(
            atsTexts.any { it == "UserProfile" },
            "ATS field selector record_declaration.name must capture the declared type identifier; got $atsTexts"
        )

        // Tree-sitter's Kotlin binding parses UTF-16LE. Prefixing the declarations
        // with CJK/Emoji text must not make ATS or native-query byte offsets drift.
        val pipelineDiagnostics = WindbellTshEngine.instance
            .computePipelineDiagnostics(tree, testSource, "java")
        val nativeQueryTexts = pipelineDiagnostics.captures.filter { it.source == 3 }.map { span ->
            testSource.substring(
                (span.startByte shr 1).coerceIn(0, testSource.length),
                (span.endByte shr 1).coerceIn(0, testSource.length)
            )
        }
        assertTrue(
            nativeQueryTexts.contains("multiline"),
            "Native Tree-sitter Query must capture variable_declarator.name after UTF-16 offsets are mapped; got $nativeQueryTexts"
        )

        assertTrue(
            nativeQueryTexts.contains("MAX_CACHE_CAPACITY"),
            "Native #match? predicate must evaluate against the UTF-8 slice mapped from UTF-16LE node offsets."
        )
        assertTrue(
            nativeQueryTexts.all { it == "multiline" || it == "MAX_CACHE_CAPACITY" },
            "Native query regex predicate must reject identifiers outside its pattern; got $nativeQueryTexts"
        )
        assertTrue(
            spans.any { it.source == 3 },
            "A native-query candidate must survive the final conflict-resolution pipeline."
        )

        assertTrue(
            (pipelineDiagnostics.matchCounts[2] ?: 0) > 0,
            "ATS selector matches must be counted against UTF-16LE Tree-sitter node offsets."
        )
        assertTrue(
            (pipelineDiagnostics.matchCounts[3] ?: 0) > 0,
            "Native query matches must be counted after predicates evaluate on the correct UTF-8 slices."
        )

        // 2. 严格硬件边界与 UTF-16 Code Unit 偶数对齐断言
        for (span in spans) {
            assertEquals(0, span.startByte and 1, "startByte must align to 2-byte UTF-16 boundary: ${span.startByte}")
            assertEquals(0, span.endByte and 1, "endByte must align to 2-byte UTF-16 boundary: ${span.endByte}")
            assertTrue(span.endByte >= span.startByte, "Span endByte must not precede startByte.")
        }

        // 3. DSL-declared scheme enumeration and palette-only switching.
        val schemes = WindbellTshEngine.instance.listSchemes("java")
        assertTrue(schemes.contains("darcula"))
        assertTrue(schemes.contains("github"))
        assertEquals("darcula", WindbellTshEngine.instance.getDefaultScheme("java"))
        assertTrue(WindbellTshEngine.instance.selectScheme("java", "darcula"))
        val darculaPalette = WindbellTshEngine.instance.getSchemePalette("java", "darcula")
        val githubPalette = WindbellTshEngine.instance.getSchemePalette("java", "github")
        assertTrue(darculaPalette.isNotEmpty(), "Every declared scheme must expose its compiled palette.")
        assertTrue(githubPalette.isNotEmpty(), "Inactive schemes must also expose their compiled palette.")
        assertTrue(darculaPalette.values.any { it.hasLightColor && it.hasDarkColor })
        val darculaLightTokens = WindbellTshEngine.instance.getSchemeThemeTokens("java", "darcula", false)
        val darculaDarkTokens = WindbellTshEngine.instance.getSchemeThemeTokens("java", "darcula", true)
        assertTrue(darculaLightTokens.containsKey("activity.toolbar.background"))
        assertTrue(darculaDarkTokens.containsKey("activity.toolbar.background"))
        assertSame(
            darculaPalette,
            WindbellTshEngine.instance.getSchemePalette("java", "darcula"),
            "Selecting another scheme must reuse the already-decoded palette snapshot."
        )
        repeat(8) {
            val selected = if (it % 2 == 0) "github" else "darcula"
            assertTrue(WindbellTshEngine.instance.selectScheme("java", selected))
            assertSame(
                if (selected == "github") githubPalette else darculaPalette,
                WindbellTshEngine.instance.getSchemePalette("java", selected)
            )
        }

        // 5. 校验多态色彩应用 (Light / Dark 热切换上屏)
        val ssb = SpannableStringBuilder(testSource)
        WindbellTshEngine.instance.setAppearance("java", false)
        WindbellTshEngine.instance.applyHighlightsToSpannable(tree, testSource, "java", ssb)
        assertEquals(testSource.length, ssb.length)
        assertTrue(
            ssb.getSpans(0, ssb.length, android.text.style.ForegroundColorSpan::class.java).isNotEmpty(),
            "The Java TSH palette must produce visible Android foreground-color spans."
        )

        WindbellTshEngine.instance.setAppearance("java", true)
        WindbellTshEngine.instance.applyHighlightsToSpannable(tree, testSource, "java", ssb)

        WindbellTshEngine.instance.clearAppearanceOverride("java")
        parser.close()
    }

    @Test
    fun testHighConcurrencyReentrancy() {
        val threadCount = 32
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        val snippet = "public class ConcurrentTest { int a = 1; void run() {} }"

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    val p = TSParser.create()
                    p.language = TSLanguageJava.getInstance()
                    val tr = p.parseString(snippet)

                    for (i in 0 until iterationsPerThread) {
                        val spans = WindbellTshEngine.instance.computeHighlights(tr, snippet, "java")
                        assertFalse(spans.isEmpty())
                    }
                    p.close()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()
    }
}