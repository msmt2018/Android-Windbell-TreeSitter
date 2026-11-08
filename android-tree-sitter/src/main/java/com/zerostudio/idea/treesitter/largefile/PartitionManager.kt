/*
 * Copyright (C) 2026 Windbell contributors.
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package com.zerostudio.idea.treesitter.largefile


/**
 * Builds line-aligned UTF-16 partitions without copying the document.
 *
 * Offsets are UTF-16 code-unit offsets in the source CharSequence; byte offsets
 * are UTF-16LE and therefore exactly twice the character offsets. A partition
 * is a scheduling unit, NOT an independently parseable syntax document.
 */
class PartitionManager(
    private val targetPartitionChars: Int = 64 * 1024,
    private val minimumPartitionChars: Int = 16 * 1024
) {
    data class Partition(
        val index: Int,
        val startChar: Int,
        val endChar: Int,
        val startByte: Long,
        val endByte: Long,
        val startLine: Int,
        val endLineExclusive: Int,
        val version: Long,
        /** Newline offsets relative to startChar; includes the first line at 0. */
        val lineStarts: IntArray
    ) {
        val charLength: Int get() = endChar - startChar
        val byteLength: Long get() = endByte - startByte
    }

    data class Viewport(val startChar: Int, val endChar: Int)

    private var sourceVersion = 0L
    private var partitions: List<Partition> = emptyList()
    private var sourceLength = 0

    @Synchronized
    fun rebuild(text: CharSequence, version: Long = sourceVersion + 1): List<Partition> {
        require(version >= 0) { "version must be non-negative" }
        sourceVersion = version
        sourceLength = text.length
        if (text.isEmpty()) {
            partitions = emptyList()
            return partitions
        }

        val result = ArrayList<Partition>()
        var start = 0
        var startLine = 0
        var lineNumber = 0
        while (start < text.length) {
            val hardEnd = minOf(text.length, start + targetPartitionChars)
            var end = hardEnd
            if (hardEnd < text.length) {
                // Prefer a nearby line boundary, but do not create tiny partitions.
                val searchFloor = minOf(hardEnd, start + minimumPartitionChars)
                var newline = -1
                var i = hardEnd - 1
                while (i >= searchFloor) {
                    if (text[i] == '\n') { newline = i + 1; break }
                    i--
                }
                if (newline >= 0) end = newline
                // Never split a UTF-16 surrogate pair.
                if (end > start && end < text.length &&
                    Character.isHighSurrogate(text[end - 1]) &&
                    Character.isLowSurrogate(text[end])) end--
                if (end <= start) end = hardEnd
            }
            val starts = ArrayList<Int>()
            starts.add(0)
            for (i in start until end) {
                if (text[i] == '\n') {
                    lineNumber++
                    if (i + 1 < end) starts.add(i + 1 - start)
                }
            }
            val endLine = lineNumber + if (end > start && text[end - 1] == '\n') 0 else 1
            result.add(
                Partition(
                    index = result.size,
                    startChar = start,
                    endChar = end,
                    startByte = start.toLong() * 2L,
                    endByte = end.toLong() * 2L,
                    startLine = startLine,
                    endLineExclusive = maxOf(startLine + 1, endLine),
                    version = version,
                    lineStarts = starts.toIntArray()
                )
            )
            start = end
            startLine = result.last().endLineExclusive
        }
        partitions = result
        return partitions
    }

    @Synchronized
    fun snapshot(): List<Partition> = partitions

    /** Selects intersecting partitions plus a bounded number of neighbors. */
    @Synchronized
    fun select(viewport: Viewport, prefetchPartitions: Int = 1): List<Partition> {
        require(prefetchPartitions >= 0)
        require(viewport.startChar >= 0 && viewport.endChar >= viewport.startChar)
        if (partitions.isEmpty() || viewport.startChar >= sourceLength) return emptyList()
        val start = viewport.startChar.coerceAtMost(sourceLength)
        val end = viewport.endChar.coerceAtMost(sourceLength)
        val first = findPartition(start)
        val last = findPartition(if (end > start) end - 1 else start)
        val from = (first - prefetchPartitions).coerceAtLeast(0)
        val through = (last + prefetchPartitions).coerceAtMost(partitions.lastIndex)
        return partitions.subList(from, through + 1)
    }

    @Synchronized
    fun invalidate(editStartChar: Int, oldEndChar: Int, newEndChar: Int): Set<Int> {
        require(editStartChar >= 0 && oldEndChar >= editStartChar && newEndChar >= editStartChar)
        val affectedEnd = maxOf(oldEndChar, newEndChar)
        return partitions.asSequence()
            .filter { it.endChar > editStartChar && it.startChar <= affectedEnd }
            .map { it.index }
            .toSet()
    }

    @Synchronized
    private fun findPartition(offset: Int): Int {
        var low = 0
        var high = partitions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val p = partitions[mid]
            when {
                offset < p.startChar -> high = mid - 1
                offset >= p.endChar -> low = mid + 1
                else -> return mid
            }
        }
        return low.coerceIn(0, partitions.lastIndex)
    }
}
