package com.zerostudio.idea.treesitter.tsh

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSTree
import dalvik.annotation.optimization.FastNative
import java.util.concurrent.ConcurrentHashMap
import com.zerostudio.idea.treesitter.TreeSitter
/**
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * Provides the dual-backend highlighting bridge and DSL-backed style rendering.
 */
class WindbellTshEngine private constructor() {

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
        val declarationOrder: Int
    )

    data class AutoClosingPair(
        val open: String,
        val close: String,
        val notIn: List<String>
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
        val completionTriggerCharacters: List<String> = emptyList()
    )

    data class SemanticStyle(
        val styleId: Int,
        val name: String,
        val scope: String,
        val description: String,
        val parent: String,
        val tags: List<String>
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
    private val selectedSchemes = ConcurrentHashMap<String, String>()
    private val defaultAppearances = ConcurrentHashMap<String, Boolean>()
    private val appearanceOverrides = ConcurrentHashMap<String, Boolean>()
    
    // 主题切换开关：切换时无需重新走分词运算，仅刷新颜色绘制
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
            defaultAppearances[extension] = nativeGetDefaultIsDark(extension)
            val defaultScheme = nativeGetDefaultScheme(extension)
            if (defaultScheme != null) {
                selectedSchemes[extension] = defaultScheme
                reloadPalette(extension, defaultScheme)
            }
        }
        return success
    }

    private fun reloadPalette(extension: String, schemeName: String) {
        val flatPalette = nativeGetPalette(extension, schemeName) ?: return
        val map = HashMap<Int, StylePalette>()
        val count = flatPalette.size / 7
        for (i in 0 until count) {
            val id = flatPalette[i * 7]
            val light = flatPalette[i * 7 + 1]
            val dark = flatPalette[i * 7 + 2]
            val lightFlags = flatPalette[i * 7 + 3]
            val darkFlags = flatPalette[i * 7 + 4]
            val hasLight = flatPalette[i * 7 + 5] != 0
            val hasDark = flatPalette[i * 7 + 6] != 0
            map[id] = StylePalette(id, light, dark, lightFlags, darkFlags, hasLight, hasDark)
        }
        paletteCache[extension] = map
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
            }
            index += 3
        }
        return EditorMetadata(
            lineComments, blockComments, brackets, bracketNotIn, autoClosingPairs, surroundingPairs,
            wordPattern, indentation, completionTriggers
        )
    }

    /** Returns all semantic style declarations authored in the .tsh file. */
    fun getStyleCatalog(extension: String): List<SemanticStyle> {
        val raw = nativeGetStyleCatalog(extension) ?: return emptyList()
        val result = ArrayList<SemanticStyle>(raw.size / 6)
        var index = 0
        while (index + 5 < raw.size) {
            result += SemanticStyle(
                styleId = raw[index].toInt(),
                name = raw[index + 1],
                scope = raw[index + 2],
                description = raw[index + 3],
                parent = raw[index + 4],
                tags = raw[index + 5].split('\u001F').filter { it.isNotEmpty() }
            )
            index += 6
        }
        return result
    }

    /** Returns every scheme declared by the loaded .tsh document. */
    fun listSchemes(extension: String): List<String> =
        nativeGetSchemeNames(extension)?.toList() ?: emptyList()

    /** Returns the default scheme selected by the .tsh document. */
    fun getDefaultScheme(extension: String): String? = nativeGetDefaultScheme(extension)

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
        val raw = nativeGetThemeTokens(extension, scheme, isDark(extension)) ?: return emptyMap()
        val tokens = LinkedHashMap<String, Int>(raw.size / 2)
        var index = 0
        while (index + 1 < raw.size) {
            tokens[raw[index]] = raw[index + 1].toLong().toInt()
            index += 2
        }
        return tokens
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
        val oldSpans = spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
        for (old in oldSpans) spannable.removeSpan(old)

        val palette = paletteCache[extension] ?: emptyMap()
        val darkMode = isDark(extension)
        val visibleStart = start.coerceIn(0, spannable.length)
        val visibleEnd = end.coerceIn(visibleStart, spannable.length)

        for (span in spans) {
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
                spannable.setSpan(ForegroundColorSpan(color), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        }
    }

    fun computeHighlights(tree: TSTree?, text: String, extension: String): List<HighlightSpan> {
        val flatData = nativeHighlight(tree?.nativeObject ?: 0L, text, extension) ?: return emptyList()
        val count = flatData.size / 5
        val result = ArrayList<HighlightSpan>(count)
        for (i in 0 until count) {
            val start = flatData[i * 5]
            val end = flatData[i * 5 + 1]
            val packedMeta = flatData[i * 5 + 2]
            val styleId = packedMeta and 0xFFFF
            val flags = (packedMeta ushr 16) and 0xFFFF
            val priority = flatData[i * 5 + 3]
            val declarationOrder = flatData[i * 5 + 4]
            result.add(HighlightSpan(start, end, styleId, flags, priority, declarationOrder))
        }
        return result
    }

    @FastNative
    private external fun nativeLoadTshSource(extension: String, tshSourceContent: String, languagePtr: Long): Boolean

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
    private external fun nativeHighlight(treePtr: Long, text: String, extension: String): IntArray?


}