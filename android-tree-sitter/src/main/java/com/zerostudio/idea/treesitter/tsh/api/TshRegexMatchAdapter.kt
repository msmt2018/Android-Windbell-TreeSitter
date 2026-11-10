package com.zerostudio.idea.treesitter.tsh.api

import java.security.MessageDigest

/**
 * One Regex capture group, expressed in absolute document UTF-16 code-unit coordinates.
 *
 * A null range represents an unmatched optional group. The executor must convert its local
 * Oniguruma byte offsets to absolute UTF-16 offsets before publishing this record.
 */
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

/** One complete Regex match and the capture groups returned by the Regex engine. */
data class TshRegexMatchRecord(
    val range: TshSourceRange,
    val captureGroups: List<TshRegexCaptureGroup> = emptyList()
)

/**
 * Source-neutral Regex execution output. This is an adapter boundary, not an executor:
 * Oniguruma execution, window ownership, timeout and cancellation remain the executor's job.
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
 * All ranges must already be absolute UTF-16 code-unit offsets. This adapter deliberately does
 * not access text, invoke Oniguruma, infer window equivalence, or manufacture a Tree-sitter
 * revision. The MatchMerger applies the shared count/memory budget and stale-input rules.
 */
object TshRegexMatchAdapter {
    @JvmStatic
    @JvmOverloads
    fun adapt(
        result: TshRegexResult,
        priority: Int = 0,
        configRevision: String? = null,
        budget: TshMatchMergeBudget = TshMatchMergeBudget()
    ): TshResult<TshMatchSet> {
        if (result.documentLengthUtf16 < 0L) {
            return failure("TSH-REGEX-MATCH-001", "Document length must be non-negative")
        }

        val matches = ArrayList<TshMatch>(result.matches.size)
        for ((matchIndex, record) in result.matches.withIndex()) {
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
                captures += TshCaptureValue(group.name, range)
            }

            val matchRange = normalizeVersion(record.range, result.documentVersion)
            val normalizedCaptures = captures.map { capture ->
                capture.copy(range = normalizeVersion(capture.range, result.documentVersion))
            }
            val id = stableId(
                result.documentId.value,
                result.documentVersion.toString(),
                result.regexId,
                result.regexRevision,
                result.pipelineRevision,
                matchIndex.toString(),
                matchRange.start.offset.toString(),
                matchRange.end.offset.toString(),
                normalizedCaptures.joinToString("|") {
                    "${it.name.length}:${it.name}:${it.range.start.offset}:${it.range.end.offset}"
                }
            )
            val provenance = TshMatchProvenance(
                source = TshMatchSource.REGEX,
                pipelineRevision = result.pipelineRevision,
                regexId = result.regexId,
                regexRevision = result.regexRevision
            )
            matches += TshMatch(
                id = TshMatchId(id),
                documentId = result.documentId,
                documentVersion = result.documentVersion,
                source = TshMatchSource.REGEX,
                pipelineRevision = result.pipelineRevision,
                ruleId = TshRuleId(result.regexId),
                captureName = "match",
                range = matchRange,
                priority = priority,
                captures = normalizedCaptures,
                provenance = provenance
            )
        }

        return TshMatchMerger.merge(
            documentId = result.documentId,
            documentVersion = result.documentVersion,
            treeSitterRevision = null,
            regexRevision = result.pipelineRevision,
            configRevision = configRevision,
            input = matches,
            completeness = result.completeness,
            diagnostics = result.diagnostics,
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
