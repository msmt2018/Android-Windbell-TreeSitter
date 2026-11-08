package com.zerostudio.idea.treesitter.tsh.api

/**
 * Immutable view of a document version. readUtf16 uses UTF-16 code-unit offsets
 * and must reject ranges outside [0, lengthUtf16].
 */
interface TshTextSnapshot {
    val documentId: String
    val version: Long
    val lengthUtf16: Long

    fun readUtf16(start: Long, end: Long): CharSequence
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
    val edits: List<TshTextEdit>
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(beforeVersion >= 0L && afterVersion > beforeVersion) {
            "Text change must advance the document version"
        }
    }
}

/**
 * Optional host adapter for reading text without flattening the whole document.
 * Implementations must define snapshot consistency and thread-safety.
 */
interface TshTextAccessProvider {
    val id: String
    fun supports(document: Any): Boolean
    fun snapshot(document: Any): TshResult<TshTextSnapshot>
}
