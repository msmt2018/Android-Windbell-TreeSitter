package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicLong

/** One bounded Oniguruma window execution result. */
data class TshRegexWindowExecution(
    val matches: List<TshRegexMatchRecord>,
    val truncated: Boolean
)

/**
 * Independent Oniguruma Regex compiler/executor backed by an opaque Native handle.
 *
 * Window constraints are an explicit caller declaration, not a proof-producing Regex analyzer.
 * Callers must only provide finite bounds that are valid for the compiled pattern.
 */
class TshOnigurumaRegex private constructor(
    private val nativeHandle: AtomicLong,
    val regexId: String,
    val regexRevision: String,
    val pipelineRevision: String,
    val windowConstraints: TshRegexWindowConstraints
) : AutoCloseable {
    private val native = TshOnigurumaNative()

    val isClosed: Boolean get() = nativeHandle.get() == 0L

    /**
     * Executes only this lease's core interval while retaining its left/right context.
     * Returned ranges are absolute UTF-16 code-unit coordinates in the source document.
     */
    fun executeWindow(
        window: TshRegexWindowLease,
        maxMatches: Int = MAX_MATCHES_PER_WINDOW,
        maxCaptureGroups: Int = DEFAULT_MAX_CAPTURE_GROUPS
    ): TshResult<TshRegexWindowExecution> {
        val handle = nativeHandle.get()
        if (handle == 0L) return failure(
            "TSH-REGEX-NATIVE-001", "Regex handle is closed"
        )
        if (window.isClosed) return failure(
            "TSH-REGEX-NATIVE-002", "Regex window lease is closed"
        )
        if (maxMatches !in 1..MAX_MATCHES_PER_WINDOW ||
            maxCaptureGroups !in 1..MAX_CAPTURE_GROUPS) {
            return failure(
                "TSH-REGEX-NATIVE-003",
                "Regex execution limits exceed the supported bounded Native limits"
            )
        }

        val raw = try {
            native.nativeFindAll(
                handle,
                window.text.toString(),
                window.coreStartInWindow,
                window.coreEndInWindow,
                window.isDocumentStart,
                window.isDocumentEnd,
                maxMatches,
                maxCaptureGroups
            )
        } catch (error: Exception) {
            return failure("TSH-REGEX-NATIVE-004", "Oniguruma execution failed: ${error.message}", error)
        } ?: return failure("TSH-REGEX-NATIVE-005", "Oniguruma returned a null result")

        if (raw.size < 2 || raw[1] < 0L || raw[1] > maxMatches.toLong()) {
            return failure("TSH-REGEX-NATIVE-006", "Oniguruma returned a malformed result header")
        }
        val count = raw[1].toInt()
        var cursor = 2
        val matches = ArrayList<TshRegexMatchRecord>(count)
        repeat(count) { matchIndex ->
            if (cursor + 3 > raw.size) return failure(
                "TSH-REGEX-NATIVE-007", "Oniguruma result is truncated at match $matchIndex"
            )
            val localStart = raw[cursor++]
            val localEnd = raw[cursor++]
            val groupCountLong = raw[cursor++]
            if (localStart < 0L || localEnd < localStart ||
                localEnd > window.lengthUtf16.toLong() ||
                groupCountLong !in 1L..(maxCaptureGroups + 1).toLong()) {
                return failure(
                    "TSH-REGEX-NATIVE-008", "Oniguruma returned invalid match or capture coordinates"
                )
            }
            val groupCount = groupCountLong.toInt()
            if (cursor + groupCount * 2 > raw.size) return failure(
                "TSH-REGEX-NATIVE-009", "Oniguruma capture payload is truncated at match $matchIndex"
            )

            val absoluteStart = window.windowStartUtf16 + localStart
            val absoluteEnd = window.windowStartUtf16 + localEnd
            if (!window.ownsMatchRange(absoluteStart, absoluteEnd)) return failure(
                "TSH-REGEX-NATIVE-010",
                "Oniguruma match is outside its core ownership or splits a Unicode code point"
            )
            val matchRange = sourceRange(absoluteStart, absoluteEnd, window.documentVersion)
            val captures = ArrayList<TshRegexCaptureGroup>(maxOf(0, groupCount - 1))
            for (groupIndex in 0 until groupCount) {
                val groupStart = raw[cursor++]
                val groupEnd = raw[cursor++]
                if (groupStart == -1L && groupEnd == -1L) {
                    if (groupIndex > 0) captures += TshRegexCaptureGroup(groupIndex, "group_$groupIndex", null)
                    continue
                }
                if (groupStart < 0L || groupEnd < groupStart ||
                    groupEnd > window.lengthUtf16.toLong()) {
                    return failure(
                        "TSH-REGEX-NATIVE-011",
                        "Oniguruma returned an invalid capture group $groupIndex"
                    )
                }
                val absoluteGroupStart = window.windowStartUtf16 + groupStart
                val absoluteGroupEnd = window.windowStartUtf16 + groupEnd
                if (!window.isCodePointBoundary(groupStart.toInt()) ||
                    !window.isCodePointBoundary(groupEnd.toInt()) ||
                    absoluteGroupStart < absoluteStart || absoluteGroupEnd > absoluteEnd) {
                    return failure(
                        "TSH-REGEX-NATIVE-012",
                        "Oniguruma capture group $groupIndex is outside the match or splits a Unicode code point"
                    )
                }
                if (groupIndex > 0) {
                    captures += TshRegexCaptureGroup(
                        groupIndex,
                        "group_$groupIndex",
                        sourceRange(absoluteGroupStart, absoluteGroupEnd, window.documentVersion)
                    )
                }
            }
            matches += TshRegexMatchRecord(matchRange, captures)
        }
        if (cursor != raw.size) return failure(
            "TSH-REGEX-NATIVE-013", "Oniguruma returned unexpected trailing result data"
        )
        return TshResult.Success(TshRegexWindowExecution(matches, raw[0] != 0L))
    }

    /**
     * Runs a full sequential snapshot scan without flattening the document.
     * Each native invocation receives one bounded window; its lease is closed immediately after use.
     * The caller retains ownership of the sequence and must close it.
     */
    fun execute(
        sequence: TshRegexWindowSequence,
        maxMatches: Int = DEFAULT_MAX_MATCHES,
        maxCaptureGroups: Int = DEFAULT_MAX_CAPTURE_GROUPS,
        budget: TshMatchMergeBudget = TshMatchMergeBudget(maxMatches = maxMatches)
    ): TshResult<TshMatchSet> {
        if (isClosed) return failure("TSH-REGEX-NATIVE-001", "Regex handle is closed")
        if (sequence.isClosed) return failure("TSH-REGEX-NATIVE-014", "Regex window sequence is closed")
        if (sequence.nextCoreOffsetUtf16 != 0L) return failure(
            "TSH-REGEX-NATIVE-015", "Full Regex execution requires a sequence positioned at document offset zero"
        )
        if (sequence.constraints != windowConstraints) return failure(
            "TSH-REGEX-NATIVE-016", "Regex sequence constraints do not match the compiled Regex declaration"
        )
        if (maxMatches <= 0 || maxMatches > budget.maxMatches) return failure(
            "TSH-REGEX-NATIVE-017", "Regex result count must fit within the Match merge budget"
        )

        val records = ArrayList<TshRegexMatchRecord>(minOf(maxMatches, 4096))
        var truncated = false
        while (true) {
            val acquired = when (val next = sequence.nextWindow()) {
                is TshResult.Failure -> return TshResult.Failure(next.diagnostics)
                is TshResult.Success -> next.value
            } ?: break

            val isLastCore = acquired.coreEndUtf16 == sequence.documentLengthUtf16
            val remaining = maxMatches - records.size
            if (remaining <= 0) {
                acquired.close()
                truncated = !isLastCore
                if (truncated) break
                // No matches can be accepted from a zero-capacity execution; conservatively report
                // truncation because this final core has not been inspected.
                truncated = true
                break
            }

            val execution = try {
                executeWindow(acquired, minOf(remaining, MAX_MATCHES_PER_WINDOW), maxCaptureGroups)
            } finally {
                // The window content and its memory reservation are released even on JNI failure.
            }
            acquired.close()

            val windowResult = when (execution) {
                is TshResult.Failure -> return execution
                is TshResult.Success -> execution.value
            }
            records.addAll(windowResult.matches)
            if (windowResult.truncated) {
                truncated = true
                break
            }
            if (records.size >= maxMatches && !isLastCore) {
                truncated = true
                break
            }
            if (records.size >= maxMatches && isLastCore) {
                // The last core was fully searched and the Native result was not truncated.
                break
            }
        }

        val regexResult = TshRegexResult(
            documentId = TshDocumentId(sequence.documentId),
            documentVersion = sequence.documentVersion,
            documentLengthUtf16 = sequence.documentLengthUtf16,
            regexId = regexId,
            regexRevision = regexRevision,
            pipelineRevision = pipelineRevision,
            matches = records,
            completeness = if (truncated) TshMatchCompleteness.TRUNCATED else TshMatchCompleteness.COMPLETE
        )
        return TshRegexMatchAdapter.adapt(regexResult, budget = budget)
    }

    override fun close() {
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) native.nativeRelease(handle)
    }

    private fun sourceRange(start: Long, end: Long, version: Long) = TshSourceRange(
        TshSourcePosition(start, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
        TshSourcePosition(end, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
        version
    )

    companion object {
        private const val MAX_MATCHES_PER_WINDOW = 8192
        private const val MAX_CAPTURE_GROUPS = 256
        private const val DEFAULT_MAX_CAPTURE_GROUPS = 32
        private const val DEFAULT_MAX_MATCHES = 250_000

        @JvmStatic
        fun compile(
            pattern: String,
            regexId: String,
            regexRevision: String,
            pipelineRevision: String,
            windowConstraints: TshRegexWindowConstraints
        ): TshResult<TshOnigurumaRegex> {
            if (regexId.isBlank() || regexRevision.isBlank() || pipelineRevision.isBlank()) {
                return failure("TSH-REGEX-NATIVE-018", "Regex identity and revisions must not be blank")
            }
            if (windowConstraints.maxMatchLengthUtf16 == null) {
                return failure(
                    "TSH-REGEX-NATIVE-019",
                    "Oniguruma window execution requires a declared finite maximum match length"
                )
            }
            return try {
                val handle = TshOnigurumaNative().nativeCompile(pattern)
                if (handle == 0L) {
                    failure("TSH-REGEX-NATIVE-020", "Oniguruma could not compile the Regex pattern")
                } else {
                    TshResult.Success(
                        TshOnigurumaRegex(
                            AtomicLong(handle), regexId, regexRevision, pipelineRevision, windowConstraints
                        )
                    )
                }
            } catch (error: Exception) {
                failure("TSH-REGEX-NATIVE-021", "Oniguruma compilation failed: ${error.message}", error)
            }
        }

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

private class TshOnigurumaNative {
    init {
        com.zerostudio.idea.treesitter.TreeSitter.loadLibrary()
    }

    external fun nativeCompile(pattern: String): Long
    external fun nativeRelease(handle: Long): Boolean
    external fun nativeFindAll(
        handle: Long,
        windowText: String,
        coreStartUtf16: Int,
        coreEndUtf16: Int,
        documentStart: Boolean,
        documentEnd: Boolean,
        maxMatches: Int,
        maxCaptureGroups: Int
    ): LongArray?
}
