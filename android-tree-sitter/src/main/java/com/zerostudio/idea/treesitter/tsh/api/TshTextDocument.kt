package com.zerostudio.idea.treesitter.tsh.api

/**
 * Immutable view of one document version. Offsets are UTF-16 code units and ranges are
 * half-open. Implementations must keep the snapshot readable until its lease is closed.
 */
interface TshTextSnapshot {
    val documentId: String
    val version: Long
    val lengthUtf16: Long

    fun readUtf16(start: Long, end: Long): CharSequence
}

/** A stable reference to a snapshot. close() releases only the snapshot lease, never the host document. */
interface TshTextSnapshotLease : AutoCloseable {
    val snapshot: TshTextSnapshot
    override fun close()
}

/** Host-owned document abstraction; storage may be String, Rope, Piece Tree or stream. */
interface TshTextDocument {
    val documentId: String
    val languageId: String?
    val version: Long

    /** Snapshot must remain logically consistent for its reported version. */
    fun snapshot(): TshTextSnapshot
}

/** One versioned text replacement expressed in UTF-16 code-unit coordinates. */
data class TshTextEdit(
    val startUtf16: Long,
    val endUtf16: Long,
    val replacement: String
) {
    init {
        require(startUtf16 >= 0L) { "startUtf16 must be >= 0" }
        require(endUtf16 >= startUtf16) { "endUtf16 must be >= startUtf16" }
    }
}

data class TshTextChange(
    val documentId: String,
    val beforeVersion: Long,
    val afterVersion: Long,
    /** Edits are ascending by start offset and must not overlap in the before-version snapshot. */
    val edits: List<TshTextEdit>
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(beforeVersion >= 0L && afterVersion > beforeVersion) {
            "Text change must advance the document version"
        }
        require(edits.zipWithNext().all { (left, right) ->
            left.startUtf16 <= right.startUtf16 && left.endUtf16 <= right.startUtf16
        }) { "Edits must be ascending and non-overlapping in before-version coordinates" }
    }
}

data class TshTextChunk(
    val documentId: String,
    val documentVersion: Long,
    val startUtf16: Long,
    val endUtf16: Long,
    val text: CharSequence
) {
    init {
        require(documentId.isNotBlank())
        require(documentVersion >= 0L)
        require(startUtf16 >= 0L && endUtf16 >= startUtf16)
        require(text.length.toLong() == endUtf16 - startUtf16) {
            "Chunk length must equal its UTF-16 range length"
        }
    }
}

fun interface TshTextChangeListener {
    fun onChanged(change: TshTextChange)
}

interface TshTextSubscription : AutoCloseable {
    override fun close()
}

/**
 * Optional host adapter for reading text without flattening the whole document.
 * Methods are safe defaults so existing providers remain source-compatible.
 */
interface TshTextAccessProvider {
    val id: String
    fun supports(document: Any): Boolean
    fun snapshot(document: Any): TshResult<TshTextSnapshot>

    /** Default non-owning lease is valid only if the provider's snapshot is intrinsically immutable. */
    fun acquireSnapshot(document: Any): TshResult<TshTextSnapshotLease> =
        when (val result = snapshot(document)) {
            is TshResult.Success -> TshResult.Success(object : TshTextSnapshotLease {
                override val snapshot: TshTextSnapshot = result.value
                override fun close() = Unit
            }, result.diagnostics)
            is TshResult.Failure -> result
        }

    /** Bounded range read. Large callers should request multiple chunks. */
    fun readChunk(
        lease: TshTextSnapshotLease,
        startUtf16: Long,
        maxUtf16Length: Int
    ): TshResult<TshTextChunk> {
        if (startUtf16 < 0L || maxUtf16Length <= 0) return failure(
            "TSH-TEXT-001", "Chunk start must be non-negative and max length must be positive"
        )
        val snapshot = lease.snapshot
        if (startUtf16 > snapshot.lengthUtf16) return failure(
            "TSH-TEXT-002", "Chunk start is outside the snapshot"
        )
        val end = minOf(snapshot.lengthUtf16, startUtf16 + maxUtf16Length.toLong())
        return try {
            val text = snapshot.readUtf16(startUtf16, end)
            TshResult.Success(TshTextChunk(snapshot.documentId, snapshot.version, startUtf16, end, text))
        } catch (error: Throwable) {
            TshResult.Failure(listOf(TshDiagnostic(
                code = "TSH-TEXT-003", severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.BINDING, message = "Snapshot chunk read failed",
                cause = error
            )))
        }
    }

    /** Providers that support live edits override this; default explicitly reports unsupported. */
    fun subscribe(document: Any, listener: TshTextChangeListener): TshResult<TshTextSubscription> =
        TshResult.Failure(listOf(TshDiagnostic(
            code = "TSH-TEXT-004", severity = TshDiagnosticSeverity.WARNING,
            stage = TshDiagnosticStage.BINDING,
            message = "This TextAccessProvider does not support change subscriptions"
        )))

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code, severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.BINDING, message = message
    )))
}
