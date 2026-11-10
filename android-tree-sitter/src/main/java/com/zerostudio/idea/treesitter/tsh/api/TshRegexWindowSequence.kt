package com.zerostudio.idea.treesitter.tsh.api

/**
 * Sequentially partitions one immutable snapshot into non-overlapping Regex core intervals.
 *
 * Every returned window has its own contiguous context and memory reservation. The sequence owns
 * an independent Snapshot Lease so the source remains stable if the caller closes its lease.
 * Callers must close each returned window before or after requesting the next one.
 */
class TshRegexWindowSequence private constructor(
    private val snapshotLease: TshTextSnapshotLease,
    val constraints: TshRegexWindowConstraints,
    private val memoryBudget: TshMemoryBudget?,
    val documentId: String,
    val documentVersion: Long,
    val documentLengthUtf16: Long
) : AutoCloseable {
    private var nextCoreStartUtf16 = 0L
    private var closed = false

    val isClosed: Boolean
        @Synchronized get() = closed

    val nextCoreOffsetUtf16: Long
        @Synchronized get() = nextCoreStartUtf16

    /**
     * Returns the next window, or Success(null) after the final core interval.
     * A failed acquisition does not advance the sequence, so a caller may retry after pressure clears.
     */
    @Synchronized
    fun nextWindow(): TshResult<TshRegexWindowLease?> {
        if (closed || snapshotLease.isClosed) {
            return failure("TSH-REGEX-SEQUENCE-001", "Regex window sequence is closed")
        }
        if (nextCoreStartUtf16 >= documentLengthUtf16) {
            return TshResult.Success(null)
        }
        val snapshot = try {
            snapshotLease.snapshot
        } catch (error: Exception) {
            return failure("TSH-REGEX-SEQUENCE-007", "Cannot access pinned Regex source snapshot: ${error.message}", error)
        }
        var coreEnd = minOf(
            documentLengthUtf16,
            safeAdd(nextCoreStartUtf16, constraints.coreWindowUtf16.toLong())
        )
        try {
            if (splitsSurrogate(snapshot, coreEnd)) coreEnd--
        } catch (error: Exception) {
            return failure("TSH-REGEX-SEQUENCE-007", "Cannot inspect Regex core boundary: ${error.message}", error)
        }
        if (coreEnd <= nextCoreStartUtf16) {
            return failure(
                "TSH-REGEX-SEQUENCE-002",
                "Cannot advance Regex core without splitting a Unicode code point"
            )
        }
        val coreLength = (coreEnd - nextCoreStartUtf16).toInt()
        return when (val acquired = TshRegexWindowLease.acquire(
            snapshotLease,
            nextCoreStartUtf16,
            coreLength,
            constraints,
            memoryBudget
        )) {
            is TshResult.Failure -> TshResult.Failure(acquired.diagnostics)
            is TshResult.Success -> {
                nextCoreStartUtf16 = coreEnd
                TshResult.Success(acquired.value, acquired.diagnostics)
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        snapshotLease.close()
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun open(
            lease: TshTextSnapshotLease,
            constraints: TshRegexWindowConstraints,
            memoryBudget: TshMemoryBudget? = null
        ): TshResult<TshRegexWindowSequence> {
            if (lease.isClosed) return failure(
                "TSH-REGEX-SEQUENCE-003", "Cannot open a Regex window sequence from a closed Snapshot Lease"
            )
            if (constraints.maxMatchLengthUtf16 == null) return failure(
                "TSH-REGEX-WINDOW-002",
                "Regex window sequence requires a finite maximum match length"
            )
            val retained = when (val result = lease.retain()) {
                is TshResult.Success -> result.value
                is TshResult.Failure -> return TshResult.Failure(result.diagnostics)
            }
            var transferred = false
            try {
                if (retained.isClosed) return failure(
                    "TSH-REGEX-SEQUENCE-004", "Snapshot Lease retention returned a closed lease"
                )
                val snapshot = retained.snapshot
                if (snapshot.documentId.isBlank() || snapshot.version < 0L || snapshot.lengthUtf16 < 0L) {
                    return failure(
                        "TSH-REGEX-SEQUENCE-005",
                        "Snapshot identity, version, or length is invalid"
                    )
                }
                val sequence = TshRegexWindowSequence(
                    retained,
                    constraints,
                    memoryBudget,
                    snapshot.documentId,
                    snapshot.version,
                    snapshot.lengthUtf16
                )
                transferred = true
                return TshResult.Success(sequence)
            } catch (error: Exception) {
                return failure(
                    "TSH-REGEX-SEQUENCE-006",
                    "Cannot open Regex window sequence: ${error.message}",
                    error
                )
            } finally {
                if (!transferred) retained.close()
            }
        }

        private fun splitsSurrogate(snapshot: TshTextSnapshot, offset: Long): Boolean {
            if (offset <= 0L || offset >= snapshot.lengthUtf16) return false
            val pair = snapshot.readUtf16(offset - 1L, offset + 1L)
            return pair.length == 2 &&
                Character.isHighSurrogate(pair[0]) && Character.isLowSurrogate(pair[1])
        }

        private fun safeAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        private fun failure(code: String, message: String, cause: Throwable? = null) =
            TshResult.Failure(listOf(TshDiagnostic(
                code = code,
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.REGEX,
                message = message,
                cause = cause
            )))
    }
}
