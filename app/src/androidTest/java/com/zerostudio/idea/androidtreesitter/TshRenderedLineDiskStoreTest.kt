package com.zerostudio.idea.androidtreesitter

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.tsh.TshAlphaCharacterStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TshRenderedLineDiskStoreTest {
    @Test fun roundTripsFinalAndroidSpansWithoutRebuildingPaletteStyles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(
            context.cacheDir,
            "tsh-rendered-disk-test-${System.nanoTime()}"
        )
        val text = "render-cache"
        val source = SpannableStringBuilder(text)
        source.setSpan(ForegroundColorSpan(Color.RED), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(BackgroundColorSpan(Color.BLUE), 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(StyleSpan(Typeface.BOLD), 0, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(UnderlineSpan(), 1, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(StrikethroughSpan(), 4, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(TshAlphaCharacterStyle(120), 7, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val immutable = SpannedString(source)
        val store = TshRenderedLineDiskStore(directory)
        try {
            store.put(3L, 128, 0, text.length, "dark", true, text, immutable)
            assertTrue(store.contains(3L, 128, 0, text.length, "dark", true))
            val restored = store.get(3L, 128, 0, text.length, "dark", true, text)
            assertNotNull(restored)
            assertEquals(Color.RED, restored!!.getSpans(0, text.length, ForegroundColorSpan::class.java).single().foregroundColor)
            assertEquals(Color.BLUE, restored.getSpans(0, text.length, BackgroundColorSpan::class.java).single().backgroundColor)
            assertEquals(Typeface.BOLD, restored.getSpans(0, text.length, StyleSpan::class.java).single().style)
            assertEquals(1, restored.getSpanStart(restored.getSpans(0, text.length, UnderlineSpan::class.java).single()))
            assertEquals(4, restored.getSpanStart(restored.getSpans(0, text.length, StrikethroughSpan::class.java).single()))
            assertEquals(120, restored.getSpans(0, text.length, TshAlphaCharacterStyle::class.java).single().alpha)
        } finally {
            store.close()
        }
    }

    @Test fun rejectsDifferentRevisionSchemeAndTextLength() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(
            context.cacheDir,
            "tsh-rendered-disk-test-${System.nanoTime()}"
        )
        val text = "cached"
        val source = SpannableStringBuilder(text)
        source.setSpan(ForegroundColorSpan(Color.GREEN), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val store = TshRenderedLineDiskStore(directory)
        try {
            store.put(5L, 256, 0, text.length, "light", false, text, SpannedString(source))
            assertNull(store.get(6L, 256, 0, text.length, "light", false, text))
            assertNull(store.get(5L, 256, 0, text.length, "dark", false, text))
            assertNull(store.get(5L, 256, 0, text.length, "light", false, "changed"))
        } finally {
            store.close()
        }
    }
}
