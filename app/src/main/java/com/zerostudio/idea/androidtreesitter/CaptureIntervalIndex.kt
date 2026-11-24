package com.zerostudio.idea.androidtreesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine

/**
 * Immutable primitive-array interval index over sorted native captures.
 *
 * Retaining one Kotlin data object per capture made the byte-budget cache
 * underestimate real heap use and evict useful viewports too early. The index
 * stores the native ABI fields in compact parallel arrays and materializes
 * HighlightSpan objects only for the requested visible interval.
 */
internal class CaptureIntervalIndex(
    captures: List<WindbellTshEngine.HighlightSpan>
) {
    private val starts: IntArray
    private val ends: IntArray
    private val styleIds: ShortArray
    private val fontFlags: ShortArray
    private val priorities: IntArray
    private val declarationOrders: IntArray
    private val sources: ByteArray
    private val prefixMaxEnd: IntArray

    val size: Int

    init {
        val ordered = if (isSorted(captures)) captures else captures.sortedWith { left, right ->
            Integer.compareUnsigned(left.startByte, right.startByte)
        }
        size = ordered.size
        starts = IntArray(size)
        ends = IntArray(size)
        styleIds = ShortArray(size)
        fontFlags = ShortArray(size)
        priorities = IntArray(size)
        declarationOrders = IntArray(size)
        sources = ByteArray(size)
        prefixMaxEnd = IntArray(size)

        var maximumEnd = 0
        ordered.forEachIndexed { index, span ->
            starts[index] = span.startByte
            ends[index] = span.endByte
            styleIds[index] = span.styleId.toShort()
            fontFlags[index] = span.fontFlags.toShort()
            priorities[index] = span.priority
            declarationOrders[index] = span.declarationOrder
            sources[index] = span.source.toByte()
            if (Integer.compareUnsigned(span.endByte, maximumEnd) > 0) {
                maximumEnd = span.endByte
            }
            prefixMaxEnd[index] = maximumEnd
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
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || size == 0) return emptyList()
        val result = ArrayList<WindbellTshEngine.HighlightSpan>()
        appendQuery(startUtf16, endUtf16, result)
        return result
    }

    /** Appends matching captures without allocating a temporary list per partition. */
    fun appendQuery(
        startUtf16: Int,
        endUtf16: Int,
        destination: MutableCollection<WindbellTshEngine.HighlightSpan>
    ) {
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || size == 0) return
        val startByte = (startUtf16.toLong() * 2L).toInt()
        val endByte = (endUtf16.toLong() * 2L).toInt()

        // First capture whose start is not before the requested exclusive end.
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (Integer.compareUnsigned(starts[middle], endByte) < 0) low = middle + 1
            else high = middle
        }
        val upper = low
        if (upper == 0) return

        // Prefix maximum end skips every prefix that cannot overlap the query.
        low = 0
        high = upper
        while (low < high) {
            val middle = (low + high) ushr 1
            if (Integer.compareUnsigned(prefixMaxEnd[middle], startByte) <= 0) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        for (index in low until upper) {
            if (Integer.compareUnsigned(ends[index], startByte) > 0) {
                destination.add(
                    WindbellTshEngine.HighlightSpan(
                        startByte = starts[index],
                        endByte = ends[index],
                        styleId = styleIds[index].toInt() and 0xFFFF,
                        fontFlags = fontFlags[index].toInt() and 0xFFFF,
                        priority = priorities[index],
                        declarationOrder = declarationOrders[index],
                        source = sources[index].toInt() and 0xFF
                    )
                )
            }
        }
    }

    /** Creates a new immutable index after dropping or relocating individual captures. */
    fun mapSpans(
        transform: (WindbellTshEngine.HighlightSpan) -> WindbellTshEngine.HighlightSpan?
    ): CaptureIntervalIndex {
        val mapped = ArrayList<WindbellTshEngine.HighlightSpan>(size)
        for (index in 0 until size) {
            val span = WindbellTshEngine.HighlightSpan(
                startByte = starts[index],
                endByte = ends[index],
                styleId = styleIds[index].toInt() and 0xFFFF,
                fontFlags = fontFlags[index].toInt() and 0xFFFF,
                priority = priorities[index],
                declarationOrder = declarationOrders[index],
                source = sources[index].toInt() and 0xFF
            )
            transform(span)?.let(mapped::add)
        }
        return CaptureIntervalIndex(mapped)
    }
}
