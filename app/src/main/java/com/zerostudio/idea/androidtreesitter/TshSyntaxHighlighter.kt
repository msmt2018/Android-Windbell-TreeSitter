package com.zerostudio.idea.androidtreesitter

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.util.Log
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSTree
import com.zerostudio.idea.treesitter.largefile.ByteBudgetLruCache
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.input.AutoPairFilter
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.EditorTextSnapshot
import x.github.module.editor.structure.PlainBracketMatcher
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TSH adapter for EditorView.
 *
 * Small documents use the original whole-document pipeline. Large documents use
 * a native-owned Piece Tree document, incremental Tree-sitter edits, viewport-range
 * TSH execution, and a byte-budgeted capture cache. The Kotlin layer transfers only
 * bounded UTF-16LE windows at document creation and the inserted slice per edit.
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
        private const val CAPTURE_CACHE_BYTES = 32L * 1024L * 1024L
        private val nextSessionId = AtomicLong(1L)
        private val captureCache = ByteBudgetLruCache<CaptureCacheKey, CaptureIntervalIndex>(
            CAPTURE_CACHE_BYTES,
            { index -> 96L + index.size.toLong() * 96L }
        )
    }

    private data class CaptureCacheKey(val sessionId: Long, val partitionIndex: Int)

    private data class LargeSession(
        /** Native-owned PieceTreeSource + incremental Tree-sitter tree. */
        val nativeHandle: Long,
        val lengthChars: AtomicLong,
        val cacheSessionId: Long,
        val ticket: Long,
        val documentRevision: AtomicLong = AtomicLong(),
        val nativeMutex: Mutex = Mutex(),
        val partitionWork: ConcurrentHashMap<Int, PartitionWork> = ConcurrentHashMap()
    )

    private enum class PartitionWorkState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private class PartitionWork {
        @Volatile var state: PartitionWorkState = PartitionWorkState.QUEUED
    }

    private data class NativeEdit(
        val startByte: Long,
        val oldEndByte: Long,
        val insertedUtf16Le: ByteArray,
        val insertedChars: Int,
        val deletedChars: Int
    )

    private val nativeEditLock = Any()
    private val pendingNativeEdits = ArrayList<NativeEdit>()
    private var nativeEditJob: Job? = null

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
        // A native grammar can outlive the UI object that originally loaded it.
        // Restore its active scheme palette before either the small- or large-file
        // renderer starts applying capture IDs to Android spans.
        if (!engine.ensurePalette(extension)) {
            Log.w(TAG, "No active TSH color palette available for extension=.$extension")
        }
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
        synchronized(nativeEditLock) { pendingNativeEdits.clear() }
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
                    pendingNativeEdits.clear()
                    host?.buffer?.let(::scheduleParse)
                }
                return
            }
            edits = pendingNativeEdits.toList()
            pendingNativeEdits.clear()
        }
        if (edits.isEmpty()) return
        applyNativeEdits(active ?: return, edits)
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
            val boundedStart = queryStart.coerceAtMost(active.lengthChars.get())
            val boundedEnd = queryEnd.coerceAtMost(active.lengthChars.get())
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
        val length = active.lengthChars.get()
        if (queryStart >= length) return true
        if (queryStart > Int.MAX_VALUE || queryEnd > Int.MAX_VALUE || queryEnd < queryStart) return false
        val boundedStart = queryStart.coerceAtLeast(0L)
        val boundedEnd = queryEnd.coerceAtMost(length)
        if (boundedStart >= boundedEnd) return true
        var ready = true
        forEachNativeViewportPartition(active, boundedStart, boundedEnd) { partitionIndex, _, _ ->
            if (captureCache.get(CaptureCacheKey(active.cacheSessionId, partitionIndex)) == null) {
                ready = false
                requestLargePartition(active, partitionIndex)
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
            if (offset < 0 || offset.toLong() >= active.lengthChars.get()) {
                emptyList()
            } else {
                val partitionIndex = (offset.toLong() / PARTITION_CHARS).toInt()
                captureCache.get(CaptureCacheKey(active.cacheSessionId, partitionIndex))?.query(offset, offset + 1).orEmpty()
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
        var cacheSessionId = 0L
        try {
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

            cacheSessionId = nextSessionId.getAndIncrement()
            val active = LargeSession(
                nativeHandle, AtomicLong(snapshot.length.toLong()),
                cacheSessionId, ticket
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
                cacheSessionId = 0L
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
            if (cacheSessionId != 0L) {
                captureCache.invalidate { key, _ -> key.sessionId == cacheSessionId }
            }
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
        val result = ArrayList<WindbellTshEngine.HighlightSpan>()
        forEachNativeViewportPartition(active, start, end) { partitionIndex, _, _ ->
            val index = captureCache.get(CaptureCacheKey(active.cacheSessionId, partitionIndex))
            if (index == null) requestLargePartition(active, partitionIndex)
            else index.appendQuery(queryStartUtf16, queryEndUtf16, result)
        }
        return result
    }

    private fun requestLargeViewport(active: LargeSession, start: Long, end: Long) {
        if (generation.get() != active.ticket || largeSession !== active) return
        forEachNativeViewportPartition(active, start, end) { partitionIndex, _, _ ->
            if (captureCache.get(CaptureCacheKey(active.cacheSessionId, partitionIndex)) == null) {
                requestLargePartition(active, partitionIndex)
            }
        }
    }

    private fun requestLargePartition(active: LargeSession, partitionIndex: Int) {
        if (generation.get() != active.ticket || largeSession !== active) return
        if (captureCache.get(CaptureCacheKey(active.cacheSessionId, partitionIndex)) != null) return
        val work = PartitionWork()
        if (active.partitionWork.putIfAbsent(partitionIndex, work) != null) return
        val requestedRevision = active.documentRevision.get()

        // One native document handle has one serialized operation stream. Viewport
        // highlighting, incremental edits and close must never race on its parser/tree.
        scope.launch(Dispatchers.IO) {
            try {
                if (generation.get() != active.ticket || largeSession !== active) {
                    work.state = PartitionWorkState.CANCELLED
                    return@launch
                }
                work.state = PartitionWorkState.RUNNING
                val flat = active.nativeMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (generation.get() != active.ticket || largeSession !== active ||
                        active.documentRevision.get() != requestedRevision) {
                        throw CancellationException("Stale native viewport request")
                    }
                    engine.highlightNativeDocument(
                        documentHandle = active.nativeHandle,
                        startByte = partitionIndex.toLong() * PARTITION_CHARS * 2L,
                        endByte = minOf(active.lengthChars.get(), (partitionIndex.toLong() + 1L) * PARTITION_CHARS) * 2L
                    ) ?: throw IllegalStateException(
                        "Native viewport TSH returned null (partition=$partitionIndex)"
                    )
                }
                val spans = decodeNativeSpans(flat)
                if (generation.get() == active.ticket && largeSession === active &&
                    active.documentRevision.get() == requestedRevision) {
                    // Empty IntArray is a valid completed result and is cached as such.
                    captureCache.put(CaptureCacheKey(active.cacheSessionId, partitionIndex), CaptureIntervalIndex(spans))
                    work.state = PartitionWorkState.SUCCEEDED
                    host?.invalidateStyles()
                    host?.invalidateStructure()
                } else {
                    work.state = PartitionWorkState.CANCELLED
                }
            } catch (_: CancellationException) {
                work.state = PartitionWorkState.CANCELLED
            } catch (error: Throwable) {
                // Retain FAILED until an edit/session replacement invalidates it.
                // Draw callbacks therefore cannot cause an immediate retry/log storm.
                work.state = PartitionWorkState.FAILED
                if (generation.get() == active.ticket && largeSession === active) {
                    Log.e(TAG, "Native viewport TSH failed: extension=.$extension partition=$partitionIndex", error)
                }
            } finally {
                if (work.state != PartitionWorkState.FAILED) {
                    active.partitionWork.remove(partitionIndex, work)
                }
            }
        }
    }

    /** Iterate viewport partitions without allocating a List or DocumentPartition per draw. */
    private inline fun forEachNativeViewportPartition(
        active: LargeSession,
        start: Long,
        end: Long,
        action: (index: Int, start: Long, endExclusive: Long) -> Unit
    ) {
        val length = active.lengthChars.get()
        if (length <= 0L || start >= end || start >= length) return
        val boundedStart = start.coerceAtLeast(0L)
        val boundedEnd = end.coerceAtMost(length)
        if (boundedStart >= boundedEnd) return
        var index = boundedStart / PARTITION_CHARS
        val last = (boundedEnd - 1L) / PARTITION_CHARS
        while (index <= last) {
            val partStart = index * PARTITION_CHARS
            action(
                index.toInt(),
                partStart,
                minOf(length, partStart + PARTITION_CHARS)
            )
            index++
        }
    }

    private fun applyNativeEdits(active: LargeSession, edits: List<NativeEdit>) {
        val previous = synchronized(nativeEditLock) { nativeEditJob }
        val job = scope.launch(Dispatchers.IO) {
            previous?.join()
            if (generation.get() != active.ticket || largeSession !== active) return@launch
            try {
                active.nativeMutex.withLock {
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
                    // Lexical state can flow to EOF. Invalidate downstream captures
                    // and failed-work markers only after the edit transaction completes.
                    active.documentRevision.incrementAndGet()
                    val firstInvalid = (earliestOffset / PARTITION_CHARS).toInt()
                    captureCache.invalidate { key, _ -> key.sessionId == active.cacheSessionId && key.partitionIndex >= firstInvalid }
                    active.partitionWork.keys.filter { it >= firstInvalid }.forEach(active.partitionWork::remove)
                    if (generation.get() == active.ticket && largeSession === active) {
                        host?.invalidateStyles()
                        host?.invalidateStructure()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Native incremental edit failed; rebuilding the native session", error)
                if (generation.get() == active.ticket) host?.buffer?.let(::scheduleParse)
            }
        }
        synchronized(nativeEditLock) { nativeEditJob = job }
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
            runCatching {
                active.nativeMutex.withLock {
                    engine.closeNativeDocument(active.nativeHandle)
                }
            }.onFailure { Log.w(TAG, "Failed to close native TSH document", it) }
            captureCache.invalidate { key, _ -> key.sessionId == active.cacheSessionId }
        }
    }
}
