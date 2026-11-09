package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSInputEdit
import com.zerostudio.idea.treesitter.TSPoint

/**
 * Compiles a versioned, ascending TSH edit batch into Tree-sitter UTF-16LE edits.
 *
 * Tree-sitter edits are returned in descending document order. Applying the later edit first
 * leaves the coordinates of all earlier edits unchanged, so every point/byte offset can be
 * computed against the same before-version snapshot.
 *
 * The line index is built with bounded reads; this never flattens the document into one String.
 */
object TshTreeSitterEditCompiler {
    private const val INDEX_CHUNK_UTF16 = 32 * 1024

    @JvmStatic
    fun compile(
        before: TshTextSnapshot,
        change: TshTextChange
    ): TshResult<List<TSInputEdit>> {
        if (before.documentId != change.documentId || before.version != change.beforeVersion) {
            return failure("TSH-EDIT-COMPILE-001",
                "TextChange does not target the supplied before-version snapshot")
        }
        if (before.lengthUtf16 < 0L || before.lengthUtf16 > Int.MAX_VALUE.toLong() / 2L) {
            return failure("TSH-EDIT-COMPILE-002",
                "Tree-sitter UTF-16LE byte offsets exceed the Native Int boundary")
        }
        if (change.afterVersion <= before.version) {
            return failure("TSH-EDIT-COMPILE-003", "TextChange version must advance")
        }

        return try {
            val index = Utf16LineIndex.build(before)
            val compiled = ArrayList<TSInputEdit>(change.edits.size)
            for (edit in change.edits) {
                if (edit.endUtf16 > before.lengthUtf16) {
                    return failure("TSH-EDIT-COMPILE-004",
                        "Edit range exceeds the before-version snapshot")
                }
                if (!index.isBoundary(edit.startUtf16) || !index.isBoundary(edit.endUtf16)) {
                    return failure("TSH-EDIT-COMPILE-005",
                        "Edit range splits a UTF-16 surrogate pair")
                }
                val start = edit.startUtf16
                val oldEnd = edit.endUtf16
                val newEnd = Math.addExact(start, edit.replacement.length.toLong())
                val startPoint = index.pointAt(start)
                val oldEndPoint = index.pointAt(oldEnd)
                val newEndPoint = advance(startPoint, edit.replacement)
                val startByte = checkedByteOffset(start)
                val oldEndByte = checkedByteOffset(oldEnd)
                val newEndByte = checkedByteOffset(newEnd)
                compiled += TSInputEdit.create(
                    startByte, oldEndByte, newEndByte,
                    startPoint, oldEndPoint, newEndPoint
                )
            }
            TshResult.Success(compiled.asReversed())
        } catch (error: ArithmeticException) {
            failure("TSH-EDIT-COMPILE-006", "Edit byte offset overflow", error)
        } catch (error: Exception) {
            failure("TSH-EDIT-COMPILE-007",
                "Cannot compile Tree-sitter input edits: ${error.message}", error)
        } catch (error: LinkageError) {
            failure("TSH-EDIT-COMPILE-008",
                "Tree-sitter Native object factory is unavailable", error)
        }
    }

    private fun checkedByteOffset(offset: Long): Int {
        val bytes = Math.multiplyExact(offset, 2L)
        require(bytes <= Int.MAX_VALUE.toLong()) { "UTF-16LE byte offset exceeds Int.MAX_VALUE" }
        return bytes.toInt()
    }

    private fun advance(start: TSPoint, replacement: String): TSPoint {
        var row = start.row
        var column = start.column
        var index = 0
        while (index < replacement.length) {
            val ch = replacement[index++]
            if (ch == '\n') {
                row = Math.addExact(row, 1)
                column = 0
            } else {
                column = Math.addExact(column, 2)
            }
        }
        return TSPoint.create(row, column)
    }

    private class Utf16LineIndex private constructor(
        private val snapshot: TshTextSnapshot,
        private val lineStarts: LongArray
    ) {
        fun pointAt(offset: Long): TSPoint {
            require(offset in 0L..snapshot.lengthUtf16)
            var low = 0
            var high = lineStarts.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lineStarts[mid] <= offset) low = mid + 1 else high = mid - 1
            }
            val row = high.coerceAtLeast(0)
            val column = Math.multiplyExact(offset - lineStarts[row], 2L)
            require(row <= Int.MAX_VALUE && column <= Int.MAX_VALUE.toLong())
            return TSPoint.create(row, column.toInt())
        }

        fun isBoundary(offset: Long): Boolean {
            if (offset <= 0L || offset >= snapshot.lengthUtf16) return true
            val around = snapshot.readUtf16(offset - 1L, offset + 1L)
            return !(Character.isHighSurrogate(around[0]) &&
                Character.isLowSurrogate(around[1]))
        }

        companion object {
            fun build(snapshot: TshTextSnapshot): Utf16LineIndex {
                val starts = ArrayList<Long>()
                starts += 0L
                var offset = 0L
                while (offset < snapshot.lengthUtf16) {
                    var end = minOf(snapshot.lengthUtf16, offset + INDEX_CHUNK_UTF16)
                    var chunk = snapshot.readUtf16(offset, end)
                    if (chunk.isNotEmpty() && Character.isHighSurrogate(chunk[chunk.length - 1]) &&
                        end < snapshot.lengthUtf16) {
                        end++
                        chunk = snapshot.readUtf16(offset, end)
                    }
                    var i = 0
                    while (i < chunk.length) {
                        val ch = chunk[i]
                        when {
                            Character.isHighSurrogate(ch) -> {
                                require(i + 1 < chunk.length &&
                                    Character.isLowSurrogate(chunk[i + 1])) {
                                    "Snapshot contains an unpaired high surrogate at ${offset + i}"
                                }
                                i += 2
                                continue
                            }
                            Character.isLowSurrogate(ch) ->
                                error("Snapshot contains an unpaired low surrogate at ${offset + i}")
                            ch == '\n' -> starts += offset + i + 1L
                        }
                        i++
                    }
                    offset = end
                }
                return Utf16LineIndex(snapshot, starts.toLongArray())
            }
        }
    }

    private fun failure(code: String, message: String, cause: Throwable? = null) =
        TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.PARSE,
            message = message,
            cause = cause
        )))
}
