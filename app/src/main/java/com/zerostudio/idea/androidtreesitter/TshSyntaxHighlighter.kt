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
import com.zerostudio.idea.treesitter.tsh.NativeTextWindowReader
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
import java.io.File
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
        /** Native-owned PieceTreeSource + incremental Tree-sitter tree. */
        val nativeHandle: Long,
        val lengthChars: AtomicLong,
        val captureIndexes: ByteBudgetLruCache<Int, CaptureIntervalIndex>,
        val ticket: Long
    )

    private data class NativeEdit(
        val startByte: Long,
        val oldEndByte: Long,
        val insertedUtf16Le: ByteArray,
        val insertedChars: Int,
        val deletedChars: Int
    )

    private val nativeEditLock = Any()
    private val pendingNativeEdits = ArrayList<NativeEdit>()

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
        synchronized(nativeEditLock) { pendingNativeEdits.clear() }
        captureIndex = CaptureIntervalIndex(emptyList())
        scheduleParse(buffer)
    }

    override fun onTextChanged(change: EditorDocument.AppliedChange) {
        // EditorDocument reports changes bottom-up in pre-edit coordinates. Preserve
        // that order: nativeApplyDocumentEdit consumes exactly the same transaction
        // sequence and copies only the inserted slice, never the unchanged document.
        val inserted = change.insertedText()
        val bytes = ByteArray(inserted.length * 2)
        for (index in inserted.indices) {
            val unit = inserted[index].code
            bytes[index * 2] = (unit and 0xFF).toByte()
            bytes[index * 2 + 1] = (unit ushr 8).toByte()
        }
        synchronized(nativeEditLock) {
            pendingNativeEdits += NativeEdit(
                startByte = change.rangeOffset.toLong() * 2L,
                oldEndByte = (change.rangeOffset.toLong() + change.deletedTextLength) * 2L,
                insertedUtf16Le = bytes,
                insertedChars = inserted.length,
                deletedChars = change.deletedTextLength
            )
        }
    }

    override fun onEditFinished() {
        val active: LargeSession?
        val edits: List<NativeEdit>
        synchronized(nativeEditLock) {
            active = largeSession
            if (active == null) {
                // Large-document initialization may still be consuming its original
                // immutable snapshot. Keep transactions queued for it to replay.
                if (host?.buffer?.length?.let { it < LARGE_DOCUMENT_THRESHOLD_CHARS } == true || !parsing) {
                    host?.buffer?.let(::scheduleParse)
                }
                return
            }
            edits = pendingNativeEdits.toList()
            pendingNativeEdits.clear()
        }
        if (edits.isEmpty()) return
        applyNativeEdits(active, edits)
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
        if (queryStart > Int.MAX_VALUE || queryEnd > Int.MAX_VALUE || queryEnd < queryStart) return false
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
        var nativeHandle = 0L
        var captureIndexes: ByteBudgetLruCache<Int, CaptureIntervalIndex>? = null
        try {
            val initJob = currentCoroutineContext()[Job]
            val source = CharSequenceUtf16DocumentSource(snapshot, snapshot.version) { start, count ->
                if (initJob?.isActive == false) {
                    throw CancellationException("Large-file partition indexing superseded")
                }
                snapshot.getNearestChunk(start, count)
            }
            // Native owns the canonical Piece Tree and sparse partition index. Kotlin
            // does not rebuild a second full-document index for each edit.
            currentCoroutineContext().ensureActive()
            if (generation.get() != ticket) throw CancellationException("Stale document snapshot")

            val temporaryDirectory = File(System.getProperty("java.io.tmpdir") ?: "")
            check(temporaryDirectory.isDirectory && temporaryDirectory.canWrite()) {
                "No writable app-private temporary directory for native TSH document"
            }
            val reader = NativeTextWindowReader { byteOffset, maxBytes ->
                require(byteOffset >= 0L && (byteOffset and 1L) == 0L && maxBytes >= 2)
                val start = (byteOffset / 2L).toInt()
                val text = snapshot.getNearestChunk(start, maxBytes / 2)
                ByteArray(text.length * 2).also { bytes ->
                    for (index in text.indices) {
                        val codeUnit = text[index].code
                        bytes[index * 2] = (codeUnit and 0xFF).toByte()
                        bytes[index * 2 + 1] = (codeUnit ushr 8).toByte()
                    }
                }
            }
            nativeHandle = engine.createNativeDocument(
                language = language,
                extension = extension,
                byteLength = snapshot.length.toLong() * 2L,
                temporaryDirectory = temporaryDirectory,
                reader = reader
            )
            check(nativeHandle != 0L) { "Native TSH document creation returned a null handle" }
            currentCoroutineContext().ensureActive()
            if (generation.get() != ticket) throw CancellationException("Stale native document")

            val createdCaptureIndexes = ByteBudgetLruCache<Int, CaptureIntervalIndex>(
                CAPTURE_CACHE_BYTES,
                { index -> 96L + index.size.toLong() * 96L }
            )
            captureIndexes = createdCaptureIndexes
            val active = LargeSession(
                snapshot, source, nativeHandle, AtomicLong(snapshot.length.toLong()),
                createdCaptureIndexes, ticket
            )
            if (generation.get() == ticket) {
                synchronized(nativeEditLock) {
                    val queued = pendingNativeEdits.toList()
                    pendingNativeEdits.clear()
                    for (edit in queued) {
                        check(engine.applyNativeDocumentEdit(
                            nativeHandle, edit.startByte, edit.oldEndByte, edit.insertedUtf16Le
                        )) { "Unable to replay edit into newly-created native document" }
                        active.lengthChars.addAndGet(edit.insertedChars.toLong() - edit.deletedChars.toLong())
                    }
                    largeSession = active
                }
                nativeHandle = 0L
                captureIndexes = null
                parsing = false
                Log.i(
                    TAG,
                    "Native large-file TSH ready: extension=.$extension version=${snapshot.version} " +
                        "utf16Length=${active.lengthChars.get()} source=PieceTreeSnapshot->NativePieceTree"
                )
                host?.invalidateStyles()
                host?.invalidateStructure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation.get() == ticket) {
                Log.e(TAG, "Native large-file TSH initialization failed: extension=.$extension length=${snapshot.length}", error)
                parsing = false
                host?.invalidateStyles()
            }
        } finally {
            if (nativeHandle != 0L) engine.closeNativeDocument(nativeHandle)
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
        val partitions = nativeViewportPartitions(active, start, end)
        val result = ArrayList<WindbellTshEngine.HighlightSpan>()
        for (partition in partitions) {
            val index = active.captureIndexes.get(partition.index)
            if (index == null) requestLargePartition(active, partition.start, partition.endExclusive)
            else result += index.query(queryStartUtf16, queryEndUtf16)
        }
        return result
    }

    private fun requestLargeViewport(active: LargeSession, start: Long, end: Long) {
        if (generation.get() != active.ticket || largeSession !== active) return
        nativeViewportPartitions(active, start, end).forEach { partition ->
            if (active.captureIndexes.get(partition.index) == null) {
                requestLargePartition(active, partition.start, partition.endExclusive)
            }
        }
    }

    private fun requestLargePartition(active: LargeSession, start: Long, end: Long) {
        if (generation.get() != active.ticket || largeSession !== active) return
        val safeStart = start.coerceIn(0L, active.lengthChars.get())
        val partitionIndex = (safeStart / PARTITION_CHARS).toInt()
        val partitionStart = partitionIndex.toLong() * PARTITION_CHARS
        val partition = DocumentPartition(
            index = partitionIndex,
            start = partitionStart,
            endExclusive = minOf(active.lengthChars.get(), partitionStart + PARTITION_CHARS),
            firstLine = 0L,
            lineCount = 0L,
            version = active.ticket
        )
        if (active.captureIndexes.get(partition.index) != null) return

        // The native document owns one persistent parse session. A viewport request
        // executes TSH against its absolute byte range; no partition-local parser/tree
        // is created and tokenizer checkpoints remain owned by the native document.
        scope.launch(Dispatchers.IO) {
            if (generation.get() != active.ticket || largeSession !== active) return@launch
            try {
                val flat = engine.highlightNativeDocument(
                    documentHandle = active.nativeHandle,
                    startByte = partition.start * 2L,
                    endByte = partition.endExclusive * 2L
                ) ?: return@launch
                val spans = decodeNativeSpans(flat)
                if (generation.get() == active.ticket && largeSession === active) {
                    active.captureIndexes.put(
                        partition.index,
                        CaptureIntervalIndex(spans)
                    )
                    host?.invalidateStyles()
                    host?.invalidateStructure()
                }
            } catch (error: Throwable) {
                if (generation.get() == active.ticket) {
                    Log.e(TAG, "Native viewport TSH failed: extension=.$extension partition=${partition.index}", error)
                }
            }
        }
    }

    private fun nativeViewportPartitions(
        active: LargeSession,
        start: Long,
        end: Long
    ): List<DocumentPartition> {
        val length = active.lengthChars.get()
        if (length <= 0L || start >= end || start >= length) return emptyList()
        val boundedStart = start.coerceAtLeast(0L)
        val boundedEnd = end.coerceAtMost(length)
        if (boundedStart >= boundedEnd) return emptyList()
        val first = boundedStart / PARTITION_CHARS
        val last = (boundedEnd - 1L) / PARTITION_CHARS
        return (first..last).map { index ->
            val partStart = index * PARTITION_CHARS
            DocumentPartition(
                index = index.toInt(),
                start = partStart,
                endExclusive = minOf(length, partStart + PARTITION_CHARS),
                firstLine = 0L,
                lineCount = 0L,
                version = active.ticket
            )
        }
    }

    private fun applyNativeEdits(active: LargeSession, edits: List<NativeEdit>) {
        scope.launch(Dispatchers.IO) {
            if (generation.get() != active.ticket || largeSession !== active) return@launch
            try {
                var earliestOffset = Long.MAX_VALUE
                for (edit in edits) {
                    check(engine.applyNativeDocumentEdit(
                        active.nativeHandle, edit.startByte, edit.oldEndByte, edit.insertedUtf16Le
                    )) { "Native incremental Tree-sitter edit failed" }
                    earliestOffset = minOf(earliestOffset, edit.startByte / 2L)
                    active.lengthChars.addAndGet(
                        edit.insertedChars.toLong() - edit.deletedChars.toLong()
                    )
                }
                // Lexical state can flow to EOF. Keep the prefix cache and invalidate
                // every page from the first affected partition onward; native TSH
                // checkpoints are versioned and will be recomputed from a valid state.
                val firstInvalid = (earliestOffset / PARTITION_CHARS).toInt()
                active.captureIndexes.invalidate { key, _ -> key >= firstInvalid }
                if (generation.get() == active.ticket && largeSession === active) {
                    host?.invalidateStyles()
                    host?.invalidateStructure()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Native incremental edit failed; rebuilding the native session", error)
                if (generation.get() == active.ticket) host?.buffer?.let(::scheduleParse)
            }
        }
    }

    private fun decodeNativeSpans(flat: IntArray): List<WindbellTshEngine.HighlightSpan> {
        val count = flat.size / 6
        if (count == 0) return emptyList()
        return ArrayList<WindbellTshEngine.HighlightSpan>(count).also { spans ->
            for (index in 0 until count) {
                val offset = index * 6
                val packed = flat[offset + 2]
                spans += WindbellTshEngine.HighlightSpan(
                    startByte = flat[offset],
                    endByte = flat[offset + 1],
                    styleId = packed and 0xFFFF,
                    fontFlags = (packed ushr 16) and 0xFFFF,
                    priority = flat[offset + 3],
                    declarationOrder = flat[offset + 4],
                    source = flat[offset + 5]
                )
            }
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
            runCatching { engine.closeNativeDocument(active.nativeHandle) }
                .onFailure { Log.w(TAG, "Failed to close native TSH document", it) }
            runCatching { active.captureIndexes.close() }
                .onFailure { Log.w(TAG, "Failed to close native TSH capture cache", it) }
        }
    }
}
