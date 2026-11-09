package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshCoordinateConverterTest {
    @Test
    fun convertsUtf8Utf16AndCodePointOffsetsWithSupplementaryCharacters() {
        val text = "A𠮷B"
        val utf8 = TshCoordinateConverter.convert(
            text, 5, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
        )
        assertEquals(3, (utf8 as TshResult.Success).value)

        val codePoints = TshCoordinateConverter.convert(
            text, 2, TshCoordinateSystem.UNICODE_CODE_POINT, TshCoordinateSystem.UTF16_CODE_UNIT
        )
        assertEquals(3, (codePoints as TshResult.Success).value)
    }

    @Test
    fun rejectsOffsetsInsideSurrogatePairsAndUtf8Sequences() {
        val text = "A𠮷B"
        val utf16 = TshCoordinateConverter.convert(
            text, 2, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UTF8_BYTE
        )
        assertTrue(utf16 is TshResult.Failure)

        val utf8 = TshCoordinateConverter.convert(
            text, 2, TshCoordinateSystem.UTF8_BYTE, TshCoordinateSystem.UTF16_CODE_UNIT
        )
        assertTrue(utf8 is TshResult.Failure)
    }

    @Test
    fun rejectsUnpairedSurrogatesAndProvidesLongOffsetEntryPoint() {
        val invalid = TshCoordinateConverter.convert(
            "\uD800", 0, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UTF8_BYTE
        )
        assertTrue(invalid is TshResult.Failure)
        val large = TshCoordinateConverter.convertLong(
            "abc", 2L, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.UTF8_BYTE
        )
        assertEquals(2L, (large as TshResult.Success).value)
    }

    @Test
    fun reportsThatPixelAndLineColumnNeedLayoutProvider() {
        val result = TshCoordinateConverter.convert(
            "abc", 1, TshCoordinateSystem.UTF16_CODE_UNIT, TshCoordinateSystem.SCREEN_PIXEL
        )
        assertTrue(result is TshResult.Failure)
    }
}
