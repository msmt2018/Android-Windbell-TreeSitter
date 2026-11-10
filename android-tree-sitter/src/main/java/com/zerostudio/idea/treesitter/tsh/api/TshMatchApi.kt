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
                require(regexId.isNullOrBlank() && regexRevision.isNullOrBlank()) { "Tree-sitter provenance must not contain a Regex id" }
            }
            TshMatchSource.REGEX -> {
                require(!regexId.isNullOrBlank())
                require(grammarFingerprint.isNullOrBlank() && queryId.isNullOrBlank() &&
                    queryRevision.isNullOrBlank()) {
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
        require(treeSitterRevision == null || treeSitterRevision.isNotBlank())
        require(regexRevision == null || regexRevision.isNotBlank())
        require(configRevision == null || configRevision.isNotBlank())

        // Validate all MatchSet invariants in one pass. The previous filter/map/distinct chain
        // created several full-size temporary collections for large result sets.
        val ids = HashSet<TshMatchId>(matches.size)
        for (match in matches) {
            require(match.documentId == documentId && match.documentVersion == documentVersion)
            require(ids.add(match.id)) { "Match ids must be unique within a MatchSet" }
            when (match.source) {
                TshMatchSource.TREE_SITTER_ATS -> {
                    require(treeSitterRevision != null) {
                        "Tree-sitter matches require a Tree-sitter pipeline revision"
                    }
                    require(match.pipelineRevision == treeSitterRevision) {
                        "Tree-sitter Match pipeline revision does not match the MatchSet"
                    }
                }
                TshMatchSource.REGEX -> {
                    require(regexRevision != null) {
                        "Regex matches require a Regex pipeline revision"
                    }
                    require(match.pipelineRevision == regexRevision) {
                        "Regex Match pipeline revision does not match the MatchSet"
                    }
                }
            }
        }
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

/** Limits the merger's retained working set; estimates are not JVM heap measurements. */
data class TshMatchMergeBudget(
    val maxMatches: Int = 250_000,
    val maxEstimatedBytes: Long = 64L * 1024L * 1024L
) {
    init {
        require(maxMatches > 0)
        require(maxEstimatedBytes > 0L)
    }
}

/** Deterministic merge point for the two execution pipelines; it does not perform styling or drawing. */
object TshMatchMerger {
    private data class DedupKey(
        val source: TshMatchSource,
        val ruleId: String,
        val captureName: String?,
        val patternIndex: Int?,
        val start: Long,
        val end: Long,
        val rawRange: TshSourceRange?,
        val captures: List<TshCaptureValue>,
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
    ): TshResult<TshMatchSet> = merge(
        documentId, documentVersion, treeSitterRevision, regexRevision, configRevision,
        input, completeness, diagnostics, TshMatchMergeBudget()
    )

    /** Convenience overload for callers that only need to customize merge limits. */
    fun merge(
        documentId: TshDocumentId,
        documentVersion: Long,
        treeSitterRevision: String?,
        regexRevision: String?,
        configRevision: String?,
        input: Collection<TshMatch>,
        budget: TshMatchMergeBudget
    ): TshResult<TshMatchSet> = merge(
        documentId, documentVersion, treeSitterRevision, regexRevision, configRevision,
        input, TshMatchCompleteness.COMPLETE, emptyList(), budget
    )

    /** Explicit-budget overload; the legacy signature above remains source/binary compatible. */
    fun merge(
        documentId: TshDocumentId,
        documentVersion: Long,
        treeSitterRevision: String?,
        regexRevision: String?,
        configRevision: String?,
        input: Collection<TshMatch>,
        completeness: TshMatchCompleteness,
        diagnostics: List<TshDiagnostic>,
        budget: TshMatchMergeBudget
    ): TshResult<TshMatchSet> {
        if (documentVersion < 0L) return failure("TSH-MATCH-001", "Document version must be non-negative")
        // Keep only bounded diagnostics from caller and stale-input filtering. Reserve one slot
        // for the diagnostic that reports diagnostic truncation itself.
        val outputDiagnostics = ArrayList<TshDiagnostic>(minOf(diagnostics.size, MAX_DIAGNOSTICS))
        diagnostics.take(MAX_DIAGNOSTICS - 1).forEach(outputDiagnostics::add)
        var diagnosticsTruncated = diagnostics.size > MAX_DIAGNOSTICS - 1
        var staleMatches = 0
        var truncated = false
        var estimatedBytes = 0L
        val acceptedById = LinkedHashMap<TshMatchId, TshMatch>()
        val deduplicated = LinkedHashMap<DedupKey, TshMatch>()

        fun addDiagnostic(value: TshDiagnostic) {
            if (outputDiagnostics.size < MAX_DIAGNOSTICS - 1) {
                outputDiagnostics += value
            } else {
                diagnosticsTruncated = true
            }
        }

        for (match in input) {
            if (match.source == TshMatchSource.TREE_SITTER_ATS && treeSitterRevision == null) {
                return failure("TSH-MATCH-003", "Tree-sitter revision is required when Tree-sitter matches are supplied")
            }
            if (match.source == TshMatchSource.REGEX && regexRevision == null) {
                return failure("TSH-MATCH-004", "Regex revision is required when Regex matches are supplied")
            }
            val expectedPipelineRevision = when (match.source) {
                TshMatchSource.TREE_SITTER_ATS -> treeSitterRevision
                TshMatchSource.REGEX -> regexRevision
            }
            val valid = match.documentId == documentId &&
                match.documentVersion == documentVersion &&
                expectedPipelineRevision != null &&
                match.pipelineRevision == expectedPipelineRevision
            if (!valid) {
                staleMatches++
                addDiagnostic(TshDiagnostic(
                    code = "TSH-MATCH-002",
                    severity = TshDiagnosticSeverity.WARNING,
                    stage = TshDiagnosticStage.SEMANTIC,
                    message = "Stale or foreign Match was excluded from the merged result"
                ))
                continue
            }

            val existing = acceptedById[match.id]
            if (existing != null) {
                if (existing != match) {
                    return failure("TSH-MATCH-005", "A Match id identifies more than one distinct Match payload")
                }
                continue
            }

            val matchBytes = estimateMatchBytes(match)
            if (acceptedById.size >= budget.maxMatches ||
                matchBytes > budget.maxEstimatedBytes - estimatedBytes) {
                truncated = true
                break
            }

            acceptedById[match.id] = match
            val key = DedupKey(
                match.source, match.ruleId.value, match.captureName, match.patternIndex,
                match.range.start.offset, match.range.end.offset, match.rawRange,
                match.captures, match.pipelineRevision
            )
            deduplicated.putIfAbsent(key, match)
            estimatedBytes += matchBytes
        }

        if (truncated) addDiagnostic(TshDiagnostic(
            code = "TSH-MATCH-006",
            severity = TshDiagnosticSeverity.WARNING,
            stage = TshDiagnosticStage.MEMORY,
            message = "Match merge stopped at its configured count or estimated-memory budget; output is truncated"
        ))
        if (diagnosticsTruncated) {
            if (outputDiagnostics.size >= MAX_DIAGNOSTICS) outputDiagnostics.removeAt(outputDiagnostics.lastIndex)
            outputDiagnostics += TshDiagnostic(
                code = "TSH-MATCH-007",
                severity = TshDiagnosticSeverity.WARNING,
                stage = TshDiagnosticStage.MEMORY,
                message = "Additional diagnostics were omitted because the diagnostic budget was reached"
            )
        }

        val sorted = deduplicated.values.sortedWith(
            compareBy<TshMatch> { it.range.start.offset }
                .thenByDescending { it.range.end.offset }
                .thenByDescending { it.priority }
                .thenBy { it.source.ordinal }
                .thenBy { it.ruleId.value }
                .thenBy { it.id.value }
        )
        val finalCompleteness = when {
            truncated -> TshMatchCompleteness.TRUNCATED
            staleMatches > 0 && completeness == TshMatchCompleteness.COMPLETE -> TshMatchCompleteness.PARTIAL
            else -> completeness
        }
        val result = TshMatchSet(
            documentId, documentVersion, treeSitterRevision, regexRevision, configRevision,
            sorted, finalCompleteness, outputDiagnostics.toList()
        )
        return TshResult.Success(result, outputDiagnostics.toList())
    }

    /** Includes nested capture/scope payload and transient merge-map/list overhead estimates. */
    private fun estimateMatchBytes(match: TshMatch): Long {
        var total = 256L
        fun addString(value: String?) {
            if (value == null) return
            total = saturatedAdd(total, saturatedAdd(saturatedMultiply(value.length.toLong(), 2L), 16L))
        }
        addString(match.id.value)
        addString(match.documentId.value)
        addString(match.pipelineRevision)
        addString(match.ruleId.value)
        addString(match.captureName)
        addString(match.provenance.grammarFingerprint)
        addString(match.provenance.queryId)
        addString(match.provenance.queryRevision)
        addString(match.provenance.regexId)
        addString(match.provenance.regexRevision)
        total = saturatedAdd(total, saturatedMultiply(match.captures.size.toLong(), 96L))
        match.captures.forEach { capture ->
            addString(capture.name)
            addString(capture.text)
        }
        total = saturatedAdd(total, saturatedMultiply(match.scopeAssignments.size.toLong(), 128L))
        match.scopeAssignments.forEach { assignment ->
            addString(assignment.captureName)
            total = saturatedAdd(total, saturatedMultiply(assignment.path.segments.size.toLong(), 64L))
            assignment.path.segments.forEach { segment ->
                addString(segment.id)
                addString(segment.displayName)
            }
        }
        return total
    }

    private fun saturatedMultiply(left: Long, right: Long): Long =
        if (left == 0L || right == 0L) 0L
        else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.SEMANTIC,
        message = message
    )))

    private const val MAX_DIAGNOSTICS = 4096
}
