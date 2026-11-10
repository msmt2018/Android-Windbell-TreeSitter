package com.zerostudio.idea.treesitter.tsh.api

import java.security.MessageDigest

/** One Regex capture group in absolute document UTF-16 code-unit coordinates. */
data class TshRegexCaptureGroup(
    val groupIndex: Int,
    val name: String,
    val range: TshSourceRange?
) {
    init {
        require(groupIndex >= 0)
        require(name.isNotBlank())
    }
}

/** One complete Regex match; null capture ranges represent unmatched optional groups. */
data class TshRegexMatchRecord(
    val range: TshSourceRange,
    val captureGroups: List<TshRegexCaptureGroup> = emptyList()
)

/**
 * Regex execution output boundary. This is not an executor: Oniguruma execution, window
 * ownership, timeout and cancellation remain the executor's responsibility.
 */
data class TshRegexResult(
    val documentId: TshDocumentId,
    val documentVersion: Long,
    val documentLengthUtf16: Long,
    val regexId: String,
    val regexRevision: String,
    val pipelineRevision: String,
    val matches: List<TshRegexMatchRecord>,
    val completeness: TshMatchCompleteness = TshMatchCompleteness.COMPLETE,
    val diagnostics: List<TshDiagnostic> = emptyList()
) {
    init {
        require(documentVersion >= 0L)
        require(documentLengthUtf16 >= 0L)
        require(regexId.isNotBlank())
        require(regexRevision.isNotBlank())
        require(pipelineRevision.isNotBlank())
    }
}

/**
 * Converts independent Regex output into the canonical unified MatchSet.
 *
 * Ranges must already be absolute UTF-16 offsets. The adapter does not read text, invoke
 * Oniguruma, infer cross-window equivalence, or create a Tree-sitter revision. It bounds its
 * own retained Match/capture working set before constructing the shared MatchMerger input.
 */
object TshRegexMatchAdapter {
    private const val MAX_DIAGNOSTICS = 4096

