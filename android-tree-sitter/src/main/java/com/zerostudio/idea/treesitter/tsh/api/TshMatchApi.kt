package com.zerostudio.idea.treesitter.tsh.api

@JvmInline
value class TshDocumentId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

@JvmInline
value class TshMatchId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class TshRuleId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class TshMatchSource { TREE_SITTER_ATS, REGEX }
enum class TshMatchCompleteness { COMPLETE, PARTIAL, TRUNCATED }

data class TshMatchProvenance(
    val source: TshMatchSource,
    val pipelineRevision: String,
    val grammarFingerprint: String? = null,
    val queryId: String? = null,
    val queryRevision: String? = null,
    val regexId: String? = null,
    val regexRevision: String? = null
) {
    init {
        require(pipelineRevision.isNotBlank())
        when (source) {
            TshMatchSource.TREE_SITTER_ATS -> {
                require(!grammarFingerprint.isNullOrBlank())
                require(!queryId.isNullOrBlank())
                require(regexId.isNullOrBlank()) { "Tree-sitter provenance must not contain a Regex id" }
            }
            TshMatchSource.REGEX -> {
                require(!regexId.isNullOrBlank())
                require(grammarFingerprint.isNullOrBlank() && queryId.isNullOrBlank()) {
                    "Regex provenance must not masquerade as Tree-sitter"
                }
            }
        }
    }
}

data class TshCaptureValue(
    val name: String,
    val range: TshSourceRange,
    val text: String? = null
) {
    init { require(name.isNotBlank()) }
}

data class TshScopeSegment(
    val id: String,
    val displayName: String
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class TshScopePath(val segments: List<TshScopeSegment>) {
    init { require(segments.isNotEmpty()) }
    override fun toString(): String = segments.joinToString(" ") { it.displayName }
}

data class TshScopeAssignment(
    val range: TshSourceRange,
    val path: TshScopePath,
    val captureName: String? = null
)

data class TshMatch(
    val id: TshMatchId,
    val documentId: TshDocumentId,
    val documentVersion: Long,
    val source: TshMatchSource,
    val pipelineRevision: String,
    val ruleId: TshRuleId,
    val patternIndex: Int? = null,
    val captureName: String? = null,
    /** Canonical UTF-16 half-open range used by style/layout consumers. */
    val range: TshSourceRange,
    /** Original UTF-8 byte or source-specific range, retained for provenance/debugging. */
    val rawRange: TshSourceRange? = null,
    val priority: Int = 0,
    val captures: List<TshCaptureValue> = emptyList(),
    val scopeAssignments: List<TshScopeAssignment> = emptyList(),
    val provenance: TshMatchProvenance
) {
    init {
        require(documentVersion >= 0L)
        require(pipelineRevision.isNotBlank())
        require(patternIndex == null || patternIndex >= 0)
        require(captureName == null || captureName.isNotBlank())
        require(range.start.coordinateSystem == TshCoordinateSystem.UTF16_CODE_UNIT) {
            "Canonical Match range must use UTF16_CODE_UNIT"
        }
        require(range.documentVersion == null || range.documentVersion == documentVersion)
        require(source == provenance.source)
        require(pipelineRevision == provenance.pipelineRevision)
    }
}

data class TshMatchSet(
    val documentId: TshDocumentId,
    val documentVersion: Long,
    val treeSitterRevision: String?,
    val regexRevision: String?,
    val configRevision: String?,
    val matches: List<TshMatch>,
    val completeness: TshMatchCompleteness,
    val diagnostics: List<TshDiagnostic>
) {
    init {
        require(documentVersion >= 0L)
        require(matches.all { it.documentId == documentId && it.documentVersion == documentVersion })
        require(matches.map { it.id }.distinct().size == matches.size)
        require(treeSitterRevision == null || treeSitterRevision.isNotBlank())
        require(regexRevision == null || regexRevision.isNotBlank())
        require(configRevision == null || configRevision.isNotBlank())
    }

    fun isCurrentFor(
        documentId: TshDocumentId,
        documentVersion: Long,
        treeSitterRevision: String?,
        regexRevision: String?,
        configRevision: String?
    ): Boolean =
        this.documentId == documentId &&
            this.documentVersion == documentVersion &&
            this.treeSitterRevision == treeSitterRevision &&
            this.regexRevision == regexRevision &&
            this.configRevision == configRevision
}

/** Deterministic merge point for the two execution pipelines; it does not perform styling or drawing. */
object TshMatchMerger {
    private data class DedupKey(
        val source: TshMatchSource,
        val ruleId: String,
        val captureName: String?,
        val start: Long,
        val end: Long,
        val pipelineRevision: String
    )

    fun merge(
        documentId: TshDocumentId,
        documentVersion: Long,
        treeSitterRevision: String?,
        regexRevision: String?,
        configRevision: String?,
        input: Collection<TshMatch>,
        completeness: TshMatchCompleteness = TshMatchCompleteness.COMPLETE,
        diagnostics: List<TshDiagnostic> = emptyList()
    ): TshResult<TshMatchSet> {
        if (documentVersion < 0L) return failure("TSH-MATCH-001", "Document version must be non-negative")
        val outputDiagnostics = diagnostics.toMutableList()
        val accepted = input.filter { match ->
            val expectedPipelineRevision = when (match.source) {
                TshMatchSource.TREE_SITTER_ATS -> treeSitterRevision
                TshMatchSource.REGEX -> regexRevision
            }
            val valid = match.documentId == documentId &&
                match.documentVersion == documentVersion &&
                (expectedPipelineRevision == null || match.pipelineRevision == expectedPipelineRevision)
            if (!valid) outputDiagnostics += TshDiagnostic(
                code = "TSH-MATCH-002",
                severity = TshDiagnosticSeverity.WARNING,
                stage = TshDiagnosticStage.SEMANTIC,
                message = "Stale or foreign Match was excluded from the merged result"
            )
            valid
        }
        val deduplicated = LinkedHashMap<DedupKey, TshMatch>()
        accepted.forEach { match ->
            val key = DedupKey(
                match.source, match.ruleId.value, match.captureName,
                match.range.start.offset, match.range.end.offset, match.pipelineRevision
            )
            deduplicated.putIfAbsent(key, match)
        }
        val sorted = deduplicated.values.sortedWith(
            compareBy<TshMatch> { it.range.start.offset }
                .thenByDescending { it.range.end.offset }
                .thenByDescending { it.priority }
                .thenBy { it.source.ordinal }
                .thenBy { it.ruleId.value }
                .thenBy { it.id.value }
        )
        val finalCompleteness = if (accepted.size != input.size && completeness == TshMatchCompleteness.COMPLETE) {
            TshMatchCompleteness.PARTIAL
        } else completeness
        val result = TshMatchSet(
            documentId, documentVersion, treeSitterRevision, regexRevision, configRevision,
            sorted, finalCompleteness, outputDiagnostics.toList()
        )
        return TshResult.Success(result, outputDiagnostics.toList())
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.SEMANTIC,
        message = message
    )))
}
