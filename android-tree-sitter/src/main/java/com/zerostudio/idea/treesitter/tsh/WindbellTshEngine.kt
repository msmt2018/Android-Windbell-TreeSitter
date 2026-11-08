package com.zerostudio.idea.treesitter.tsh

import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSTree
import dalvik.annotation.optimization.FastNative
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import com.zerostudio.idea.treesitter.TreeSitter

/** Supplies bounded UTF-16LE windows to the native document engine. */
fun interface NativeTextWindowReader {
    fun read(byteOffset: Long, maxBytes: Int): ByteArray
}
/**
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * Provides the dual-backend highlighting bridge and DSL-backed style rendering.
 */
class WindbellTshEngine private constructor() {

    data class LanguageMetadata(
        val id: String = "",
        val name: String = "",
        val extensions: List<String> = emptyList(),
        val aliases: List<String> = emptyList(),
        val mimeTypes: List<String> = emptyList(),
        val firstLinePattern: String? = null
    )

    data class CompileDiagnostic(
        val code: String,
        val message: String,
        val offset: Int,
        val line: Int,
        val column: Int
    )

    @Volatile
    var lastCompileDiagnostics: List<CompileDiagnostic> = emptyList()
        private set

    data class HighlightSpan(
        val startByte: Int,
        val endByte: Int,
        val styleId: Int,
        val fontFlags: Int,
        val priority: Int,
        val declarationOrder: Int,
        /** Native HighlightSource ordinal (ONIG_REGEX, MONARCH, AST_SELECTOR, NATIVE_QUERY). */
        val source: Int = 255
    )

    data class AutoClosingPair(
        val open: String,
        val close: String,
        val notIn: List<String>
    )

    data class OnEnterRule(
        val beforeText: String,
        val afterText: String? = null,
        val previousLineText: String? = null,
        val indent: String,
        val appendText: String? = null,
        val removeText: Int = 0
    )

    data class EditorMetadata(
        val lineComments: List<String> = emptyList(),
        val blockComments: List<Pair<String, String>> = emptyList(),
        val brackets: List<Pair<String, String>> = emptyList(),
        val bracketNotIn: List<String> = emptyList(),
        val autoClosingPairs: List<AutoClosingPair> = emptyList(),
        val surroundingPairs: List<Pair<String, String>> = emptyList(),
        val wordPattern: String? = null,
        val indentationPatterns: Map<String, String> = emptyMap(),
        val completionTriggerCharacters: List<String> = emptyList(),
        val electricCharacters: List<String> = emptyList(),
        val autoCloseBefore: String? = null,
        val onEnterRules: List<OnEnterRule> = emptyList(),
        val options: Map<String, String> = emptyMap()
    )

    data class SemanticStyle(
        val styleId: Int,
        val name: String,
        val scope: String,
        val description: String,
        val parent: String,
        val tags: List<String>,
        val deprecated: Boolean = false,
        val metadata: Map<String, String> = emptyMap()
    )

    data class StylePalette(
        val styleId: Int,
        val lightColor: Int,
        val darkColor: Int,
        val lightFontFlags: Int,
        val darkFontFlags: Int,
        val hasLightColor: Boolean,
        val hasDarkColor: Boolean
    )

    private val paletteCache = ConcurrentHashMap<String, Map<Int, StylePalette>>()
    // Resolve scheme names/defaults once when a TSH grammar is loaded. Menu browsing
    // and scheme selection must not cross JNI on the interactive theme-switch path.
    private val schemeNamesCache = ConcurrentHashMap<String, List<String>>()
    private val defaultSchemeCache = ConcurrentHashMap<String, String>()
    // Keep every compiled scheme palette, not just the currently selected one. A scheme
    // switch is then a map reference swap and does not cross JNI or recompile the DSL.
    private val schemePaletteCache = ConcurrentHashMap<String, Map<Int, StylePalette>>()
    // Cache both Light and Dark UI tokens independently so a theme switch only
    // swaps already-decoded maps and never crosses JNI on the hot path.
    private val schemeThemeTokenCache = ConcurrentHashMap<String, Map<String, Int>>()
    private val selectedSchemes = ConcurrentHashMap<String, String>()
    private val defaultAppearances = ConcurrentHashMap<String, Boolean>()
    private val appearanceOverrides = ConcurrentHashMap<String, Boolean>()
    
