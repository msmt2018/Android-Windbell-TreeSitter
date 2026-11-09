package com.zerostudio.idea.treesitter.tsh.api

import java.text.BreakIterator
import java.util.Locale

/**
 * Correctness-first converter for in-memory text.
 * Piece Tree/Rope providers must implement indexed conversion and must not flatten documents.
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

    /** Long-offset API; UTF-8 byte offsets may exceed Int even when JVM String length does not. */
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
        val value = text as? String ?: return failure(
            "TSH-COORD-007",
            "Non-String CharSequence conversion would require flattening; use a host indexed coordinate provider"
        )
        if (!hasValidSurrogates(value)) {
            return failure("TSH-COORD-006", "Text contains an unpaired UTF-16 surrogate")
        }
        if (from == TshCoordinateSystem.SCREEN_PIXEL || from == TshCoordinateSystem.LINE_COLUMN ||
            to == TshCoordinateSystem.SCREEN_PIXEL || to == TshCoordinateSystem.LINE_COLUMN) {
            return failure("TSH-COORD-002", "Screen and line/column conversion requires a layout-aware provider")
        }
        if (from == to) {
            if (toUtf16(value, offset, from) == null) {
                return failure("TSH-COORD-003", "Offset is outside the text or splits an encoded character")
            }
            return TshResult.Success(offset)
        }
        val utf16Offset = toUtf16(value, offset, from)
            ?: return failure("TSH-COORD-003", "Source offset is invalid or not on a character boundary")
        val converted = fromUtf16(value, utf16Offset, to)
            ?: return failure("TSH-COORD-004", "Target offset cannot represent this character boundary")
        return TshResult.Success(converted)
    }

    private fun toUtf16(value: String, offset: Long, system: TshCoordinateSystem): Int? = when (system) {
        TshCoordinateSystem.UTF16_CODE_UNIT ->
            if (offset <= value.length.toLong()) offset.toInt()
                .takeIf { isUtf16Boundary(value, it) } else null
        TshCoordinateSystem.UNICODE_CODE_POINT -> {
            val count = value.codePointCount(0, value.length)
            if (offset > count.toLong()) null else value.offsetByCodePoints(0, offset.toInt())
        }
        TshCoordinateSystem.UTF8_BYTE -> {
            var utf16 = 0
            var bytes = 0L
            while (utf16 < value.length) {
                if (bytes == offset) return utf16
                val codePoint = value.codePointAt(utf16)
                bytes += utf8Length(codePoint)
                utf16 += Character.charCount(codePoint)
                if (bytes > offset) return null
            }
            if (bytes == offset) utf16 else null
        }
        TshCoordinateSystem.GRAPHEME_CLUSTER -> {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(value)
            var boundary = iterator.first()
            var index = 0L
            while (index < offset) {
                boundary = iterator.next()
                if (boundary == BreakIterator.DONE) return null
                index++
            }
            boundary
        }
        TshCoordinateSystem.LINE_COLUMN, TshCoordinateSystem.SCREEN_PIXEL -> null
    }

    private fun fromUtf16(value: String, utf16Offset: Int, system: TshCoordinateSystem): Long? {
        if (!isUtf16Boundary(value, utf16Offset)) return null
        return when (system) {
            TshCoordinateSystem.UTF16_CODE_UNIT -> utf16Offset.toLong()
            TshCoordinateSystem.UNICODE_CODE_POINT -> value.codePointCount(0, utf16Offset).toLong()
            TshCoordinateSystem.UTF8_BYTE -> {
                var bytes = 0L
                var index = 0
                while (index < utf16Offset) {
                    val codePoint = value.codePointAt(index)
                    bytes += utf8Length(codePoint)
                    index += Character.charCount(codePoint)
                }
                bytes
            }
            TshCoordinateSystem.GRAPHEME_CLUSTER -> {
                val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
                iterator.setText(value)
                var boundary = iterator.first()
                var clusters = 0L
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
            if (converted > Int.MAX_VALUE.toLong()) failure(
                "TSH-COORD-005", "Converted offset exceeds Int.MAX_VALUE; use convertLong()"
            ) else TshResult.Success(converted.toInt(), diagnostics)
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
