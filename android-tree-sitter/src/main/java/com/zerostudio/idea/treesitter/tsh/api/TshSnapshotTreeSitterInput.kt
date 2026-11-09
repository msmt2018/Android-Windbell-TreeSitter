package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSInput

/** Streams one immutable TSH Snapshot through Tree-sitter's UTF-16LE TSInput callback. */
class TshSnapshotTreeSitterInput(
    private val textProvider: TshTextAccessProvider,
    private val snapshotLease: TshTextSnapshotLease
) : TSInput {
    override fun read(
        byteOffset: Long,
        row: Int,
        column: Int,
        destination: ByteArray
    ): Int {
        if (snapshotLease.isClosed) {
            throw TshTextInputException("Snapshot lease closed during Tree-sitter parse")
        }
        if (byteOffset < 0L || (byteOffset and 1L) != 0L) {
            throw TshTextInputException("Tree-sitter requested a byte offset inside a UTF-16 code unit")
        }
        if (row < 0 || column < 0) {
            throw TshTextInputException("Tree-sitter input point exceeds the supported signed coordinate range")
        }
        if (destination.isEmpty() || (destination.size and 1) != 0) {
            throw TshTextInputException("Tree-sitter input buffer must have a positive even byte capacity")
        }

        val snapshot = snapshotLease.snapshot
        val startUtf16 = byteOffset / 2L
        if (snapshot.lengthUtf16 < 0L || startUtf16 > snapshot.lengthUtf16) {
            throw TshTextInputException("Tree-sitter requested an offset outside the Snapshot")
        }
        if (startUtf16 == snapshot.lengthUtf16) return 0
        val maxUtf16Length = destination.size / 2
        val result = textProvider.readChunk(snapshotLease, startUtf16, maxUtf16Length)
        val chunk = when (result) {
            is TshResult.Success -> result.value
            is TshResult.Failure -> throw TshTextInputException(
                "Host TextAccessProvider failed to read Tree-sitter input at byte offset $byteOffset",
                result.diagnostics
            )
        }
        if (chunk.documentId != snapshot.documentId || chunk.documentVersion != snapshot.version ||
            chunk.startUtf16 != startUtf16) {
            throw TshTextInputException("TextAccessProvider returned a chunk from a different snapshot or offset")
        }
        val text = chunk.text
        if (text.isEmpty()) {
            throw TshTextInputException("TextAccessProvider returned an empty chunk before end of document")
        }
        val byteCount = text.length * 2
        if (byteCount > destination.size || (byteCount and 1) != 0) {
            throw TshTextInputException("TextAccessProvider returned a chunk larger than the Native input buffer")
        }
        for (index in text.indices) {
            val codeUnit = text[index].code
            destination[index * 2] = (codeUnit and 0xFF).toByte()
            destination[index * 2 + 1] = ((codeUnit ushr 8) and 0xFF).toByte()
        }
        return byteCount
    }
}

class TshTextInputException(
    message: String,
    val diagnostics: List<TshDiagnostic> = emptyList()
) : IllegalStateException(message)
