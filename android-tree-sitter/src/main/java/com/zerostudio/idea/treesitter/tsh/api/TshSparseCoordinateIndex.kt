package com.zerostudio.idea.treesitter.tsh.api

/**
 * Sparse, snapshot-scoped UTF-8/UTF-16/Unicode-code-point index.
 *
 * The index stores one checkpoint per [checkpointStrideUtf16] code units (rounded forward to a
 * code-point boundary). It never materializes the document: construction reads bounded chunks,
 * while conversion scans at most one checkpoint interval plus a small boundary allowance.
 *
 * Instances are immutable and tied to the exact documentId/version/length from the source snapshot.
 * Rebuild after edits unless the host supplies a persistent-root-aware incremental index.
 */
class TshSparseCoordinateIndex private constructor(
    val documentId: String,
    val documentVersion: Long,
    val lengthUtf16: Long,
    private val checkpoints: List<Checkpoint>,
    private val source: TshTextSnapshot
) {
    private data class Checkpoint(
        val utf16: Long,
        val utf8: Long,
        val codePoints: Long
    )

    /** Converts only between UTF-8 bytes, UTF-16 code units and Unicode code points. */
    fun convert(
        offset: Long,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): TshResult<Long> {
        if (offset < 0L) return failure("TSH-COORD-INDEX-001", "Offset must be non-negative")
        val fromValue = metricLimit(from) ?: return failure(
            "TSH-COORD-INDEX-002", "Unsupported coordinate system: $from"
        )
        val toValue = metricLimit(to) ?: return failure(
            "TSH-COORD-INDEX-002", "Unsupported coordinate system: $to"
        )
        if (offset > fromValue) return failure(
            "TSH-COORD-INDEX-003", "Offset is outside the indexed snapshot"
        )
        if (from == to) {
            if (from == TshCoordinateSystem.UTF16_CODE_UNIT &&
                !isUtf16Boundary(offset)) return failure(
                "TSH-COORD-INDEX-004", "UTF-16 offset splits a surrogate pair"
            )
            return TshResult.Success(offset)
        }

        val checkpoint = findCheckpoint(from, offset)
        val converted = scanFrom(checkpoint, offset, from, to)
            ?: return failure("TSH-COORD-INDEX-005", "Offset is not on a valid Unicode character boundary")
        return TshResult.Success(converted)
    }

    private fun metricLimit(system: TshCoordinateSystem): Long? {
        val last = checkpoints.last()
        return when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> lengthUtf16
            TshCoordinateSystem.UTF8_BYTE -> last.utf8
            TshCoordinateSystem.UNICODE_CODE_POINT -> last.codePoints
            else -> null
        }
    }

    private fun findCheckpoint(system: TshCoordinateSystem, offset: Long): Checkpoint {
        var low = 0
        var high = checkpoints.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val value = metric(checkpoints[mid], system)
            if (value <= offset) low = mid else high = mid - 1
        }
        return checkpoints[low]
    }

    private fun metric(checkpoint: Checkpoint, system: TshCoordinateSystem): Long =
        when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> checkpoint.utf16
            TshCoordinateSystem.UTF8_BYTE -> checkpoint.utf8
            TshCoordinateSystem.UNICODE_CODE_POINT -> checkpoint.codePoints
            else -> error("Unsupported coordinate system")
        }

    private fun scanFrom(
        checkpoint: Checkpoint,
        target: Long,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): Long? {
        var utf16 = checkpoint.utf16
        var utf8 = checkpoint.utf8
        var codePoints = checkpoint.codePoints
        if (metric(checkpoint, from) == target) return metric(checkpoint, to)
        var end = minOf(lengthUtf16, checkpoint.utf16 + MAX_SCAN_UTF16)
        var text = source.readUtf16(checkpoint.utf16, end)
        if (text.isNotEmpty() && Character.isHighSurrogate(text.last()) && end < lengthUtf16) {
            end++
            text = source.readUtf16(checkpoint.utf16, end)
        }
        var index = 0
        while (index < text.length) {
            val cp = Character.codePointAt(text, index)
            val units = Character.charCount(cp).toLong()
            val byteCount = when {
                cp <= 0x7f -> 1L
                cp <= 0x7ff -> 2L
                cp <= 0xffff -> 3L
                else -> 4L
            }
            val current = when (from) {
                TshCoordinateSystem.UTF16_CODE_UNIT -> utf16
                TshCoordinateSystem.UTF8_BYTE -> utf8
                TshCoordinateSystem.UNICODE_CODE_POINT -> codePoints
                else -> return null
            }
            if (current == target) return metric(utf16, utf8, codePoints, to)
            val next = when (from) {
                TshCoordinateSystem.UTF16_CODE_UNIT -> utf16 + units
                TshCoordinateSystem.UTF8_BYTE -> utf8 + byteCount
                TshCoordinateSystem.UNICODE_CODE_POINT -> codePoints + 1L
                else -> return null
            }
            if (target < next) return null
            utf16 += units
            utf8 += byteCount
            codePoints++
            index += units.toInt()
        }
        return if (when (from) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> utf16
            TshCoordinateSystem.UTF8_BYTE -> utf8
            else -> codePoints
        } == target) metric(utf16, utf8, codePoints, to) else null
    }

    private fun metric(utf16: Long, utf8: Long, codePoints: Long, system: TshCoordinateSystem): Long =
        when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> utf16
            TshCoordinateSystem.UTF8_BYTE -> utf8
            TshCoordinateSystem.UNICODE_CODE_POINT -> codePoints
            else -> error("Unsupported coordinate system")
        }

    private fun isUtf16Boundary(offset: Long): Boolean {
        if (offset <= 0L || offset >= lengthUtf16) return true
        val pair = source.readUtf16(offset - 1L, offset + 1L)
        return !(Character.isHighSurrogate(pair[0]) && Character.isLowSurrogate(pair[1]))
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.COORDINATE,
        message = message
    )))

    companion object {
        private const val DEFAULT_CHUNK_UTF16 = 32 * 1024
        private const val DEFAULT_CHECKPOINT_STRIDE = 4 * 1024
        private const val MAX_SCAN_UTF16 = DEFAULT_CHECKPOINT_STRIDE + 4

        /**
         * Builds a sparse index from a stable snapshot. [chunkSizeUtf16] is bounded to 256 KiB;
         * checkpoint stride must be at least 256 code units to keep metadata proportional.
         */
        @JvmStatic
        fun build(
            snapshot: TshTextSnapshot,
            chunkSizeUtf16: Int = DEFAULT_CHUNK_UTF16,
            checkpointStrideUtf16: Int = DEFAULT_CHECKPOINT_STRIDE
        ): TshResult<TshSparseCoordinateIndex> {
            if (snapshot.documentId.isBlank() || snapshot.version < 0L || snapshot.lengthUtf16 < 0L) {
                return failure("TSH-COORD-INDEX-006", "Snapshot identity, version or length is invalid")
            }
            if (chunkSizeUtf16 !in 256..(256 * 1024) ||
                checkpointStrideUtf16 !in 256..(1024 * 1024)) {
                return failure("TSH-COORD-INDEX-007", "Chunk/stride settings exceed safe bounds")
            }
            return try {
                val points = ArrayList<Checkpoint>()
                var utf16 = 0L
                var utf8 = 0L
                var codePoints = 0L
                var nextCheckpoint = checkpointStrideUtf16.toLong()
                points += Checkpoint(0L, 0L, 0L)
                while (utf16 < snapshot.lengthUtf16) {
                    var end = minOf(snapshot.lengthUtf16, utf16 + chunkSizeUtf16)
                    var chunk = snapshot.readUtf16(utf16, end)
                    if (chunk.isNotEmpty() && Character.isHighSurrogate(chunk.last()) &&
                        end < snapshot.lengthUtf16) {
                        end++
                        chunk = snapshot.readUtf16(utf16, end)
                    }
                    if (chunk.length.toLong() != end - utf16 || !TshUnicode.isWellFormedUtf16(chunk)) {
                        return failure("TSH-COORD-INDEX-008", "Snapshot contains malformed or inconsistent UTF-16")
                    }
                    var i = 0
                    while (i < chunk.length) {
                        val cp = Character.codePointAt(chunk, i)
                        val units = Character.charCount(cp)
                        utf16 += units.toLong()
                        utf8 += when {
                            cp <= 0x7f -> 1L
                            cp <= 0x7ff -> 2L
                            cp <= 0xffff -> 3L
                            else -> 4L
                        }
                        codePoints++
                        i += units
                        if (utf16 >= nextCheckpoint) {
                            points += Checkpoint(utf16, utf8, codePoints)
                            nextCheckpoint = Math.addExact(utf16, checkpointStrideUtf16.toLong())
                        }
                    }
                    check(utf16 == end) { "Snapshot chunk boundary changed during index build" }
                }
                if (points.last().utf16 != utf16) points += Checkpoint(utf16, utf8, codePoints)
                TshResult.Success(TshSparseCoordinateIndex(
                    snapshot.documentId, snapshot.version, snapshot.lengthUtf16, points, snapshot
                ))
            } catch (error: Exception) {
                failure("TSH-COORD-INDEX-009", "Cannot build sparse coordinate index: ${error.message}", error)
            }
        }

        private fun failure(code: String, message: String, cause: Throwable? = null) =
            TshResult.Failure(listOf(TshDiagnostic(
                code = code,
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.COORDINATE,
                message = message,
                cause = cause
            )))
    }
}
