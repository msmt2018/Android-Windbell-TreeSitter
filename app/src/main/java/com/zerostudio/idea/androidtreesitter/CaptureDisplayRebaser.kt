package com.zerostudio.idea.androidtreesitter

/**
 * Re-bases display-only captures across one UTF-16 edit. Captures intersecting
 * the edit are dropped; captures wholly before/after it are retained and moved.
 * This is not semantic invalidation: language features must never read this cache.
 */
internal object CaptureDisplayRebaser {
    data class Result(val key: CaptureRangeKey, val index: CaptureIntervalIndex)

    /**
     * An edit expressed in the document coordinate space immediately before it
     * was applied. A batch is kept in application order (the editor reports
     * bottom-up edits), so reverse mapping walks it backwards.
     */
    data class PendingEdit(
        val startUtf16: Int,
        val deletedChars: Int,
        val insertedChars: Int
    ) {
        init {
            require(startUtf16 >= 0 && deletedChars >= 0 && insertedChars >= 0)
        }
    }

    data class SourceRange(val startUtf16: Int, val endUtf16: Int)

    /**
     * Maps an unchanged range in the current editor buffer back to the cached
     * pre-edit document. A range touching inserted/replaced text, or spanning an
     * insertion boundary, has no safe old representation and returns null.
     */
    fun mapCurrentRangeToOriginal(
        startUtf16: Int,
        endUtf16: Int,
        edits: List<PendingEdit>
    ): SourceRange? {
        if (startUtf16 < 0 || endUtf16 <= startUtf16) return null
        var start = startUtf16.toLong()
        var end = endUtf16.toLong()
        for (edit in edits.asReversed()) {
            val editStart = edit.startUtf16.toLong()
            val insertedEnd = editStart + edit.insertedChars.toLong()
            val delta = edit.insertedChars.toLong() - edit.deletedChars.toLong()
            when {
                end <= editStart -> Unit
                start >= insertedEnd -> {
                    start -= delta
                    end -= delta
                }
                else -> return null
            }
            if (start < 0L || end <= start || end > Int.MAX_VALUE.toLong()) return null
        }
        return SourceRange(start.toInt(), end.toInt())
    }

    /**
     * Maps one old capture into the current document. Captures that intersect
     * replaced text or cross an insertion point are intentionally withheld until
     * the native parser has produced a fresh semantic result.
     */
    fun mapOriginalCaptureToCurrentPacked(
        startUtf16: Int,
        endUtf16: Int,
        edits: List<PendingEdit>
    ): Long {
        if (startUtf16 < 0 || endUtf16 <= startUtf16) return -1L
        var start = startUtf16.toLong()
        var end = endUtf16.toLong()
        for (edit in edits) {
            val editStart = edit.startUtf16.toLong()
            val oldEnd = editStart + edit.deletedChars.toLong()
            val delta = edit.insertedChars.toLong() - edit.deletedChars.toLong()
            when {
                end <= editStart -> Unit
                start >= oldEnd -> { start += delta; end += delta }
                else -> return -1L
            }
            if (start < 0L || end <= start || end > Int.MAX_VALUE.toLong()) return -1L
        }
        return (start shl 32) or (end and 0xFFFF_FFFFL)
    }

    fun mapOriginalCaptureToCurrent(
        startUtf16: Int,
        endUtf16: Int,
        edits: List<PendingEdit>
    ): SourceRange? {
        val packed = mapOriginalCaptureToCurrentPacked(startUtf16, endUtf16, edits)
        return if (packed == -1L) null else SourceRange((packed ushr 32).toInt(), packed.toInt())
    }

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
