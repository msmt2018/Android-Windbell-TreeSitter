package com.zerostudio.idea.treesitter.tsh.api

/**
 * Reads bounded UTF-16LE windows from one immutable TSH Snapshot for Tree-sitter.
 *
 * The callback validates the complete provider response, not just its start offset. This is
 * important because custom Piece Tree/Rope providers may override readChunk().
 */
class TshSnapshotTreeSitterInput(
    private val textProvider: TshTextAccessProvider,
    private val snapshotLease: TshTextSnapshotLease
) {
    fun read(byteOffset: Long, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || (maxBytes and 1) != 0) {
            throw TshTextInputException("Tree-sitter input window must have a positive even byte capacity")
        }
        val destination = ByteArray(maxBytes)
        val byteCount = readInto(byteOffset, destination)
        return if (byteCount == destination.size) destination else destination.copyOf(byteCount)
    }

    private fun readInto(byteOffset: Long, destination: ByteArray): Int {
        if (snapshotLease.isClosed) {
            throw TshTextInputException("Snapshot lease closed during Tree-sitter parse")
        }
        if (!textProvider.isAccessAllowedOnCurrentThread()) {
            throw TshTextInputException("TextAccessProvider rejected the current parser thread")
        }
        if (byteOffset < 0L || (byteOffset and 1L) != 0L) {
            throw TshTextInputException("Tree-sitter requested a byte offset inside a UTF-16 code unit")
        }
        if (destination.isEmpty() || (destination.size and 1) != 0) {
            throw TshTextInputException("Tree-sitter input buffer must have a positive even byte capacity")
        }

        val snapshot = snapshotLease.snapshot
        val startUtf16 = byteOffset / 2L
        val lengthUtf16 = snapshot.lengthUtf16
        if (lengthUtf16 < 0L || startUtf16 > lengthUtf16) {
            throw TshTextInputException("Tree-sitter requested an offset outside the Snapshot")
        }
        if (startUtf16 == lengthUtf16) return 0

        val maxUtf16Length = destination.size / 2
        val result = textProvider.readChunk(snapshotLease, startUtf16, maxUtf16Length)
        val chunk = when (result) {
            is TshResult.Success -> result.value
            is TshResult.Failure -> throw TshTextInputException(
                "Host TextAccessProvider failed to read Tree-sitter input at byte offset $byteOffset",
                result.diagnostics
            )
        }

        validateChunkMetadata(snapshot, chunk, startUtf16, maxUtf16Length)

        // A host may return a borrowed CharSequence backed by a reusable PieceTree/Rope window.
        // Copy it before any boundary look-ahead read: readUtf16() may reuse and overwrite that
        // same backing window. Never inspect chunk.text again after the first snapshot read.
        val borrowedText = chunk.text
        val textLength = borrowedText.length
        val stableText = CharArray(textLength)
        for (index in 0 until textLength) {
            stableText[index] = borrowedText[index]
        }
        if (!TshUnicode.isWellFormedUtf16(stableText)) {
            throw TshTextInputException("TextAccessProvider returned malformed UTF-16")
        }

        val byteCountLong = textLength.toLong() * 2L
        if (byteCountLong <= 0L || byteCountLong > destination.size.toLong()) {
            throw TshTextInputException("TextAccessProvider returned a chunk outside the Native input buffer bounds")
        }
        val byteCount = byteCountLong.toInt()
        for (index in stableText.indices) {
            val codeUnit = stableText[index].code
            destination[index * 2] = (codeUnit and 0xFF).toByte()
            destination[index * 2 + 1] = ((codeUnit ushr 8) and 0xFF).toByte()
        }

        // Boundary reads are deliberately after the stable copy. Read each adjacent unit only
        // once and inspect it before issuing another provider read that could reuse its buffer.
        if (startUtf16 > 0L) {
            val previous = snapshot.readUtf16(startUtf16 - 1L, startUtf16)
            if (previous.isNotEmpty() &&
                Character.isHighSurrogate(previous[0]) && Character.isLowSurrogate(stableText[0])
            ) {
                throw TshTextInputException("TextAccessProvider returned a chunk starting inside a surrogate pair")
            }
        }
        if (chunk.endUtf16 < snapshot.lengthUtf16) {
            val next = snapshot.readUtf16(chunk.endUtf16, chunk.endUtf16 + 1L)
            if (next.isNotEmpty() &&
                Character.isHighSurrogate(stableText[stableText.lastIndex]) &&
                Character.isLowSurrogate(next[0])
            ) {
                throw TshTextInputException("TextAccessProvider returned a chunk ending inside a surrogate pair")
            }
        }
        return byteCount
    }

    private fun validateChunkMetadata(
        snapshot: TshTextSnapshot,
        chunk: TshTextChunk,
        requestedStart: Long,
        maxUtf16Length: Int
    ) {
        if (chunk.documentId != snapshot.documentId ||
            chunk.documentVersion != snapshot.version ||
            chunk.startUtf16 != requestedStart
        ) {
            throw TshTextInputException("TextAccessProvider returned a chunk from a different snapshot or offset")
        }
        if (chunk.endUtf16 <= requestedStart ||
            chunk.endUtf16 > snapshot.lengthUtf16 ||
            chunk.endUtf16 - requestedStart > maxUtf16Length.toLong() ||
            chunk.text.length.toLong() != chunk.endUtf16 - requestedStart
        ) {
            throw TshTextInputException("TextAccessProvider returned an empty, oversized, or out-of-range chunk")
        }
    }
}

class TshTextInputException(
    message: String,
    val diagnostics: List<TshDiagnostic> = emptyList()
) : IllegalStateException(message)
