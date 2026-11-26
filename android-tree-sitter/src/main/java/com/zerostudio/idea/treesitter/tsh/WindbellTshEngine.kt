package com.zerostudio.idea.treesitter.tsh

import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSTree
import dalvik.annotation.optimization.FastNative
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.nio.ByteBuffer
import com.zerostudio.idea.treesitter.TreeSitter

data class NativeDirtyByteRange(val startByte: Long, val endByte: Long) {
    init {
        // Deletions have an empty post-edit byte interval but still invalidate
        // the insertion/deletion boundary and its containing line.
        require(startByte >= 0L && endByte >= startByte)
        require((startByte and 1L) == 0L && (endByte and 1L) == 0L)
    }
}

data class NativeDirtyLineRange(val startLine: Long, val endLineExclusive: Long) {
    init {
        require(startLine >= 0L && endLineExclusive > startLine)
    }
}

data class NativeDirtyLineSnapshot(
    val documentVersion: Long,
    val ranges: List<NativeDirtyLineRange>,
    /** Exact UTF-16LE byte intervals in the same committed document version. */
    val byteRanges: List<NativeDirtyByteRange> = emptyList()
)

/** Supplies bounded UTF-16LE windows to the native document engine. */
fun interface NativeTextWindowReader {
    /** Fill a reusable direct native window and return the number of UTF-16LE bytes written. */
    fun read(byteOffset: Long, maxBytes: Int, destination: ByteBuffer): Int
}
private interface HighlightCaptureSource {
    val size: Int
    fun startByteUnsigned(index: Int): Long
    fun endByteUnsigned(index: Int): Long
    fun styleId(index: Int): Int
    fun fontFlags(index: Int): Int
    fun priority(index: Int): Int
    fun declarationOrder(index: Int): Int
    fun source(index: Int): Int
}
private class ListHighlightCaptureSource(private val values: List<WindbellTshEngine.HighlightSpan>) : HighlightCaptureSource {
    override val size get() = values.size
    override fun startByteUnsigned(index: Int) = values[index].startByteUnsigned
    override fun endByteUnsigned(index: Int) = values[index].endByteUnsigned
    override fun styleId(index: Int) = values[index].styleId
    override fun fontFlags(index: Int) = values[index].fontFlags
    override fun priority(index: Int) = values[index].priority
    override fun declarationOrder(index: Int) = values[index].declarationOrder
    override fun source(index: Int) = values[index].source
}
private class BatchHighlightCaptureSource(private val values: HighlightCaptureBatch) : HighlightCaptureSource {
    override val size get() = values.size
    override fun startByteUnsigned(index: Int) = values.startByteUnsignedAt(index)
    override fun endByteUnsigned(index: Int) = values.endByteUnsignedAt(index)
    override fun styleId(index: Int) = values.styleIdAt(index)
    override fun fontFlags(index: Int) = values.fontFlagsAt(index)
    override fun priority(index: Int) = values.priorityAt(index)
    override fun declarationOrder(index: Int) = values.declarationOrderAt(index)
    override fun source(index: Int) = values.sourceAt(index)
}
/**
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * Provides the dual-backend highlighting bridge and DSL-backed style rendering.
 */
class WindbellTshEngine private constructor() {
    private fun packBoundaryEvent(offset: Int, captureIndex: Int, starts: Boolean): Long {
        require(offset >= 0 && captureIndex >= 0)
        return (offset.toLong() shl 32) or
            (if (starts) (1L shl 31) else 0L) or (captureIndex.toLong() and 0x7FFF_FFFFL)
    }

    private fun boundaryEventOffset(event: Long): Int = (event ushr 32).toInt()
    private fun boundaryEventCaptureIndex(event: Long): Int =
        (event and 0x7FFF_FFFFL).toInt()
    private fun boundaryEventStarts(event: Long): Boolean =
        (event and (1L shl 31)) != 0L

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

    data class NativeHighlightRange(val startByte: Long, val endByte: Long) {
        init {
            require(startByte >= 0L && endByte >= startByte)
            require((startByte and 1L) == 0L && (endByte and 1L) == 0L)
        }
    }

