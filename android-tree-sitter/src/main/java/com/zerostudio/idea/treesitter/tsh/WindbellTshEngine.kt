package com.zerostudio.idea.treesitter.tsh

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.TextPaint
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSTree
import dalvik.annotation.optimization.FastNative
import java.util.concurrent.ConcurrentHashMap
import com.zerostudio.idea.treesitter.TreeSitter
/**
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * 提供双模高亮渲染桥接、代码折叠、符号大纲与悬停提示。
 */
class WindbellTshEngine private constructor() {

    data class HighlightSpan(
        val startByte: Int,
        val endByte: Int,
        val styleId: Int,
        val fontFlags: Int
    )

    data class FoldRange(
        val startByte: Int,
        val endByte: Int,
        val kind: Int // 0: Code Block, 1: Comment, 2: Imports
    )

    data class OutlineItem(
        val startByte: Int,
        val endByte: Int,
        val kind: Int, // 1: Class, 2: Method, 3: Field, 4: Enum
        val name: String
    )

    data class HoverResult(
        val startByte: Int,
        val endByte: Int,
        val nodeType: String,
        val detail: String
    )

    data class StylePalette(
        val styleId: Int,
        val lightColor: Int,
        val darkColor: Int
    )

    private val paletteCache = ConcurrentHashMap<String, Map<Int, StylePalette>>()

    /** Marker used to remove only spans created by this engine. */
    private interface TshOwnedSpan

    private class TshForegroundColorSpan(private val color: Int) : CharacterStyle(), TshOwnedSpan {
        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.color = color
        }
    }

    private class TshBoldSpan : MetricAffectingSpan(), TshOwnedSpan {
        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.typeface = Typeface.create(textPaint.typeface, Typeface.BOLD)
        }

        override fun updateMeasureState(textPaint: TextPaint) {
            textPaint.typeface = Typeface.create(textPaint.typeface, Typeface.BOLD)
        }
    }
    
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

        // Never remove host/editor spans. Only TSH-owned span instances are replaceable.
        val oldSpans = spannable.getSpans(0, spannable.length, CharacterStyle::class.java)
        for (old in oldSpans) {
            if (old is TshOwnedSpan) spannable.removeSpan(old)
        }

        for (span in spans) {
            // Native Tree-sitter offsets are UTF-16LE byte offsets for this legacy path.
            // Reject malformed/odd/out-of-range ranges instead of silently rounding them.
            if (span.startByte < 0 || span.endByte < span.startByte ||
                (span.startByte and 1) != 0 || (span.endByte and 1) != 0) {
                continue
            }
            val charStartLong = span.startByte.toLong() / 2L
            val charEndLong = span.endByte.toLong() / 2L
            if (charStartLong >= spannable.length.toLong() || charEndLong <= charStartLong) continue
            val charStart = charStartLong.toInt()
            val charEnd = minOf(charEndLong, spannable.length.toLong()).toInt()
            if (charStart < charEnd) {
                val colorDef = palette[span.styleId]
                val color = if (isDarkTheme) colorDef?.darkColor ?: Color.WHITE else colorDef?.lightColor ?: Color.BLACK
                spannable.setSpan(TshForegroundColorSpan(color), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if ((span.fontFlags and 0x01) != 0) {
                    spannable.setSpan(TshBoldSpan(), charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    fun computeFolds(tree: TSTree, text: String, extension: String): List<FoldRange> {
        val flatData = nativeComputeFolds(tree.nativeObject, text, extension) ?: return emptyList()
        val count = flatData.size / 3
        val result = ArrayList<FoldRange>(count)
        for (i in 0 until count) {
            result.add(FoldRange(flatData[i * 3 + 0], flatData[i * 3 + 1], flatData[i * 3 + 2]))
        }
        return result
    }

    fun computeOutline(tree: TSTree, text: String, extension: String): List<OutlineItem> {
        val raw = nativeGetOutline(tree.nativeObject, text, extension) ?: return emptyList()
        val count = raw.size / 4
        val result = ArrayList<OutlineItem>(count)
        for (i in 0 until count) {
            val s = raw[i * 4 + 0].toInt()
            val e = raw[i * 4 + 1].toInt()
            val k = raw[i * 4 + 2].toInt()
            val name = raw[i * 4 + 3]
            result.add(OutlineItem(s, e, k, name))
        }
        return result
    }

    fun getHoverInfo(tree: TSTree, byteOffset: Int): HoverResult? {
        val raw = nativeGetHoverInfo(tree.nativeObject, byteOffset) ?: return null
        return HoverResult(raw[0].toInt(), raw[1].toInt(), raw[2], raw[3])
    }

    @FastNative
    private external fun nativeLoadTshSource(extension: String, tshSourceContent: String, languagePtr: Long): Boolean

    @FastNative
    private external fun nativeGetPalette(extension: String): IntArray?

    @FastNative
    private external fun nativeHighlight(treePtr: Long, text: String, extension: String): IntArray?

    @FastNative
    private external fun nativeComputeFolds(treePtr: Long, text: String, extension: String): IntArray?

    @FastNative
    private external fun nativeGetOutline(treePtr: Long, text: String, extension: String): Array<String>?

    @FastNative
    private external fun nativeGetHoverInfo(treePtr: Long, byteOffset: Int): Array<String>?
}