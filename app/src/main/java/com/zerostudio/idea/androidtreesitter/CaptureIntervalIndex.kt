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
    // Native returns resolved spans in unsigned byte-offset order. Reuse that
    // immutable result directly; sort only defensive callers that supply an
    // unsorted list. Avoid a second full-sized capture-object array per partition.
    private val spans: List<WindbellTshEngine.HighlightSpan> = if (isSorted(captures)) {
        captures
    } else {
        captures.sortedWith { left, right ->
            Integer.compareUnsigned(left.startByte, right.startByte)
        }
    }

    // Tree-sitter byte offsets are uint32. Keep the prefix maximum in the same
    // four-byte representation instead of widening every entry to Long.
    private val prefixMaxEnd = IntArray(spans.size)

    init {
        var maximum = 0
        spans.forEachIndexed { index, span ->
            if (Integer.compareUnsigned(span.endByte, maximum) > 0) maximum = span.endByte
            prefixMaxEnd[index] = maximum
        }
    }

    private fun isSorted(values: List<WindbellTshEngine.HighlightSpan>): Boolean {
        for (index in 1 until values.size) {
            if (Integer.compareUnsigned(values[index - 1].startByte, values[index].startByte) > 0) {
                return false
            }
        }
        return true
    }

    fun query(startUtf16: Int, endUtf16: Int): List<WindbellTshEngine.HighlightSpan> {
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || spans.isEmpty()) return emptyList()
        val result = ArrayList<WindbellTshEngine.HighlightSpan>()
        appendQuery(startUtf16, endUtf16, result)
        return result
    }

    /** Appends matching captures without allocating a temporary list per partition. */
    fun appendQuery(
        startUtf16: Int,
        endUtf16: Int,
        destination: MutableList<WindbellTshEngine.HighlightSpan>
    ) {
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || spans.isEmpty()) return
        val startByte = (startUtf16.toLong() * 2L).toInt()
        val endByte = (endUtf16.toLong() * 2L).toInt()
        var low = 0
        var high = spans.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (Integer.compareUnsigned(spans[middle].startByte, endByte) < 0) low = middle + 1
            else high = middle
        }
        val upper = low
        if (upper == 0) return
        low = 0
        high = upper
        while (low < high) {
            val middle = (low + high) ushr 1
            if (Integer.compareUnsigned(prefixMaxEnd[middle], startByte) <= 0) low = middle + 1
            else high = middle
        }
        for (index in low until upper) {
            val span = spans[index]
            if (Integer.compareUnsigned(span.endByte, startByte) > 0) destination.add(span)
        }
    }

    val size: Int get() = spans.size
}
