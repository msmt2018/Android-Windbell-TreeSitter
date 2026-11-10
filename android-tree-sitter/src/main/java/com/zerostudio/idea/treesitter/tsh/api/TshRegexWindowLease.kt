package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicBoolean

/** Finite-window contract for Regex engines that require contiguous input. */
data class TshRegexWindowConstraints(
    val coreWindowUtf16: Int = 64 * 1024,
    val maxWindowUtf16: Int = 256 * 1024,
    /** Null means the pattern may match unbounded input and cannot be windowed safely. */
    val maxMatchLengthUtf16: Int? = null,
    val maxLookbehindUtf16: Int = 0,
    val maxLookaheadUtf16: Int = 0
) {
    init {
        require(coreWindowUtf16 in 256..(1024 * 1024))
        require(maxWindowUtf16 in 256..(1024 * 1024))
        require(maxMatchLengthUtf16 == null || maxMatchLengthUtf16 > 0)
        require(maxLookbehindUtf16 >= 0)
        require(maxLookaheadUtf16 >= 0)
        val matchLength = maxMatchLengthUtf16
        if (matchLength != null) {
            val required = coreWindowUtf16.toLong() +
                maxLookbehindUtf16.toLong() + matchLength.toLong() +
                maxLookaheadUtf16.toLong()
            require(required <= maxWindowUtf16.toLong()) {
                "Configured core and Regex context exceed maxWindowUtf16"
            }
        }
    }
}

/**
 * Owns one immutable UTF-16 source slice and its optional memory reservation.
 * The caller must close it when the Regex operation ends.
 */
