package com.zerostudio.idea.treesitter

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
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

    private fun javaTestDsl(): String {
        return """
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
    }

    @Test
    fun testCompileFailureExposesStructuredDiagnostic() {
        val engine = WindbellTshEngine.instance
        assertFalse(engine.loadTshSource("invalid", """
            language {
                id: "invalid"
                extensions: [".invalid"]
                unknownField: true
            }
        """.trimIndent(), null))
        val diagnostic = engine.lastCompileDiagnostics.firstOrNull()
        assertNotNull(diagnostic)
        assertEquals("TSH_PARSE_OR_COMPILE_ERROR", diagnostic?.code)
        assertTrue((diagnostic?.line ?: 0) >= 1)
        assertTrue((diagnostic?.column ?: 0) >= 1)
        assertTrue(diagnostic?.message?.isNotBlank() == true)
    }

    @Test
    fun testNativeLoadTshSourceLinkageAndExecution() {
        val engine = WindbellTshEngine.instance

        // 验证具备复杂代理对 (Emoji / 罕见字) 与正则规则的 TSH 配置载入
        val tshDsl = javaTestDsl()

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
            tsh 2 {
                language {
                id: "scheme-test"
                name: "Scheme Test"
                extensions: [".scheme", ".sch"]
                aliases: ["Scheme", "Test Scheme", "Line\\nBreak"]
                mimeTypes: ["text/x-scheme-test"]
                firstLine: /^#!.*scheme-test/
            }
            name: ["Scheme Test", ".scheme"]
            isDark: true
            styles {
                keyword {
                    scope: "keyword.control"
                    tags: ["keyword", "control"]
                    deprecated: false
                    metadata: { standard: "textmate", category: "syntax", localized: "类型" }
                }
                keywordStrong { scope: "keyword.control.strong", parent: "keyword", tags: ["keyword", "emphasis"] }
                selectorOnly { scope: "keyword.control.selector", tags: ["keyword", "selector"] }
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
                derived {
                    extends: "midnight"
                    dark [ keywordStrong: #FF00AA ]
                }
                selectorTheme {
                    scopeRules: [
                        { selector: "keyword.control", light: #00AA00, dark: #AA0000 }
                        { selector: "source.scheme-test keyword.control", light: #00BB00, dark: #BB0000 }
                    ]
                }
            }
            editor {
                tabSize: 2
                fontSize: 16
                insertSpaces: false
                autoIndent: true
                comments {
                    line: ["//"]
                    block: [["/*", "*/"]]
                }
                brackets: ["{}", "()", "[]"]
                bracketNotIn: ["string", "comment"]
                autoClosingPairs: [
                    { open: "(", close: ")", notIn: ["string", "comment"] }
                ]
                surroundingPairs: ["()", "[]"]
                wordPattern: /[A-Za-z_][A-Za-z0-9_]*/
                indentation {
                    increaseIndentPattern: /\{[^}]*$/
                    decreaseIndentPattern: /^\s*\}/
                }
                completion {
                    triggerCharacters: [".", ":"]
                }
                electricCharacters: ["}", ";"]
                autoCloseBefore: /[\s)\]};,]/
                onEnterRules: [
                    {
                        beforeText: /\{[^}]*$/
                        afterText: /^\s*\\}/
                        action: { indent: "indentOutdent", appendText: "" }
                    }
                ]
            }

            }        """.trimIndent()

        assertTrue(engine.loadTshSource("scheme", source, null))
        val language = engine.getLanguageMetadata("scheme")
        assertEquals("scheme-test", language.id)
        assertEquals("Scheme Test", language.name)
        assertEquals(listOf("scheme", "sch"), language.extensions)
        assertEquals(listOf("Scheme", "Test Scheme", "Line\nBreak"), language.aliases)
        assertEquals(listOf("text/x-scheme-test"), language.mimeTypes)
        assertEquals("^#!.*scheme-test", language.firstLinePattern)
        assertTrue(engine.matchesFirstLine("scheme", "#!/usr/bin/env scheme-test"))
        assertFalse(engine.matchesFirstLine("scheme", "ordinary source file"))
        assertEquals(listOf("derived", "github", "midnight", "selectorTheme"), engine.listSchemes("scheme").sorted())
        assertEquals("midnight", engine.getDefaultScheme("scheme"))
        assertTrue(engine.isDark("scheme"))
        val editorMetadata = engine.getEditorMetadata("scheme")
        assertEquals(listOf("//"), editorMetadata.lineComments)
        assertEquals(listOf("/*" to "*/"), editorMetadata.blockComments)
        assertEquals(3, editorMetadata.brackets.size)
        assertEquals(listOf("string", "comment"), editorMetadata.bracketNotIn)
        assertEquals(listOf("string", "comment"), editorMetadata.autoClosingPairs.single().notIn)
        assertEquals(listOf(".", ":"), editorMetadata.completionTriggerCharacters)
        assertEquals(listOf("}", ";"), editorMetadata.electricCharacters)
        assertEquals(1, editorMetadata.onEnterRules.size)
        assertEquals("indentOutdent", editorMetadata.onEnterRules.single().indent)
        assertEquals("", editorMetadata.onEnterRules.single().appendText)
        assertNotNull(editorMetadata.autoCloseBefore)
        assertEquals("2", editorMetadata.options["tabSize"])
        assertEquals("16", editorMetadata.options["fontSize"])
        assertEquals("false", editorMetadata.options["insertSpaces"])
        assertEquals("true", editorMetadata.options["autoIndent"])
        val catalog = engine.getStyleCatalog("scheme")
        assertEquals("keyword.control", catalog.first { it.name == "keyword" }.scope)
        assertTrue(catalog.first { it.name == "keyword" }.tags.contains("control"))
        assertFalse(catalog.first { it.name == "keyword" }.deprecated)
        assertEquals("textmate", catalog.first { it.name == "keyword" }.metadata["standard"])
        assertEquals("syntax", catalog.first { it.name == "keyword" }.metadata["category"])
        assertEquals("类型", catalog.first { it.name == "keyword" }.metadata["localized"])
        assertTrue(engine.selectScheme("scheme", "midnight"))
        assertEquals(0xFF1E1E1E.toInt(), engine.getThemeTokens("scheme")["editor.background"])
        engine.setAppearance("scheme", false)
        assertFalse(engine.isDark("scheme"))
        assertEquals(0xFFFFFFFF.toInt(), engine.getThemeTokens("scheme")["editor.background"])
        engine.clearAppearanceOverride("scheme")
        assertTrue(engine.isDark("scheme"))
        assertTrue(engine.selectScheme("scheme", "derived"))
        assertEquals("derived", engine.getSelectedScheme("scheme"))
        assertEquals(0xFF1E1E1E.toInt(), engine.getThemeTokens("scheme")["editor.background"])
        val inheritedKeyword = engine.getStylePalette("scheme", "keyword")
        val explicitChild = engine.getStylePalette("scheme", "keywordStrong")
        assertEquals(0xFF112233.toInt(), inheritedKeyword?.lightColor)
        assertEquals(0xFFFF00AA.toInt(), explicitChild?.darkColor)
        assertEquals(true, explicitChild?.hasLightColor)
        assertTrue(engine.selectScheme("scheme", "selectorTheme"))
        val selectorPalette = engine.getStylePalette("scheme", "selectorOnly")
        assertEquals(0xFF00BB00.toInt(), selectorPalette?.lightColor)
        assertEquals(0xFFBB0000.toInt(), selectorPalette?.darkColor)
        assertEquals("selectorTheme", engine.getSelectedScheme("scheme"))
    }

    @Test
    fun testMonarchTokenizerStateTransitions() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language {
                    id: "stateful"
                    name: "Stateful Test"
                    extensions: [".stateful"]
                }
                styles {
                    identifier.state { scope: "variable.other", tags: ["identifier"] }
                    string.state { scope: "string.quoted", tags: ["string"] }
                }
                schemes {
                    plain {
                        light [ identifier.state: #202020, string.state: #008000 ]
                        dark [ identifier.state: #E0E0E0, string.state: #80FF80 ]
                    }
                }
                monarch {
                    variables {
                        identifier: /[A-Za-z_][A-Za-z0-9_]*/
                    }
                    tokenPostfix: ".state"
                    start: "root"
                    tokenizer {
                        root: [
                            { match: /"/, token: "string", push: "@string" }
                            { include: "@identifiers" }
                        ]
                        identifiers: [
                            { match: /@identifier/, token: "identifier" }
                        ]
                        string: [
                            { match: /"/, token: "string", pop: true }
                            { match: /[^"]+/, token: "string" }
                        ]
                    }
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("stateful", source, null))
        val code = "foo \"bar\" baz"
        val spans = engine.computeHighlights(null, code, "stateful")
        val identifierId = engine.getStyleCatalog("stateful").first { it.name == "identifier.state" }.styleId
        val stringId = engine.getStyleCatalog("stateful").first { it.name == "string.state" }.styleId
        assertTrue(spans.any { it.styleId == identifierId && it.startByte == 0 && it.endByte == 6 })
        assertTrue(spans.any { it.styleId == stringId && it.startByte == 8 && it.endByte == 10 })
        assertTrue(spans.any { it.styleId == stringId && it.startByte == 10 && it.endByte == 16 })
        assertTrue(spans.any { it.styleId == stringId && it.startByte == 16 && it.endByte == 18 })
        assertTrue(spans.any { it.styleId == identifierId && it.startByte == 20 && it.endByte == 26 })
    }

    @Test
    fun testMonarchRejectsUnknownAndCyclicVariables() {
        val engine = WindbellTshEngine.instance
        val cyclic = """
            tsh 2 {
                language { id: "monarch-cycle" name: "Monarch Cycle" extensions: [".mcycle"] }
                styles { identifier { scope: "variable.other" } }
                schemes { plain { light [ identifier: #111111 ] dark [ identifier: #EEEEEE ] } }
                monarch {
                    variables {
                        first: /@second/
                        second: /@first/
                    }
                    tokenizer {
                        root: [{ match: /@first/, token: "identifier" }]
                    }
                }
            }
        """.trimIndent()
        assertFalse(engine.loadTshSource("monarch-cycle", cyclic, null))

        val unknown = cyclic.replace("second: /@first/", "second: /@missing/")
        assertFalse(engine.loadTshSource("monarch-unknown", unknown, null))
    }

    @Test
    fun testEmbeddedTreeSitterQueryAndCaptureBinding() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language {
                    id: "java-query"
                    name: "Java Query"
                    extensions: [".java"]
                }
                styles {
                    type { scope: "entity.name.type", tags: ["type"] }
                }
                schemes {
                    plain {
                        light [ type: #0000FF ]
                        dark [ type: #79C0FF ]
                    }
                }
                treeSitter {
                    queries: [
                        {
                            id: "type-declarations"
                            source: `(class_declaration name: (identifier) @type)`
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-query", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo {}"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-query")
        val typeId = engine.getStyleCatalog("java-query").first { it.name == "type" }.styleId
        assertTrue(spans.any { it.styleId == typeId && it.startByte == 12 && it.endByte == 20 })
        tree?.close()
        parser.close()

        val invalidCapture = source.replace("@type", "@unknown")
        assertFalse(engine.loadTshSource("java-query-invalid", invalidCapture, language))
        assertTrue(engine.lastCompileDiagnostics.isNotEmpty())
    }

    @Test
    fun testOnigurumaCaptureGroupsAndNestedRegions() {
        val engine = WindbellTshEngine.instance
        val sourceDsl = """
            name: ["Regex Test", ".regex"]
            matching {
                caseSensitive: false
                multiline: true
                dotAll: false
                extended: false
                unicode: true
            }
            styles {
                comment { scope: "comment.block", tags: ["comment"] }
                commentContent { scope: "comment.content", tags: ["comment", "content"] }
                todo { scope: "keyword.other.todo", tags: ["todo"] }
            }
            schemes {
                plain {
                    light [ comment: #808080, commentContent: #707070, todo: #CC5500 ]
                    dark [ comment: #888888, commentContent: #777777, todo: #FFAA55 ]
                }
            }
            repository {
                todoBase: /FIXME/
                todoWord: "include(\"#todoBase\")"
            }
            contains: [
                {
                    start: { match: /(?<open>\/\*)/ }
                    end: { match: /(?<close>\*\/)/ }
                    name: "comment"
                    contentName: "commentContent"
                    beginCaptures: { open: "comment" }
                    endCaptures: { close: "comment" }
                    contains: [
                        {
                        match: /(?<todo>todo)/
                        flags: ["i"]
                        captures: { todo: "todo" }
                        priority: 100
                    }
                    {
                        match: include("#todoWord")
                        captures: { 0: "todo" }
                        priority: 90
                    }
                    ]
                }
            ]
        """.trimIndent()

        assertTrue(engine.loadTshSource("regex", sourceDsl, null))
        val text = "/* TODO FIXME: verify named capture */"
        val spans = engine.computeHighlights(null, text, "regex")
        assertTrue(spans.isNotEmpty())
        val todoId = engine.getStyleCatalog("regex").first { it.name == "todo" }.styleId
        assertTrue(spans.any { it.styleId == todoId && it.priority == 100 })
        assertTrue(spans.any { it.styleId == engine.getStyleCatalog("regex").first { it.name == "comment" }.styleId && it.startByte == 0 && it.endByte == 4 })
        assertTrue(spans.any { it.styleId == engine.getStyleCatalog("regex").first { it.name == "comment" }.styleId && it.endByte == text.length * 2 })
        val contentId = engine.getStyleCatalog("regex").first { it.name == "commentContent" }.styleId
        assertTrue(spans.any { it.styleId == contentId && it.startByte == 4 && it.endByte == text.length * 2 - 4 })
        assertTrue(spans.any { it.styleId == todoId && it.priority == 90 })
    }

    @Test
    fun testSurrogatePairsAndHighlightingStability() {
        val engine = WindbellTshEngine.instance
        if (engine.listSchemes("java-test").isEmpty()) {
            assertTrue(engine.loadTshSource("java-test", javaTestDsl(), null))
        }

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