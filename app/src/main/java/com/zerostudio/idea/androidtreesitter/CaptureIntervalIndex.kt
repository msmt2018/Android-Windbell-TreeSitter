package com.zerostudio.idea.androidtreesitter

import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine

/**
 * Immutable primitive-array interval index over sorted native captures.
 *
 * Retaining one Kotlin data object per capture made the byte-budget cache
 * underestimate real heap use and evict useful viewports too early. The index
 * stores native ABI fields in compact parallel arrays and materializes
 * HighlightSpan objects only for the requested visible interval.
 */
internal class CaptureIntervalIndex private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val styleIds: ShortArray,
    private val fontFlags: ShortArray,
    private val priorities: IntArray,
    private val declarationOrders: IntArray,
    private val sources: ByteArray,
    private val prefixMaxEnd: IntArray,
    val size: Int
) {
    private data class PackedArrays(
        val starts: IntArray,
        val ends: IntArray,
        val styleIds: ShortArray,
        val fontFlags: ShortArray,
        val priorities: IntArray,
        val declarationOrders: IntArray,
        val sources: ByteArray,
        val prefixMaxEnd: IntArray,
        val size: Int
    )

    private constructor(packed: PackedArrays) : this(
        packed.starts,
        packed.ends,
        packed.styleIds,
        packed.fontFlags,
        packed.priorities,
        packed.declarationOrders,
        packed.sources,
        packed.prefixMaxEnd,
        packed.size
    )

    constructor(captures: List<WindbellTshEngine.HighlightSpan>) : this(pack(captures))

    /**
     * Consumes the native six-int capture ABI directly. The normal JNI path is
     * already ordered by start byte, so it avoids both per-capture Kotlin objects
     * and a temporary boxed sort index. The defensive sort is retained for any
     * producer that violates that ordering contract.
     */
    constructor(nativeFlat: IntArray) : this(packNative(nativeFlat))

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

    /**
     * Rebase every cached capture by one uniform UTF-16LE byte delta without
     * materializing per-capture objects or sorting a new list. The caller has
     * already proven the viewport/dependency envelope reusable across the edit.
     */
    fun shiftBytes(delta: Long): CaptureIntervalIndex? {
        if (delta == 0L) return this
        val shiftedStarts = IntArray(size)
        val shiftedEnds = IntArray(size)
        val shiftedPrefixMaxEnd = IntArray(size)
        var previousPrefixMax = 0L
        for (index in 0 until size) {
            val start = (starts[index].toLong() and 0xFFFF_FFFFL) + delta
            val end = (ends[index].toLong() and 0xFFFF_FFFFL) + delta
            val prefixEnd = (prefixMaxEnd[index].toLong() and 0xFFFF_FFFFL) + delta
            if (start < 0L || end < start || end > 0xFFFF_FFFFL ||
                prefixEnd < 0L || prefixEnd > 0xFFFF_FFFFL) {
                return null
            }
            shiftedStarts[index] = start.toInt()
            shiftedEnds[index] = end.toInt()
            if (prefixEnd < previousPrefixMax) return null
            previousPrefixMax = prefixEnd
            shiftedPrefixMaxEnd[index] = prefixEnd.toInt()
        }
        return CaptureIntervalIndex(
            shiftedStarts,
            shiftedEnds,
            styleIds,
            fontFlags,
            priorities,
            declarationOrders,
            sources,
            shiftedPrefixMaxEnd,
            size
        )
    }

    /**
     * Rebase packed captures across one UTF-16LE edit without materializing
     * HighlightSpan objects. Captures intersecting replaced text (or crossing
     * an insertion point) are dropped; captures after the edit are shifted.
     * The input and output remain sorted by unsigned byte offset.
     */
    fun rebaseEdit(
        editStartByte: Long,
        oldEndByte: Long,
        deltaBytes: Long
    ): CaptureIntervalIndex? {
        if (editStartByte < 0L || oldEndByte < editStartByte ||
            oldEndByte > 0xFFFF_FFFFL) return null
        if (deltaBytes == 0L && oldEndByte == editStartByte) return this

        val nextStarts = IntArray(size)
        val nextEnds = IntArray(size)
        val nextPrefixMaxEnd = IntArray(size)
        var outputSize = 0
        var maximumEnd = 0L

        for (index in 0 until size) {
            val start = starts[index].toLong() and 0xFFFF_FFFFL
            val end = ends[index].toLong() and 0xFFFF_FFFFL
            val mappedStart: Long
            val mappedEnd: Long

            when {
                end <= editStartByte -> {
                    mappedStart = start
                    mappedEnd = end
                }
                start >= oldEndByte -> {
                    mappedStart = start + deltaBytes
                    mappedEnd = end + deltaBytes
                }
                else -> continue
            }

            if (mappedStart < 0L || mappedEnd <= mappedStart ||
                mappedEnd > 0xFFFF_FFFFL) return null

            nextStarts[outputSize] = mappedStart.toInt()
            nextEnds[outputSize] = mappedEnd.toInt()
            if (mappedEnd > maximumEnd) maximumEnd = mappedEnd
            nextPrefixMaxEnd[outputSize] = maximumEnd.toInt()
            outputSize++
        }

        return CaptureIntervalIndex(
            nextStarts.copyOf(outputSize),
            nextEnds.copyOf(outputSize),
            styleIds.copyOf(outputSize),
            fontFlags.copyOf(outputSize),
            priorities.copyOf(outputSize),
            declarationOrders.copyOf(outputSize),
            sources.copyOf(outputSize),
            nextPrefixMaxEnd.copyOf(outputSize),
            outputSize
        )
    }

    companion object {
        private const val NATIVE_CAPTURE_STRIDE = 6

        private fun packNative(flat: IntArray): PackedArrays {
            require(flat.size % NATIVE_CAPTURE_STRIDE == 0) {
                "Native capture payload must contain six integers per capture"
            }
            val count = flat.size / NATIVE_CAPTURE_STRIDE
            var sorted = true
            for (index in 1 until count) {
                val previous = flat[(index - 1) * NATIVE_CAPTURE_STRIDE]
                val current = flat[index * NATIVE_CAPTURE_STRIDE]
                if (Integer.compareUnsigned(previous, current) > 0) {
                    sorted = false
                    break
                }
            }
            val order: Array<Int>? = if (sorted) null else
                Array(count) { it }.also { indices ->
                    indices.sortWith(Comparator { left, right ->
                        Integer.compareUnsigned(
                            flat[left * NATIVE_CAPTURE_STRIDE],
                            flat[right * NATIVE_CAPTURE_STRIDE]
                        )
                    })
                }

            val starts = IntArray(count)
            val ends = IntArray(count)
            val styleIds = ShortArray(count)
            val fontFlags = ShortArray(count)
            val priorities = IntArray(count)
            val declarationOrders = IntArray(count)
            val sources = ByteArray(count)
            val prefixMaxEnd = IntArray(count)
            var maximumEnd = 0
            for (index in 0 until count) {
                val sourceIndex = order?.get(index) ?: index
                val offset = sourceIndex * NATIVE_CAPTURE_STRIDE
                val start = flat[offset]
                val end = flat[offset + 1]
                val packedStyle = flat[offset + 2]
                starts[index] = start
                ends[index] = end
                styleIds[index] = (packedStyle and 0xFFFF).toShort()
                fontFlags[index] = ((packedStyle ushr 16) and 0xFFFF).toShort()
                priorities[index] = flat[offset + 3]
                declarationOrders[index] = flat[offset + 4]
                sources[index] = flat[offset + 5].toByte()
                if (Integer.compareUnsigned(end, maximumEnd) > 0) maximumEnd = end
                prefixMaxEnd[index] = maximumEnd
            }
            return PackedArrays(
                starts, ends, styleIds, fontFlags, priorities,
                declarationOrders, sources, prefixMaxEnd, count
            )
        }
        private fun pack(
            captures: List<WindbellTshEngine.HighlightSpan>
        ): PackedArrays {
            val ordered = if (isSorted(captures)) captures else captures.sortedWith { left, right ->
                Integer.compareUnsigned(left.startByte, right.startByte)
            }
            val size = ordered.size
            val starts = IntArray(size)
            val ends = IntArray(size)
            val styleIds = ShortArray(size)
            val fontFlags = ShortArray(size)
            val priorities = IntArray(size)
            val declarationOrders = IntArray(size)
            val sources = ByteArray(size)
            val prefixMaxEnd = IntArray(size)

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
            return PackedArrays(
                starts, ends, styleIds, fontFlags, priorities,
                declarationOrders, sources, prefixMaxEnd, size
            )
        }

        private fun isSorted(
            values: List<WindbellTshEngine.HighlightSpan>
        ): Boolean {
            for (index in 1 until values.size) {
                if (Integer.compareUnsigned(
                        values[index - 1].startByte, values[index].startByte
                    ) > 0) return false
            }
            return true
        }
    }
}