    data class HighlightSpan(
        val startByte: Int,
        val endByte: Int,
        val styleId: Int,
        val fontFlags: Int,
        val priority: Int,
        val declarationOrder: Int,
        /** Native HighlightSource ordinal (ONIG_REGEX, REGEX_STATE, AST_SELECTOR, NATIVE_QUERY). */
        val source: Int = 255
    ) {
        /** Unsigned Tree-sitter byte offsets (the Int fields retain their ABI bit pattern). */
        val startByteUnsigned: Long get() = startByte.toLong() and 0xFFFF_FFFFL
        val endByteUnsigned: Long get() = endByte.toLong() and 0xFFFF_FFFFL
    }

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
        /** Per-appearance attributes explicitly set or cleared by this style. */
        val lightFontDefinedMask: Int = lightFontFlags,
        val darkFontDefinedMask: Int = darkFontFlags,
        val hasLightColor: Boolean,
        val hasDarkColor: Boolean,
        val lightBackground: Int = 0,
        val darkBackground: Int = 0,
        val hasLightBackground: Boolean = false,
        val hasDarkBackground: Boolean = false
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
                // Empty native results are transient/unavailable states, not cache hits.
                // Only retain a palette after at least one style has been decoded.
                readSchemePalette(extension, schemeName).takeIf { it.isNotEmpty() }?.let {
                    schemePaletteCache["$extension::$schemeName"] = it
                }
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
        val fields = 13
        require(flatPalette.size % fields == 0) { "Native palette has an invalid field count" }
        val map = LinkedHashMap<Int, StylePalette>(flatPalette.size / fields)
        val count = flatPalette.size / fields
        for (i in 0 until count) {
            val base = i * fields
            val id = flatPalette[base]
            map[id] = StylePalette(
                styleId = id,
                lightColor = flatPalette[base + 1],
                darkColor = flatPalette[base + 2],
                lightFontFlags = flatPalette[base + 3],
                darkFontFlags = flatPalette[base + 4],
                hasLightColor = flatPalette[base + 5] != 0,
                hasDarkColor = flatPalette[base + 6] != 0,
                lightBackground = flatPalette[base + 7],
                darkBackground = flatPalette[base + 8],
                hasLightBackground = flatPalette[base + 9] != 0,
                hasDarkBackground = flatPalette[base + 10] != 0,
                lightFontDefinedMask = flatPalette[base + 11],
                darkFontDefinedMask = flatPalette[base + 12]
            )
        }
        return map.toMap()
    }

    /** Reads a scheme's compiled light/dark palette without changing the active scheme. */
    fun getSchemePalette(extension: String, schemeName: String): Map<Int, StylePalette> {
        if (schemeName !in listSchemes(extension)) return emptyMap()
        val cacheKey = "$extension::$schemeName"
        schemePaletteCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return it }
        val resolved = readSchemePalette(extension, schemeName)
        if (resolved.isNotEmpty()) {
            schemePaletteCache[cacheKey] = resolved
        } else {
            schemePaletteCache.remove(cacheKey)
        }
        return resolved
    }

    private fun readSchemePalette(extension: String, schemeName: String): Map<Int, StylePalette> {
        val flatPalette = nativeGetPalette(extension, schemeName) ?: return emptyMap()
        val fields = 13
        require(flatPalette.size % fields == 0) { "Native palette has an invalid field count" }
        val map = HashMap<Int, StylePalette>(flatPalette.size / fields)
        val count = flatPalette.size / fields
        for (i in 0 until count) {
            val base = i * fields
            val id = flatPalette[base]
            map[id] = StylePalette(
                styleId = id,
                lightColor = flatPalette[base + 1],
                darkColor = flatPalette[base + 2],
                lightFontFlags = flatPalette[base + 3],
                darkFontFlags = flatPalette[base + 4],
                hasLightColor = flatPalette[base + 5] != 0,
                hasDarkColor = flatPalette[base + 6] != 0,
                lightBackground = flatPalette[base + 7],
                darkBackground = flatPalette[base + 8],
                hasLightBackground = flatPalette[base + 9] != 0,
                hasDarkBackground = flatPalette[base + 10] != 0,
                lightFontDefinedMask = flatPalette[base + 11],
                darkFontDefinedMask = flatPalette[base + 12]
            )
        }
        return map
    }

    private fun reloadPalette(extension: String, schemeName: String) {
        val cacheKey = "$extension::$schemeName"
        val schemePalette = schemePaletteCache[cacheKey]?.takeIf { it.isNotEmpty() }
            ?: readSchemePalette(extension, schemeName).also { resolved ->
                if (resolved.isNotEmpty()) schemePaletteCache[cacheKey] = resolved
                else schemePaletteCache.remove(cacheKey)
            }
        if (schemePalette.isNotEmpty()) paletteCache[extension] = schemePalette
        else paletteCache.remove(extension)
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
                        parts.drop(1).filter { it.isNotEmpty() })
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
        ensurePalette(extension)
        return paletteCache[extension]?.get(style.styleId)
    }

    /**
     * Ensures the active scheme palette is available to the renderer.
     *
     * Loading a grammar normally warms this cache, but editor instances can be
     * attached after a native grammar has been restored or registered by another
     * owner. In that case capture IDs are valid while paletteCache is still empty,
     * which silently turns every syntax span into uncoloured text.
     */
    fun ensurePalette(extension: String): Boolean {
        // A cache entry can exist while containing no decoded styles: this happens
        // when an editor attaches during grammar restoration/registration and the
        // first native palette read races the grammar owner. Treat an empty map as
        // unavailable, not as a successful warm cache.
        paletteCache[extension]?.takeIf { it.isNotEmpty() }?.let { return true }

        val scheme = selectedSchemes[extension] ?: getDefaultScheme(extension) ?: return false
        selectedSchemes[extension] = scheme
        val cacheKey = "$extension::$scheme"
        val cachedScheme = schemePaletteCache[cacheKey]?.takeIf { it.isNotEmpty() }
        val resolved = cachedScheme ?: readSchemePalette(extension, scheme).also {
            // Empty results are deliberately not cached: the native grammar may
            // become palette-ready after an asynchronous restore/registration.
            if (it.isNotEmpty()) schemePaletteCache[cacheKey] = it
            else schemePaletteCache.remove(cacheKey)
        }
        if (resolved.isNotEmpty()) {
            paletteCache[extension] = resolved
            return true
        }
        paletteCache.remove(extension)
        return false
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
        applyCaptureSourceToSpannable(ListHighlightCaptureSource(spans), documentOffset, text, extension, spannable, start, end)
    }

    fun applyHighlightCaptureBatchToSpannable(
        batch: HighlightCaptureBatch,
        documentOffset: Int,
        text: String,
        extension: String,
        spannable: SpannableStringBuilder,
        start: Int = 0,
        end: Int = spannable.length
    ) {
        applyCaptureSourceToSpannable(BatchHighlightCaptureSource(batch), documentOffset, text, extension, spannable, start, end)
    }

    private fun applyCaptureSourceToSpannable(
        captures: HighlightCaptureSource,
        documentOffset: Int,
        text: String,
        extension: String,
        spannable: SpannableStringBuilder,
        start: Int,
        end: Int
    ) {
        ensurePalette(extension)
        synchronized(spannable) {
            val palette = paletteCache[extension].orEmpty()
            val darkMode = isDark(extension)
            val visibleStart = start.coerceIn(0, spannable.length)
            val visibleEnd = end.coerceIn(visibleStart, spannable.length)
            if (visibleStart >= visibleEnd) return@synchronized

            // Remove only the old styles intersecting this render slice. The caller
            // supplies a fresh line builder, so no document-global span ownership is
            // involved here.
            spannable.getSpans(
                visibleStart, visibleEnd, CharacterStyle::class.java
            ).forEach(spannable::removeSpan)

            // Resolve overlap before creating Android spans. Android's SPAN_PRIORITY
            // field is only eight bits; assigning priorities to hundreds of distinct
            // captures necessarily creates collisions. StyledTextPainter then has no
            // deterministic way to choose the intended foreground/background.
            // A sweep-line produces disjoint runs, so the renderer never has to arbitrate
            // between overlapping TSH styles and the full native ordering is preserved.
            // Rank native captures without a boxed MutableList<Int> or comparator
            // object. The ordering is byte-for-byte equivalent to the former
            // compareByDescending/thenBy chain, with source index as final tie-break.
            fun compareCaptureOrder(leftIndex: Int, rightIndex: Int): Int {
                var order = captures.priority(rightIndex).compareTo(captures.priority(leftIndex))
                if (order != 0) return order
                order = captures.declarationOrder(leftIndex).compareTo(captures.declarationOrder(rightIndex))
                if (order != 0) return order
                order = java.lang.Long.compare(
                    captures.startByteUnsigned(leftIndex), captures.startByteUnsigned(rightIndex))
                if (order != 0) return order
                order = java.lang.Long.compare(
                    captures.endByteUnsigned(rightIndex), captures.endByteUnsigned(leftIndex))
                if (order != 0) return order
                order = captures.source(leftIndex).compareTo(captures.source(rightIndex))
                if (order != 0) return order
                order = captures.styleId(leftIndex).compareTo(captures.styleId(rightIndex))
                if (order != 0) return order
                order = captures.fontFlags(leftIndex).compareTo(captures.fontFlags(rightIndex))
                if (order != 0) return order
                return leftIndex.compareTo(rightIndex)
            }

            val rankedIndices = IntArray(captures.size) { it }
            fun siftRankedDown(rootIndex: Int, limit: Int) {
                var root = rootIndex
                while (true) {
                    val leftChild = root * 2 + 1
                    if (leftChild >= limit) return
                    val rightChild = leftChild + 1
                    var largest = leftChild
                    if (rightChild < limit &&
                        compareCaptureOrder(rankedIndices[rightChild], rankedIndices[leftChild]) > 0) {
                        largest = rightChild
                    }
                    if (compareCaptureOrder(rankedIndices[largest], rankedIndices[root]) <= 0) return
                    val swap = rankedIndices[root]
                    rankedIndices[root] = rankedIndices[largest]
                    rankedIndices[largest] = swap
                    root = largest
                }
            }

            // In-place heapsort yields ascending native precedence order.
            for (root in (rankedIndices.size ushr 1) - 1 downTo 0) {
                siftRankedDown(root, rankedIndices.size)
            }
            for (limit in rankedIndices.size - 1 downTo 1) {
                val swap = rankedIndices[0]
                rankedIndices[0] = rankedIndices[limit]
                rankedIndices[limit] = swap
                siftRankedDown(0, limit)
            }

            val capturePriorityFlags = IntArray(captures.size)
            var rank = -1
            var previousPriority: Int? = null
            var previousDeclaration: Int? = null
            for (index in rankedIndices) {
                val priority = captures.priority(index)
                val declarationOrder = captures.declarationOrder(index)
                if (previousPriority != priority || previousDeclaration != declarationOrder) {
                    rank++
                    previousPriority = priority
                    previousDeclaration = declarationOrder
                }
                val androidPriority = (255 - rank).coerceAtLeast(1)
                capturePriorityFlags[index] =
                    androidPriority shl Spanned.SPAN_PRIORITY_SHIFT
            }

            // Keep clipped captures in primitive parallel arrays. The previous
            // ClippedCapture wrapper allocated one object per overlapping span on
            // every line draw, despite the source HighlightSpan being immutable.
            val captureSourceIndices = IntArray(captures.size)
            val captureStarts = IntArray(captures.size)
            val captureEnds = IntArray(captures.size)
            var captureCount = 0
            for (sourceIndex in 0 until captures.size) {
                val globalStart = captures.startByteUnsigned(sourceIndex) ushr 1
                val globalEnd = captures.endByteUnsigned(sourceIndex) ushr 1
                val localStart = globalStart - documentOffset.toLong()
                val localEnd = globalEnd - documentOffset.toLong()
                val clippedStart = maxOf(localStart, visibleStart.toLong())
                val clippedEnd = minOf(localEnd, visibleEnd.toLong())
                if (clippedStart >= clippedEnd) continue
                captureSourceIndices[captureCount] = sourceIndex
                captureStarts[captureCount] = clippedStart.toInt()
                captureEnds[captureCount] = clippedEnd.toInt()
                captureCount++
            }
            if (captureCount == 0) return@synchronized

            fun compareCaptureIndices(left: Int, right: Int): Int =
                compareCaptureOrder(captureSourceIndices[left], captureSourceIndices[right])
            val hasForeground = BooleanArray(captureCount)
            val hasBackground = BooleanArray(captureCount)
            val captureFontFlags = IntArray(captureCount)
            val captureFontDefinedMasks = IntArray(captureCount)
            // Primitive packed sweep events avoid two heap objects per capture.
            // Ordering is offset first, then end(0) before start(1), then capture index.
            val events = LongArray(captureCount * 2)
            val activeHeapCapacities = IntArray(7)
            var eventWrite = 0
            for (index in 0 until captureCount) {
                val sourceIndex = captureSourceIndices[index]
                val style = palette[captures.styleId(sourceIndex)]
                // Resolve each appearance independently, but never let an
                // omitted dark/light override fall through to the editor's base
                // TextPaint color. That fallback is unrelated to the TSH style and
                // can make a configured yellow annotation appear green in dark mode.
                // An explicitly supplied color for the active appearance wins;
                // otherwise inherit the other appearance's value.
                hasForeground[index] = style != null &&
                    (style.hasDarkColor || style.hasLightColor)
                hasBackground[index] = style != null &&
                    (style.hasDarkBackground || style.hasLightBackground)
                val schemeFlags = if (darkMode) {
                    style?.darkFontFlags ?: 0
                } else {
                    style?.lightFontFlags ?: 0
                }
                val schemeDefinedMask = if (darkMode) {
                    style?.darkFontDefinedMask ?: 0
                } else {
                    style?.lightFontDefinedMask ?: 0
                }
                val rawCaptureFlags = captures.fontFlags(sourceIndex)
                // Scheme declarations override raw capture flags only for attributes
                // they explicitly define. Unspecified scheme attributes preserve the
                // capture's own flags; explicit clears remain represented by the mask.
                captureFontFlags[index] =
                    (rawCaptureFlags and schemeDefinedMask.inv()) or schemeFlags
                // A defined-but-disabled attribute is still an active winner:
                // it must suppress a lower-priority enclosing capture's style.
                captureFontDefinedMasks[index] = rawCaptureFlags or schemeDefinedMask
                if (hasForeground[index]) activeHeapCapacities[0]++
                if (hasBackground[index]) activeHeapCapacities[1]++
                for (bitIndex in 0 until 5) {
                    if ((captureFontDefinedMasks[index] and (1 shl bitIndex)) != 0) {
                        activeHeapCapacities[bitIndex + 2]++
                    }
                }
                events[eventWrite++] = packBoundaryEvent(captureStarts[index], index, starts = true)
                events[eventWrite++] = packBoundaryEvent(captureEnds[index], index, starts = false)
            }
            events.sort()

            // Allocate each heap to the exact number of captures that can enter it,
            // rather than seven full captureCount-sized arrays. This keeps the
            // primitive replacement memory-proportional to actual active attributes.
            val activeCaptures = BooleanArray(captureCount)
            val activeHeaps = Array(7) { IntArray(activeHeapCapacities[it]) }
            val activeHeapSizes = IntArray(7)

            fun siftDown(heapIndex: Int, startIndex: Int) {
                val heap = activeHeaps[heapIndex]
                val size = activeHeapSizes[heapIndex]
                var parent = startIndex
                while (true) {
                    val left = parent * 2 + 1
                    if (left >= size) break
                    val right = left + 1
                    var best = left
                    if (right < size &&
                        compareCaptureIndices(heap[right], heap[left]) < 0) best = right
                    if (compareCaptureIndices(heap[best], heap[parent]) >= 0) break
                    val swap = heap[parent]
                    heap[parent] = heap[best]
                    heap[best] = swap
                    parent = best
                }
            }

            fun addToHeap(heapIndex: Int, captureIndex: Int) {
                val heap = activeHeaps[heapIndex]
                var position = activeHeapSizes[heapIndex]
                activeHeapSizes[heapIndex] = position + 1
                heap[position] = captureIndex
                while (position > 0) {
                    val parent = (position - 1) ushr 1
                    if (compareCaptureIndices(heap[position], heap[parent]) >= 0) break
                    val swap = heap[parent]
                    heap[parent] = heap[position]
                    heap[position] = swap
                    position = parent
                }
            }

            fun activeWinner(heapIndex: Int): Int? {
                val heap = activeHeaps[heapIndex]
                while (activeHeapSizes[heapIndex] > 0 && !activeCaptures[heap[0]]) {
                    val nextSize = activeHeapSizes[heapIndex] - 1
                    activeHeapSizes[heapIndex] = nextSize
                    if (nextSize > 0) {
                        heap[0] = heap[nextSize]
                        siftDown(heapIndex, 0)
                    }
                }
                return if (activeHeapSizes[heapIndex] > 0) heap[0] else null
            }

            fun hasActiveStyles(): Boolean {
                if (activeWinner(0) != null || activeWinner(1) != null) return true
                for (bitIndex in 0 until 5) {
                    if (activeWinner(bitIndex + 2) != null) return true
                }
                return false
            }

            fun paintRun(
                runStart: Int,
                runEnd: Int,
                foregroundIndex: Int?,
                backgroundIndex: Int?,
                fontFlags: Int,
                packedFontPriorities: Long
            ) {
                if (runStart >= runEnd) return
                val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                backgroundIndex?.let { index ->
                    val sourceIndex = captureSourceIndices[index]
                    val style = palette[captures.styleId(sourceIndex)] ?: return@let
                    val background = when {
                        darkMode && style.hasDarkBackground -> style.darkBackground
                        style.hasLightBackground -> style.lightBackground
                        style.hasDarkBackground -> style.darkBackground
                        else -> return@let
                    }
                    spannable.setSpan(
                        BackgroundColorSpan(background), runStart, runEnd,
                        flags or capturePriorityFlags[sourceIndex]
                    )
                }
                foregroundIndex?.let { index ->
                    val sourceIndex = captureSourceIndices[index]
                    val style = palette[captures.styleId(sourceIndex)] ?: return@let
                    val color = when {
                        darkMode && style.hasDarkColor -> style.darkColor
                        style.hasLightColor -> style.lightColor
                        style.hasDarkColor -> style.darkColor
                        else -> return@let
                    }
                    spannable.setSpan(
                        ForegroundColorSpan(color), runStart, runEnd,
                        flags or capturePriorityFlags[sourceIndex]
                    )
                }
                if ((fontFlags and 0x01) != 0) {
                    spannable.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD), runStart, runEnd,
                        flags or (((packedFontPriorities ushr 0) and 0xFFL).toInt()
                            shl Spanned.SPAN_PRIORITY_SHIFT)
                    )
                }
                if ((fontFlags and 0x02) != 0) {
                    spannable.setSpan(
                        StyleSpan(android.graphics.Typeface.ITALIC), runStart, runEnd,
                        flags or (((packedFontPriorities ushr 8) and 0xFFL).toInt()
                            shl Spanned.SPAN_PRIORITY_SHIFT)
                    )
                }
                if ((fontFlags and 0x04) != 0) {
                    spannable.setSpan(
                        UnderlineSpan(), runStart, runEnd,
                        flags or (((packedFontPriorities ushr 16) and 0xFFL).toInt()
                            shl Spanned.SPAN_PRIORITY_SHIFT)
                    )
                }
                if ((fontFlags and 0x08) != 0) {
                    spannable.setSpan(
                        StrikethroughSpan(), runStart, runEnd,
                        flags or (((packedFontPriorities ushr 24) and 0xFFL).toInt()
                            shl Spanned.SPAN_PRIORITY_SHIFT)
                    )
                }
                if ((fontFlags and 0x10) != 0) {
                    spannable.setSpan(object : CharacterStyle() {
                        override fun updateDrawState(textPaint: TextPaint) {
                            textPaint.alpha = minOf(textPaint.alpha, 160)
                        }
                    }, runStart, runEnd, flags)
                }
            }

            fun setCaptureActive(index: Int, active: Boolean) {
                activeCaptures[index] = active
                if (!active) return
                if (hasForeground[index]) addToHeap(0, index)
                if (hasBackground[index]) addToHeap(1, index)
                val definedMask = captureFontDefinedMasks[index]
                for (bitIndex in 0 until 5) {
                    if ((definedMask and (1 shl bitIndex)) != 0) addToHeap(bitIndex + 2, index)
                }
            }

            var pendingStart = -1
            var pendingEnd = -1
            var pendingForeground: Int? = null
            var pendingBackground: Int? = null
            var pendingFontFlags = 0
            var pendingFontPriorityFlags = 0L
            fun flushPendingRun() {
                if (pendingStart >= 0) {
                    paintRun(
                        pendingStart, pendingEnd,
                        pendingForeground, pendingBackground,
                        pendingFontFlags, pendingFontPriorityFlags
                    )
                }
                pendingStart = -1
                pendingEnd = -1
                pendingForeground = null
                pendingBackground = null
                pendingFontFlags = 0
                pendingFontPriorityFlags = 0
            }
            fun appendResolvedRun(startOffset: Int, endOffset: Int) {
                if (startOffset >= endOffset) return
                val foreground = activeWinner(0)
                val background = activeWinner(1)
                var fontFlags = 0
                var packedFontPriorities = 0L
                for (bitIndex in 0 until 5) {
                    val winner = activeWinner(bitIndex + 2) ?: continue
                    // The winning capture defines this attribute even when its
                    // value is off. This prevents nested @no-bold/@no-italic styles
                    // from leaking the enclosing capture's enabled attribute.
                    val winnerSourceIndex = captureSourceIndices[winner]
                    if ((captureFontFlags[winner] and (1 shl bitIndex)) != 0) {
                        fontFlags = fontFlags or (1 shl bitIndex)
                    }
                    if (bitIndex < 4) {
                        val priority = (capturePriorityFlags[winnerSourceIndex] ushr
                            Spanned.SPAN_PRIORITY_SHIFT) and 0xFF
                        packedFontPriorities = packedFontPriorities or
                            (priority.toLong() shl (bitIndex * 8))
                    }
                }
                if (pendingStart >= 0 && pendingEnd == startOffset &&
                    pendingForeground == foreground &&
                    pendingBackground == background &&
                    pendingFontFlags == fontFlags &&
                    pendingFontPriorityFlags == packedFontPriorities) {
                    pendingEnd = endOffset
                } else {
                    flushPendingRun()
                    pendingStart = startOffset
                    pendingEnd = endOffset
                    pendingForeground = foreground
                    pendingBackground = background
                    pendingFontFlags = fontFlags
                    pendingFontPriorityFlags = packedFontPriorities
                }
            }

            var eventIndex = 0
            var cursor = visibleStart
            while (eventIndex < events.size) {
                val boundary = boundaryEventOffset(events[eventIndex])
                if (cursor < boundary &&
                    hasActiveStyles()) {
                    appendResolvedRun(cursor, boundary)
                } else if (cursor < boundary) {
                    flushPendingRun()
                }
                while (eventIndex < events.size &&
                    boundaryEventOffset(events[eventIndex]) == boundary &&
                    !boundaryEventStarts(events[eventIndex])) {
                    val index = boundaryEventCaptureIndex(events[eventIndex])
                    setCaptureActive(index, active = false)
                    eventIndex++
                }
                while (eventIndex < events.size &&
                    boundaryEventOffset(events[eventIndex]) == boundary &&
                    boundaryEventStarts(events[eventIndex])) {
                    val index = boundaryEventCaptureIndex(events[eventIndex])
                    setCaptureActive(index, active = true)
                    eventIndex++
                }
                cursor = boundary
            }
            if (cursor < visibleEnd &&
                hasActiveStyles()) {
                appendResolvedRun(cursor, visibleEnd)
            }
            flushPendingRun()
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
                startByte = flatData[offset],
                endByte = flatData[offset + 1],
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
     * Creates a native-owned UTF-16LE document and parses it in JNI. Native prefers
     * an anonymous memfd-backed mapping and falls back to the app-private cache
     * directory. The callback fills a reusable direct buffer, avoiding per-window
     * ByteArray allocation and an additional JNI array copy.
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

    /**
     * Applies an edit to the native-owned document. Offsets and inserted bytes
     * are UTF-16LE byte units; native code updates the Tree-sitter tree
     * incrementally and atomically swaps the backing snapshot on success.
     */
    fun applyNativeDocumentEdit(
        documentHandle: Long,
        startByte: Long,
        oldEndByte: Long,
        insertedUtf16Le: ByteArray
    ): Boolean {
        require(documentHandle != 0L)
        require(startByte >= 0L && oldEndByte >= startByte)
        require((startByte and 1L) == 0L && (oldEndByte and 1L) == 0L)
        require((insertedUtf16Le.size and 1) == 0)
        return nativeApplyDocumentEdit(
            documentHandle, startByte, oldEndByte, insertedUtf16Le
        )
    }

    /**
     * Atomically consumes the latest committed native dirty-line intervals.
     * Ranges are half-open and expressed in the new document's line coordinates.
     * They describe Tree-sitter structural changes plus the actual edited text range;
     * they are not, by themselves, a safe Regex state-convergence boundary.
     */
    fun consumeNativeDirtyLineRanges(documentHandle: Long): NativeDirtyLineSnapshot {
        require(documentHandle != 0L)
        val encoded = nativeConsumeDirtyLineRanges(documentHandle)
            ?: error("Native dirty-line snapshot could not be allocated")
        require(encoded.size >= 3) { "Native dirty snapshot is missing its range counts" }
        val byteCount = encoded[1]
        require(byteCount >= 0L && byteCount <= (encoded.size - 3L) / 2L) {
            "Native dirty byte-range count is malformed"
        }
        val byteRanges = ArrayList<NativeDirtyByteRange>(byteCount.toInt())
        var index = 2
        repeat(byteCount.toInt()) {
            byteRanges += NativeDirtyByteRange(encoded[index], encoded[index + 1])
            index += 2
        }
        require(index < encoded.size) { "Native dirty snapshot is missing its line-range count" }
        val lineCount = encoded[index++]
        require(lineCount >= 0L && lineCount == (encoded.size - index).toLong() / 2L &&
            (encoded.size - index) % 2 == 0) {
            "Native dirty line-range count is malformed"
        }
        val ranges = ArrayList<NativeDirtyLineRange>(lineCount.toInt())
        repeat(lineCount.toInt()) {
            ranges += NativeDirtyLineRange(encoded[index], encoded[index + 1])
            index += 2
        }
        return NativeDirtyLineSnapshot(encoded[0], ranges, byteRanges)
    }

    fun highlightNativeDocument(
        documentHandle: Long,
        startByte: Long = 0L,
        endByte: Long,
        diagnostics: Boolean = false
    ): IntArray? = nativeHighlightDocument(
        documentHandle, diagnostics, startByte, endByte
    )

    /**
     * Exact viewport keys that the native dependency cache retained after edits.
     * The editor uses this authoritative set to preserve only Kotlin capture
     * entries whose native AST/query/Regex dependencies were proven reusable.
     */
    fun reusableNativeHighlightRanges(documentHandle: Long): List<NativeHighlightRange> {
        require(documentHandle != 0L)
        val encoded = nativeGetReusableHighlightRanges(documentHandle)
            ?: error("Native reusable highlight range snapshot could not be allocated")
        require(encoded.isNotEmpty()) { "Native reusable highlight snapshot is missing its range count" }
        val count = encoded[0]
        require(count >= 0L && count == (encoded.size - 1L) / 2L &&
            (encoded.size - 1) % 2 == 0) {
            "Native reusable highlight range count is malformed"
        }
        return ArrayList<NativeHighlightRange>(count.toInt()).also { ranges ->
            var index = 1
            repeat(count.toInt()) {
                ranges += NativeHighlightRange(encoded[index], encoded[index + 1])
                index += 2
            }
        }
    }

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

    private external fun nativeApplyDocumentEdit(
        documentHandle: Long,
        startByte: Long,
        oldEndByte: Long,
        insertedUtf16Le: ByteArray
    ): Boolean

    private external fun nativeConsumeDirtyLineRanges(documentHandle: Long): LongArray?

    private external fun nativeHighlightDocument(
        documentHandle: Long,
        diagnostics: Boolean,
        rangeStartByte: Long,
        rangeEndByte: Long
    ): IntArray?

    private external fun nativeGetReusableHighlightRanges(
        documentHandle: Long
    ): LongArray?

    private external fun nativeCloseDocument(documentHandle: Long)

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

    private external fun nativeHighlight(
        treePtr: Long,
        text: String,
        extension: String,
        diagnostics: Boolean,
        rangeStartByte: Long,
        rangeEndByte: Long
    ): IntArray?


}