package com.zerostudio.idea.androidtreesitter

import android.text.SpannableStringBuilder
import android.text.Spanned
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSTree
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditorDocument
import java.util.concurrent.atomic.AtomicLong

/**
 * Connects a loaded TSH language package to EditorView's line-based renderer.
 *
 * Parsing and capture calculation happen on a worker dispatcher. Drawing only reads
 * the last immutable capture snapshot and maps document UTF-16 offsets to line offsets.
 */
class TshSyntaxHighlighter(
    private val language: TSLanguage,
    private val extension: String,
    private val engine: WindbellTshEngine = WindbellTshEngine.instance
) : SyntaxHighlighter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()
    @Volatile private var host: SyntaxHighlighter.Host? = null
    @Volatile private var capturesByLine: Map<Int, List<WindbellTshEngine.HighlightSpan>> = emptyMap()
    @Volatile private var parsing = false
    private var parseJob: Job? = null

    override fun onAttached(host: SyntaxHighlighter.Host) {
        this.host = host
        scheduleParse(host.buffer)
    }

    override fun onDetached() {
        generation.incrementAndGet()
        parseJob?.cancel(CancellationException("TSH highlighter detached"))
        parseJob = null
        capturesByLine = emptyMap()
        parsing = false
        host = null
    }

    override fun onTextReplaced(buffer: EditorBuffer) {
        captures = emptyList()
        scheduleParse(buffer)
    }

    override fun onTextChanged(change: EditorDocument.AppliedChange) {
        // The editor batches changes and calls onEditFinished once per edit.
    }

    override fun onEditFinished() {
        host?.buffer?.let(::scheduleParse)
    }

    override fun styleLine(
        text: String,
        bufferLine: Int,
        lineStartOffset: Int,
        start: Int,
        end: Int
    ): Spanned? {
        val snapshot = capturesByLine[bufferLine].orEmpty()
        if (snapshot.isEmpty() || start >= end) return null

        val styled = SpannableStringBuilder(text)
        engine.applyHighlightSpansToSpannable(
            spans = snapshot,
            documentOffset = lineStartOffset,
            text = text,
            extension = extension,
            spannable = styled,
            start = start,
            end = end
        )
        return if (styled.getSpans(start, end, Any::class.java).isEmpty()) null else styled
    }

    override fun isStyleReady(
        bufferLine: Int,
        lineStartOffset: Int,
        start: Int,
        end: Int
    ): Boolean = !parsing

    /** Call after selecting another scheme or appearance to repaint without reparsing. */
    fun refreshTheme() {
        host?.invalidateStyles()
    }

    fun selectScheme(name: String): Boolean {
        val selected = engine.selectScheme(extension, name)
        if (selected) refreshTheme()
        return selected
    }

    fun setAppearance(isDark: Boolean) {
        engine.setAppearance(extension, isDark)
        refreshTheme()
    }

    fun clearAppearanceOverride() {
        engine.clearAppearanceOverride(extension)
        refreshTheme()
    }

    private fun indexCapturesByLine(
        text: String,
        spans: List<WindbellTshEngine.HighlightSpan>
    ): Map<Int, List<WindbellTshEngine.HighlightSpan>> {
        if (text.isEmpty() || spans.isEmpty()) return emptyMap()

        val lineStarts = ArrayList<Int>()
        lineStarts.add(0)
        for (index in text.indices) {
            if (text[index] == '\n') lineStarts.add(index + 1)
        }

        fun lineAt(offset: Int): Int {
            var low = 0
            var high = lineStarts.lastIndex
            var result = 0
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (lineStarts[middle] <= offset) {
                    result = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return result
        }

        val mutable = HashMap<Int, MutableList<WindbellTshEngine.HighlightSpan>>()
        for (span in spans) {
            val start = (span.startByte shr 1).coerceIn(0, text.length)
            val end = (span.endByte shr 1).coerceIn(start, text.length)
            if (start == end) continue
            val firstLine = lineAt(start)
            val lastLine = lineAt((end - 1).coerceAtLeast(start))
            for (lineIndex in firstLine..lastLine) {
                mutable.getOrPut(lineIndex + 1) { ArrayList() }.add(span)
            }
        }
        return mutable.mapValues { (_, value) -> value.toList() }
    }

    private fun scheduleParse(buffer: EditorBuffer) {
        val textSnapshot = buffer.getText()
        val ticket = generation.incrementAndGet()
        parseJob?.cancel(CancellationException("Superseded by newer document snapshot"))
        parsing = true

        parseJob = scope.launch {
            var parser: TSParser? = null
            var tree: TSTree? = null
            try {
                parser = TSParser.create().also { it.setLanguage(language) }
                tree = parser.parseString(textSnapshot)
                val nextCaptures = engine.computeHighlights(tree, textSnapshot, extension)
                if (generation.get() == ticket) {
                    capturesByLine = indexCapturesByLine(textSnapshot, nextCaptures)
                    parsing = false
                    host?.invalidateStyles()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation.get() == ticket) {
                    capturesByLine = emptyMap()
                    parsing = false
                    host?.invalidateStyles()
                }
            } finally {
                tree?.close()
                parser?.close()
            }
        }
    }
}