    // 主题切换开关：切换时无需重新走分词运算，仅刷新颜色绘制
    @Volatile
    var isDarkTheme: Boolean = false

    companion object {
        val instance: WindbellTshEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            WindbellTshEngine()
        }

        init {
            TreeSitter.loadLibrary()
        }
    }

    /**
     * 加载并编译 .tsh 语法规则配置
     */
    fun loadTshSource(extension: String, tshSourceContent: String, language: TSLanguage? = null): Boolean {
        val success = nativeLoadTshSource(extension, tshSourceContent, language?.nativeObject ?: 0L)
        lastCompileDiagnostics = if (success) emptyList() else getCompileDiagnostics()
        if (success) {
            // A newly compiled source replaces the grammar snapshot; never retain
            // palette entries decoded from the previous source revision.
            schemeNamesCache.remove(extension)
            defaultSchemeCache.remove(extension)
            schemePaletteCache.keys
                .filter { it.startsWith("$extension::") }
                .forEach { cacheKey -> schemePaletteCache.remove(cacheKey) }
            schemeThemeTokenCache.keys
                .filter { it.startsWith("$extension::") }
                .forEach { cacheKey -> schemeThemeTokenCache.remove(cacheKey) }
            paletteCache.remove(extension)
            defaultAppearances[extension] = nativeGetDefaultIsDark(extension)

            // Warm every scheme before the UI can open its picker. The hot path below
            // becomes a pair of ConcurrentHashMap lookups and a palette reference swap.
            val schemes = nativeGetSchemeNames(extension)?.toList().orEmpty()
            schemeNamesCache[extension] = schemes
            val defaultScheme = nativeGetDefaultScheme(extension)
            if (defaultScheme != null) defaultSchemeCache[extension] = defaultScheme
            for (schemeName in schemes) {
                schemePaletteCache["$extension::$schemeName"] =
                    readSchemePalette(extension, schemeName)
                schemeThemeTokenCache["$extension::$schemeName::light"] =
                    readSchemeThemeTokens(extension, schemeName, false)
                schemeThemeTokenCache["$extension::$schemeName::dark"] =
                    readSchemeThemeTokens(extension, schemeName, true)
            }
            if (defaultScheme != null) {
                selectedSchemes[extension] = defaultScheme
                reloadPalette(extension, defaultScheme)
            }
        }
        return success
    }

    private fun decodePalette(flatPalette: IntArray): Map<Int, StylePalette> {
        val map = LinkedHashMap<Int, StylePalette>(flatPalette.size / 7)
        val count = flatPalette.size / 7
        for (i in 0 until count) {
            val id = flatPalette[i * 7]
            map[id] = StylePalette(
                styleId = id,
                lightColor = flatPalette[i * 7 + 1],
                darkColor = flatPalette[i * 7 + 2],
                lightFontFlags = flatPalette[i * 7 + 3],
                darkFontFlags = flatPalette[i * 7 + 4],
                hasLightColor = flatPalette[i * 7 + 5] != 0,
                hasDarkColor = flatPalette[i * 7 + 6] != 0
            )
        }
        return map.toMap()
    }

    /** Reads a scheme's compiled light/dark palette without changing the active scheme. */
    fun getSchemePalette(extension: String, schemeName: String): Map<Int, StylePalette> {
        if (schemeName !in listSchemes(extension)) return emptyMap()
        val cacheKey = "$extension::$schemeName"
        return schemePaletteCache[cacheKey]
            ?: readSchemePalette(extension, schemeName).also { schemePaletteCache[cacheKey] = it }
    }

    private fun readSchemePalette(extension: String, schemeName: String): Map<Int, StylePalette> {
        val flatPalette = nativeGetPalette(extension, schemeName) ?: return emptyMap()
        val map = HashMap<Int, StylePalette>(flatPalette.size / 7)
        val count = flatPalette.size / 7
        for (i in 0 until count) {
            val id = flatPalette[i * 7]
            map[id] = StylePalette(
                styleId = id,
                lightColor = flatPalette[i * 7 + 1],
                darkColor = flatPalette[i * 7 + 2],
                lightFontFlags = flatPalette[i * 7 + 3],
                darkFontFlags = flatPalette[i * 7 + 4],
                hasLightColor = flatPalette[i * 7 + 5] != 0,
                hasDarkColor = flatPalette[i * 7 + 6] != 0
            )
        }
        return map
    }

    private fun reloadPalette(extension: String, schemeName: String) {
        val cacheKey = "$extension::$schemeName"
        val schemePalette = schemePaletteCache[cacheKey]
            ?: readSchemePalette(extension, schemeName).also { schemePaletteCache[cacheKey] = it }
        paletteCache[extension] = schemePalette
    }

    fun matchesFirstLine(extension: String, line: String): Boolean =
        nativeMatchesFirstLine(extension, line)

    /** True when this process already owns a compiled native grammar for the extension. */
    fun isGrammarLoaded(extension: String): Boolean =
        getLanguageMetadata(extension).id.isNotBlank()

    fun getLanguageMetadata(extension: String): LanguageMetadata {
        val raw = nativeGetLanguageMetadata(extension) ?: return LanguageMetadata()
        val values = raw.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
        fun list(key: String) = values[key].orEmpty().split('\u001F').filter { it.isNotEmpty() }
        return LanguageMetadata(
            id = values["id"].orEmpty(),
            name = values["name"].orEmpty(),
            extensions = list("extensions"),
            aliases = list("aliases"),
            mimeTypes = list("mimeTypes"),
            firstLinePattern = values["firstLine"]?.takeIf { it.isNotEmpty() }
        )
    }

    fun getCompileDiagnostics(): List<CompileDiagnostic> {
        val raw = nativeGetCompileDiagnostics() ?: return emptyList()
        val result = ArrayList<CompileDiagnostic>(raw.size / 5)
        var i = 0
        while (i + 4 < raw.size) {
            result += CompileDiagnostic(
                raw[i], raw[i + 1], raw[i + 2].toIntOrNull() ?: 0,
                raw[i + 3].toIntOrNull() ?: 1, raw[i + 4].toIntOrNull() ?: 1
            )
            i += 5
        }
        return result
    }

    /** Returns editor behaviors and language metadata declared by the .tsh file. */
    fun getEditorMetadata(extension: String): EditorMetadata {
        val raw = nativeGetEditorMetadata(extension) ?: return EditorMetadata()
        val lineComments = ArrayList<String>()
        val blockComments = ArrayList<Pair<String, String>>()
        val brackets = ArrayList<Pair<String, String>>()
        val bracketNotIn = ArrayList<String>()
        val autoClosingPairs = ArrayList<AutoClosingPair>()
        val surroundingPairs = ArrayList<Pair<String, String>>()
        val indentation = LinkedHashMap<String, String>()
        val completionTriggers = ArrayList<String>()
        val options = LinkedHashMap<String, String>()
        val electricCharacters = ArrayList<String>()
        val onEnterRecords = java.util.TreeMap<Int, MutableMap<String, String>>()
        var autoCloseBefore: String? = null
        var wordPattern: String? = null

        var index = 0
        while (index + 2 < raw.size) {
            val kind = raw[index]
            val first = raw[index + 1]
            val second = raw[index + 2]
            when (kind) {
                "lineComment" -> lineComments += first
                "blockComment" -> blockComments += first to second
                "bracket" -> brackets += first to second
                "bracketNotIn" -> bracketNotIn += first
                "surrounding" -> surroundingPairs += first to second
                "autoClosing" -> {
                    val parts = second.split('\u001F')
                    autoClosingPairs += AutoClosingPair(first, parts.firstOrNull().orEmpty(),
                        parts.getOrNull(1)?.split('\u001F')?.filter { it.isNotEmpty() }.orEmpty())
                }
                "wordPattern" -> wordPattern = first
                "indentIncrease", "indentDecrease", "indentNextLine", "unIndentedLine" ->
                    indentation[kind] = first
                "completionTrigger" -> completionTriggers += first
                "electricCharacter" -> electricCharacters += first
                "autoCloseBefore" -> autoCloseBefore = first
                "onEnterBefore", "onEnterAfter", "onEnterPrevious", "onEnterIndent",
                "onEnterAppend", "onEnterRemove" -> {
                    val recordIndex = first.toIntOrNull()
                    if (recordIndex != null && recordIndex >= 0) {
                        onEnterRecords.getOrPut(recordIndex) { LinkedHashMap() }[kind] = second
                    }
                }
                "editorOption" -> options[first] = second
            }
            index += 3
        }
        val onEnterRules = onEnterRecords.values.mapNotNull { record ->
            val before = record["onEnterBefore"] ?: return@mapNotNull null
            val indent = record["onEnterIndent"] ?: return@mapNotNull null
            OnEnterRule(
                beforeText = before,
                afterText = record["onEnterAfter"],
                previousLineText = record["onEnterPrevious"],
                indent = indent,
                appendText = record["onEnterAppend"],
                removeText = record["onEnterRemove"]?.toIntOrNull() ?: 0
            )
        }
        return EditorMetadata(
            lineComments, blockComments, brackets, bracketNotIn, autoClosingPairs, surroundingPairs,
            wordPattern, indentation, completionTriggers, electricCharacters, autoCloseBefore,
            onEnterRules, options
        )
    }

    /** Returns all semantic style declarations authored in the .tsh file. */
    fun getStyleCatalog(extension: String): List<SemanticStyle> {
        val raw = nativeGetStyleCatalog(extension) ?: return emptyList()
        val result = ArrayList<SemanticStyle>(raw.size / 8)
        var index = 0
        while (index + 7 < raw.size) {
            val metadata = LinkedHashMap<String, String>()
            val encodedMetadata = raw[index + 7]
            var metadataOffset = 0
            fun readMetadataValue(): String? {
                val separator = encodedMetadata.indexOf(':', metadataOffset)
                if (separator < 0) return null
                val length = encodedMetadata.substring(metadataOffset, separator).toIntOrNull() ?: return null
                metadataOffset = separator + 1
                if (length < 0 || metadataOffset + length > encodedMetadata.length) return null
                val value = encodedMetadata.substring(metadataOffset, metadataOffset + length)
                metadataOffset += length
                return value
            }
            while (metadataOffset < encodedMetadata.length) {
                val key = readMetadataValue() ?: break
                val value = readMetadataValue() ?: break
                metadata[key] = value
            }
            result += SemanticStyle(
                styleId = raw[index].toInt(),
                name = raw[index + 1],
                scope = raw[index + 2],
                description = raw[index + 3],
                parent = raw[index + 4],
                tags = raw[index + 5].split('\u001F').filter { it.isNotEmpty() },
                deprecated = raw[index + 6] == "true",
                metadata = metadata
            )
            index += 8
        }
        return result
    }

    fun getStylePalette(extension: String, styleName: String): StylePalette? {
        val style = getStyleCatalog(extension).firstOrNull { it.name == styleName } ?: return null
        return paletteCache[extension]?.get(style.styleId)
    }

    /** Returns every scheme declared by the loaded .tsh document. */
    fun listSchemes(extension: String): List<String> =
        schemeNamesCache[extension] ?: nativeGetSchemeNames(extension)?.toList().orEmpty()
            .also { schemeNamesCache[extension] = it }

    /** Returns the default scheme selected by the .tsh document. */
    fun getDefaultScheme(extension: String): String? =
        defaultSchemeCache[extension] ?: nativeGetDefaultScheme(extension)?.also {
            defaultSchemeCache[extension] = it
        }

    /** Switches only the palette; parsing and the Tree-sitter tree are retained. */
    fun selectScheme(extension: String, schemeName: String): Boolean {
        if (schemeName !in listSchemes(extension)) return false
        selectedSchemes[extension] = schemeName
        reloadPalette(extension, schemeName)
        return true
    }

    fun getSelectedScheme(extension: String): String? = selectedSchemes[extension]

    /** Returns arbitrary DSL-defined UI colors (for example editor.background). */
    fun getThemeTokens(extension: String): Map<String, Int> {
        val scheme = selectedSchemes[extension] ?: getDefaultScheme(extension) ?: return emptyMap()
        return getSchemeThemeTokens(extension, scheme, isDark(extension))
    }

    /** Reads Light or Dark UI tokens from a named scheme without changing selection. */
    fun getSchemeThemeTokens(extension: String, schemeName: String, isDark: Boolean): Map<String, Int> {
        val cacheKey = "$extension::$schemeName::" + if (isDark) "dark" else "light"
        return schemeThemeTokenCache[cacheKey]
            ?: readSchemeThemeTokens(extension, schemeName, isDark).also {
                schemeThemeTokenCache[cacheKey] = it
            }
    }

    private fun readSchemeThemeTokens(
        extension: String,
        schemeName: String,
        isDark: Boolean
    ): Map<String, Int> {
        val raw = nativeGetThemeTokens(extension, schemeName, isDark)
        val tokens = LinkedHashMap<String, Int>((raw?.size ?: 0) / 2)
        var index = 0
        while (raw != null && index + 1 < raw.size) {
            tokens[raw[index]] = raw[index + 1].toLong().toInt()
            index += 2
        }
        return tokens.toMap()
    }

    /** Explicitly overrides the DSL's default appearance for this extension. */
    fun setAppearance(extension: String, isDark: Boolean) {
        appearanceOverrides[extension] = isDark
    }

    fun clearAppearanceOverride(extension: String) {
        appearanceOverrides.remove(extension)
    }

    fun isDark(extension: String): Boolean =
        appearanceOverrides[extension] ?: defaultAppearances[extension] ?: isDarkTheme

    /**
     * 直接向 Android 编辑器 Spannable 批量直填着色 (零闪烁、零装箱开销)
     */
    fun applyHighlightsToSpannable(
        tree: TSTree?,
        text: String,
        extension: String,
        spannable: SpannableStringBuilder
    ) {
        applyHighlightSpansToSpannable(
            spans = computeHighlights(tree, text, extension),
            documentOffset = 0,
            text = text,
            extension = extension,
            spannable = spannable
        )
    }

    /**
     * Paints already-computed document captures into a line-local Spannable.
     * [documentOffset] is the line's UTF-16 character offset in the full document.
     */
    fun applyHighlightSpansToSpannable(
        spans: List<HighlightSpan>,
        documentOffset: Int,
        text: String,
        extension: String,
        spannable: SpannableStringBuilder,
        start: Int = 0,
        end: Int = spannable.length
    ) {
        // SpannableStringBuilder is mutable and is not thread-safe. A caller may
        // schedule rendering from several workers; serialize only mutations of the
        // same document buffer, never the native tokenizer or unrelated documents.
        synchronized(spannable) {
            val palette = paletteCache[extension] ?: emptyMap()
            val darkMode = isDark(extension)
            val visibleStart = start.coerceIn(0, spannable.length)
            val visibleEnd = end.coerceIn(visibleStart, spannable.length)

            // Incremental/viewport rendering must not erase styles outside the
            // requested range. Only spans intersecting this range are invalidated.
            if (visibleStart < visibleEnd) {
                val oldSpans = spannable.getSpans(
                    visibleStart,
                    visibleEnd,
                    CharacterStyle::class.java
                )
                for (old in oldSpans) spannable.removeSpan(old)
            }

            for ((spanIndex, span) in spans.withIndex()) {
                val globalStart = span.startByte shr 1
                val globalEnd = span.endByte shr 1
                val localStart = globalStart - documentOffset
                val localEnd = globalEnd - documentOffset
                val charStart = maxOf(localStart, visibleStart)
                val charEnd = minOf(localEnd, visibleEnd)
                if (charStart >= charEnd) continue

                val colorDef = palette[span.styleId]
                // Missing colors leave the editor's base foreground untouched.
                val hasColor = if (darkMode) colorDef?.hasDarkColor == true else colorDef?.hasLightColor == true
                val color = if (darkMode) colorDef?.darkColor ?: 0 else colorDef?.lightColor ?: 0
                if (hasColor) {
                    // Native emits deterministic paint order: outer captures first,
                    // then nested/high-priority captures. Encode that order in Android's
                    // span priority instead of relying on incidental getSpans ordering.
                    val precedence = (((spanIndex + 1L) * 255L) / spans.size.coerceAtLeast(1))
                        .toInt().coerceIn(1, 255)
                    val spanFlags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or
                        (precedence shl Spanned.SPAN_PRIORITY_SHIFT)
                    spannable.setSpan(ForegroundColorSpan(color), charStart, charEnd, spanFlags)
                }

                val schemeFlags = if (darkMode) colorDef?.darkFontFlags ?: 0 else colorDef?.lightFontFlags ?: 0
                val flags = span.fontFlags or schemeFlags
                if ((flags and 0x01) != 0) {
                    spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if ((flags and 0x02) != 0) {
                    spannable.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if ((flags and 0x04) != 0) {
                    spannable.setSpan(UnderlineSpan(), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if ((flags and 0x08) != 0) {
                    spannable.setSpan(StrikethroughSpan(), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if ((flags and 0x10) != 0) {
                    spannable.setSpan(object : CharacterStyle() {
                        override fun updateDrawState(textPaint: TextPaint) {
                            textPaint.alpha = minOf(textPaint.alpha, 160)
                        }
                    }, charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    /**
     * Computes captures intersecting a UTF-16LE byte range. Native Tree-sitter
     * queries are range-restricted; regex and Monarch retain document-start
     * context to preserve multiline matching and tokenizer state, then their
     * emitted spans are clipped to this range. This bounds returned results,
     * but is not yet a fully windowed regex/Monarch execution path.
     */
    fun computeHighlights(
        tree: TSTree?,
        text: String,
        extension: String,
        startByte: Long = 0L,
        endByte: Long = text.length.toLong() * 2L
    ): List<HighlightSpan> {
        require(startByte >= 0L && endByte >= startByte &&
            endByte <= text.length.toLong() * 2L &&
            (startByte and 1L) == 0L && (endByte and 1L) == 0L) {
            "TSH range must be ordered UTF-16LE byte offsets within the document"
        }
        val flatData = nativeHighlight(
            tree?.nativeObject ?: 0L, text, extension, false, startByte, endByte
        ) ?: return emptyList()
        // JNI row layout: start, end, packed(styleId|flags), priority, order, source.
        val count = flatData.size / 6
        val result = ArrayList<HighlightSpan>(count)
        for (i in 0 until count) {
            val start = flatData[i * 6]
            val end = flatData[i * 6 + 1]
            val packedMeta = flatData[i * 6 + 2]
            val styleId = packedMeta and 0xFFFF
            val flags = (packedMeta ushr 16) and 0xFFFF
            val priority = flatData[i * 6 + 3]
            val declarationOrder = flatData[i * 6 + 4]
            val source = flatData[i * 6 + 5]
            result.add(HighlightSpan(start, end, styleId, flags, priority, declarationOrder, source))
        }
        return result
    }

    data class PipelineDiagnostics(val matchCounts: Map<Int, Int>, val captures: List<HighlightSpan>)

    fun computePipelineDiagnostics(
        tree: TSTree?,
        text: String,
        extension: String,
        startByte: Long = 0L,
        endByte: Long = text.length.toLong() * 2L
    ): PipelineDiagnostics {
        require(startByte >= 0L && endByte >= startByte &&
            endByte <= text.length.toLong() * 2L &&
            (startByte and 1L) == 0L && (endByte and 1L) == 0L) {
            "TSH range must be ordered UTF-16LE byte offsets within the document"
        }
        val flatData = nativeHighlight(
            tree?.nativeObject ?: 0L, text, extension, true, startByte, endByte
        )
            ?: return PipelineDiagnostics(emptyMap(), emptyList())
        if (flatData.size < 4) return PipelineDiagnostics(emptyMap(), emptyList())
        val counts = (0..3).associateWith { flatData[it] }
        val count = (flatData.size - 4) / 6
        val captures = ArrayList<HighlightSpan>(count)
        for (i in 0 until count) {
            val offset = 4 + i * 6
            val packedMeta = flatData[offset + 2]
            captures.add(HighlightSpan(
                startByte = flatData[offset], endByte = flatData[offset + 1],
                styleId = packedMeta and 0xFFFF, fontFlags = (packedMeta ushr 16) and 0xFFFF,
                priority = flatData[offset + 3], declarationOrder = flatData[offset + 4],
                source = flatData[offset + 5]
            ))
        }
        return PipelineDiagnostics(counts, captures)
    }

    fun computeDiagnosticCandidates(tree: TSTree?, text: String, extension: String): List<HighlightSpan> =
        computePipelineDiagnostics(tree, text, extension).captures


    /**
     * Creates a native-owned, file-backed UTF-16LE document and parses it in
     * JNI. The callback only supplies bounded bytes; partitioning, storage,
     * parsing and TSH execution remain inside the native engine.
     */
    fun createNativeDocument(
        language: TSLanguage,
        extension: String,
        byteLength: Long,
        temporaryDirectory: File,
        reader: NativeTextWindowReader
    ): Long {
        require(byteLength >= 0L && (byteLength and 1L) == 0L)
        require(temporaryDirectory.isDirectory && temporaryDirectory.canWrite())
        return nativeCreateDocument(
            reader, byteLength, temporaryDirectory.absolutePath,
            language.nativeObject, extension
        )
    }

    fun highlightNativeDocument(
        documentHandle: Long,
        startByte: Long = 0L,
        endByte: Long,
        diagnostics: Boolean = false
    ): IntArray? = nativeHighlightDocument(
        documentHandle, diagnostics, startByte, endByte
    )

    fun closeNativeDocument(documentHandle: Long) {
        if (documentHandle != 0L) nativeCloseDocument(documentHandle)
    }

    private external fun nativeCreateDocument(
        reader: NativeTextWindowReader,
        byteLength: Long,
        temporaryDirectory: String,
        languagePointer: Long,
        extension: String
    ): Long

    private external fun nativeHighlightDocument(
        documentHandle: Long,
        diagnostics: Boolean,
        rangeStartByte: Long,
        rangeEndByte: Long
    ): IntArray?

    private external fun nativeCloseDocument(documentHandle: Long)

    @FastNative
    private external fun nativeLoadTshSource(extension: String, tshSourceContent: String, languagePtr: Long): Boolean

    @FastNative
    private external fun nativeMatchesFirstLine(extension: String, line: String): Boolean

    @FastNative
    private external fun nativeGetLanguageMetadata(extension: String): Array<String>?

    @FastNative
    private external fun nativeGetCompileDiagnostics(): Array<String>?

    @FastNative
    private external fun nativeGetEditorMetadata(extension: String): Array<String>?

    @FastNative
    private external fun nativeGetStyleCatalog(extension: String): Array<String>?

    @FastNative
    private external fun nativeGetSchemeNames(extension: String): Array<String>?

    @FastNative
    private external fun nativeGetDefaultScheme(extension: String): String?

    @FastNative
    private external fun nativeGetDefaultIsDark(extension: String): Boolean

    @FastNative
    private external fun nativeGetThemeTokens(extension: String, schemeName: String, isDark: Boolean): Array<String>?

    @FastNative
    private external fun nativeGetPalette(extension: String, schemeName: String): IntArray?

    @FastNative
    private external fun nativeHighlight(
        treePtr: Long,
        text: String,
        extension: String,
        diagnostics: Boolean,
        rangeStartByte: Long,
        rangeEndByte: Long
    ): IntArray?


}