    @JvmStatic
    @JvmOverloads
    fun adapt(
        result: TshRegexResult,
        priority: Int = 0,
        configRevision: String? = null,
        budget: TshMatchMergeBudget = TshMatchMergeBudget()
    ): TshResult<TshMatchSet> {
        val matches = ArrayList<TshMatch>(minOf(result.matches.size, budget.maxMatches))
        var estimatedBytes = 0L
        var truncatedByAdapter = false

        for ((matchIndex, record) in result.matches.withIndex()) {
            if (matches.size >= budget.maxMatches) {
                truncatedByAdapter = true
                break
            }
            val remainingBytes = budget.maxEstimatedBytes - estimatedBytes
            val minimumRecordBytes = saturatedAdd(
                256L,
                saturatedMultiply(record.captureGroups.size.toLong(), 96L)
            )
            if (minimumRecordBytes > remainingBytes) {
                truncatedByAdapter = true
                break
            }
            if (!isValidDocumentRange(record.range, result)) {
                return failure(
                    "TSH-REGEX-MATCH-002",
                    "Regex match $matchIndex is outside the document or is not a canonical UTF-16 range"
                )
            }

            val seenGroups = HashSet<Int>(record.captureGroups.size)
            val captures = ArrayList<TshCaptureValue>(record.captureGroups.size)
            for (group in record.captureGroups) {
                if (!seenGroups.add(group.groupIndex)) {
                    return failure(
                        "TSH-REGEX-MATCH-003",
                        "Regex match $matchIndex contains duplicate capture group index ${group.groupIndex}"
                    )
                }
                val range = group.range ?: continue
                if (!isValidDocumentRange(range, result) ||
                    range.start.offset < record.range.start.offset ||
                    range.end.offset > record.range.end.offset) {
                    return failure(
                        "TSH-REGEX-MATCH-004",
                        "Regex capture group ${group.groupIndex} of match $matchIndex is outside its full match"
                    )
                }
                captures += TshCaptureValue(group.name, normalizeVersion(range, result.documentVersion))
            }

            val matchRange = normalizeVersion(record.range, result.documentVersion)
            val id = stableId(
                result.documentId.value,
                result.documentVersion.toString(),
                result.regexId,
                result.regexRevision,
                result.pipelineRevision,
                matchIndex.toString(),
                matchRange.start.offset.toString(),
                matchRange.end.offset.toString(),
                captures.joinToString("|") {
                    "${it.name.length}:${it.name}:${it.range.start.offset}:${it.range.end.offset}"
                }
            )
            val match = TshMatch(
                id = TshMatchId(id),
                documentId = result.documentId,
                documentVersion = result.documentVersion,
                source = TshMatchSource.REGEX,
                pipelineRevision = result.pipelineRevision,
                ruleId = TshRuleId(result.regexId),
                captureName = "match",
                range = matchRange,
                priority = priority,
                captures = captures,
                provenance = TshMatchProvenance(
                    source = TshMatchSource.REGEX,
                    pipelineRevision = result.pipelineRevision,
                    regexId = result.regexId,
                    regexRevision = result.regexRevision
                )
            )

            val matchBytes = estimateMatchBytes(match)
            if (matches.size >= budget.maxMatches ||
                matchBytes > budget.maxEstimatedBytes - estimatedBytes) {
                truncatedByAdapter = true
                break
            }
            matches += match
            estimatedBytes = saturatedAdd(estimatedBytes, matchBytes)
        }

        val diagnostics = ArrayList<TshDiagnostic>(minOf(result.diagnostics.size + 1, MAX_DIAGNOSTICS))
        diagnostics.addAll(result.diagnostics.take(MAX_DIAGNOSTICS - 1))
        if (truncatedByAdapter) {
            diagnostics += TshDiagnostic(
                code = "TSH-REGEX-MATCH-005",
                severity = TshDiagnosticSeverity.WARNING,
                stage = TshDiagnosticStage.MEMORY,
                message = "Regex Match adaptation stopped at its configured count or estimated-memory budget"
            )
        } else if (result.diagnostics.size > MAX_DIAGNOSTICS - 1) {
            diagnostics += TshDiagnostic(
                code = "TSH-REGEX-MATCH-006",
                severity = TshDiagnosticSeverity.WARNING,
                stage = TshDiagnosticStage.MEMORY,
                message = "Additional Regex diagnostics were omitted because the diagnostic budget was reached"
            )
        }

        val completeness = if (truncatedByAdapter) TshMatchCompleteness.TRUNCATED
            else result.completeness

        return TshMatchMerger.merge(
            documentId = result.documentId,
            documentVersion = result.documentVersion,
            treeSitterRevision = null,
            regexRevision = result.pipelineRevision,
            configRevision = configRevision,
            input = matches,
            completeness = completeness,
            diagnostics = diagnostics,
            budget = budget
        )
    }

    private fun isValidDocumentRange(range: TshSourceRange, result: TshRegexResult): Boolean {
        if (range.start.coordinateSystem != TshCoordinateSystem.UTF16_CODE_UNIT ||
            range.end.coordinateSystem != TshCoordinateSystem.UTF16_CODE_UNIT) return false
        if (range.start.offset > result.documentLengthUtf16 ||
            range.end.offset > result.documentLengthUtf16) return false
        return range.documentVersion == null || range.documentVersion == result.documentVersion
    }

    private fun normalizeVersion(range: TshSourceRange, version: Long) =
        if (range.documentVersion == version) range else range.copy(documentVersion = version)

    private fun estimateMatchBytes(match: TshMatch): Long {
        var total = 256L
        fun addString(value: String?) {
            if (value != null) total = saturatedAdd(
                total, saturatedAdd(saturatedMultiply(value.length.toLong(), 2L), 16L)
            )
        }
        addString(match.id.value)
        addString(match.documentId.value)
        addString(match.pipelineRevision)
        addString(match.ruleId.value)
        addString(match.captureName)
        addString(match.provenance.regexId)
        addString(match.provenance.regexRevision)
        total = saturatedAdd(total, saturatedMultiply(match.captures.size.toLong(), 96L))
        match.captures.forEach { addString(it.name); addString(it.text) }
        return total
    }

    private fun saturatedMultiply(left: Long, right: Long): Long =
        if (left == 0L || right == 0L) 0L
        else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun stableId(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return "trm:" + digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.REGEX,
        message = message
    )))
}
