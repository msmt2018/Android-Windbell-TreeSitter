package com.zerostudio.idea.treesitter

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import com.zerostudio.idea.treesitter.tsh.NativeTextWindowReader
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import android.text.SpannableStringBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun testLargeNativeDocumentExecutesBoundedRegexWithoutWholeFileBuffer() {
        val engine = WindbellTshEngine.instance
        val extension = "large-regex-window"
        val dsl = """
            name: ["Large Regex Window", ".large-regex-window"]
            styles: [
                "token", #007F00, #6A8759,
            ]
            contains: [
                { match: /token/, style: "token" }
            ]
        """.trimIndent()
        assertTrue(engine.loadTshSource(extension, dsl, TSLanguageJava.getInstance()))

        // Exceed the JNI legacy contiguous-buffer threshold deliberately.
        // The file is a single Java block comment, so its AST stays small while
        // the TSH Regex token is placed near EOF in a later 64 KiB source window.
        val totalBytes = 32L * 1024L * 1024L + 128L * 1024L
        val targetChar = totalBytes / 2L - 64L
        val token = "token"
        val directory = File(System.getProperty("java.io.tmpdir"))
        val reader = NativeTextWindowReader { offset, requested ->
            val bytes = ByteArray(requested)
            for (i in 0 until requested step 2) {
                val charIndex = (offset + i) / 2L
                val value = when {
                    charIndex == 0L -> '/'
                    charIndex == 1L -> '*'
                    charIndex in targetChar until targetChar + token.length ->
                        token[(charIndex - targetChar).toInt()]
                    charIndex == totalBytes / 2L - 2L -> '*'
                    charIndex == totalBytes / 2L - 1L -> '/'
                    else -> 'a'
                }.code
                bytes[i] = (value and 0xFF).toByte()
                bytes[i + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            bytes
        }

        val handle = engine.createNativeDocument(
            TSLanguageJava.getInstance(), extension, totalBytes, directory, reader
        )
        try {
            val start = (targetChar - 8L) * 2L
            val end = (targetChar + token.length + 8L) * 2L
            val encoded = engine.highlightNativeDocument(handle, start, end, false)
            assertNotNull(encoded)
            val values = encoded!!
            assertTrue(values.size >= 6, "Expected the bounded native Regex pipeline to emit the token capture")
            val found = (0 until values.size / 6).any { index ->
                val base = index * 6
                values[base] == (targetChar * 2L).toInt() &&
                    values[base + 1] == ((targetChar + token.length) * 2L).toInt()
            }
            assertTrue(found, "Expected a Regex capture near EOF in a document larger than 32 MiB")
        } finally {
            engine.closeNativeDocument(handle)
        }
    }

    @Test
    fun testLargeTextSourceQueryPredicateDoesNotMaterializeHugeCaptureForEquality() {
        val engine = WindbellTshEngine.instance
        val extension = "large-query-predicate"
        val dsl = """
            tsh 2 {
                language { id: "large-query-predicate" extensions: [".large-query-predicate"] }
                styles { comment { scope: "comment.block" } }
                treeSitter {
                    queries: [
                        {
                            id: "large-comment-not-equal"
                            priority: 25
                            source: `(block_comment) @comment (#not-eq? @comment "x")`
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource(extension, dsl, TSLanguageJava.getInstance()))

        // One enormous AST capture, but a tiny predicate literal. The old
        // evaluator copied the complete capture into a temporary vector for
        // every match. The range comparator must reject by length without
        // materializing that 32 MiB capture.
        val totalBytes = 32L * 1024L * 1024L + 128L * 1024L
        val directory = File(System.getProperty("java.io.tmpdir"))
        val reader = NativeTextWindowReader { offset, requested ->
            val bytes = ByteArray(requested)
            for (i in 0 until requested step 2) {
                val charIndex = (offset + i) / 2L
                val value = when {
                    charIndex == 0L -> '/'
                    charIndex == 1L -> '*'
                    charIndex == totalBytes / 2L - 2L -> '*'
                    charIndex == totalBytes / 2L - 1L -> '/'
                    else -> 'a'
                }.code
                bytes[i] = (value and 0xFF).toByte()
                bytes[i + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            bytes
        }
        val handle = engine.createNativeDocument(
            TSLanguageJava.getInstance(), extension, totalBytes, directory, reader
        )
        try {
            val start = totalBytes - 128L
            val encoded = engine.highlightNativeDocument(handle, start, totalBytes, false)
            assertNotNull(encoded)
            val values = encoded!!
            val commentStyle = engine.getStyleCatalog(extension)
                .first { it.name == "comment" }.styleId
            assertTrue(
                (0 until values.size / 6).any { index ->
                    val base = index * 6
                    values[base] <= start.toInt() &&
                        values[base + 1] >= start.toInt() &&
                        values[base + 2] == commentStyle.toInt()
                },
                "The large block-comment capture must survive #not-eq? and produce a clipped style span"
            )
        } finally {
            engine.closeNativeDocument(handle)
        }
    }

    @Test
    fun testNativeQueryPredicateSourceReadFailureIsNotSilentlyTreatedAsNoMatch() {
        val engine = WindbellTshEngine.instance
        val extension = "query-predicate-read-failure"
        val dsl = """
            tsh 2 {
                language { id: "query-predicate-read-failure" extensions: [".qprf"] }
                styles { type { scope: "entity.name.type" } }
                treeSitter {
                    queries: [
                        {
                            id: "exact-class-name"
                            source: `(class_declaration name: (identifier) @type (#eq? @type "Demo"))`
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource(extension, dsl, TSLanguageJava.getInstance()))

        val bytes = "class Demo {}".toByteArray(Charsets.UTF_16LE)
        val readsAllowed = AtomicBoolean(true)
        val reader = NativeTextWindowReader { offset, requested ->
            if (!readsAllowed.get()) {
                ByteArray(0)
            } else {
                val start = offset.toInt()
                val end = minOf(bytes.size, start + requested)
                if (start >= end) ByteArray(0) else bytes.copyOfRange(start, end)
            }
        }
        val handle = engine.createNativeDocument(
            TSLanguageJava.getInstance(), extension, bytes.size.toLong(),
            File(System.getProperty("java.io.tmpdir")), reader
        )
        try {
            // Parsing has completed successfully. Fail subsequent TextSource reads
            // so the equality predicate must report an execution error, not reject
            // the query match and return an apparently complete empty result.
            readsAllowed.set(false)
            var failure: Throwable? = null
            try {
                engine.highlightNativeDocument(handle, 0L, bytes.size.toLong(), false)
            } catch (error: Throwable) {
                failure = error
            }
            assertNotNull(failure, "A failed capture read must escape as a highlight failure")
            assertTrue(
                failure is IllegalStateException,
                "Expected an explicit native highlight failure, got: $failure"
            )
        } finally {
            engine.closeNativeDocument(handle)
        }
    }

    @Test
    fun testOverlappingForegroundSpansCarryDeterministicPrecedence() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "span-precedence" extensions: [".span-precedence"] }
                styles {
                    outer { scope: "meta.outer" }
                    inner { scope: "entity.name.inner" }
                }
                schemes {
                    test {
                        light [ outer: #112233, inner: #AABBCC ]
                        dark [ outer: #223344, inner: #BBCCDD ]
                    }
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("span-precedence", source, null))
        assertTrue(engine.selectScheme("span-precedence", "test"))
        engine.setAppearance("span-precedence", false)

        val styles = engine.getStyleCatalog("span-precedence").associateBy { it.name }
        val outerId = styles.getValue("outer").styleId
        val innerId = styles.getValue("inner").styleId
        val captures = listOf(
            WindbellTshEngine.HighlightSpan(0, 8, outerId, 0, 0, 0, 2),
            WindbellTshEngine.HighlightSpan(2, 6, innerId, 0, 10, 1, 3)
        )
        val styled = SpannableStringBuilder("abcdefgh")
        engine.applyHighlightSpansToSpannable(captures, 0, "abcdefgh", "span-precedence", styled)

        val innerColor = engine.getSchemePalette("span-precedence", "test").getValue(innerId).lightColor
        val spans = styled.getSpans(2, 6, android.text.style.ForegroundColorSpan::class.java)
        val winning = spans.maxByOrNull {
            styled.getSpanFlags(it) and android.text.Spanned.SPAN_PRIORITY
        }
        assertEquals(innerColor, winning?.foregroundColor)
        assertTrue(spans.size >= 2, "Both nested captures must remain available to the painter")
        engine.clearAppearanceOverride("span-precedence")
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
    fun testByteRangeHighlightingRestrictsNativeQueryAndClipsOutput() {
        val engine = WindbellTshEngine.instance
        val rangeDsl = """
            tsh 2 {
                language { id: "range-test" extensions: [".range-test"] }
                styles { identifier { scope: "variable.other" } }
                contains: [
                    { match: /[a-z]+/, style: "identifier" }
                ]
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("range-test", rangeDsl, null))

        val text = "alpha beta"
        val spans = engine.computeHighlights(
            tree = null,
            text = text,
            extension = "range-test",
            startByte = 0L,
            endByte = 10L
        )

        assertTrue(spans.isNotEmpty(), "The first identifier should be highlighted")
        assertTrue(spans.all { it.startByte >= 0 && it.endByte <= 10 })
        assertTrue(spans.none { it.startByte >= 12 }, "Captures outside the requested range must be omitted")

        val emptyRange = engine.computeHighlights(
            tree = null,
            text = text,
            extension = "range-test",
            startByte = 4L,
            endByte = 4L
        )
        assertTrue(emptyRange.isEmpty())
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
                aliases: ["Scheme", "Test Scheme", "Line\nBreak", "Rocket \uD83D\uDE80"]
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
        val diagnostics = engine.computePipelineDiagnostics(null, code, "stateful")
        assertTrue(spans.any { it.source == 1 }, "Monarch captures must retain their producer source through JNI.")
        assertTrue((diagnostics.matchCounts[1] ?: 0) > 0,
            "Monarch token match count must be reported independently of emitted captures.")
        assertTrue(diagnostics.captures.any { it.source == 1 })
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
        val code = "// 😀 prefix\nclass Demo { void run() {} void skip() {} void other() {} }"
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
                    methodName { scope: "entity.name.function" }
                    methodWithBody { scope: "meta.function.body" }
                    operatorWithOperands { scope: "keyword.operator.arithmetic" }
                    classKeyword { scope: "keyword.declaration.class" }
                    firstClassName { scope: "entity.name.type.first" }
                    methodRunText { scope: "entity.name.function.run" }
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
                        previousSibling: "decimal_integer_literal"
                        nextSibling: "decimal_integer_literal"
                        style: "operator"
                        priority: 20
                    },
                    {
                        node: "class"
                        named: false
                        firstChild: true
                        childIndex: 0
                        style: "classKeyword"
                        priority: 25
                    },
                    {
                        node: "identifier"
                        ancestor: "class_declaration"
                        namedChildIndex: 0
                        style: "firstClassName"
                        priority: 26
                    },
                    {
                        node: "identifier"
                        ancestor: "method_declaration"
                        style: "methodName"
                        priority: 45
                    },
                    {
                        node: "identifier"
                        ancestor: "method_declaration"
                        text: "run"
                        textPrefix: "r"
                        textSuffix: "n"
                        style: "methodRunText"
                        priority: 60
                    },
                    {
                        node: "method_declaration"
                        child: "block"
                        style: "methodWithBody"
                        priority: 55
                    }
                ]
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-ast-node-flags", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "// 😀 prefix\nclass Demo { int value = 1 + 2; void run() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-ast-node-flags")
        assertTrue(spans.any { it.source == 2 }, "ATS captures must retain their producer source through JNI.")
        val styleId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "declaration" }.styleId
        assertTrue(spans.any {
            it.styleId == styleId && it.startByte == code.indexOf("Demo") * 2 && it.priority == 35
        })
        val aliasId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "declarationAlias" }.styleId
        assertTrue(spans.any {
            it.styleId == aliasId && it.startByte == code.indexOf("Demo") * 2 && it.priority == 34
        }, "All matching ATS rules must emit candidates; the resolver handles overlap.")
        val operatorId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "operator" }.styleId
        assertTrue(spans.any {
            it.styleId == operatorId && it.startByte == code.indexOf("+") * 2 && it.endByte == (code.indexOf("+") + 1) * 2
        }, "ATS must resolve and match anonymous Tree-sitter symbols.")
        val methodNameId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "methodName" }.styleId
        assertTrue(spans.any {
            it.styleId == methodNameId && it.startByte == code.indexOf("run") * 2
        }, "ATS ancestor constraints must match descendants.")
        assertFalse(spans.any {
            it.styleId == methodNameId && it.startByte == code.indexOf("Demo") * 2
        }, "ATS ancestor constraints must reject nodes outside the selected ancestor.")
        val classKeywordId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "classKeyword" }.styleId
        assertTrue(spans.any {
            it.styleId == classKeywordId && it.startByte == code.indexOf("class") * 2
        }, "ATS firstChild and childIndex constraints must select the first syntax child.")
        val firstClassNameId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "firstClassName" }.styleId
        assertTrue(spans.any {
            it.styleId == firstClassNameId && it.startByte == code.indexOf("Demo") * 2
        }, "ATS namedChildIndex must count named siblings only.")
        val operatorWithOperandsId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "operatorWithOperands" }.styleId
        assertTrue(spans.any {
            it.styleId == operatorWithOperandsId && it.startByte == code.indexOf("+") * 2
        }, "ATS previousSibling and nextSibling constraints must both match.")
        val methodRunTextId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "methodRunText" }.styleId
        assertTrue(spans.any {
            it.styleId == methodRunTextId && it.startByte == code.indexOf("run") * 2
        }, "ATS exact text, prefix and suffix constraints must be applied.")
        val methodBodyId = engine.getStyleCatalog("java-ast-node-flags")
            .first { it.name == "methodWithBody" }.styleId
        assertTrue(spans.any { it.styleId == methodBodyId && it.priority == 55 },
            "ATS child constraints must match direct child node types.")
        tree?.close()
        parser.close()
    }

    @Test
    fun testAstSelectorTextRegexIsCompiledAndExecuted() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-ast-text-regex" extensions: [".jatr"] }
                styles { methodName { scope: "entity.name.function" } }
                ast: [
                    {
                        node: "identifier"
                        ancestor: "method_declaration"
                        textRegex: /^run$/
                        style: "methodName"
                        priority: 70
                    }
                ]
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("java-ast-text-regex", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} void skip() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-ast-text-regex")
        val styleId = engine.getStyleCatalog("java-ast-text-regex")
            .first { it.name == "methodName" }.styleId

        assertTrue(spans.any {
            it.styleId == styleId && it.startByte == code.indexOf("run") * 2 && it.priority == 70
        }, "ATS textRegex must match the complete UTF-16 node text.")
        assertFalse(spans.any {
            it.styleId == styleId && it.startByte == code.indexOf("skip") * 2
        }, "ATS textRegex must reject nodes whose text does not match.")

        tree?.close()
        parser.close()

        val invalid = """
            tsh 2 {
                language { id: "java-ast-text-regex-invalid" extensions: [".jatri"] }
                styles { token { scope: "variable.other" } }
                ast: [
                    { node: "identifier" textRegex: /(/ style: "token" }
                ]
            }
        """.trimIndent()
        assertFalse(engine.loadTshSource("java-ast-text-regex-invalid", invalid, language),
            "Invalid ATS regex patterns must fail during compilation.")
    }

    @Test
    fun testAstAndNativeQueryCandidatesShareResolver() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-unified-span-sources" extensions: [".juss"] }
                styles {
                    astName { scope: "entity.name.function.ast" }
                    queryName { scope: "entity.name.function.query" }
                }
                ast: [
                    {
                        node: "method_declaration"
                        field: "name"
                        style: "astName"
                        priority: 40
                        overlap: allow
                    }
                ]
                treeSitter {
                    queries: [
                        {
                            id: "method-name-query"
                            priority: 50
                            source: `(method_declaration name: (identifier) @queryName)`
                        }
                    ]
                }
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("java-unified-span-sources", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-unified-span-sources")
        val diagnostics = engine.computePipelineDiagnostics(tree, code, "java-unified-span-sources")
        val rawCandidates = diagnostics.captures
        val catalog = engine.getStyleCatalog("java-unified-span-sources")
        val astId = catalog.first { it.name == "astName" }.styleId
        val queryId = catalog.first { it.name == "queryName" }.styleId
        val runStart = code.indexOf("run") * 2
        val sameRange = spans.filter {
            it.startByte == runStart && it.endByte == runStart + 6
        }

        assertTrue(sameRange.any { it.styleId == astId && it.priority == 40 },
            "ATS candidates must survive the shared resolver when overlap is explicitly allowed.")
        assertTrue(sameRange.any { it.styleId == queryId && it.priority == 50 },
            "Native query candidates must join the same resolved output.")
        assertEquals(queryId, sameRange.first().styleId,
            "Higher-priority spans must be ordered first for identical ranges.")

        val rawSameRange = rawCandidates.filter {
            it.startByte == runStart && it.endByte == runStart + 6
        }
        assertTrue(rawSameRange.any { it.source == 2 && it.styleId == astId },
            "Diagnostic mode must preserve AST selector candidates before resolver processing.")
        assertTrue(rawSameRange.any { it.source == 3 && it.styleId == queryId },
            "Diagnostic mode must preserve native query candidates before resolver processing.")
        assertTrue(rawCandidates.size >= spans.size,
            "Raw producer diagnostics must not lose candidates during final conflict resolution.")
        assertTrue((diagnostics.matchCounts[2] ?: 0) > 0,
            "AST selector match count must be reported independently of capture count.")
        assertTrue((diagnostics.matchCounts[3] ?: 0) > 0,
            "Native Tree-sitter Query match count must be reported independently of capture count.")

        tree?.close()
        parser.close()
    }

    @Test
    fun testAstFieldSelectorUsesIndexedOwnerDispatch() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-ast-field-index" extensions: [".jafi"] }
                styles { functionName { scope: "entity.name.function" } }
                ast: [
                    {
                        node: "method_declaration"
                        field: "name"
                        style: "functionName"
                        priority: 75
                    }
                ]
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("java-ast-field-index", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} void skip() {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-ast-field-index")
        val styleId = engine.getStyleCatalog("java-ast-field-index")
            .first { it.name == "functionName" }.styleId

        assertTrue(spans.any {
            it.styleId == styleId && it.startByte == code.indexOf("run") * 2 &&
                it.endByte == (code.indexOf("run") + 3) * 2
        }, "A field selector must be dispatched from its owner symbol to the selected child.")
        assertFalse(spans.any {
            it.styleId == styleId && it.startByte == code.indexOf("Demo") * 2
        })

        tree?.close()
        parser.close()
    }

    @Test
    fun testAstSelectorRejectsDuplicateProperties() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "duplicate-ast-property" extensions: [".dap"] }
                styles { token { scope: "variable.other" } }
                ast: [
                    {
                        node: "identifier"
                        node: "method_declaration"
                        style: "token"
                    }
                ]
            }
        """.trimIndent()
        assertFalse(engine.loadTshSource(
            "duplicate-ast-property", source, TSLanguageJava.getInstance()
        ))
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
            it.styleId == tokenId && it.startByte == code.indexOf("Demo") * 2
        })
        assertTrue(spans.any {
            it.styleId == tokenId && it.startByte == code.indexOf("run") * 2 && it.priority == 90
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
                        },
                        {
                            id: "combined-method-regex"
                            priority: 70
                            source: `(method_declaration name: (identifier) @method (#match? @method "^r") (#match? @method "n$"))`
                        },
                        {
                            id: "all-captures-not-equal"
                            priority: 80
                            source: `(method_declaration name: (identifier) @method parameters: (formal_parameters (formal_parameter name: (identifier) @method)) (#not-eq? @method "skip"))`
                        },
                        {
                            id: "all-captures-not-match"
                            priority: 90
                            source: `(method_declaration name: (identifier) @method parameters: (formal_parameters (formal_parameter name: (identifier) @method)) (#not-match? @method "^skip$"))`
                        },
                        {
                            id: "all-captures-any-of"
                            priority: 100
                            source: `(method_declaration name: (identifier) @method parameters: (formal_parameters (formal_parameter name: (identifier) @method)) (#any-of? @method "multiOk" "value"))`
                        },
                        {
                            id: "all-captures-not-any-of"
                            priority: 110
                            source: `(method_declaration name: (identifier) @method parameters: (formal_parameters (formal_parameter name: (identifier) @method)) (#not-any-of? @method "skip"))`
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-query-regex", source, language))
        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() {} void rune() {} void skip() {} void other() {} void multiOk(int value) {} void multiBad(int skip) {} }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-query-regex")
        val methodId = engine.getStyleCatalog("java-query-regex").first { it.name == "method" }.styleId

        assertTrue(spans.any { it.styleId == methodId && it.priority == 50 })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 60 })
        assertTrue(spans.any {
            it.styleId == methodId && it.priority == 70 && it.startByte == code.indexOf("run") * 2
        }, "Both regex predicates must be evaluated for the same query pattern.")
        assertFalse(spans.any {
            it.styleId == methodId && it.priority == 70 && it.startByte == code.indexOf("rune") * 2
        }, "A match accepted by only the first regex predicate must be rejected.")
        assertFalse(spans.any {
            it.styleId == methodId && it.priority == 50 && it.startByte == code.indexOf("other") * 2
        })
        assertFalse(spans.any {
            it.styleId == methodId && it.priority == 60 && it.startByte == code.indexOf("skip") * 2
        })
        val multiOkName = code.indexOf("multiOk") * 2
        val multiOkParameter = code.indexOf("value") * 2
        val multiBadName = code.indexOf("multiBad") * 2
        val multiBadParameter = code.lastIndexOf("skip") * 2
        assertTrue(spans.any { it.styleId == methodId && it.priority == 80 && it.startByte == multiOkName })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 80 && it.startByte == multiOkParameter })
        assertFalse(spans.any { it.styleId == methodId && it.priority == 80 && it.startByte == multiBadName },
            "#not-eq? must reject a match when any captured value equals the forbidden string.")
        assertFalse(spans.any { it.styleId == methodId && it.priority == 80 && it.startByte == multiBadParameter })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 90 && it.startByte == multiOkName })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 90 && it.startByte == multiOkParameter })
        assertFalse(spans.any { it.styleId == methodId && it.priority == 90 && it.startByte == multiBadName },
            "#not-match? must require every captured value not to match.")
        assertFalse(spans.any { it.styleId == methodId && it.priority == 90 && it.startByte == multiBadParameter })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 100 && it.startByte == multiOkName })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 100 && it.startByte == multiOkParameter })
        assertFalse(spans.any { it.styleId == methodId && it.priority == 100 && it.startByte == multiBadName },
            "#any-of? requires every captured value to belong to the allowed set.")
        assertFalse(spans.any { it.styleId == methodId && it.priority == 100 && it.startByte == multiBadParameter })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 110 && it.startByte == multiOkName })
        assertTrue(spans.any { it.styleId == methodId && it.priority == 110 && it.startByte == multiOkParameter })
        assertFalse(spans.any { it.styleId == methodId && it.priority == 110 && it.startByte == multiBadName },
            "#not-any-of? rejects a match if any captured value belongs to the forbidden set.")
        assertFalse(spans.any { it.styleId == methodId && it.priority == 110 && it.startByte == multiBadParameter })
        tree?.close()
        parser.close()
    }

    @Test
    fun testNativeQueryStylesJavaLocalVariableDeclaration() {
        val engine = WindbellTshEngine.instance
        val language = TSLanguageJava.getInstance()
        val source = """
            tsh 2 {
                language { id: "java-local-variable-query" extensions: [".jlvq"] }
                styles { variable { scope: "variable.other" } }
                treeSitter {
                    queries: [
                        {
                            id: "local-variable-declarations"
                            priority: 12
                            source: `(variable_declarator name: (identifier) @localName)`
                            captures: { localName: variable }
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(engine.loadTshSource("java-local-variable-query", source, language))

        val parser = TSParser.create().apply { setLanguage(language) }
        val code = "class Demo { void run() { int count = 1; } }"
        val tree = parser.parseString(code)
        val spans = engine.computeHighlights(tree, code, "java-local-variable-query")
        val variableId = engine.getStyleCatalog("java-local-variable-query")
            .first { it.name == "variable" }.styleId
        val variableSpan = spans.firstOrNull {
            it.styleId == variableId &&
                code.substring(it.startByte shr 1, it.endByte shr 1) == "count"
        }
        assertNotNull(variableSpan, "Native Tree-sitter Query must capture the local variable name")
        assertEquals(3, variableSpan?.source)
        assertEquals(code.indexOf("count") * 2, variableSpan?.startByte)
        assertEquals((code.indexOf("count") + "count".length) * 2, variableSpan?.endByte)
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
        val diagnostics = engine.computePipelineDiagnostics(null, text, "regex")
        assertTrue(spans.any { it.source == 0 }, "Oniguruma captures must retain their producer source through JNI.")
        assertTrue((diagnostics.matchCounts[0] ?: 0) > 0,
            "Oniguruma match count must include block and nested regex matches.")
        assertTrue(diagnostics.captures.any { it.source == 0 })
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
    fun testUtf16UnicodeCrossLineBlockAndZeroWidthRegexRegression() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "unicode-cross-line" extensions: [".unicode-cross-line"] }
                matching { multiline: true dotAll: true unicode: true }
                styles {
                    block { scope: "comment.block" }
                    blockContent { scope: "comment.content" }
                    word { scope: "variable.other" }
                    zeroWidth { scope: "meta.zero-width" }
                }
                contains: [
                    {
                        start: { match: /BEGIN/ }
                        end: { match: /END/ }
                        name: "block"
                        contentName: "blockContent"
                    }
                    { match: /^/, style: "zeroWidth" }
                    { match: /alpha/, style: "word" }
                ]
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("unicode-cross-line", source, null))
        val code = "BEGIN\n中文 👋 𠮷\nEND\nalpha"
        val spans = engine.computeHighlights(null, code, "unicode-cross-line")
        val styles = engine.getStyleCatalog("unicode-cross-line").associateBy { it.name }
        val blockId = styles.getValue("block").styleId
        val contentId = styles.getValue("blockContent").styleId
        val wordId = styles.getValue("word").styleId
        val zeroWidthId = styles.getValue("zeroWidth").styleId

        val block = spans.firstOrNull { it.styleId == blockId }
        assertNotNull(block, "The block start delimiter must be captured.")
        assertEquals(0, block!!.startByte)
        assertEquals("BEGIN".length * 2, block.endByte)

        val content = spans.firstOrNull { it.styleId == contentId }
        assertNotNull(content, "The multiline block content must be captured.")
        val contentText = code.substring(content!!.startByte / 2, content.endByte / 2)
        assertEquals("中文 👋 𠮷\n", contentText)
        assertEquals(code.indexOf("中文") * 2, content.startByte)
        assertEquals((code.indexOf("END")) * 2, content.endByte)

        assertTrue(spans.any {
            it.styleId == wordId &&
                it.startByte == code.indexOf("alpha") * 2 &&
                it.endByte == (code.indexOf("alpha") + "alpha".length) * 2
        }, "The regex pipeline must continue after a zero-width match.")
        assertFalse(spans.any { it.styleId == zeroWidthId && it.startByte == it.endByte },
            "Zero-width matches must never escape as paint spans.")
        spans.forEach { span ->
            assertEquals(0, span.startByte % 2)
            assertEquals(0, span.endByte % 2)
            assertTrue(span.endByte >= span.startByte)
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

 
    @Test
    fun testSpanResolverClipsOnlyStrictPartialOverlap() {
        val engine = WindbellTshEngine.instance
        val source = """
            tsh 2 {
                language { id: "partial-span-arbitration" extensions: [".psa"] }
                styles {
                    low { scope: "variable.other.low" }
                    high { scope: "keyword.control.high" }
                }
                contains: [
                    { match: /abcd/, style: "low", priority: 10, overlap: "deny" }
                    { match: /cdef/, style: "high", priority: 20, overlap: "deny" }
                ]
            }
        """.trimIndent()

        assertTrue(engine.loadTshSource("partial-span-arbitration", source, null))
        val code = "abcdef"
        val spans = engine.computeHighlights(null, code, "partial-span-arbitration")
        val catalog = engine.getStyleCatalog("partial-span-arbitration")
        val lowId = catalog.first { it.name == "low" }.styleId
        val highId = catalog.first { it.name == "high" }.styleId

        assertTrue(spans.any {
            it.styleId == lowId && it.startByte == 0 && it.endByte == 4
        }, "The non-conflicting prefix of a lower-priority span must be preserved.")
        assertTrue(spans.any {
            it.styleId == highId && it.startByte == 4 && it.endByte == 12
        }, "The higher-priority span must retain its complete range.")
        assertFalse(spans.any {
            it.styleId == lowId && it.startByte == 0 && it.endByte == 8
        }, "The lower-priority span must not retain the conflicting interval.")
    }


    @Test
    fun largeNativeTreeSitterQueryCapturesSurviveIncrementalEditsNearEof() {
        val engine = WindbellTshEngine.instance
        val extension = "large-query-window"
        val dsl = """
            tsh 2 {
                language { id: "large-query-window" extensions: [".lqw"] }
                styles { token { scope: "entity.name.type" } }
                treeSitter {
                    queries: [
                        {
                            id: "class-name"
                            priority: 20
                            source: `(class_declaration name: (identifier) @className)`
                            captures: { className: token }
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue("Large query TSH DSL must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource(extension, dsl, TSLanguageJava.getInstance()))

        val totalBytes = 32L * 1024L * 1024L + 128L * 1024L
        val totalChars = totalBytes / 2L
        val classText = "class DistantTokenProbe { int value; }"
        val targetChar = totalChars - classText.length
        val closeStart = targetChar - 3L
        val reader = NativeTextWindowReader { offset, requested ->
            val bytes = ByteArray(requested)
            for (i in 0 until requested step 2) {
                val charIndex = (offset + i) / 2L
                val ch = when {
                    charIndex == 0L -> '/'
                    charIndex == 1L -> '*'
                    charIndex == closeStart -> '*'
                    charIndex == closeStart + 1L -> '/'
                    charIndex == closeStart + 2L -> '\n'
                    charIndex in targetChar until targetChar + classText.length ->
                        classText[(charIndex - targetChar).toInt()]
                    else -> 'a'
                }.code
                bytes[i] = (ch and 0xFF).toByte()
                bytes[i + 1] = ((ch ushr 8) and 0xFF).toByte()
            }
            bytes
        }
        val handle = engine.createNativeDocument(
            TSLanguageJava.getInstance(), extension, totalBytes,
            File(System.getProperty("java.io.tmpdir")), reader
        )
        try {
            val queryStart = targetChar * 2L
            val queryEnd = (targetChar + classText.length) * 2L
            val first = engine.highlightNativeDocument(handle, queryStart, queryEnd, false)
                ?: error("Initial large-file query returned null")
            val originalNameStart = targetChar + classText.indexOf("DistantTokenProbe")
            val originalNameEnd = originalNameStart + "DistantTokenProbe".length
            fun containsCapture(values: IntArray, start: Long, end: Long): Boolean =
                (0 until values.size / 6).any { index ->
                    val base = index * 6
                    Integer.toUnsignedLong(values[base]) == start * 2L &&
                        Integer.toUnsignedLong(values[base + 1]) == end * 2L
                }
            assertTrue(
                "Native Tree-sitter query must capture a declaration near EOF beyond 32 MiB",
                containsCapture(first, originalNameStart, originalNameEnd)
            )

            val replacement = "UpdatedTokenProbe"
            assertEquals("Incremental test replacement must preserve token width",
                "DistantTokenProbe".length, replacement.length)
            val editStart = originalNameStart * 2L
            val editEnd = originalNameEnd * 2L
            val inserted = ByteArray(replacement.length * 2)
            for (index in replacement.indices) {
                val unit = replacement[index].code
                inserted[index * 2] = (unit and 0xFF).toByte()
                inserted[index * 2 + 1] = (unit ushr 8).toByte()
            }
            assertTrue("Native Piece Tree and Tree-sitter incremental edit must commit",
                engine.applyNativeDocumentEdit(handle, editStart, editEnd, inserted))

            val updated = engine.highlightNativeDocument(handle, queryStart, queryEnd, false)
                ?: error("Post-edit large-file query returned null")
            val updatedStart = targetChar + classText.indexOf("DistantTokenProbe")
            val updatedEnd = updatedStart + replacement.length
            assertTrue(
                "Tree-sitter query must return the edited identifier at the same absolute range",
                containsCapture(updated, updatedStart, updatedEnd)
            )
        } finally {
            engine.closeNativeDocument(handle)
        }
    }
    @Test
    fun denseNativeQueryReturnsEveryCaptureWithoutArtificialWorkOrSpanCaps() {
        val engine = WindbellTshEngine.instance
        val extension = "dense-query-completeness"
        val dsl = """
            tsh 2 {
                language { id: "dense-query-completeness" extensions: [".dqc"] }
                styles { token { scope: "entity.name.type" } }
                treeSitter {
                    queries: [
                        {
                            id: "complete-class-captures"
                            priority: 20
                            source: `(class_declaration name: (identifier) @name body: (class_body) @body) @whole`
                            captures: { whole: token, name: token, body: token }
                        }
                    ]
                }
            }
        """.trimIndent()
        assertTrue(
            "Dense-query TSH DSL must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource(extension, dsl, TSLanguageJava.getInstance())
        )

        // Three captures per declaration deliberately exceed the former
        // 500,000-span render cap. The query also traverses enough AST nodes to
        // pass the former fixed 1,024-callback progress cutoff.
        val declarationCount = 200_000
        val source = buildString(declarationCount * 24) {
            append("package dense;\n")
            repeat(declarationCount) { index ->
                append("class DenseType")
                append(index)
                append(" {}\n")
            }
        }
        val parser = TSParser.create().apply { language = TSLanguageJava.getInstance() }
        val tree = parser.parseString(source)
        assertNotNull(tree)
        try {
            val root = tree!!.rootNode
            assertFalse(
                root.hasErrors(),
                "Large full-document ATS tree must not contain ERROR or MISSING descendants"
            )
            assertEquals(0, root.startByte, "The ATS root must begin at absolute byte zero")
            assertEquals(
                source.length.toLong() * 2L,
                root.endByte.toLong(),
                "The ATS root must cover the complete UTF-16LE source without truncation"
            )
            val spans = engine.computeHighlights(tree, source, extension)
            val queryCaptures = spans.count { it.source == 3 }
            assertEquals(
                declarationCount * 3,
                queryCaptures,
                "Every declaration must retain its whole-node, name, and body captures"
            )
        } finally {
            tree?.close()
            parser.close()
        }
    }


}