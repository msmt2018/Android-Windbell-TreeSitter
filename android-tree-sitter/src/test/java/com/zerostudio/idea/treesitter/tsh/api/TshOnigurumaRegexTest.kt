package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TshOnigurumaRegexTest {
    @Test
    fun rejectsSearchCursorAndResetStartEscapes() {
        assertEquals('G', unsupportedWindowSearchEscape("\\Gfoo"))
        assertEquals('K', unsupportedWindowSearchEscape("foo\\K"))
    }

    @Test
    fun doesNotRejectEscapedBackslashOrOrdinaryPatterns() {
        assertNull(unsupportedWindowSearchEscape("foo"))
        assertNull(unsupportedWindowSearchEscape("\\\\G"))
    }
}
