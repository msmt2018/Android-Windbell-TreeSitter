package com.zerostudio.idea.androidtreesitter

/**
 * Re-bases display-only captures across one UTF-16 edit. Captures intersecting
 * the edit are dropped; captures wholly before/after it are retained and moved.
 * This is not semantic invalidation: language features must never read this cache.
 */
internal object CaptureDisplayRebaser {
    data class Result(val key: CaptureRangeKey, val index: CaptureIntervalIndex)

    fun rebase(
        key: CaptureRangeKey,
        index: CaptureIntervalIndex,
        editStartUtf16: Int,
        deletedChars: Int,
        insertedChars: Int
    ): Result? {
        if (editStartUtf16 < 0 || deletedChars < 0 || insertedChars < 0) return null
        if (deletedChars == 0 && insertedChars == 0) return Result(key, index)
        val editStart = editStartUtf16.toLong()
        val oldEnd = editStart + deletedChars.toLong()
        val delta = insertedChars.toLong() - deletedChars.toLong()
        val newEnd = oldEnd + delta
        if (oldEnd > Int.MAX_VALUE || newEnd < 0L || newEnd > Int.MAX_VALUE) return null

        val rangeStart = key.startUtf16.toLong()
        val rangeEnd = key.endUtf16.toLong()
        val mappedStart: Long
        val mappedEnd: Long
        when {
            rangeEnd <= editStart -> {
                mappedStart = rangeStart
                mappedEnd = rangeEnd
            }
            rangeStart >= oldEnd -> {
                mappedStart = rangeStart + delta
                mappedEnd = rangeEnd + delta
            }
            else -> {
                mappedStart = minOf(rangeStart, editStart)
                mappedEnd = if (rangeEnd > oldEnd) rangeEnd + delta else newEnd
            }
        }
        if (mappedStart < 0L || mappedEnd <= mappedStart || mappedEnd > Int.MAX_VALUE) return null

        val editStartByte = editStart * 2L
        val oldEndByte = oldEnd * 2L
        val deltaBytes = delta * 2L
        val rebased = index.rebaseEdit(
            editStartByte = editStartByte,
            oldEndByte = oldEndByte,
            deltaBytes = deltaBytes
        ) ?: return null
        return Result(CaptureRangeKey(key.sessionId, mappedStart.toInt(), mappedEnd.toInt()), rebased)
    }
}
