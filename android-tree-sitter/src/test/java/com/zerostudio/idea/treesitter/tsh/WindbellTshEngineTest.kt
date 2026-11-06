package com.zerostudio.idea.treesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class WindbellTshLinkageTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            // 确保测试环境装载 Native 动态库
            System.loadLibrary("android_tree_sitter")
        }
    }

    @Test
    fun testNativeLoadTshSourceLinkageAndExecution() {
        val engine = WindbellTshEngine.instance

        // 验证具备复杂代理对 (Emoji / 罕见字) 与正则规则的 TSH 配置载入
        val tshDsl = """
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

        // 断言 nativeLoadTshSource 绝不产生 UnsatisfiedLinkError
        assertDoesNotThrow {
            val loaded = engine.loadTshSource("java-test", tshDsl, null)
            assertTrue(loaded, "TSH DSL source parsing and compilation should succeed.")
        }
    }

    @Test
    fun testNamedSchemesAndDslAppearance() {
        val engine = WindbellTshEngine.instance
        val source = """
            name: ["Scheme Test", ".scheme"]
            isDark: true
            styles {
                keyword { scope: "keyword.control", tags: ["keyword", "control"] }
                string { scope: "string.quoted", tags: ["string"] }
            }
            schemes {
                midnight {
                    light [ keyword: #112233, string: #223344 ]
                    dark [ keyword: #AABBCC, string: #BBCCDD ]
                    ui {
                        light {
                            editor.background: #FFFFFF
                            editor.foreground: #202020
                        }
                        dark {
                            editor.background: #1E1E1E
                            editor.foreground: #D4D4D4
                        }
                    }
                }
                github {
                    light [ keyword: #0550AE, string: #0A3069 ]
                    dark [ keyword: #79C0FF, string: #A5D6FF ]
                }
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("scheme", source, null))
        assertEquals(listOf("github", "midnight"), engine.listSchemes("scheme").sorted())
        assertEquals("midnight", engine.getDefaultScheme("scheme"))
        assertTrue(engine.isDark("scheme"))
        val catalog = engine.getStyleCatalog("scheme")
        assertEquals("keyword.control", catalog.first { it.name == "keyword" }.scope)
        assertTrue(catalog.first { it.name == "keyword" }.tags.contains("control"))
        assertTrue(engine.selectScheme("scheme", "midnight"))
        assertEquals(0xFF1E1E1E.toInt(), engine.getThemeTokens("scheme")["editor.background"])
        engine.setAppearance("scheme", false)
        assertFalse(engine.isDark("scheme"))
        assertEquals(0xFFFFFFFF.toInt(), engine.getThemeTokens("scheme")["editor.background"])
        engine.clearAppearanceOverride("scheme")
        assertTrue(engine.isDark("scheme"))
        assertTrue(engine.selectScheme("scheme", "github"))
        assertEquals("github", engine.getSelectedScheme("scheme"))
    }

    @Test
    fun testOnigurumaCaptureGroupsAndNestedRegions() {
        val engine = WindbellTshEngine.instance
        val sourceDsl = """
            name: ["Regex Test", ".regex"]
            styles {
                comment { scope: "comment.block", tags: ["comment"] }
                todo { scope: "keyword.other.todo", tags: ["todo"] }
            }
            schemes {
                plain {
                    light [ comment: #808080, todo: #CC5500 ]
                    dark [ comment: #888888, todo: #FFAA55 ]
                }
            }
            contains: [
                {
                    style: "comment"
                    start: { match: /\/\*/ }
                    end: { match: /\*\// }
                    contains: [
                        { match: /TODO/, captures: { 0: "todo" }, priority: 100 }
                    ]
                }
            ]
        """.trimIndent()

        assertTrue(engine.loadTshSource("regex", sourceDsl, null))
        val text = "/* TODO: verify nested capture */"
        val spans = engine.computeHighlights(null, text, "regex")
        assertTrue(spans.isNotEmpty())
        val todoId = engine.getStyleCatalog("regex").first { it.name == "todo" }.styleId
        assertTrue(spans.any { it.styleId == todoId && it.priority == 100 })
    }

    @Test
    fun testSurrogatePairsAndHighlightingStability() {
        val engine = WindbellTshEngine.instance

        // 包含 Emoji 👨‍👩‍👧‍👦 与罕见 CJK 字符 𠮷 (测试跨语言 UTF-16 双字节切片完整性)
        val testCode = """
            // Emoji 👨‍👩‍👧‍👦 and Surrogate 𠮷
            public class Test {
                String emoji = "👋 Hello 🌍";
            }
        """.trimIndent()

        assertDoesNotThrow {
            val spans = engine.computeHighlights(null, testCode, "java-test")
            assertNotNull(spans, "Highlight spans should not be null.")
            assertFalse(spans.isEmpty(), "Should produce highlight spans for keyword and strings.")
            
            // 验证切片坐标必须处于有效双字节边界 (偶数对齐)
            for (span in spans) {
                assertTrue(span.startByte % 2 == 0, "Start byte offset must be aligned to UTF-16 code unit.")
                assertTrue(span.endByte % 2 == 0, "End byte offset must be aligned to UTF-16 code unit.")
                assertTrue(span.endByte >= span.startByte, "Span end offset must be >= start offset.")
            }
        }
    }
}