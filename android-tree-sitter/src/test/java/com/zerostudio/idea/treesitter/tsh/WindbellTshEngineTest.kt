package com.zerostudio.idea.treesitter.tsh

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.cpp.TSLanguageCpp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class WindbellTshEngineTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            // 初始化 C++ 规则集合
            val patterns = arrayOf(
                "\\b(class|struct|enum|int|void)\\b", // 关键字
                "//.*",                              // 单行注释
                "\".*?\""                            // 基础字符串
            )
            val styleIds = intArrayOf(1, 2, 3)
            val registered = WindbellTshEngine.instance.registerGrammar("cpp", patterns, styleIds)
            assertTrue(registered, "Cpp grammar registration must succeed.")
        }
    }

    @Test
    @DisplayName("测试代理对 (Surrogate Pairs) 复合字符切割安全性")
    fun testSurrogatePairsSafety() {
        val engine = WindbellTshEngine.instance
        // 包含 Emoji 👨‍👩‍👧‍👦 以及罕见汉字 𠮷 (长度占 2 个 UTF-16 Code Units)
        val testCode = "void testEmoji() { // 𠮷野家 👨‍👩‍👧‍👦\n int x = 42; }"
        
        val parser = TSParser.create()
        parser.language = TSLanguageCpp.getInstance()
        val tree = parser.parseString(testCode)
        assertNotNull(tree)

        // 触发混合高亮，不应出现 SIGSEGV 或数组越界
        val highlights = engine.computeHighlights(tree, testCode, "cpp")
        assertNotNull(highlights)
        assertTrue(highlights.isNotEmpty(), "Highlights should contain matched spans.")

        // 校验返回字节索引是否绝对对齐到双字节（偶数）边界
        for (span in highlights) {
            assertEquals(0, span.startByte and 1, "startByte must be aligned to UTF-16 unit (even)")
            assertEquals(0, span.endByte and 1, "endByte must be aligned to UTF-16 unit (even)")
            assertTrue(span.endByte >= span.startByte, "endByte must be greater than or equal to startByte")
        }
    }

    @Test
    @DisplayName("测试高并发场景下线程重入与无竞争安全性")
    fun testConcurrentReentrancy() {
        val engine = WindbellTshEngine.instance
        val threadCount = 16
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        val sampleCode = """
            struct MyNode {
                int id;
                // Internal comment
                void process() {}
            };
        """.trimIndent()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    val parser = TSParser.create()
                    parser.language = TSLanguageCpp.getInstance()
                    val tree = parser.parseString(sampleCode)

                    for (i in 0 until iterationsPerThread) {
                        val spans = engine.computeHighlights(tree, sampleCode, "cpp")
                        assertFalse(spans.isEmpty())
                        val folds = engine.computeFolds(tree, sampleCode)
                        assertNotNull(folds)
                    }
                    parser.close()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()
    }
}