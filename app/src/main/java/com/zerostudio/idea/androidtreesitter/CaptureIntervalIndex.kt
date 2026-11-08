package com.zerostudio.idea.androidtreesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine

/**
 * Immutable interval index over sorted native captures. It avoids the previous
 * per-line map, which duplicated long-lived comment/string captures into every
 * covered line and could consume more memory than the native capture list itself.
 */
internal class CaptureIntervalIndex(
    captures: List<WindbellTshEngine.HighlightSpan>
) {
    private val spans = captures.sortedBy { it.startByte }
    private val prefixMaxEnd = LongArray(spans.size)

    init {
        var maximum = Long.MIN_VALUE
        spans.forEachIndexed { index, span ->
            maximum = maxOf(maximum, span.endByte.toLong())
            prefixMaxEnd[index] = maximum
        }
    }

    fun query(startUtf16: Int, endUtf16: Int): List<WindbellTshEngine.HighlightSpan> {
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || spans.isEmpty()) return emptyList()
        val startByte = startUtf16.toLong() * 2L
        val endByte = endUtf16.toLong() * 2L
        var low = 0
        var high = spans.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (spans[middle].startByte.toLong() < endByte) low = middle + 1
            else high = middle
        }
        val upper = low
        if (upper == 0) return emptyList()
        low = 0
        high = upper
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefixMaxEnd[middle] <= startByte) low = middle + 1
            else high = middle
        }
        if (low >= upper) return emptyList()
        val result = ArrayList<WindbellTshEngine.HighlightSpan>()
        for (index in low until upper) {
            val span = spans[index]
            if (span.endByte.toLong() > startByte) result += span
        }
        return result
    }

    val size: Int get() = spans.size
}
