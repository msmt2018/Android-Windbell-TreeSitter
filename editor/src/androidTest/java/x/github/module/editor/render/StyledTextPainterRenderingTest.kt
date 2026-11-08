package x.github.module.editor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pixel-level regression tests for the editor's custom Canvas text renderer.
 *
 * These tests deliberately inspect the rendered bitmap instead of only checking
 * that Android spans were attached: StyledTextPainter does not use TextView/Layout,
 * so a span can exist while its visual effect is silently ignored.
 */
@RunWith(AndroidJUnit4::class)
class StyledTextPainterRenderingTest {

    @Test
    fun foregroundColorIsPresentInCanvasPixels() {
        val text = SpannableString("MMMM")
        text.setSpan(
            ForegroundColorSpan(Color.RED),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val bitmap = render(text)
        assertTrue(
            "Expected red glyph pixels from ForegroundColorSpan",
            hasPixel(bitmap, 8, 130) { red, green, blue ->
                red > 180 && green < 80 && blue < 80
            }
        )
    }

    @Test
    fun customCharacterStyleAlphaIsAppliedToCanvasPixels() {
        val text = SpannableString("MMMM")
        text.setSpan(
            ForegroundColorSpan(Color.RED),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            object : CharacterStyle() {
                override fun updateDrawState(textPaint: TextPaint) {
                    textPaint.alpha = minOf(textPaint.alpha, 128)
                }
            },
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val bitmap = render(text)
        assertTrue(
            "Expected translucent red glyph pixels composited over the white canvas",
            hasPixel(bitmap, 8, 130) { red, green, blue ->
                red > 180 && green in 35..220 && blue in 35..220
            }
        )
        assertTrue(
            "Expected alpha to prevent fully opaque red glyph pixels",
            !hasPixel(bitmap, 8, 130) { red, green, blue ->
                red > 245 && green < 8 && blue < 8
            }
        )
    }

    private fun render(text: SpannableString): Bitmap {
        val bitmap = Bitmap.createBitmap(180, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 42f
        }
        StyledTextPainter().draw(
            canvas = canvas,
            text = text.toString(),
            spans = text,
            start = 0,
            end = text.length,
            x = 8f,
            baseline = 56f,
            rowTop = 0f,
            rowBottom = 80f,
            basePaint = paint
        )
        return bitmap
    }

    private fun hasPixel(
        bitmap: Bitmap,
        startX: Int,
        endX: Int,
        predicate: (red: Int, green: Int, blue: Int) -> Boolean
    ): Boolean {
        for (y in 0 until bitmap.height) {
            for (x in startX until minOf(endX, bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (predicate(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                    return true
                }
            }
        }
        return false
    }
}
