package com.zerostudio.idea.treesitter

import android.text.SpannableStringBuilder
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals as junitAssertEquals
import org.junit.Assert.assertFalse as junitAssertFalse
import org.junit.Assert.assertTrue as junitAssertTrue

/**
 * Android Spannable integration tests run on a real Android instrumentation runtime.
 */
@RunWith(AndroidJUnit4::class)
class WindbellTshSpannableRenderingTest {
    private fun assertTrue(condition: Boolean, message: String? = null) = junitAssertTrue(message, condition)
    private fun assertFalse(condition: Boolean, message: String? = null) = junitAssertFalse(message, condition)
    private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) = junitAssertEquals(message, expected, actual)
    @Before
    fun setUp() {
        TreeSitter.loadLibrary()
    }

    private fun javaTestDsl(): String {
            return """
                name: ["Java", ".java"]
                styles: [
                    "keyword", #FF007F, #CC7832, @B,
                    "string", #007F00, #6A8759,
                    "comment", #808080, #808080, @I
                ]
                defines: [
                    "ident": /[a-zA-Z_][a-zA-Z0-9_]*/
                ]
                contains: [
                    { match: keywordsToRegex("public class static void int if else return"), style: "keyword" },
                    { start: { match: '"' }, end: { match: '"' }, style: "string" },
                    { match: /\/\/[^\n]*/, style: "comment" }
                ]
            """.trimIndent()
        }

    @Test
        fun testConcurrentHighlightAndSharedSpannableRendering() {
            val engine = WindbellTshEngine.instance
            assertTrue(engine.loadTshSource("java-concurrency", javaTestDsl(), null))
    
            val source = """
                // concurrency 👨‍👩‍👧‍👦 𠮷
                public class Demo {
                    String value = "hello 🌍";
                    static void run() { return; }
                }
            """.trimIndent()
            val expected = engine.computeHighlights(null, source, "java-concurrency")
            assertFalse(expected.isEmpty(), "The single-thread baseline must produce tokens.")
    
            // Deliberately share one mutable Spannable to verify that only writes to
            // that document are serialized; native highlight computation stays parallel.
            val sharedBuffer = SpannableStringBuilder(source)
            val pool = Executors.newFixedThreadPool(32)
            try {
                val jobs = (0 until 32).map {
                    pool.submit {
                        repeat(30) {
                            val actual = engine.computeHighlights(null, source, "java-concurrency")
                            assertEquals(expected, actual, "Concurrent output must equal the single-thread baseline.")
                            engine.applyHighlightSpansToSpannable(
                                spans = actual,
                                documentOffset = 0,
                                text = source,
                                extension = "java-concurrency",
                                spannable = sharedBuffer
                            )
                        }
                    }
                }
                jobs.forEach { it.get(60, TimeUnit.SECONDS) }
                assertTrue(sharedBuffer.getSpans(0, sharedBuffer.length, android.text.style.CharacterStyle::class.java).isNotEmpty())
            } finally {
                pool.shutdownNow()
            }
        }
    
        @Test
        fun testIncrementalRenderPreservesSpansOutsideRequestedRange() {
            val engine = WindbellTshEngine.instance
            assertTrue(engine.loadTshSource("java-incremental-range", javaTestDsl(), null))
            val source = "public class RangeDemo { String value = \"hello\"; }"
            val buffer = SpannableStringBuilder(source)
            val sentinel = android.text.style.ForegroundColorSpan(0xFF123456.toInt())
            buffer.setSpan(sentinel, 0, 3, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    
            val spans = engine.computeHighlights(null, source, "java-incremental-range")
            engine.applyHighlightSpansToSpannable(
                spans = spans,
                documentOffset = 0,
                text = source,
                extension = "java-incremental-range",
                spannable = buffer,
                start = 10,
                end = 20
            )
    
            assertTrue(
                buffer.getSpans(0, 3, android.text.style.ForegroundColorSpan::class.java)
                    .any { it === sentinel },
                "A partial render must preserve pre-existing spans outside its invalidation range."
            )
        }
    
        @Test
        fun testOverlappingForegroundSpansCarryDeterministicPrecedence() {
            val engine = WindbellTshEngine.instance
            val source = """
                tsh 2 {
                    language { id: "span-precedence" extensions: [".span-precedence"] }
                    styles {
                        outer { scope: "meta.outer" }
                        inner { scope: "entity.name.inner" }
                    }
                    schemes {
                        test {
                            light [ outer: #112233, inner: #AABBCC ]
                            dark [ outer: #223344, inner: #BBCCDD ]
                        }
                    }
                }
            """.trimIndent()
            assertTrue(engine.loadTshSource("span-precedence", source, null))
            assertTrue(engine.selectScheme("span-precedence", "test"))
            engine.setAppearance("span-precedence", false)
    
            val styles = engine.getStyleCatalog("span-precedence").associateBy { it.name }
            val outerId = styles.getValue("outer").styleId
            val innerId = styles.getValue("inner").styleId
            val captures = listOf(
                WindbellTshEngine.HighlightSpan(0, 8, outerId, 0, 0, 0, 2),
                WindbellTshEngine.HighlightSpan(2, 6, innerId, 0, 10, 1, 3)
            )
            val styled = SpannableStringBuilder("abcdefgh")
            engine.applyHighlightSpansToSpannable(captures, 0, "abcdefgh", "span-precedence", styled)
    
            val innerColor = engine.getSchemePalette("span-precedence", "test").getValue(innerId).lightColor
            val spans = styled.getSpans(2, 6, android.text.style.ForegroundColorSpan::class.java)
            val winning = spans.maxByOrNull {
                styled.getSpanFlags(it) and android.text.Spanned.SPAN_PRIORITY
            }
            assertEquals(innerColor, winning?.foregroundColor)
            assertTrue(spans.size >= 2, "Both nested captures must remain available to the painter")
            engine.clearAppearanceOverride("span-precedence")
        }
}
