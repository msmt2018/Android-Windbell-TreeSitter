package com.zerostudio.idea.treesitter.tsh.api

import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.util.Locale

/**
 * Correctness-first converter for in-memory text snapshots.
 *
 * Piece Tree/Rope integrations should implement indexed conversion in their own
 * provider rather than flattening large documents into a String.
 */
object TshCoordinateConverter {
    @JvmStatic
    fun convert(
        text: CharSequence,
        offset: Int,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): TshResult<Int> {
        if (offset < 0) return failure("TSH-COORD-001", "Offset must be non-negative")
        if (from == to) {
            if (from == TshCoordinateSystem.SCREEN_PIXEL || from == TshCoordinateSystem.LINE_COLUMN) {
                return failure("TSH-COORD-002", "This coordinate system requires a layout-aware converter")
            }
            if (!isValidBoundary(text, offset, from)) {
                return failure("TSH-COORD-003", "Offset is outside the text or splits an encoded character")
            }
            return TshResult.Success(offset)
        }
        if (from == TshCoordinateSystem.SCREEN_PIXEL || to == TshCoordinateSystem.SCREEN_PIXEL ||
            from == TshCoordinateSystem.LINE_COLUMN || to == TshCoordinateSystem.LINE_COLUMN
        ) {
            return failure("TSH-COORD-002", "Screen and line/column conversion requires a layout-aware provider")
        }

        val utf16Offset = toUtf16(text, offset, from)
            ?: return failure("TSH-COORD-003", "Source offset is invalid or not on a character boundary")
        val converted = fromUtf16(text, utf16Offset, to)
            ?: return failure("TSH-COORD-004", "Target offset cannot represent this character boundary")
        return TshResult.Success(converted)
    }

    private fun toUtf16(text: CharSequence, offset: Int, system: TshCoordinateSystem): Int? {
        val value = text.toString()
        return when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT ->
                offset.takeIf { it <= value.length && isUtf16Boundary(value, it) }
            TshCoordinateSystem.UNICODE_CODE_POINT -> {
                val count = value.codePointCount(0, value.length)
                if (offset > count) null else value.offsetByCodePoints(0, offset)
            }
            TshCoordinateSystem.UTF8_BYTE -> {
                var utf16 = 0
                var bytes = 0
                while (utf16 < value.length) {
                    if (bytes == offset) return utf16
                    val cp = value.codePointAt(utf16)
                    bytes += utf8Length(cp)
                    utf16 += Character.charCount(cp)
                    if (bytes > offset) return null
                }
                if (bytes == offset) utf16 else null
            }
            TshCoordinateSystem.GRAPHEME_CLUSTER -> {
                val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
                iterator.setText(value)
                var boundary = iterator.first()
                var index = 0
                while (index < offset) {
                    boundary = iterator.next()
                    if (boundary == BreakIterator.DONE) return null
                    index++
                }
                boundary
            }
            TshCoordinateSystem.LINE_COLUMN, TshCoordinateSystem.SCREEN_PIXEL -> null
        }
    }

    private fun fromUtf16(text: CharSequence, utf16Offset: Int, system: TshCoordinateSystem): Int? {
        val value = text.toString()
        if (!isUtf16Boundary(value, utf16Offset)) return null
        return when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> utf16Offset
            TshCoordinateSystem.UNICODE_CODE_POINT -> value.codePointCount(0, utf16Offset)
            TshCoordinateSystem.UTF8_BYTE ->
                value.substring(0, utf16Offset).toByteArray(StandardCharsets.UTF_8).size
            TshCoordinateSystem.GRAPHEME_CLUSTER -> {
                val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
                iterator.setText(value)
                var boundary = iterator.first()
                var clusters = 0
                while (boundary < utf16Offset) {
                    boundary = iterator.next()
                    if (boundary == BreakIterator.DONE) return null
                    clusters++
                }
                if (boundary == utf16Offset) clusters else null
            }
            TshCoordinateSystem.LINE_COLUMN, TshCoordinateSystem.SCREEN_PIXEL -> null
        }
    }

    private fun isValidBoundary(text: CharSequence, offset: Int, system: TshCoordinateSystem): Boolean =
        toUtf16(text, offset, system) != null

    private fun isUtf16Boundary(value: String, offset: Int): Boolean =
        offset in 0..value.length &&
            !(offset > 0 && offset < value.length &&
                Character.isHighSurrogate(value[offset - 1]) &&
                Character.isLowSurrogate(value[offset]))

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }

    private fun failure(code: String, message: String): TshResult.Failure =
        TshResult.Failure(
            listOf(
                TshDiagnostic(
                    code = code,
                    severity = TshDiagnosticSeverity.ERROR,
                    stage = TshDiagnosticStage.COORDINATE,
                    message = message
                )
            )
        )
}
