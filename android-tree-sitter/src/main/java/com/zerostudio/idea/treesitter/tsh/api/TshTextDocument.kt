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
    /** Providers should expose the closed state; false is the compatibility default. */
    val isClosed: Boolean get() = false
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
        require(TshUnicode.isWellFormedUtf16(replacement)) {
            "Replacement contains an unpaired UTF-16 surrogate"
        }
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
            left.startUtf16 < right.startUtf16 && left.endUtf16 <= right.startUtf16
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

enum class TshTextAccessThreading { UNKNOWN, ANY_THREAD, UI_THREAD_ONLY, SERIALIZED }

fun interface TshTextDocumentDisposedListener {
    fun onDisposed(documentId: String, finalVersion: Long)
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
    /** UNKNOWN requires the host integration layer to choose a safe dispatcher explicitly. */
    val threading: TshTextAccessThreading get() = TshTextAccessThreading.UNKNOWN
    /** Conservative default: providers must opt in before TSH performs concurrent snapshot reads. */
    val supportsConcurrentReads: Boolean get() = false
    /** Providers with UI_THREAD_ONLY or UNKNOWN policies must override this explicitly. */
    fun isAccessAllowedOnCurrentThread(): Boolean =
        threading == TshTextAccessThreading.ANY_THREAD ||
            threading == TshTextAccessThreading.SERIALIZED
    fun supports(document: Any): Boolean
    fun snapshot(document: Any): TshResult<TshTextSnapshot>

    /** Default non-owning lease is valid only if the provider's snapshot is intrinsically immutable. */
    fun acquireSnapshot(document: Any): TshResult<TshTextSnapshotLease> =
        when (val result = snapshot(document)) {
            is TshResult.Success -> TshResult.Success(object : TshTextSnapshotLease {
                override val snapshot: TshTextSnapshot = result.value
                @Volatile private var closed = false
                override val isClosed: Boolean get() = closed
                override fun close() { closed = true }
            }, result.diagnostics)
            is TshResult.Failure -> result
        }

    /** Bounded range read. Large callers should request multiple chunks. */
    fun readChunk(
        lease: TshTextSnapshotLease,
        startUtf16: Long,
        maxUtf16Length: Int
    ): TshResult<TshTextChunk> {
        if (lease.isClosed) return failure("TSH-TEXT-009", "Snapshot lease is already closed")
        if (startUtf16 < 0L || maxUtf16Length <= 0) return failure(
            "TSH-TEXT-001", "Chunk start must be non-negative and max length must be positive"
        )
        val snapshot = lease.snapshot
        if (startUtf16 > snapshot.lengthUtf16) return failure(
            "TSH-TEXT-002", "Chunk start is outside the snapshot"
        )
        return try {
            var end = startUtf16 + minOf(snapshot.lengthUtf16 - startUtf16, maxUtf16Length.toLong())
            if (startUtf16 > 0L && startUtf16 < snapshot.lengthUtf16) {
                val previous = snapshot.readUtf16(startUtf16 - 1L, startUtf16)
                val first = snapshot.readUtf16(startUtf16, startUtf16 + 1L)
                if (previous.isNotEmpty() && first.isNotEmpty() &&
                    Character.isHighSurrogate(previous[0]) && Character.isLowSurrogate(first[0])) {
                    return failure("TSH-TEXT-005", "Chunk start splits a UTF-16 surrogate pair")
                }
            }
            if (end > startUtf16 && end < snapshot.lengthUtf16) {
                val last = snapshot.readUtf16(end - 1L, end)
                val next = snapshot.readUtf16(end, end + 1L)
                if (last.isNotEmpty() && next.isNotEmpty() &&
                    Character.isHighSurrogate(last[0]) && Character.isLowSurrogate(next[0])) {
                    end--
                }
            }
            if (end == startUtf16 && startUtf16 < snapshot.lengthUtf16) {
                return failure("TSH-TEXT-006", "Chunk limit is too small to include the next Unicode code point")
            }
            val text = snapshot.readUtf16(startUtf16, end)
            if (!TshUnicode.isWellFormedUtf16(text)) {
                return failure("TSH-TEXT-007", "Snapshot chunk contains malformed UTF-16")
            }
            TshResult.Success(TshTextChunk(snapshot.documentId, snapshot.version, startUtf16, end, text))
        } catch (error: Exception) {
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
            code = "TSH-TEXT-004", severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.BINDING,
            message = "This TextAccessProvider does not support change subscriptions"
        )))

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code, severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.BINDING, message = message
    )))
}


/** Shared strict UTF-16 validation for text edits and chunk boundaries. */
internal object TshUnicode {
    fun isWellFormedUtf16(value: CharSequence): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index++
            }
        }
        return true
    }
}