class TshRegexWindowLease private constructor(
    val documentId: String,
    val documentVersion: Long,
    val documentLengthUtf16: Long,
    val windowStartUtf16: Long,
    val windowEndUtf16: Long,
    val coreStartUtf16: Long,
    val coreEndUtf16: Long,
    private var content: String?,
    private val reservation: TshMemoryReservation?
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val text: CharSequence
        get() {
            check(!closed.get()) { "Regex window lease is closed" }
            return checkNotNull(content) { "Regex window content has been released" }
        }

    val lengthUtf16: Int get() = text.length
    val isDocumentStart: Boolean get() = windowStartUtf16 == 0L
    val isDocumentEnd: Boolean get() = windowEndUtf16 == documentLengthUtf16
    val coreStartInWindow: Int get() = (coreStartUtf16 - windowStartUtf16).toInt()
    val coreEndInWindow: Int get() = (coreEndUtf16 - windowStartUtf16).toInt()

    /**
     * Emit a match only when its absolute start belongs to this core interval. The final document
     * boundary is included by the last window so zero-width EOF matches are not lost.
     */
    fun ownsMatchStart(absoluteStartUtf16: Long): Boolean =
        !closed.get() && absoluteStartUtf16 >= coreStartUtf16 &&
            (absoluteStartUtf16 < coreEndUtf16 ||
                (isDocumentEnd && coreEndUtf16 == documentLengthUtf16 &&
                    absoluteStartUtf16 == documentLengthUtf16))

    fun absoluteUtf16Offset(windowOffsetUtf16: Int): Long? {
        if (closed.get() || windowOffsetUtf16 < 0 || windowOffsetUtf16 > lengthUtf16) return null
        return windowStartUtf16 + windowOffsetUtf16.toLong()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            content = null
            reservation?.close()
        }
    }

    companion object {
        private const val FIXED_ESTIMATE_BYTES = 64L

        /**
         * Acquires one bounded contiguous window from a retained immutable snapshot.
         * Unbounded patterns are rejected instead of silently flattening the whole document.
         */
        @JvmStatic
        @JvmOverloads
        fun acquire(
            lease: TshTextSnapshotLease,
            coreStartUtf16: Long,
            coreLengthUtf16: Int,
            constraints: TshRegexWindowConstraints,
            memoryBudget: TshMemoryBudget? = null
        ): TshResult<TshRegexWindowLease> {
            if (lease.isClosed) return failure(
                "TSH-REGEX-WINDOW-001", "Cannot acquire a Regex window from a closed Snapshot Lease"
            )
            val maxMatchLength = constraints.maxMatchLengthUtf16
                ?: return failure(
                    "TSH-REGEX-WINDOW-002",
                    "Regex windowing requires a finite maximum match length; unbounded patterns must be rejected or handled by a separately bounded strategy"
                )
            if (coreLengthUtf16 <= 0 || coreLengthUtf16 > constraints.coreWindowUtf16) {
                return failure("TSH-REGEX-WINDOW-003", "Requested core length is outside the configured core window")
            }

            val retained = when (val result = lease.retain()) {
                is TshResult.Success -> result.value
                is TshResult.Failure -> return TshResult.Failure(result.diagnostics)
            }
            var transferred = false
            var reservation: TshMemoryReservation? = null
            try {
                if (retained.isClosed) return failure(
                    "TSH-REGEX-WINDOW-004", "Snapshot Lease retention returned a closed lease"
                )
                val snapshot = retained.snapshot
                val length = snapshot.lengthUtf16
                if (snapshot.documentId.isBlank() || snapshot.version < 0L || length < 0L) {
                    return failure("TSH-REGEX-WINDOW-005", "Snapshot identity, version, or length is invalid")
                }
                if (coreStartUtf16 < 0L || coreStartUtf16 >= length) {
                    return failure("TSH-REGEX-WINDOW-006", "Core start is outside the source snapshot")
                }
                val coreEnd = minOf(length, safeAdd(coreStartUtf16, coreLengthUtf16.toLong()))
                if (splitsSurrogate(snapshot, coreStartUtf16) || splitsSurrogate(snapshot, coreEnd)) {
                    return failure(
                        "TSH-REGEX-WINDOW-007",
                        "Regex core boundaries must not split a UTF-16 surrogate pair"
                    )
                }

                var windowStart = maxOf(0L, coreStartUtf16 - constraints.maxLookbehindUtf16.toLong())
                var windowEnd = minOf(
                    length,
                    safeAdd(coreEnd, maxMatchLength.toLong() - 1L + constraints.maxLookaheadUtf16.toLong())
                )
                if (splitsSurrogate(snapshot, windowStart)) windowStart--
                if (splitsSurrogate(snapshot, windowEnd)) windowEnd++
                val actualLength = windowEnd - windowStart
                if (actualLength <= 0L || actualLength > constraints.maxWindowUtf16.toLong()) {
                    return failure(
                        "TSH-REGEX-WINDOW-008",
                        "Required Regex window ($actualLength UTF-16 units) exceeds maxWindowUtf16 (${constraints.maxWindowUtf16})"
                    )
                }
                if (actualLength > Int.MAX_VALUE.toLong() ||
                    actualLength > (Long.MAX_VALUE - FIXED_ESTIMATE_BYTES) / 4L) {
                    return failure("TSH-REGEX-WINDOW-009", "Regex window size exceeds supported capacity")
                }

                if (memoryBudget != null) {
                    when (val reserved = memoryBudget.reserve(
                        TshMemoryCategory.REGEX_WINDOW,
                        actualLength * 4L + FIXED_ESTIMATE_BYTES,
                        "regex-window:" + snapshot.documentId + ":" + snapshot.version + ":" + coreStartUtf16
                    )) {
                        is TshResult.Success -> reservation = reserved.value
                        is TshResult.Failure -> return TshResult.Failure(reserved.diagnostics)
                    }
                }

                val source = snapshot.readUtf16(windowStart, windowEnd)
                if (source.length.toLong() != actualLength || !TshUnicode.isWellFormedUtf16(source)) {
                    return failure(
                        "TSH-REGEX-WINDOW-010",
                        "Snapshot returned an inconsistent or malformed UTF-16 Regex window"
                    )
                }
                val window = TshRegexWindowLease(
                    snapshot.documentId, snapshot.version, length, windowStart, windowEnd,
                    coreStartUtf16, coreEnd, source.toString(), reservation
                )
                transferred = true
                return TshResult.Success(window)
            } catch (error: Exception) {
                return failure("TSH-REGEX-WINDOW-011", "Cannot acquire Regex window: ${error.message}", error)
            } finally {
                try {
                    retained.close()
                } finally {
                    if (!transferred) reservation?.close()
                }
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
