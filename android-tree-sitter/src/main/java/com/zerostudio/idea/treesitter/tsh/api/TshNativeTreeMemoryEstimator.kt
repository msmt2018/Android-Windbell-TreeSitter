package com.zerostudio.idea.treesitter.tsh.api

/**
 * Inputs available to a host-specific Native syntax-tree memory estimator.
 *
 * [sourceBytesUtf16Le] is the logical UTF-16LE source size, not an allocation measurement.
 */
data class TshNativeTreeMemoryRequest(
    val documentId: String,
    val documentVersion: Long,
    val treeRevision: Long,
    val sourceLengthUtf16: Long,
    val grammarLanguageId: String,
    val grammarFingerprint: String,
    val incremental: Boolean
) {
    init {
        require(documentId.isNotBlank())
        require(documentVersion >= 0L)
        require(treeRevision >= 0L)
        require(sourceLengthUtf16 >= 0L && sourceLengthUtf16 <= Long.MAX_VALUE / 2L)
        require(grammarLanguageId.isNotBlank())
        require(grammarFingerprint.isNotBlank())
    }

    val sourceBytesUtf16Le: Long
        get() = sourceLengthUtf16 * 2L
}

/**
 * Supplies an admission estimate for one resulting Native syntax tree.
 *
 * The estimator belongs to the host because actual Tree-sitter tree size depends on grammar,
 * syntax, node density, incremental reuse and the core allocator. The returned amount is reserved
 * for the tree lifetime; it is not a measurement or a hard guarantee of Native heap consumption.
 */
fun interface TshNativeTreeMemoryEstimator {
    /** Must return a positive byte estimate. */
    fun estimateBytes(request: TshNativeTreeMemoryRequest): Long
}

/** Shared validation and reservation path used by full and snapshot-backed parser entry points. */
internal object TshNativeTreeMemoryReservations {
    fun reserve(
        budget: TshMemoryBudget?,
        estimator: TshNativeTreeMemoryEstimator?,
        request: TshNativeTreeMemoryRequest
    ): TshResult<TshMemoryReservation?> {
        if (estimator == null) return TshResult.Success(null)
        if (budget == null) return failure(
            "TSH-MEMORY-011",
            "A Native tree memory estimator requires an Engine-owned memory budget"
        )
        val estimate = try {
            estimator.estimateBytes(request)
        } catch (error: Exception) {
            return failure(
                "TSH-MEMORY-007",
                "Native syntax-tree memory estimator failed: ${error.message}",
                error
            )
        }
        if (estimate <= 0L) return failure(
            "TSH-MEMORY-006",
            "Native syntax-tree memory estimator must return a positive byte estimate"
        )
        return when (val result = budget.reserve(
            TshMemoryCategory.SYNTAX_TREE,
            estimate,
            "syntax-tree:${request.documentId}:${request.documentVersion}:${request.treeRevision}"
        )) {
            is TshResult.Success -> TshResult.Success(result.value)
            is TshResult.Failure -> TshResult.Failure(result.diagnostics)
        }
    }

    private fun failure(code: String, message: String, cause: Throwable? = null) =
        TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.MEMORY,
            message = message,
            cause = cause
        )))
}

/**
 * Conservative input admission for the legacy String parse path. It reserves the full logical
 * UTF-16LE source size even when TSParser internally switches large String inputs to windows.
 */
internal object TshParserInputMemoryReservations {
    private const val ESTIMATED_FIXED_OVERHEAD_BYTES = 64L

    fun reserveStringInput(
        budget: TshMemoryBudget?,
        documentId: String,
        documentVersion: Long,
        sourceLengthUtf16: Long
    ): TshResult<TshMemoryReservation?> {
        if (budget == null) return TshResult.Success(null)
        if (documentId.isBlank() || documentVersion < 0L || sourceLengthUtf16 < 0L ||
            sourceLengthUtf16 > (Long.MAX_VALUE - ESTIMATED_FIXED_OVERHEAD_BYTES) / 2L) {
            return failure(
                "TSH-MEMORY-008",
                "String parser input size or document identity is invalid"
            )
        }
        val estimate = sourceLengthUtf16 * 2L + ESTIMATED_FIXED_OVERHEAD_BYTES
        return when (val result = budget.reserve(
            TshMemoryCategory.PARSER_INPUT,
            estimate,
            "tree-sitter-string-input:${documentId}:${documentVersion}"
        )) {
            is TshResult.Success -> TshResult.Success(result.value)
            is TshResult.Failure -> TshResult.Failure(result.diagnostics)
        }
    }

    /** Reserves two bounded byte-window arrays plus fixed overhead for the current JNI callback path. */
    fun reserveStreamingBridge(
        budget: TshMemoryBudget?,
        documentId: String,
        documentVersion: Long,
        chunkSizeBytes: Int
    ): TshResult<TshMemoryReservation?> {
        if (budget == null) return TshResult.Success(null)
        if (documentId.isBlank() || documentVersion < 0L ||
            chunkSizeBytes !in 2..(1024 * 1024) || (chunkSizeBytes and 1) != 0) {
            return failure(
                "TSH-MEMORY-009",
                "Streaming parser input buffer size or document identity is invalid"
            )
        }
        val estimate = chunkSizeBytes.toLong() * 2L + ESTIMATED_FIXED_OVERHEAD_BYTES
        return when (val result = budget.reserve(
            TshMemoryCategory.PARSER_INPUT,
            estimate,
            "tree-sitter-input-buffer:${documentId}:${documentVersion}"
        )) {
            is TshResult.Success -> TshResult.Success(result.value)
            is TshResult.Failure -> TshResult.Failure(result.diagnostics)
        }
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.MEMORY,
        message = message
    )))
}
