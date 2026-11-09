package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSInputEdit
import com.zerostudio.idea.treesitter.TSPoint

/**
 * Compiles a versioned, ascending TSH edit batch into Tree-sitter UTF-16LE edits.
 *
 * Edits are returned in descending document order. Applying the later edit first leaves the
 * coordinates of all earlier edits unchanged, so every point/byte offset is based on one
 * before-version snapshot.
 *
 * Position lookup uses one bounded streaming pass and stores only requested edit endpoints.
 * It does not flatten the document or allocate a line-start table proportional to line count.
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
        if (change.edits.isEmpty()) return TshResult.Success(emptyList())
        if (change.edits.any { it.endUtf16 > before.lengthUtf16 }) {
            return failure("TSH-EDIT-COMPILE-004",
                "Edit range exceeds the before-version snapshot")
        }

        return try {
            val endpoints = change.edits.flatMap { listOf(it.startUtf16, it.endUtf16) }
            val points = Utf16EndpointPoints.build(before, endpoints)
            val compiled = ArrayList<TSInputEdit>(change.edits.size)
            for (edit in change.edits) {
                val startPoint = points[edit.startUtf16] ?: return failure(
                    "TSH-EDIT-COMPILE-005", "Edit start splits a UTF-16 surrogate pair or is invalid"
                )
                val oldEndPoint = points[edit.endUtf16] ?: return failure(
                    "TSH-EDIT-COMPILE-005", "Edit end splits a UTF-16 surrogate pair or is invalid"
                )
                val newEnd = Math.addExact(edit.startUtf16, edit.replacement.length.toLong())
                val newEndPoint = advance(startPoint, edit.replacement)
                compiled += TSInputEdit.create(
                    checkedByteOffset(edit.startUtf16),
                    checkedByteOffset(edit.endUtf16),
                    checkedByteOffset(newEnd),
                    startPoint,
                    oldEndPoint,
                    newEndPoint
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

    private object Utf16EndpointPoints {
        fun build(snapshot: TshTextSnapshot, requested: List<Long>): Map<Long, TSPoint> {
            val targets = requested.distinct().sorted()
            require(targets.all { it in 0L..snapshot.lengthUtf16 }) {
                "Edit endpoint is outside the before-version snapshot"
            }
            val result = HashMap<Long, TSPoint>(targets.size)
            var targetIndex = 0
            var absolute = 0L
            var row = 0
            var column = 0

            while (absolute < snapshot.lengthUtf16) {
                var end = minOf(snapshot.lengthUtf16, absolute + INDEX_CHUNK_UTF16)
                var chunk = snapshot.readUtf16(absolute, end)
                if (chunk.isNotEmpty() && Character.isHighSurrogate(chunk[chunk.length - 1]) &&
                    end < snapshot.lengthUtf16) {
                    end++
                    chunk = snapshot.readUtf16(absolute, end)
                }

                var i = 0
                while (i < chunk.length) {
                    while (targetIndex < targets.size && targets[targetIndex] == absolute) {
                        result[absolute] = TSPoint.create(row, column)
                        targetIndex++
                    }
                    val ch = chunk[i]
                    when {
                        Character.isHighSurrogate(ch) -> {
                            require(i + 1 < chunk.length &&
                                Character.isLowSurrogate(chunk[i + 1])) {
                                "Snapshot contains an unpaired high surrogate at $absolute"
                            }
                            i += 2
                            absolute += 2L
                            column = Math.addExact(column, 4)
                        }
                        Character.isLowSurrogate(ch) ->
                            error("Snapshot contains an unpaired low surrogate at $absolute")
                        ch == '\n' -> {
                            i++
                            absolute++
                            row = Math.addExact(row, 1)
                            column = 0
                        }
                        else -> {
                            i++
                            absolute++
                            column = Math.addExact(column, 2)
                        }
                    }
                }
                check(absolute == end) { "Snapshot chunk length changed during indexed read" }
            }

            while (targetIndex < targets.size && targets[targetIndex] == absolute) {
                result[absolute] = TSPoint.create(row, column)
                targetIndex++
            }
            require(targetIndex == targets.size) {
                "An edit endpoint falls inside a UTF-16 surrogate pair"
            }
            return result
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
