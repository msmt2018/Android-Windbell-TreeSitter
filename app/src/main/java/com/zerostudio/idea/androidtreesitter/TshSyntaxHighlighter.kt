package com.zerostudio.idea.androidtreesitter

import android.util.Log
import android.text.style.CharacterStyle
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSTree
import com.zerostudio.idea.treesitter.largefile.parseWindowed
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.input.AutoPair
import x.github.module.editor.input.AutoPairFilter
import x.github.module.editor.structure.PlainBracketMatcher
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditorDocument
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

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

    companion object {
        private const val TAG = "WindbellTSH"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()
    private val firstStyleCallLogged = AtomicBoolean(false)
    @Volatile private var host: SyntaxHighlighter.Host? = null
    @Volatile private var captureIndex = CaptureIntervalIndex(emptyList())()
    private val editorMetadata by lazy { engine.getEditorMetadata(extension) }
    private val stylesById by lazy { engine.getStyleCatalog(extension).associateBy { it.styleId } }
    @Volatile private var parsing = false
    private var parseJob: Job? = null

    override fun onAttached(host: SyntaxHighlighter.Host) {
        this.host = host
        Log.i(TAG, "Attached to EditorView: extension=.$extension, textLength=${host.buffer.length}")
        scheduleParse(host.buffer)
    }

    override fun onDetached() {
        generation.incrementAndGet()
        parseJob?.cancel(CancellationException("TSH highlighter detached"))
        parseJob = null
        captureIndex = CaptureIntervalIndex(emptyList())
        parsing = false
        host = null
    }

    override fun onTextReplaced(buffer: EditorBuffer) {
        captureIndex = CaptureIntervalIndex(emptyList())
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
        // A compact interval index avoids duplicating multiline captures into every
        // line bucket. A long comment/string therefore occupies one index entry.
        val snapshot = captureIndex.query(lineStartOffset, lineStartOffset + text.length)
        if (start >= end) return null
        if (firstStyleCallLogged.compareAndSet(false, true)) {
            Log.i(TAG, "Editor renderer invoked styleLine: extension=.$extension line=$bufferLine range=$start..$end captures=${snapshot.size}")
        }
        if (snapshot.isEmpty()) return null

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
        val applied = styled.getSpans(start, end, CharacterStyle::class.java)
        if (applied.isEmpty()) {
            if (firstStyleCallLogged.get()) {
                Log.w(TAG, "No Android CharacterStyle applied: extension=.$extension line=$bufferLine captures=${snapshot.size} paletteMissing=${snapshot.map { it.styleId }.distinct().filter { id -> stylesById.values.none { style -> style.styleId == id && engine.getStylePalette(extension, style.name) != null } }}")
            }
            return null
        }
        return styled
    }

    override fun isStyleReady(
        bufferLine: Int,
        lineStartOffset: Int,
        start: Int,
        end: Int
    ): Boolean = !parsing

    fun createBracketMatcher(): PlainBracketMatcher? {
        val pairs = editorMetadata.brackets.joinToString("") { it.first + it.second }
        if (pairs.isEmpty()) return null
        return object : PlainBracketMatcher(pairs) {
            override fun isBracket(buffer: EditorBuffer, lineNumber: Int, index: Int): Boolean {
                val offset = buffer.getOffsetAt(lineNumber, index + 1)
                return !isInExcludedScope(offset, editorMetadata.bracketNotIn)
            }
        }
    }

    fun createAutoPairFilter(): AutoPairFilter = AutoPairFilter { pair, offset ->
        val configured = editorMetadata.autoClosingPairs.firstOrNull {
            it.open.length == 1 && it.close.length == 1 &&
                it.open[0] == pair.open && it.close[0] == pair.close
        }
        !isInExcludedScope(offset, configured?.notIn.orEmpty())
    }

    private fun isInExcludedScope(offset: Int, excludedScopes: List<String>): Boolean {
        if (excludedScopes.isEmpty()) return false
        return captureIndex.query(offset, offset + 1).any { span ->
            val style = stylesById[span.styleId]
            excludedScopes.any { scope ->
                style?.tags?.contains(scope) == true ||
                    style?.scope?.startsWith(scope) == true
            }
        }
    }

    /** Call after selecting another scheme or appearance to repaint without reparsing. */
    fun refreshTheme() {
        host?.invalidateStyles()
    }

    /** Re-runs the same parser/highlighter pipeline used by EditorView rendering. */
    fun reparse() {
        host?.buffer?.let(::scheduleParse)
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

    private fun scheduleParse(buffer: EditorBuffer) {
        // PieceTreeBuffer returns an immutable, versioned snapshot backed by its
        // original chunks. Never call getText() here: that eagerly flattens the
        // complete document before parsing even starts.
        val snapshot = buffer.createTextSnapshot()
        val ticket = generation.incrementAndGet()
        parseJob?.cancel(CancellationException("Superseded by newer document snapshot"))
        parsing = true

        parseJob = scope.launch {
            delay(45)
            var parser: TSParser? = null
            var tree: TSTree? = null
            try {
                parser = TSParser.create().also { it.setLanguage(language) }
                tree = parser.parseWindowed(
                    oldTree = null,
                    source = snapshot,
                    includedRanges = emptyArray()
                ) { offset, maxChars ->
                    snapshot.getNearestChunk(offset, maxChars)
                }

                // Regex/Monarch currently use the contiguous-source JNI ABI.
                // Tree-sitter now reads the immutable piece snapshot through bounded
                // windows and no longer allocates a second full-document native copy.
                val tshText = snapshot.toString()
                val nextCaptures = engine.computeHighlights(tree, tshText, extension)
                if (generation.get() == ticket) {
                    captureIndex = CaptureIntervalIndex(nextCaptures)
                    parsing = false
                    val styleIds = nextCaptures.map { it.styleId }.distinct()
                    val catalog = stylesById
                    val missingPalette = styleIds.filter { id ->
                        val style = catalog[id]
                        style == null || engine.getStylePalette(extension, style.name) == null
                    }
                    Log.i(TAG, "TSH pipeline ready: extension=.$extension snapshotVersion=${snapshot.version} textLength=${snapshot.length} spans=\${nextCaptures.size} indexedCaptures=\${nextCaptures.size} styles=\${catalog.size} usedStyleIds=$styleIds missingPalette=$missingPalette")
                    host?.invalidateStyles()
                    host?.invalidateStructure()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation.get() == ticket) {
                    Log.e(TAG, "TSH parse/highlight failed for .$extension (snapshotLength=${snapshot.length}, version=${snapshot.version})", error)
                    captureIndex = CaptureIntervalIndex(emptyList())
                    parsing = false
                    host?.invalidateStyles()
                    host?.invalidateStructure()
                }
            } finally {
                tree?.close()
                parser?.close()
            }
        }
    }
}
