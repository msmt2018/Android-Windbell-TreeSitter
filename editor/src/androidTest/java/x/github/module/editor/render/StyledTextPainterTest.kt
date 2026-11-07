package x.github.module.editor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the final Canvas-facing paint state rather than merely checking that
 * ForegroundColorSpan instances exist in the source Spannable.
 *
 * This is an instrumentation test because Android's Canvas/TextPaint implementations
 * are framework-backed and the local JVM android.jar stubs cannot paint text.
 */
class StyledTextPainterTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val textPaintColors = ArrayList<Int>()

        override fun drawText(
            text: String,
            start: Int,
            end: Int,
            x: Float,
            y: Float,
            paint: Paint
        ) {
            textPaintColors += paint.color
            super.drawText(text, start, end, x, y, paint)
        }
    }

    @Test
    fun drawUsesHighestPriorityForegroundColorForNestedCaptures() {
        val bitmap = Bitmap.createBitmap(160, 64, Bitmap.Config.ARGB_8888)
        try {
            val canvas = RecordingCanvas(bitmap)
            val text = "abcd"
            val spans = SpannableStringBuilder(text)
            val outerColor = 0xFFE53935.toInt()
            val innerColor = 0xFF1E88E5.toInt()

            spans.setSpan(
                ForegroundColorSpan(outerColor),
                0,
                4,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or
                    (1 shl Spanned.SPAN_PRIORITY_SHIFT)
            )
            spans.setSpan(
                ForegroundColorSpan(innerColor),
                1,
                3,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or
                    (2 shl Spanned.SPAN_PRIORITY_SHIFT)
            )

            val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 20f
                color = 0xFF202020.toInt()
            }
            StyledTextPainter().draw(
                canvas = canvas,
                text = text,
                spans = spans,
                start = 0,
                end = text.length,
                x = 4f,
                baseline = 32f,
                rowTop = 0f,
                rowBottom = 40f,
                basePaint = basePaint
            )

            assertEquals(listOf(outerColor, innerColor, outerColor), canvas.textPaintColors)

            // Verify that the Canvas wrote pixels using the foreground colors,
            // not merely that the painter passed the expected Paint to drawText().
            var redPixels = 0
            var bluePixels = 0
            for (py in 0 until bitmap.height) {
                for (px in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(px, py)
                    val alpha = pixel ushr 24 and 0xFF
                    val red = pixel ushr 16 and 0xFF
                    val green = pixel ushr 8 and 0xFF
                    val blue = pixel and 0xFF
                    if (alpha > 0 && red > green * 1.5 && red > blue * 1.5) redPixels++
                    if (alpha > 0 && blue > red * 1.5 && blue > green * 1.2) bluePixels++
                }
            }
            assertTrue("Canvas bitmap must contain the outer foreground color", redPixels > 0)
            assertTrue("Canvas bitmap must contain the nested foreground color", bluePixels > 0)
        } finally {
            bitmap.recycle()
        }
    }
}
