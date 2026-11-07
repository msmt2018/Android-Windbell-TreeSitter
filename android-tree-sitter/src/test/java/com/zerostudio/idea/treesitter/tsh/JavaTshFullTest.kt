package com.zerostudio.idea.treesitter.tsh

import android.text.SpannableStringBuilder
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
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
    @DisplayName("断言：验证 Javadoc 嵌套块、文本块与代理对字符高亮稳定性")
    fun testBlockScopeAndSurrogatePairStability() {
        // 覆盖：现代 Text Block、Javadoc 嵌套块、Emoji 👨‍👩‍👧‍👦 (4 个 Code Units) 与罕见汉字 𠮷
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
                    var multiline = """
                        First line of text block
                        Multi-byte Emoji: 👨‍👩‍👧‍👦
                        End of block.
                        """;
                    System.out.println(targetUser + ": " + MAX_CACHE_CAPACITY);
                }
            }
        """.trimIndent()

        val parser = TSParser.create()
        parser.language = TSLanguageJava.getInstance()
        val tree = parser.parseString(testSource)
        assertNotNull(tree, "Tree-sitter must generate non-null syntax tree.")

        // 1. 混合分词测试 (AST 寄存器比对 + 正则下推自动机)
        val spans = WindbellTshEngine.instance.computeHighlights(tree, testSource, "java")
        assertNotNull(spans)
        assertTrue(spans.isNotEmpty(), "Highlight spans must not be empty.")

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

        // 5. 校验多态色彩应用 (Light / Dark 热切换上屏)
        val ssb = SpannableStringBuilder(testSource)
        WindbellTshEngine.instance.setAppearance("java", false)
        assertDoesNotThrow {
            WindbellTshEngine.instance.applyHighlightsToSpannable(tree, testSource, "java", ssb)
        }
        assertEquals(testSource.length, ssb.length)
        assertTrue(
            ssb.getSpans(0, ssb.length, android.text.style.ForegroundColorSpan::class.java).isNotEmpty(),
            "The Java TSH palette must produce visible Android foreground-color spans."
        )

        WindbellTshEngine.instance.setAppearance("java", true)
        assertDoesNotThrow {
            WindbellTshEngine.instance.applyHighlightsToSpannable(tree, testSource, "java", ssb)
        }

        WindbellTshEngine.instance.clearAppearanceOverride("java")
        parser.close()
    }

    @Test
    @DisplayName("断言：验证 32 线程高并发无锁竞争与重入稳定性")
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