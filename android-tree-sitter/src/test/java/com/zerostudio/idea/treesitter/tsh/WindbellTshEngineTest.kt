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
import android.text.SpannableStringBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        val invalidEscape = """
            language { id: "bad-escape" name: "bad\q" }
        """.trimIndent()
        assertFalse(engine.loadTshSource("bad-escape", invalidEscape, null))
        assertTrue(engine.lastCompileDiagnostics.first().message.contains("unsupported string escape"))

        val invalidPunctuation = """
            language { id: "bad-punctuation" = name: "invalid" }
        """.trimIndent()
        assertFalse(engine.loadTshSource("bad-punctuation", invalidPunctuation, null))
        assertTrue(engine.lastCompileDiagnostics.first().message.contains("unexpected character"))
    }

    @Test
    fun testUnimplementedRegexAttributesAreRejectedInsteadOfSilentlyAccepted() {
        val engine = WindbellTshEngine.instance
        val unsupportedAttributes = listOf(
            "while: /continuation/",
            "applyEndPatternLast: true",
            "when: { extension: \".sample\" }",
            "metadata: { owner: \"user\" }"
        )
        unsupportedAttributes.forEachIndexed { index, attribute ->
            val source = """
                tsh 2 {
                    language { id: "unsupported-$index" extensions: [".unsupported$index"] }
                    styles { token { scope: "variable.other" } }
                    contains: [
                        { match: /token/, style: "token", $attribute }
                    ]
                }
            """.trimIndent()
            assertFalse(
                engine.loadTshSource("unsupported-$index", source, null),
                "The parser must reject unsupported rule attribute: $attribute"
            )
        }
    }

    @Test
    fun testOutOfRangeNumericCaptureIsRejectedAtCompileTime() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "bad-capture" extensions: [".badcapture"] }
                styles { token { scope: "variable.other" } }
                contains: [
                    { match: /(token)/, captures: { 2: "token" } }
                ]
            }
        """.trimIndent()

        assertFalse(engine.loadTshSource("bad-capture", source, null))
        assertTrue(
            engine.lastCompileDiagnostics.firstOrNull()?.message
                ?.contains("capture group index is out of range") == true
        )
    }

    @Test
    fun testEmptyDocumentHighlightIsSafe() {
        val engine = WindbellTshEngine.instance
        assertTrue(engine.loadTshSource("empty-document", javaTestDsl(), null))
        assertTrue(engine.computeHighlights(null, "", "empty-document").isEmpty())
    }

    @Test
    fun testTextMateNamedRepositoryIncludeExecutes() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "repository-test" extensions: [".repository-test"] }
                styles {
                    identifier { scope: "variable.other", tags: ["identifier"] }
                }
                repository {
                    identifier: /[A-Za-z_][A-Za-z0-9_]*/
                    identifierAlias: include("#identifier")
                }
                contains: [
                    { match: include("#identifierAlias"), style: "identifier" }
                ]
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("repository-test", source, null))
        val spans = engine.computeHighlights(null, "alpha beta", "repository-test")
        val identifierId = engine.getStyleCatalog("repository-test")
            .first { it.name == "identifier" }.styleId
        assertEquals(2, spans.count { it.styleId == identifierId })
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
                aliases: ["Scheme", "Test Scheme", "Line\\nBreak", "Rocket \uD83D\uDE80"]
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
                    dark [ keyword: #AABBCC @dim, string: #BBCCDD ]
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
                        { selector: "source.scheme-test (keyword.control.selector | keyword.operator)", light: #00CC00, dark: #CC0000 }
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
                        afterText: /^\s*\}/
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
        assertEquals(listOf("Scheme", "Test Scheme", "Line\nBreak", "Rocket 🚀"), language.aliases)
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
        assertEquals(0x10, inheritedKeyword?.darkFontFlags)
        val explicitChild = engine.getStylePalette("scheme", "keywordStrong")
        assertEquals(0xFF112233.toInt(), inheritedKeyword?.lightColor)
        assertEquals(0xFFFF00AA.toInt(), explicitChild?.darkColor)
        assertEquals(true, explicitChild?.hasLightColor)
        assertTrue(engine.selectScheme("scheme", "selectorTheme"))
        val selectorPalette = engine.getStylePalette("scheme", "selectorOnly")
        assertEquals(0xFF00CC00.toInt(), selectorPalette?.lightColor)
        assertEquals(0xFFCC0000.toInt(), selectorPalette?.darkColor)
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
                    keyword.state { scope: "keyword.control", tags: ["keyword"] }
                    text.state { scope: "text.plain", tags: ["text"] }
                }
                schemes {
                    plain {
                        light [ identifier.state: #202020, string.state: #008000, keyword.state: #0000FF, text.state: #404040 ]
                        dark [ identifier.state: #E0E0E0, string.state: #80FF80, keyword.state: #80A0FF, text.state: #C0C0C0 ]
                    }
                }
                monarch {
                    variables {
                        identifier: /[A-Za-z_][A-Za-z0-9_]*/
                        keywords: /(?:if|else|return)/
                    }
                    start: "root"
                    ignoreCase: true
                    tokenizer {
                        root: [
                            { match: /"/, token: "string", push: "@string" }
                            { include: "@identifiers" }
                        ]
                        identifiers: [
                            { match: /@identifier/, cases: { "@keywords": "keyword", "@default": "identifier" } }
                        ]
                        string: [
                            { match: /"/, token: "string", pop: true }
                            { match: /[^"]+/, token: "string" }
                        ]
                    }
                    tokenPostfix: ".state"
                    defaultToken: "text"
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("stateful", source, null))
        val code = "IF foo \"bar\" baz"
        val spans = engine.computeHighlights(null, code, "stateful")
        val identifierId = engine.getStyleCatalog("stateful").first { it.name == "identifier.state" }.styleId
        val stringId = engine.getStyleCatalog("stateful").first { it.name == "string.state" }.styleId
        val keywordId = engine.getStyleCatalog("stateful").first { it.name == "keyword.state" }.styleId
        val textId = engine.getStyleCatalog("stateful").first { it.name == "text.state" }.styleId
        assertTrue(spans.any { it.styleId == keywordId && it.startByte == 0 && it.endByte == 4 })
        assertTrue(spans.any { it.styleId == textId && it.startByte == 4 && it.endByte == 6 })
        assertTrue(spans.any { it.styleId == identifierId && it.startByte == 6 && it.endByte == 12 })
        assertTrue(spans.any { it.styleId == stringId && it.startByte == 14 && it.endByte == 16 })
        assertTrue(spans.any { it.styleId == stringId && it.startByte == 16 && it.endByte == 22 })
        assertTrue(spans.any { it.styleId == stringId && it.startByte == 22 && it.endByte == 24 })
        assertTrue(spans.any { it.styleId == identifierId && it.startByte == 26 && it.endByte == 32 })
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
                    method { scope: "entity.name.function", tags: ["function"] }
                }
                schemes {
                    plain {
                        light [ type: #0000FF, method: #008000 ]
                        dark [ type: #79C0FF, method: #4EC9B0 ]
                    }
                }
                treeSitter {
                    queries: [
                        {
                            id: "type-declarations"
                            priority: 10
                            source: `(class_declaration name: (identifier) @type)`
                        },
                        {
                            id: "method-declarations"
                            priority: 30
                            source: `(method_declaration name: (identifier) @method)`
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-query", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-query")
        val typeId = engine.getStyleCatalog("java-query").first { it.name == "type" }.styleId
        val methodId = engine.getStyleCatalog("java-query").first { it.name == "method" }.styleId
        assertTrue(spans.any { it.styleId == typeId && it.priority == 10 })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 30 })
        tree?.close()
        parser.close()

        val invalidCapture = source.replace("@type", "@unknown")
        assertFalse(engine.loadTshSource("java-query-invalid", invalidCapture, language))
        assertTrue(engine.lastCompileDiagnostics.isNotEmpty())
    }

    @Test
    fun testNativeQueryTextPredicatesFilterWholeMatches() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-query-predicates" name: "Java Query Predicates" extensions: [".jqp"] }
                styles {
                    type { scope: "entity.name.type" }
                    method { scope: "entity.name.function" }
                }
                treeSitter {
                    queries: [
                        {
                            id: "exact-type"
                            priority: 20
                            source: `(class_declaration name: (identifier) @type (#eq? @type "Demo"))`
                        },
                        {
                            id: "exclude-method"
                            priority: 30
                            source: `(method_declaration name: (identifier) @method (#not-eq? @method "skip"))`
                        },
                        {
                            id: "allowed-method"
                            priority: 40
                            source: `(method_declaration name: (identifier) @method (#any-of? @method "run" "start"))`
                        }
                    ]
                }
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("java-query-predicates", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} void skip() {} void other() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-query-predicates")
        val catalog = engine.getStyleCatalog("java-query-predicates")
        val typeId = catalog.first { it.name == "type" }.styleId
        val methodId = catalog.first { it.name == "method" }.styleId

        assertTrue(spans.any { it.styleId == typeId && it.priority == 20 })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 30 })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 40 })
        val skipOffset = code.indexOf("skip") * 2
        val otherOffset = code.indexOf("other") * 2
        assertFalse(spans.any { it.styleId == methodId && it.startByte == skipOffset })
        assertFalse(spans.any {
            it.styleId == methodId && it.priority == 40 && it.startByte == otherOffset
        })

        tree?.close()
        parser.close()
    }

    @Test
    fun testAstSelectorNodeFlagsAreExecuted() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-ast-node-flags" name: "Java AST Node Flags" extensions: [".janf"] }
                styles {
                    declaration { scope: "entity.name.type" }
                    declarationAlias { scope: "entity.name.type.alias" }
                    operator { scope: "keyword.operator" }
                }
                ast: [
                    {
                        node: "identifier"
                        named: true
                        missing: false
                        extra: false
                        error: false
                        hasError: false
                        style: "declaration"
                        priority: 35
                        overlap: allow
                    },
                    {
                        node: "identifier"
                        named: true
                        missing: false
                        extra: false
                        error: false
                        hasError: false
                        style: "declarationAlias"
                        priority: 34
                        overlap: allow
                    },
                    {
                        node: "+"
                        named: false
                        style: "operator"
                        priority: 20
                    }
                ]
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-ast-node-flags", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { int value = 1 + 2; }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-ast-node-flags")
        val styleId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "declaration" }.styleId
        assertTrue(spans.any {
            it.styleId == styleId && it.startByte == code.indexOf("Demo") && it.priority == 35
        })
        val aliasId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "declarationAlias" }.styleId
        assertTrue(spans.any {
            it.styleId == aliasId && it.startByte == code.indexOf("Demo") && it.priority == 34
        }, "All matching ATS rules must emit candidates; the resolver handles overlap.")
        val operatorId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "operator" }.styleId
        assertTrue(spans.any {
            it.styleId == operatorId && it.startByte == code.indexOf("+") && it.endByte == code.indexOf("+") + 1
        }, "ATS must resolve and match anonymous Tree-sitter symbols.")
        tree?.close()
        parser.close()
    }

    @Test
    fun testNativeQueryPatternMetadataControlsPatternsIndependently() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-pattern-metadata" name: "Java Pattern Metadata" extensions: [".jpm"] }
                styles { token { scope: "variable.other" } }
                treeSitter {
                    queries: [
                        {
                            id: "mixed-patterns"
                            priority: 10
                            source: `(class_declaration name: (identifier) @token) (method_declaration name: (identifier) @token)`
                            patterns: [
                                { index: 0 enabled: false },
                                { index: 1 priority: 90 }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-pattern-metadata", source, language))

        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-pattern-metadata")
        val tokenId = engine.getStyleCatalog("java-pattern-metadata").first { it.name == "token" }.styleId

        assertFalse(spans.any {
            it.styleId == tokenId && it.startByte == code.indexOf("Demo")
        })
        assertTrue(spans.any {
            it.styleId == tokenId && it.startByte == code.indexOf("run") && it.priority == 90
        })
        tree?.close()
        parser.close()
    }

    @Test
    fun testNativeQueryRejectsOutOfRangePatternMetadata() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "query-pattern-out-of-range" extensions: [".qpo"] }
                styles { token { scope: "variable.other" } }
                treeSitter {
                    queries: [
                        {
                            id: "invalid-pattern-index"
                            source: `(identifier) @token`
                            patterns: [{ index: 4 enabled: false }]
                        }
                    ]
                }
            }
        """.trimIndent()
        assertFalse(engine.loadTshSource(
            "query-pattern-out-of-range", source, TSLanguageJava.getInstance()
        ))
    }

    @Test
    fun testNativeQueryRegexPredicates() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-query-regex" name: "Java Query Regex" extensions: [".jqr"] }
                styles { method { scope: "entity.name.function" } }
                treeSitter {
                    queries: [
                        {
                            id: "matching-method"
                            priority: 50
                            source: `(method_declaration name: (identifier) @method (#match? @method "^(run|start)$"))`
                        },
                        {
                            id: "nonmatching-method"
                            priority: 60
                            source: `(method_declaration name: (identifier) @method (#not-match? @method "^skip$"))`
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-query-regex", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} void skip() {} void other() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-query-regex")
        val methodId = engine.getStyleCatalog("java-query-regex").first { it.name == "method" }.styleId

        assertTrue(spans.any { it.styleId == methodId && it.priority == 50 })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 60 })
        assertFalse(spans.any {
            it.styleId == methodId && it.priority == 50 && it.startByte == code.indexOf("other") * 2
        })
        assertFalse(spans.any {
            it.styleId == methodId && it.priority == 60 && it.startByte == code.indexOf("skip") * 2
        })
        tree?.close()
        parser.close()
    }

    @Test
    fun testNativeQueryCaptureBindingsSeparateNamesFromStyles() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-capture-binding" name: "Java Capture Binding" extensions: [".jcb"] }
                styles {
                    declaration { scope: "entity.name.type" }
                    callable { scope: "entity.name.function" }
                }
                treeSitter {
                    queries: [
                        {
                            id: "types"
                            priority: 15
                            source: `(class_declaration name: (identifier) @declType)`
                            captures: { declType: declaration }
                        },
                        {
                            id: "methods"
                            priority: 25
                            source: `(method_declaration name: (identifier) @callableName)`
                            captures: { callableName: callable }
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-capture-binding", source, language))

        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-capture-binding")
        val catalog = engine.getStyleCatalog("java-capture-binding")
        val declarationId = catalog.first { it.name == "declaration" }.styleId
        val callableId = catalog.first { it.name == "callable" }.styleId

        assertTrue(spans.any { it.styleId == declarationId && it.priority == 15 })
        assertTrue(spans.any { it.styleId == callableId && it.priority == 25 })
        tree?.close()
        parser.close()

        val invalidBinding = source.replace("callableName: callable", "missingName: callable")
        assertFalse(engine.loadTshSource("java-capture-binding-invalid", invalidBinding, language))
    }

    @Test
    fun testNativeQueryRejectsUnsupportedPredicates() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "query-unsupported-predicate" extensions: [".qup"] }
                styles { token { scope: "variable.other" } }
                treeSitter {
                    queries: [
                        { id: "unsupported" source: `(identifier) @token (#match? @token "^[a-z]+$")` }
                    ]
                }
            }
        """.trimIndent()
        assertFalse(engine.loadTshSource(
            "query-unsupported-predicate", source, TSLanguageJava.getInstance()
        ))
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

    @Test
    fun testConcurrentHighlightAndSharedSpannableRendering() {
        val engine = WindbellTshEngine.instance
        assertTrue(engine.loadTshSource("java-concurrency", javaTestDsl(), null))

        val source = """
            // concurrency 👨‍👩‍👧‍👦 𠮷
            public class Demo {
                String value = "hello 🌍";
                static void run() { return; }
            }
        """.trimIndent()
        val expected = engine.computeHighlights(null, source, "java-concurrency")
        assertFalse(expected.isEmpty(), "The single-thread baseline must produce tokens.")

        // Deliberately share one mutable Spannable to verify that only writes to
        // that document are serialized; native highlight computation stays parallel.
        val sharedBuffer = SpannableStringBuilder(source)
        val pool = Executors.newFixedThreadPool(32)
        try {
            val jobs = (0 until 32).map {
                pool.submit {
                    repeat(30) {
                        val actual = engine.computeHighlights(null, source, "java-concurrency")
                        assertEquals(expected, actual, "Concurrent output must equal the single-thread baseline.")
                        engine.applyHighlightSpansToSpannable(
                            spans = actual,
                            documentOffset = 0,
                            text = source,
                            extension = "java-concurrency",
                            spannable = sharedBuffer
                        )
                    }
                }
            }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
            assertTrue(sharedBuffer.getSpans(0, sharedBuffer.length, android.text.style.CharacterStyle::class.java).isNotEmpty())
        } finally {
            pool.shutdownNow()
        }
    }


    @Test
    fun testIncrementalRenderPreservesSpansOutsideRequestedRange() {
        val engine = WindbellTshEngine.instance
        assertTrue(engine.loadTshSource("java-incremental-range", javaTestDsl(), null))
        val source = "public class RangeDemo { String value = \"hello\"; }"
        val buffer = SpannableStringBuilder(source)
        val sentinel = android.text.style.ForegroundColorSpan(0xFF123456.toInt())
        buffer.setSpan(sentinel, 0, 3, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val spans = engine.computeHighlights(null, source, "java-incremental-range")
        engine.applyHighlightSpansToSpannable(
            spans = spans,
            documentOffset = 0,
            text = source,
            extension = "java-incremental-range",
            spannable = buffer,
            start = 10,
            end = 20
        )

        assertTrue(
            buffer.getSpans(0, 3, android.text.style.ForegroundColorSpan::class.java)
                .any { it === sentinel },
            "A partial render must preserve pre-existing spans outside its invalidation range."
        )
    }

}