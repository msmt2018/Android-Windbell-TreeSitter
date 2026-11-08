package com.zerostudio.idea.androidtreesitter

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.util.Log
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSTree
import com.zerostudio.idea.treesitter.largefile.ByteBudgetLruCache
import com.zerostudio.idea.treesitter.largefile.CharSequenceUtf16DocumentSource
import com.zerostudio.idea.treesitter.largefile.DocumentPartitionManager
import com.zerostudio.idea.treesitter.largefile.PartitionAstCache
import com.zerostudio.idea.treesitter.largefile.PartitionParseRequest
import com.zerostudio.idea.treesitter.largefile.TreeSitterPartitionScheduler
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.input.AutoPairFilter
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.EditorTextSnapshot
import x.github.module.editor.structure.PlainBracketMatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TSH adapter for EditorView.
 *
 * Small documents use the original whole-document pipeline. Large documents use
 * immutable Piece Tree snapshots, sparse partition indexing, viewport-prioritized
 * parsing, and byte-budgeted capture/AST caches. Only the current partition's
 * bounded context window is materialized as a String for the existing TSH regex,
 * Monarch, ATS and Tree-sitter Query pipeline.
 */
class TshSyntaxHighlighter(
    private val language: TSLanguage,
    private val extension: String,
    private val engine: WindbellTshEngine = WindbellTshEngine.instance
) : SyntaxHighlighter {

    companion object {
        private const val TAG = "WindbellTSH"
        private const val LARGE_DOCUMENT_THRESHOLD_CHARS = 1 * 1024 * 1024
        private const val PARTITION_CHARS = 64L * 1024L
        private const val CONTEXT_CHARS = 128L * 1024L
        private const val AST_CACHE_BYTES = 16L * 1024L * 1024L
        private const val CAPTURE_CACHE_BYTES = 32L * 1024L * 1024L
    }

    private data class LargeSession(
        val snapshot: EditorTextSnapshot,
        val source: CharSequenceUtf16DocumentSource,
        val partitions: DocumentPartitionManager,
        val parser: TSParser,
        val astCache: PartitionAstCache,
        val scheduler: TreeSitterPartitionScheduler,
        val captureIndexes: ByteBudgetLruCache<Int, CaptureIntervalIndex>,
        val ticket: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()
    private val firstStyleCallLogged = AtomicBoolean(false)

    @Volatile private var host: SyntaxHighlighter.Host? = null
    @Volatile private var captureIndex = CaptureIntervalIndex(emptyList())
    @Volatile private var largeSession: LargeSession? = null
    @Volatile private var parsing = false

    private val editorMetadata by lazy { engine.getEditorMetadata(extension) }
    private val stylesById by lazy { engine.getStyleCatalog(extension).associateBy { it.styleId } }
    private var parseJob: Job? = null

    override fun onAttached(host: SyntaxHighlighter.Host) {
        this.host = host
        Log.i(TAG, "Attached to EditorView: extension=.$extension textLength=${host.buffer.length}")
        scheduleParse(host.buffer)
    }

    override fun onDetached() {
        generation.incrementAndGet()
        parseJob?.cancel(CancellationException("TSH highlighter detached"))
        parseJob = null
        largeSession?.let(::disposeSession)
        largeSession = null
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
        val visibleStart = start.coerceIn(0, text.length)
        val visibleEnd = end.coerceIn(visibleStart, text.length)
        if (visibleStart >= visibleEnd || lineStartOffset < 0) return null

        // EditorView passes columns within the line, not document offsets. Query only
        // the painted slice: this matters for horizontally scrolled/minified lines and
        // avoids requesting every partition crossed by an off-screen part of the line.
        val queryStart = lineStartOffset.toLong() + visibleStart.toLong()
        val queryEnd = lineStartOffset.toLong() + visibleEnd.toLong()
        if (queryStart > Int.MAX_VALUE || queryEnd > Int.MAX_VALUE) {
            Log.e(TAG, "Editor offset exceeds the 32-bit capture index: start=$queryStart end=$queryEnd")
            return null
        }
        val active = largeSession
        val spans = if (active != null) {
            val boundedStart = queryStart.coerceAtMost(active.source.length)
            val boundedEnd = queryEnd.coerceAtMost(active.source.length)
            if (boundedStart >= boundedEnd) emptyList()
            else queryLargeCaptures(
                active, boundedStart, boundedEnd, boundedStart.toInt(), boundedEnd.toInt()
            )
        } else {
            captureIndex.query(queryStart.toInt(), queryEnd.toInt())
        }

        if (firstStyleCallLogged.compareAndSet(false, true)) {
            Log.i(TAG, "Editor renderer invoked styleLine: extension=.$extension line=$bufferLine range=$start..$end captures=${spans.size}")
        }
        if (spans.isEmpty()) return null

        val styled = SpannableStringBuilder(text)
        engine.applyHighlightSpansToSpannable(
            spans = spans,
            documentOffset = lineStartOffset,
            text = text,
            extension = extension,
            spannable = styled,
            start = visibleStart,
            end = visibleEnd
        )
        val applied = styled.getSpans(visibleStart, visibleEnd, CharacterStyle::class.java)
        if (applied.isEmpty()) {
            Log.w(TAG, "No Android CharacterStyle applied: extension=.$extension line=$bufferLine captures=${spans.size}")
            return null
        }
        return styled
    }

    override fun isStyleReady(
        bufferLine: Int,
        lineStartOffset: Int,
        start: Int,
        end: Int
    ): Boolean {
        val active = largeSession ?: return !parsing
        if (lineStartOffset < 0 || start < 0 || end <= start) return false

        // start/end are columns in the current buffer line. Omitting start here used
        // to test the first columns even when the renderer was painting a later
        // horizontal slice, allowing an uncached partition to be treated as ready.
        val queryStart = lineStartOffset.toLong() + start.toLong()
        val queryEnd = lineStartOffset.toLong() + end.toLong()
        if (queryStart >= active.source.length) return true
        if (queryStart > Int.MAX_VALUE || queryEnd < queryStart) return false
        val boundedStart = queryStart.coerceAtLeast(0L)
        val boundedEnd = queryEnd.coerceAtMost(active.source.length)
        if (boundedStart >= boundedEnd) return true
        val partitions = active.partitions.planViewport(active.source, boundedStart, boundedEnd).partitions
        var ready = true
        for (partition in partitions) {
            if (active.captureIndexes.get(partition.index) == null) {
                ready = false
                requestLargePartition(active, partition.start, partition.endExclusive)
            }
        }
        return ready
    }

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
        val active = largeSession
        val spans = if (active != null) {
            if (offset < 0 || offset.toLong() >= active.source.length) {
                emptyList()
            } else {
                val partition = active.partitions.partitionAt(active.source, offset.toLong())
                active.captureIndexes.get(partition.index)?.query(offset, offset + 1).orEmpty()
            }
        } else {
            captureIndex.query(offset, offset + 1)
        }
        return spans.any { span ->
            val style = stylesById[span.styleId]
            excludedScopes.any { scope ->
                style?.tags?.contains(scope) == true || style?.scope?.startsWith(scope) == true
            }
        }
    }

    /** Theme changes only invalidate drawing; partition parses and captures remain valid. */
    fun refreshTheme() {
        host?.invalidateStyles()
    }

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
        // PieceTreeBuffer snapshots retain their backing chunks. Large-file mode
        // never calls getText() or snapshot.toString() for the complete document.
        val snapshot = buffer.createTextSnapshot()
        val ticket = generation.incrementAndGet()
        parseJob?.cancel(CancellationException("Superseded by newer document snapshot"))
        largeSession?.let(::disposeSession)
        largeSession = null
        captureIndex = CaptureIntervalIndex(emptyList())
        parsing = true

        parseJob = scope.launch {
            delay(45)
            if (snapshot.length >= LARGE_DOCUMENT_THRESHOLD_CHARS) {
                initializeLargeSession(snapshot, ticket)
            } else {
                parseSmallSnapshot(snapshot, ticket)
            }
        }
    }

    private suspend fun initializeLargeSession(snapshot: EditorTextSnapshot, ticket: Long) {
        var parser: TSParser? = null
        var astCache: PartitionAstCache? = null
        var scheduler: TreeSitterPartitionScheduler? = null
        var captureIndexes: ByteBudgetLruCache<Int, CaptureIntervalIndex>? = null
        try {
            val createdParser = TSParser.create().also { it.setLanguage(language) }
            parser = createdParser
            val initJob = currentCoroutineContext()[Job]
            val source = CharSequenceUtf16DocumentSource(snapshot, snapshot.version) { start, count ->
                if (initJob?.isActive == false) {
                    throw CancellationException("Large-file partition indexing superseded")
                }
                snapshot.getNearestChunk(start, count)
            }
            val manager = DocumentPartitionManager(
                targetPartitionChars = PARTITION_CHARS,
                prefetchPartitions = 0
            )
            // Full-file indexing is linear and chunked, never a full String/byte-array copy.
            manager.rebuild(source)
            currentCoroutineContext().ensureActive()
            if (generation.get() != ticket) throw CancellationException("Stale document snapshot")

            val createdAstCache = PartitionAstCache(AST_CACHE_BYTES) { length ->
                maxOf(128L * 1024L, length * 64L)
            }
            astCache = createdAstCache
            val createdScheduler = TreeSitterPartitionScheduler(
                partitionManager = manager,
                astCache = createdAstCache,
                contextChars = CONTEXT_CHARS,
                parallelism = 1,
                onFailure = { request, error ->
                    Log.e(TAG, "Partition parse failed: extension=.$extension partition=${request.partition.index}", error)
                    if (generation.get() == ticket) host?.invalidateStyles()
                }
            )
            scheduler = createdScheduler
            val createdCaptureIndexes = ByteBudgetLruCache<Int, CaptureIntervalIndex>(
                CAPTURE_CACHE_BYTES,
                { index -> 96L + index.size.toLong() * 96L }
            )
            captureIndexes = createdCaptureIndexes

            val active = LargeSession(
                snapshot,
                source,
                manager,
                createdParser,
                createdAstCache,
                createdScheduler,
                createdCaptureIndexes,
                ticket
            )
            if (generation.get() == ticket) {
                largeSession = active
                parser = null
                astCache = null
                scheduler = null
                captureIndexes = null
                parsing = false
                Log.i(
                    TAG,
                    "Large-file TSH index ready: extension=.$extension version=${snapshot.version} " +
                        "utf16Length=${snapshot.length} partitions=${manager.snapshot().size} " +
                        "source=PieceTreeSnapshot mode=viewport-partitioned"
                )
                host?.invalidateStyles()
                host?.invalidateStructure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation.get() == ticket) {
                Log.e(TAG, "Large-file TSH initialization failed: extension=.$extension length=${snapshot.length}", error)
                parsing = false
                host?.invalidateStyles()
            }
        } finally {
            scheduler?.close() ?: astCache?.close()
            parser?.close()
            captureIndexes?.close()
        }
    }

    private suspend fun parseSmallSnapshot(snapshot: EditorTextSnapshot, ticket: Long) {
        var parser: TSParser? = null
        var tree: TSTree? = null
        try {
            val createdParser = TSParser.create().also { it.setLanguage(language) }
            parser = createdParser
            val text = snapshot.toString()
            val parsedTree = createdParser.parseString(text)
            tree = parsedTree
            val nextCaptures = if (parsedTree != null) {
                engine.computeHighlights(parsedTree, text, extension)
            } else {
                emptyList()
            }
            if (generation.get() == ticket) {
                captureIndex = CaptureIntervalIndex(nextCaptures)
                parsing = false
                logPipelineReady(snapshot.length, nextCaptures)
                host?.invalidateStyles()
                host?.invalidateStructure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation.get() == ticket) {
                Log.e(TAG, "TSH parse/highlight failed for .$extension (length=${snapshot.length})", error)
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

    private fun queryLargeCaptures(
        active: LargeSession,
        start: Long,
        end: Long,
        queryStartUtf16: Int,
        queryEndUtf16: Int
    ): List<WindbellTshEngine.HighlightSpan> {
        if (generation.get() != active.ticket || largeSession !== active) return emptyList()
        val partitions = active.partitions.planViewport(active.source, start, end).partitions
        val result = ArrayList<WindbellTshEngine.HighlightSpan>()
        for (partition in partitions) {
            val index = active.captureIndexes.get(partition.index)
            if (index == null) {
                requestLargePartition(active, partition.start, partition.endExclusive)
            } else {
                result += index.query(queryStartUtf16, queryEndUtf16)
            }
        }
        return result
    }

    private fun requestLargeViewport(active: LargeSession, start: Long, end: Long) {
        if (generation.get() != active.ticket || largeSession !== active) return
        val partitions = active.partitions.planViewport(active.source, start, end).partitions
        partitions.forEach { partition ->
            if (active.captureIndexes.get(partition.index) == null) {
                requestLargePartition(active, partition.start, partition.endExclusive)
            }
        }
    }

    private fun requestLargePartition(active: LargeSession, start: Long, end: Long) {
        if (generation.get() != active.ticket || largeSession !== active) return
        val partition = active.partitions.partitionAt(
            active.source,
            start.coerceIn(0L, active.source.length)
        )
        if (active.captureIndexes.get(partition.index) != null) return

        // A capture page may have been evicted while its AST remains cached. Invalidate
        // that AST key so the scheduler reruns the bounded TSH capture pass for this page.
        active.astCache.invalidate { it.partitionIndex == partition.index }
        try {
            active.scheduler.requestViewport(
                active.source,
                partition.start,
                partition.endExclusive,
                active.ticket
            ) { request ->
                parseLargePartition(active, request)
            }
        } catch (error: IllegalStateException) {
            if (generation.get() == active.ticket) {
                Log.w(TAG, "Unable to queue large-file viewport parse", error)
            }
        }
    }

    private fun parseLargePartition(
        active: LargeSession,
        request: PartitionParseRequest
    ): TSTree? {
        if (generation.get() != active.ticket || largeSession !== active) return null
        val contextStart = request.contextStartUtf16.toInt()
        val contextEnd = request.contextEndUtf16.toInt()
        // Only this bounded context becomes a String. The complete Piece Tree snapshot
        // remains chunk-backed and is never flattened by the large-file pipeline.
        val contextText = active.snapshot.substring(contextStart, contextEnd)
        val tree = active.parser.parseString(contextText) ?: return null
        try {
            val localSpans = engine.computeHighlights(tree, contextText, extension)
            val baseByte = request.contextStartUtf16 * 2L
            val partitionStartByte = request.partition.start * 2L
            val partitionEndByte = request.partition.endExclusive * 2L
            val absoluteSpans = localSpans.mapNotNull { span ->
                val absoluteStart = baseByte + span.startByte.toLong()
                val absoluteEnd = baseByte + span.endByte.toLong()
                val clippedStart = maxOf(absoluteStart, partitionStartByte)
                val clippedEnd = minOf(absoluteEnd, partitionEndByte)
                if (clippedStart >= clippedEnd || clippedEnd > Int.MAX_VALUE) {
                    null
                } else {
                    span.copy(
                        startByte = clippedStart.toInt(),
                        endByte = clippedEnd.toInt()
                    )
                }
            }
            if (generation.get() == active.ticket && largeSession === active) {
                active.captureIndexes.put(
                    request.partition.index,
                    CaptureIntervalIndex(absoluteSpans)
                )
                host?.invalidateStyles()
                host?.invalidateStructure()
            }
            return tree
        } catch (error: Throwable) {
            tree.close()
            throw error
        }
    }

    private fun logPipelineReady(length: Int, captures: List<WindbellTshEngine.HighlightSpan>) {
        val styleIds = captures.map { it.styleId }.distinct()
        val catalog = stylesById
        val missingPalette = styleIds.filter { id ->
            val style = catalog[id]
            style == null || engine.getStylePalette(extension, style.name) == null
        }
        Log.i(
            TAG,
            "TSH pipeline ready: extension=.$extension textLength=$length spans=${captures.size} " +
                "styles=${catalog.size} usedStyleIds=$styleIds missingPalette=$missingPalette"
        )
    }

    private fun disposeSession(active: LargeSession) {
        scope.launch(Dispatchers.IO) {
            runCatching { active.scheduler.close() }
                .onFailure { Log.w(TAG, "Failed to close large-file partition scheduler", it) }
            runCatching { active.parser.close() }
                .onFailure { Log.w(TAG, "Failed to close large-file Tree-sitter parser", it) }
            runCatching { active.captureIndexes.close() }
                .onFailure { Log.w(TAG, "Failed to close large-file capture cache", it) }
        }
    }
}
