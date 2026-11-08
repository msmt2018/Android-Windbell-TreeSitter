package x.github.module.editor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
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


    @Test
    fun backgroundColorIsPaintedAcrossTheRow() {
        val text = SpannableString("MMMM")
        text.setSpan(
            BackgroundColorSpan(Color.YELLOW),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val bitmap = render(text)
        val pixel = bitmap.getPixel(10, 4)
        assertTrue(
            "Expected background color outside glyph coverage",
            Color.red(pixel) > 220 && Color.green(pixel) > 180 && Color.blue(pixel) < 80
        )
    }

    @Test
    fun higherPriorityBackgroundWinsWhenSpansOverlap() {
        val text = SpannableString("MMMM")
        text.setSpan(
            BackgroundColorSpan(Color.RED),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            BackgroundColorSpan(Color.BLUE),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (2 shl Spanned.SPAN_PRIORITY_SHIFT)
        )

        val bitmap = render(text)
        val pixel = bitmap.getPixel(10, 4)
        assertTrue(
            "Expected the higher-priority blue background span",
            Color.blue(pixel) > 180 && Color.red(pixel) < 80
        )
    }

    @Test
    fun underlineAndStrikethroughProduceCanvasPixels() {
        val underlined = SpannableString("MMMM")
        underlined.setSpan(
            UnderlineSpan(),
            0,
            underlined.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val underlineBitmap = render(underlined)
        assertTrue(
            "Expected underline pixels below the glyph baseline",
            hasPixel(underlineBitmap, 8, 130) { red, green, blue ->
                red < 80 && green < 80 && blue < 80
            } && hasDarkPixelInRows(underlineBitmap, 58, 78)
        )

        val struck = SpannableString("MMMM")
        struck.setSpan(
            StrikethroughSpan(),
            0,
            struck.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val strikeBitmap = render(struck)
        assertTrue(
            "Expected strikethrough pixels through the glyph body",
            hasDarkPixelInRows(strikeBitmap, 30, 50)
        )
    }

    private fun hasDarkPixelInRows(bitmap: Bitmap, firstRow: Int, lastRow: Int): Boolean {
        for (y in firstRow until minOf(lastRow, bitmap.height)) {
            for (x in 8 until minOf(130, bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) {
                    return true
                }
            }
        }
        return false
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
