package com.zerostudio.idea.treesitter.tsh.api

import java.security.MessageDigest

/**
 * One Tree-sitter query result converted to canonical TSH matches.
 *
 * The adapter never reads or materializes document text. A snapshot-scoped sparse coordinate
 * index performs source-byte to UTF-16 conversion; the caller owns that index and its lifetime.
 */
data class TshTreeSitterMatchBatch(
    val documentId: TshDocumentId,
    val documentVersion: Long,
    val treeRevision: Long,
    val pipelineRevision: String,
    val queryId: String,
    val queryRevision: String,
    val grammarFingerprint: String,
    val matches: List<TshMatch>,
    val completeness: TshMatchCompleteness,
    val diagnostics: List<TshDiagnostic>
) {
    init {
        require(documentVersion >= 0L)
        require(treeRevision >= 0L)
        require(pipelineRevision.isNotBlank())
        require(queryId.isNotBlank())
        require(queryRevision.isNotBlank())
        require(grammarFingerprint.isNotBlank())
        require(matches.all {
            it.documentId.value == documentId.value &&
                it.documentVersion == documentVersion &&
                it.pipelineRevision == pipelineRevision &&
                it.source == TshMatchSource.TREE_SITTER_ATS
        })
    }
}

/**
 * Bridges the native Tree-sitter capture model to the shared two-pipeline Match model.
 *
 * Native byte offsets are never treated as UTF-16 offsets. UTF-8 trees use the sparse index's
 * UTF-8 conversion; UTF-16 trees require even byte offsets and are checked against surrogate
 * boundaries. No String/CharSequence flattening occurs here.
 */
