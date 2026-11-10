package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sparse, snapshot-scoped UTF-8/UTF-16/Unicode-code-point index.
 *
 * The index owns an independent retained Snapshot Lease. Closing the caller's lease therefore
 * cannot invalidate this index; callers must close the index when it is no longer needed.
 *
 * Construction reads bounded chunks and never materializes the document. Conversion starts at
 * the nearest checkpoint and reads at most one configured checkpoint interval, in bounded chunks.
 * This is a snapshot index, not an incremental Piece Tree/Rope node index.
 */
class TshSparseCoordinateIndex private constructor(
    val documentId: String,
    val documentVersion: Long,
    val lengthUtf16: Long,
    private val checkpoints: List<Checkpoint>,
    private val sourceLease: TshTextSnapshotLease,
    private val source: TshTextSnapshot,
    private val checkpointStrideUtf16: Int,
    private val memoryReservation: TshMemoryReservation?
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

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
        if (closed.get() || sourceLease.isClosed) return failure(
            "TSH-COORD-INDEX-010", "Sparse coordinate index is closed"
        )
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
            val valid = when (from) {
                TshCoordinateSystem.UTF16_CODE_UNIT -> isUtf16Boundary(offset)
                TshCoordinateSystem.UTF8_BYTE -> {
                    val checkpoint = findCheckpoint(from, offset)
                    scanFrom(checkpoint, offset, from, TshCoordinateSystem.UTF16_CODE_UNIT) != null
                }
                TshCoordinateSystem.UNICODE_CODE_POINT -> true
                else -> false
            }
            if (!valid) return failure(
                "TSH-COORD-INDEX-004", "Offset splits an encoded character or is invalid"
            )
            return TshResult.Success(offset)
        }

        val checkpoint = findCheckpoint(from, offset)
        val converted = scanFrom(checkpoint, offset, from, to)
            ?: return failure("TSH-COORD-INDEX-005", "Offset is not on a valid Unicode character boundary")
        return TshResult.Success(converted)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                sourceLease.close()
            } finally {
                memoryReservation?.close()
            }
        }
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
            if (metric(checkpoints[mid], system) <= offset) low = mid else high = mid - 1
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

    /**
     * Streams the interval in bounded chunks. The configured stride, rather than the default
     * stride, determines the maximum searchable distance; surrogate rounding can add one unit.
     */
    private fun scanFrom(
        checkpoint: Checkpoint,
        target: Long,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): Long? {
        var utf16 = checkpoint.utf16
        var utf8 = checkpoint.utf8
        var codePoints = checkpoint.codePoints
        if (metric(checkpoint, from) == target) return metric(utf16, utf8, codePoints, to)

        val maxEnd = minOf(
            lengthUtf16,
            safeAdd(checkpoint.utf16, checkpointStrideUtf16.toLong() + 2L)
        )
        while (utf16 < maxEnd) {
            var end = minOf(maxEnd, safeAdd(utf16, SCAN_CHUNK_UTF16.toLong()))
            var text = source.readUtf16(utf16, end)
            if (text.isNotEmpty() && Character.isHighSurrogate(text.last()) && end < lengthUtf16) {
                end++
                text = source.readUtf16(utf16, end)
            }
            if (text.length.toLong() != end - utf16 || !TshUnicode.isWellFormedUtf16(text)) return null

            var index = 0
            while (index < text.length) {
                val current = when (from) {
                    TshCoordinateSystem.UTF16_CODE_UNIT -> utf16
                    TshCoordinateSystem.UTF8_BYTE -> utf8
                    TshCoordinateSystem.UNICODE_CODE_POINT -> codePoints
                    else -> return null
                }
                if (current == target) return metric(utf16, utf8, codePoints, to)

                val cp = Character.codePointAt(text, index)
                val units = Character.charCount(cp).toLong()
                val byteCount = when {
                    cp <= 0x7f -> 1L
                    cp <= 0x7ff -> 2L
                    cp <= 0xffff -> 3L
                    else -> 4L
                }
                val next = when (from) {
                    TshCoordinateSystem.UTF16_CODE_UNIT -> safeAdd(utf16, units)
                    TshCoordinateSystem.UTF8_BYTE -> safeAdd(utf8, byteCount)
                    TshCoordinateSystem.UNICODE_CODE_POINT -> safeAdd(codePoints, 1L)
                    else -> return null
                }
                if (target < next) return null
                utf16 = safeAdd(utf16, units)
                utf8 = safeAdd(utf8, byteCount)
                codePoints = safeAdd(codePoints, 1L)
                index += units.toInt()
            }
            if (utf16 != end) return null
        }
        return if (when (from) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> utf16
            TshCoordinateSystem.UTF8_BYTE -> utf8
            TshCoordinateSystem.UNICODE_CODE_POINT -> codePoints
            else -> return null
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

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.COORDINATE,
        message = message
    )))

    companion object {
        private const val DEFAULT_CHUNK_UTF16 = 32 * 1024
        private const val SCAN_CHUNK_UTF16 = 32 * 1024
        private const val DEFAULT_CHECKPOINT_STRIDE = 4 * 1024

        /**
         * Builds an index while retaining an independent lease. The caller keeps ownership of
         * [lease] and may close it immediately after this method returns.
         */
        @JvmStatic
        fun build(
            lease: TshTextSnapshotLease,
            chunkSizeUtf16: Int = DEFAULT_CHUNK_UTF16,
            checkpointStrideUtf16: Int = DEFAULT_CHECKPOINT_STRIDE,
            memoryBudget: TshMemoryBudget? = null
        ): TshResult<TshSparseCoordinateIndex> {
            if (lease.isClosed) return failure(
                "TSH-COORD-INDEX-011", "Cannot build an index from a closed Snapshot Lease"
            )
            if (chunkSizeUtf16 !in 256..(256 * 1024) ||
                checkpointStrideUtf16 !in 256..(1024 * 1024)) {
                return failure("TSH-COORD-INDEX-007", "Chunk/stride settings exceed safe bounds")
            }

            val retained = when (val result = lease.retain()) {
                is TshResult.Success -> result.value
                is TshResult.Failure -> return TshResult.Failure(result.diagnostics)
            }
            var transferred = false
            var reservation: TshMemoryReservation? = null
            try {
                if (retained.isClosed) return failure(
                    "TSH-COORD-INDEX-012", "Snapshot Lease retention returned a closed lease"
                )
                val snapshot = retained.snapshot
                if (snapshot.documentId.isBlank() || snapshot.version < 0L || snapshot.lengthUtf16 < 0L) {
                    return failure("TSH-COORD-INDEX-006", "Snapshot identity, version or length is invalid")
                }

                if (memoryBudget != null) {
                    // Upper-bound the sparse checkpoint list before allocating it (64 bytes/entry
                    // is an estimate, not a JVM object-layout measurement).
                    val checkpointCapacity = snapshot.lengthUtf16 / checkpointStrideUtf16.toLong() + 2L
                    if (checkpointCapacity > Int.MAX_VALUE.toLong() ||
                        checkpointCapacity > (Long.MAX_VALUE - 64L) / 64L) {
                        return failure("TSH-COORD-INDEX-013", "Sparse coordinate index checkpoint estimate exceeds supported capacity")
                    }
                    when (val reserved = memoryBudget.reserve(
                        TshMemoryCategory.COORDINATE_INDEX,
                        checkpointCapacity * 64L + 64L,
                        "coordinate-index:" + snapshot.documentId + ":" + snapshot.version
                    )) {
                        is TshResult.Success -> reservation = reserved.value
                        is TshResult.Failure -> return TshResult.Failure(reserved.diagnostics)
                    }
                }

                val points = ArrayList<Checkpoint>()
                var utf16 = 0L
                var utf8 = 0L
                var codePoints = 0L
                var nextCheckpoint = checkpointStrideUtf16.toLong()
                points += Checkpoint(0L, 0L, 0L)
                while (utf16 < snapshot.lengthUtf16) {
                    var end = minOf(snapshot.lengthUtf16, safeAdd(utf16, chunkSizeUtf16.toLong()))
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
                        utf16 = safeAdd(utf16, units.toLong())
                        utf8 = safeAdd(utf8, when {
                            cp <= 0x7f -> 1L
                            cp <= 0x7ff -> 2L
                            cp <= 0xffff -> 3L
                            else -> 4L
                        })
                        codePoints = safeAdd(codePoints, 1L)
                        i += units
                        if (utf16 >= nextCheckpoint) {
                            points += Checkpoint(utf16, utf8, codePoints)
                            nextCheckpoint = safeAdd(utf16, checkpointStrideUtf16.toLong())
                        }
                    }
                    check(utf16 == end) { "Snapshot chunk boundary changed during index build" }
                }
                if (points.last().utf16 != utf16) points += Checkpoint(utf16, utf8, codePoints)
                val index = TshSparseCoordinateIndex(
                    snapshot.documentId, snapshot.version, snapshot.lengthUtf16,
                    points, retained, snapshot, checkpointStrideUtf16, reservation
                )
                transferred = true
                return TshResult.Success(index)
            } catch (error: Exception) {
                return failure("TSH-COORD-INDEX-009", "Cannot build sparse coordinate index: ${error.message}", error)
            } finally {
                if (!transferred) {
                    retained.close()
                    reservation?.close()
                }
            }
        }

        private fun safeAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

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
