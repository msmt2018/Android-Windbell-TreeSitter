package com.zerostudio.idea.treesitter.tsh.api

import java.text.BreakIterator
import java.util.Locale

/**
 * Correctness-first converter for in-memory text.
 *
 * This implementation is intentionally not the large-document path: Piece Tree/Rope
 * providers must implement indexed conversion and must not flatten the document.
 * Unpaired UTF-16 surrogates are rejected consistently in both directions.
 */
object TshCoordinateConverter {
    @JvmStatic
    fun convert(
        text: CharSequence,
        offset: Int,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): TshResult<Int> = convertInternal(text, offset.toLong(), from, to).mapInt()

    /** Long-offset overload for document-sized coordinates; the Int overload remains ABI-compatible. */
    @JvmStatic
    fun convertLong(
        text: CharSequence,
        offset: Long,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): TshResult<Long> = convertInternal(text, offset, from, to)

    private fun convertInternal(
        text: CharSequence,
        offset: Long,
        from: TshCoordinateSystem,
        to: TshCoordinateSystem
    ): TshResult<Long> {
        if (offset < 0L) return failure("TSH-COORD-001", "Offset must be non-negative")
        if (offset > Int.MAX_VALUE.toLong()) {
            return failure("TSH-COORD-005", "In-memory converter cannot index beyond Int.MAX_VALUE; use a long-indexed TextAccessProvider")
        }
        val value = text.toString()
        if (!hasValidSurrogates(value)) {
            return failure("TSH-COORD-006", "Text contains an unpaired UTF-16 surrogate")
        }
        val sourceOffset = offset.toInt()
        if (from == TshCoordinateSystem.SCREEN_PIXEL || from == TshCoordinateSystem.LINE_COLUMN ||
            to == TshCoordinateSystem.SCREEN_PIXEL || to == TshCoordinateSystem.LINE_COLUMN) {
            return failure("TSH-COORD-002", "Screen and line/column conversion requires a layout-aware provider")
        }
        if (from == to) {
            if (!isValidBoundary(value, sourceOffset, from)) {
                return failure("TSH-COORD-003", "Offset is outside the text or splits an encoded character")
            }
            return TshResult.Success(offset)
        }
        val utf16Offset = toUtf16(value, sourceOffset, from)
            ?: return failure("TSH-COORD-003", "Source offset is invalid or not on a character boundary")
        val converted = fromUtf16(value, utf16Offset, to)
            ?: return failure("TSH-COORD-004", "Target offset cannot represent this character boundary")
        return TshResult.Success(converted.toLong())
    }

    private fun toUtf16(value: String, offset: Int, system: TshCoordinateSystem): Int? = when (system) {
        TshCoordinateSystem.UTF16_CODE_UNIT ->
            offset.takeIf { it <= value.length && isUtf16Boundary(value, it) }
        TshCoordinateSystem.UNICODE_CODE_POINT -> {
            val count = value.codePointCount(0, value.length)
            if (offset > count) null else value.offsetByCodePoints(0, offset)
        }
        TshCoordinateSystem.UTF8_BYTE -> {
            var utf16 = 0
            var bytes = 0L
            while (utf16 < value.length) {
                if (bytes == offset.toLong()) return utf16
                val cp = value.codePointAt(utf16)
                bytes += utf8Length(cp)
                utf16 += Character.charCount(cp)
                if (bytes > offset) return null
            }
            if (bytes == offset.toLong()) utf16 else null
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

    private fun fromUtf16(value: String, utf16Offset: Int, system: TshCoordinateSystem): Int? {
        if (!isUtf16Boundary(value, utf16Offset)) return null
        return when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> utf16Offset
            TshCoordinateSystem.UNICODE_CODE_POINT -> value.codePointCount(0, utf16Offset)
            TshCoordinateSystem.UTF8_BYTE -> {
                var bytes = 0
                var index = 0
                while (index < utf16Offset) {
                    val cp = value.codePointAt(index)
                    bytes += utf8Length(cp)
                    index += Character.charCount(cp)
                }
                bytes
            }
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

    private fun isValidBoundary(value: String, offset: Int, system: TshCoordinateSystem): Boolean =
        toUtf16(value, offset, system) != null

    private fun isUtf16Boundary(value: String, offset: Int): Boolean =
        offset in 0..value.length &&
            !(offset > 0 && offset < value.length &&
                Character.isHighSurrogate(value[offset - 1]) &&
                Character.isLowSurrogate(value[offset]))

    private fun hasValidSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(ch) -> return false
                else -> index++
            }
        }
        return true
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }

    private fun <T> TshResult<T>.mapInt(): TshResult<Int> = when (this) {
        is TshResult.Success -> {
            val converted = value
            if (converted > Int.MAX_VALUE.toLong()) failure("TSH-COORD-005", "Converted offset exceeds Int.MAX_VALUE")
            else TshResult.Success(converted.toInt(), diagnostics)
        }
        is TshResult.Failure -> this
    }

    private fun failure(code: String, message: String): TshResult.Failure =
        TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.COORDINATE,
            message = message
        )))
}
