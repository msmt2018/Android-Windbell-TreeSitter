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

        validateChunk(snapshot, chunk, startUtf16, maxUtf16Length)
        val text = chunk.text
        val byteCountLong = text.length.toLong() * 2L
        if (byteCountLong <= 0L || byteCountLong > destination.size.toLong()) {
            throw TshTextInputException("TextAccessProvider returned a chunk outside the Native input buffer bounds")
        }
        val byteCount = byteCountLong.toInt()
        for (index in text.indices) {
            val codeUnit = text[index].code
            destination[index * 2] = (codeUnit and 0xFF).toByte()
            destination[index * 2 + 1] = ((codeUnit ushr 8) and 0xFF).toByte()
        }
        return byteCount
    }

    private fun validateChunk(
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
            chunk.endUtf16 - requestedStart > maxUtf16Length.toLong()
        ) {
            throw TshTextInputException("TextAccessProvider returned an empty, oversized, or out-of-range chunk")
        }
        if (!TshUnicode.isWellFormedUtf16(chunk.text)) {
            throw TshTextInputException("TextAccessProvider returned malformed UTF-16")
        }

        // Provider overrides must preserve the same code-point boundary guarantee as the default
        // readChunk implementation. Validate both edges against the pinned snapshot.
        if (requestedStart > 0L) {
            val previous = snapshot.readUtf16(requestedStart - 1L, requestedStart)
            val first = chunk.text.first()
            if (previous.isNotEmpty() &&
                Character.isHighSurrogate(previous[0]) && Character.isLowSurrogate(first)
            ) {
                throw TshTextInputException("TextAccessProvider returned a chunk starting inside a surrogate pair")
            }
        }
        if (chunk.endUtf16 < snapshot.lengthUtf16) {
            val last = chunk.text.last()
            val next = snapshot.readUtf16(chunk.endUtf16, chunk.endUtf16 + 1L)
            if (next.isNotEmpty() &&
                Character.isHighSurrogate(last) && Character.isLowSurrogate(next[0])
            ) {
                throw TshTextInputException("TextAccessProvider returned a chunk ending inside a surrogate pair")
            }
        }
    }
}

class TshTextInputException(
    message: String,
    val diagnostics: List<TshDiagnostic> = emptyList()
) : IllegalStateException(message)
