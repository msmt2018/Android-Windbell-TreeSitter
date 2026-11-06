package com.zerostudio.idea.treesitter.tsh

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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

    data class HighlightSpan(
        val startByte: Int,
        val endByte: Int,
        val styleId: Int,
        val fontFlags: Int
    )

    data class StylePalette(
        val styleId: Int,
        val lightColor: Int,
        val darkColor: Int
    )

    private val paletteCache = ConcurrentHashMap<String, Map<Int, StylePalette>>()
    
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
        if (success) {
            reloadPalette(extension)
        }
        return success
    }

    private fun reloadPalette(extension: String) {
        val flatPalette = nativeGetPalette(extension) ?: return
        val map = HashMap<Int, StylePalette>()
        val count = flatPalette.size / 3
        for (i in 0 until count) {
            val id = flatPalette[i * 3 + 0]
            val light = flatPalette[i * 3 + 1]
            val dark = flatPalette[i * 3 + 2]
            map[id] = StylePalette(id, light, dark)
        }
        paletteCache[extension] = map
    }

    /**
     * 直接向 Android 编辑器 Spannable 批量直填着色 (零闪烁、零装箱开销)
     */
    fun applyHighlightsToSpannable(
        tree: TSTree?,
        text: String,
        extension: String,
        spannable: SpannableStringBuilder
    ) {
        val spans = computeHighlights(tree, text, extension)
        val palette = paletteCache[extension] ?: emptyMap()

        val oldSpans = spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
        for (old in oldSpans) {
            spannable.removeSpan(old)
        }

        for (span in spans) {
            val charStart = span.startByte shr 1
            val charEnd = (span.endByte shr 1).coerceAtMost(spannable.length)
            if (charStart < charEnd) {
                val colorDef = palette[span.styleId]
                // A missing DSL color means "leave the editor's foreground alone".
                // Never inject a platform black/white fallback.
                val color = if (isDarkTheme) colorDef?.darkColor ?: 0 else colorDef?.lightColor ?: 0
                if (color != 0) {
                    spannable.setSpan(ForegroundColorSpan(color), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val flags = span.fontFlags
                if ((flags and 0x01) != 0) {
                    spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if ((flags and 0x02) != 0) {
                    spannable.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    fun computeHighlights(tree: TSTree?, text: String, extension: String): List<HighlightSpan> {
        val flatData = nativeHighlight(tree?.nativeObject ?: 0L, text, extension) ?: return emptyList()
        val count = flatData.size / 3
        val result = ArrayList<HighlightSpan>(count)
        for (i in 0 until count) {
            val start = flatData[i * 3 + 0]
            val end = flatData[i * 3 + 1]
            val packedMeta = flatData[i * 3 + 2]
            val styleId = packedMeta and 0xFFFF
            val flags = (packedMeta ushr 16) and 0xFFFF
            result.add(HighlightSpan(start, end, styleId, flags))
        }
        return result
    }

    @FastNative
    private external fun nativeLoadTshSource(extension: String, tshSourceContent: String, languagePtr: Long): Boolean

    @FastNative
    private external fun nativeGetPalette(extension: String): IntArray?

    @FastNative
    private external fun nativeHighlight(treePtr: Long, text: String, extension: String): IntArray?


}