object TshTreeSitterMatchAdapter {
    @JvmStatic
    fun adapt(
        query: TshCompiledQuery,
        tree: TshSyntaxTreeSnapshot,
        result: TshQueryResult,
        coordinates: TshSparseCoordinateIndex,
        pipelineRevision: String,
        priority: Int = 0
    ): TshResult<TshTreeSitterMatchBatch> {
        if (pipelineRevision.isBlank()) {
            return failure("TSH-MATCH-ADAPTER-001", "Pipeline revision must not be blank")
        }
        if (!query.isCompatibleWith(tree.grammar)) {
            return failure("TSH-MATCH-ADAPTER-002", "Compiled query Grammar does not match the syntax tree")
        }
        if (result.queryId != query.queryId || result.queryRevision != query.revision) {
            return failure("TSH-MATCH-ADAPTER-003", "Query result belongs to a different query revision")
        }
        if (!result.isCurrentFor(tree)) {
            return failure("TSH-MATCH-ADAPTER-004", "Query result is stale for the supplied syntax tree")
        }
        if (result.inputEncoding != tree.inputEncoding) {
            return failure("TSH-MATCH-ADAPTER-005", "Query result byte encoding does not match the syntax tree")
        }
        if (coordinates.documentId != tree.documentId ||
            coordinates.documentVersion != tree.documentVersion) {
            return failure("TSH-MATCH-ADAPTER-006", "Coordinate index belongs to a different document snapshot")
        }

        val matches = ArrayList<TshMatch>(result.captures.size)
        val occurrenceByIdentity = HashMap<String, Int>(result.captures.size)
        for ((captureIndex, capture) in result.captures.withIndex()) {
            val bytes = capture.byteRange
            if (bytes.startByte < tree.rootByteRange.startByte ||
                bytes.endByte > tree.rootByteRange.endByte) {
                return failure(
                    "TSH-MATCH-ADAPTER-007",
                    "Capture $captureIndex lies outside the syntax tree root range"
                )
            }

            val startUtf16 = convertOffset(bytes.startByte, tree.inputEncoding, coordinates)
                ?: return failure(
                    "TSH-MATCH-ADAPTER-008",
                    "Capture $captureIndex start is not a valid source character boundary"
                )
            val endUtf16 = convertOffset(bytes.endByte, tree.inputEncoding, coordinates)
                ?: return failure(
                    "TSH-MATCH-ADAPTER-009",
                    "Capture $captureIndex end is not a valid source character boundary"
                )
            if (endUtf16 < startUtf16) {
                return failure("TSH-MATCH-ADAPTER-010", "Converted capture range is reversed")
            }

            val canonicalRange = TshSourceRange(
                TshSourcePosition(startUtf16, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                TshSourcePosition(endUtf16, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                documentVersion = tree.documentVersion
            )
            val rawRange = if (tree.inputEncoding == TshTreeSitterInputEncoding.UTF8) {
                TshSourceRange(
                    TshSourcePosition(bytes.startByte, coordinateSystem = TshCoordinateSystem.UTF8_BYTE),
                    TshSourcePosition(bytes.endByte, coordinateSystem = TshCoordinateSystem.UTF8_BYTE),
                    documentVersion = tree.documentVersion
                )
            } else null

            val ruleId = TshRuleId("${query.queryId}#pattern-${capture.patternIndex}")
            val identity = "${capture.patternIndex}:${capture.name}:${capture.nodeId}:${bytes.startByte}:${bytes.endByte}"
            val occurrence = occurrenceByIdentity[identity] ?: 0
            occurrenceByIdentity[identity] = occurrence + 1
            val matchId = stableId(
                tree.documentId, tree.documentVersion.toString(), query.queryId, query.revision,
                pipelineRevision, identity, occurrence.toString()
            )
            val provenance = TshMatchProvenance(
                source = TshMatchSource.TREE_SITTER_ATS,
                pipelineRevision = pipelineRevision,
                grammarFingerprint = tree.grammar.fingerprint,
                queryId = query.queryId,
                queryRevision = query.revision
            )
            matches += TshMatch(
                id = TshMatchId(matchId),
                documentId = TshDocumentId(tree.documentId),
                documentVersion = tree.documentVersion,
                source = TshMatchSource.TREE_SITTER_ATS,
                pipelineRevision = pipelineRevision,
                ruleId = ruleId,
                patternIndex = capture.patternIndex,
                captureName = capture.name,
                range = canonicalRange,
                rawRange = rawRange,
                priority = priority,
                captures = listOf(TshCaptureValue(capture.name, canonicalRange)),
                provenance = provenance
            )
        }

        // Native cursor order is not part of the public API. Normalize it before publication.
        matches.sortWith(
            compareBy<TshMatch> { it.range.start.offset }
                .thenByDescending { it.range.end.offset }
                .thenBy { it.patternIndex ?: -1 }
                .thenBy { it.captureName.orEmpty() }
                .thenBy { it.id.value }
        )
        return TshResult.Success(
            TshTreeSitterMatchBatch(
                documentId = TshDocumentId(tree.documentId),
                documentVersion = tree.documentVersion,
                treeRevision = tree.treeRevision,
                pipelineRevision = pipelineRevision,
                queryId = query.queryId,
                queryRevision = query.revision,
                grammarFingerprint = tree.grammar.fingerprint,
                matches = matches,
                completeness = if (result.isComplete) TshMatchCompleteness.COMPLETE
                    else TshMatchCompleteness.TRUNCATED,
                diagnostics = emptyList()
            )
        )
    }

    private fun convertOffset(
        byteOffset: Long,
        encoding: TshTreeSitterInputEncoding,
        index: TshSparseCoordinateIndex
    ): Long? {
        return when (encoding) {
            TshTreeSitterInputEncoding.UTF8 -> when (val converted = index.convert(
                byteOffset, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
            )) {
                is TshResult.Success -> converted.value
                is TshResult.Failure -> null
            }
            TshTreeSitterInputEncoding.UTF16_LE,
            TshTreeSitterInputEncoding.UTF16_BE -> {
                if ((byteOffset and 1L) != 0L) return null
                val utf16 = byteOffset / 2L
                when (val checked = index.convert(
                    utf16, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UTF16_CODE_UNIT
                )) {
                    is TshResult.Success -> checked.value
                    is TshResult.Failure -> null
                }
            }
        }
    }

    private fun stableId(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            // Length-prefix each component to avoid ambiguous concatenations.
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return "tsm:" + digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.SEMANTIC,
        message = message
    )))
}
