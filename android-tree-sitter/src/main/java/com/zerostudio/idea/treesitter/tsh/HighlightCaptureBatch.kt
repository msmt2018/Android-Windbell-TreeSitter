package com.zerostudio.idea.treesitter.tsh

/**
 * Primitive capture projection. Allocate the exact record count, fill each record,
 * then publish it to consumers. Tree-sitter byte offsets retain their unsigned
 * 32-bit Int bit pattern. No per-capture HighlightSpan objects are created.
 */
class HighlightCaptureBatch private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val styleIds: ShortArray,
    private val fontFlags: ShortArray,
    private val priorities: IntArray,
    private val declarationOrders: IntArray,
    private val sources: ByteArray
) {
    val size: Int get() = starts.size
    fun startByteAt(index: Int): Int = starts[index]
    fun endByteAt(index: Int): Int = ends[index]
    fun startByteUnsignedAt(index: Int): Long = starts[index].toLong() and 0xFFFF_FFFFL
    fun endByteUnsignedAt(index: Int): Long = ends[index].toLong() and 0xFFFF_FFFFL
    fun styleIdAt(index: Int): Int = styleIds[index].toInt() and 0xFFFF
    fun fontFlagsAt(index: Int): Int = fontFlags[index].toInt() and 0xFFFF
    fun priorityAt(index: Int): Int = priorities[index]
    fun declarationOrderAt(index: Int): Int = declarationOrders[index]
    fun sourceAt(index: Int): Int = sources[index].toInt() and 0xFF

    fun writeRecord(
        index: Int, startByte: Int, endByte: Int, styleId: Int, fontFlags: Int,
        priority: Int, declarationOrder: Int, source: Int
    ) {
        require(index in starts.indices)
        require(Integer.compareUnsigned(startByte, endByte) < 0)
        starts[index] = startByte
        ends[index] = endByte
        styleIds[index] = styleId.toShort()
        this.fontFlags[index] = fontFlags.toShort()
        priorities[index] = priority
        declarationOrders[index] = declarationOrder
        sources[index] = source.toByte()
    }

    fun copyRecordFrom(
        destinationIndex: Int, source: HighlightCaptureBatch, sourceIndex: Int,
        mappedStartByte: Int, mappedEndByte: Int
    ) {
        writeRecord(
            destinationIndex, mappedStartByte, mappedEndByte,
            source.styleIdAt(sourceIndex), source.fontFlagsAt(sourceIndex),
            source.priorityAt(sourceIndex), source.declarationOrderAt(sourceIndex),
            source.sourceAt(sourceIndex)
        )
    }

    companion object {
        private val EMPTY = HighlightCaptureBatch(
            IntArray(0), IntArray(0), ShortArray(0), ShortArray(0),
            IntArray(0), IntArray(0), ByteArray(0)
        )
        @JvmStatic fun allocate(captureCount: Int): HighlightCaptureBatch {
            require(captureCount >= 0)
            if (captureCount == 0) return EMPTY
            return HighlightCaptureBatch(
                IntArray(captureCount), IntArray(captureCount),
                ShortArray(captureCount), ShortArray(captureCount),
                IntArray(captureCount), IntArray(captureCount), ByteArray(captureCount)
            )
        }
        @JvmStatic fun empty(): HighlightCaptureBatch = EMPTY
    }
}
