package com.zerostudio.idea.androidtreesitter

import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.util.Log
import com.zerostudio.idea.treesitter.TSLanguage
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
 * Every document size uses one native-owned Piece Tree pipeline, incremental
 * Tree-sitter edits, viewport-range TSH execution, and a byte-budgeted capture cache.
 * Kotlin transfers bounded UTF-16LE windows at document creation and only inserted
 * edit slices afterward; it never flattens the complete document into a String.
 */
class TshSyntaxHighlighter(
    private val language: TSLanguage,
    private val extension: String,
    private val engine: WindbellTshEngine = WindbellTshEngine.instance,
    private val temporaryDirectory: File
) : SyntaxHighlighter {

    companion object {
        private const val TAG = "WindbellTSH"
        // One immutable interval payload is shared by scope lookups and drawing.
        // Keeping duplicate semantic/display LRUs charged the same payload twice
        // and doubled index maintenance without providing an independent lifetime.
        private const val CAPTURE_CACHE_BYTES = 32L * 1024L * 1024L
        // Failed viewport attempts are retained only for retry backoff. Keep their
        // bookkeeping bounded when a broken grammar/native call is hit while scrolling
        // through many distinct regions of a very large document.
        private const val MAX_RANGE_WORK_ENTRIES = 256
        private val nextSessionId = AtomicLong(1L)
        private val captureRangeIndex = CaptureRangeLookupIndex()
        private val captureCache = ByteBudgetLruCache<CaptureRangeKey, CaptureIntervalIndex>(
            CAPTURE_CACHE_BYTES,
            { index -> 96L + index.size.toLong() * 96L },
            onEntryAdded = { key, _ -> captureRangeIndex.add(key) },
            onEntryRemoved = { key, _ -> captureRangeIndex.remove(key) }
        )
    }

    private data class LargeSession(
        /** Native-owned PieceTreeSource + incremental Tree-sitter tree. */
        val nativeHandle: Long,
        val lengthChars: AtomicLong,
        val cacheSessionId: Long,
        val ticket: Long,
        val documentRevision: AtomicLong = AtomicLong(),
        // Mirrors the native document's committed edit version. Dirty ranges are
        // consumed only after the matching edit transaction has fully committed.
        val nativeDocumentVersion: AtomicLong = AtomicLong(),
        // Distinguish edits received by the UI from edits committed to the native tree.
        // While these epochs differ, cached spans belong to an older document revision.
        val pendingEditEpoch: AtomicLong = AtomicLong(),
        val appliedEditEpoch: AtomicLong = AtomicLong(),
        val nativeMutex: Mutex = Mutex(),
        val rangeWork: ConcurrentHashMap<CaptureRangeKey, RangeWork> = ConcurrentHashMap()
    )

    private enum class RangeWorkState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private class RangeWork(val attempt: Int = 0) {
        @Volatile var state: RangeWorkState = RangeWorkState.QUEUED
        @Volatile var retryAfterNanos: Long = 0L
        @Volatile var lastAttemptNanos: Long = System.nanoTime()
        @Volatile var retryWakeJob: Job? = null
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
    // Structure is invalidated only when a lexical-scope lookup (bracket/auto-pair)
    // was waiting for this viewport's captures. Ordinary syntax capture completion
    // must not rebuild editor structure for every scroll frame.
    private val structureRefreshPending = AtomicBoolean(false)

    private val viewportRequestLock = Any()
    // Requests collected from actual renderer callbacks during the current draw.
    private val pendingViewportRanges = ArrayList<CaptureRangeKey>()
    // At most one not-yet-running viewport batch is retained. A newer frame replaces
    // obsolete queued scroll positions instead of building an unbounded native backlog.
    private var queuedViewportRanges: List<CaptureRangeKey> = emptyList()
    private var viewportWorkerActive = false
    @Volatile private var viewportFrameActive = false

    override fun onViewportFrameStarted() {
        synchronized(viewportRequestLock) {
            viewportFrameActive = true
            pendingViewportRanges.clear()
        }
    }

    override fun onViewportFrameFinished() {
        val pending = synchronized(viewportRequestLock) {
            viewportFrameActive = false
            pendingViewportRanges.toList().also { pendingViewportRanges.clear() }
        }
        submitCoalescedViewportRanges(pending)
    }

    @Volatile private var host: SyntaxHighlighter.Host? = null
    @Volatile private var largeSession: LargeSession? = null
    @Volatile private var parsing = false

    private val editorMetadata by lazy { engine.getEditorMetadata(extension) }
    private val stylesById by lazy { engine.getStyleCatalog(extension).associateBy { it.styleId } }
    private var parseJob: Job? = null

    override fun onAttached(host: SyntaxHighlighter.Host) {
        this.host = host
        // A native grammar can outlive the UI object that originally loaded it.
        // Restore its active scheme palette before the unified native-document
        // renderer starts applying capture IDs to Android spans.
        if (!engine.ensurePalette(extension)) {
            Log.w(TAG, "No active TSH color palette available for extension=.$extension")
        }
        Log.i(TAG, "Attached to EditorView: extension=.$extension textLength=${host.buffer.length}")
        scheduleParse(host.buffer)
    }

    override fun onDetached() {
        generation.incrementAndGet()
        structureRefreshPending.set(false)
        parseJob?.cancel(CancellationException("TSH highlighter detached"))
        parseJob = null
        largeSession?.let(::disposeSession)
        largeSession = null
        parsing = false
        synchronized(nativeEditLock) { pendingNativeEdits.clear() }
        synchronized(viewportRequestLock) {
            pendingViewportRanges.clear()
            queuedViewportRanges = emptyList()
            viewportFrameActive = false
        }
        host = null
    }

    override fun onTextReplaced(buffer: EditorBuffer) {
        synchronized(nativeEditLock) { pendingNativeEdits.clear() }
        scheduleParse(buffer)
    }

    override fun onTextChanged(change: EditorDocument.AppliedChange) {
        // All document sizes share the native Piece Tree pipeline. While its
        // initial snapshot is being transferred and parsed, retain edits for replay;
        // after publication, apply only inserted UTF-16LE slices incrementally.
        val needsNativeTransaction = synchronized(nativeEditLock) {
            largeSession != null || parsing
        }
        if (!needsNativeTransaction) return

        // EditorDocument reports changes bottom-up in pre-edit coordinates. Preserve
        // that order: nativeApplyDocumentEdit consumes exactly the same transaction
        // sequence and copies only the inserted slice, never the unchanged document.
        val inserted = change.insertedText()
        if (inserted.length > Int.MAX_VALUE / 2) {
            Log.e(TAG, "Inserted text is too large for a single JNI edit transaction")
            host?.buffer?.let(::scheduleParse)
            return
        }
        val bytes = ByteArray(inserted.length * 2)
        for (index in inserted.indices) {
            val unit = inserted[index].code
            bytes[index * 2] = (unit and 0xFF).toByte()
            bytes[index * 2 + 1] = (unit ushr 8).toByte()
        }
        synchronized(nativeEditLock) {
            // Session publication and edit draining use this same lock. If a large
            // session became active while the inserted slice was encoded, queueing
            // here still makes onEditFinished apply it to that newly-published tree.
            largeSession?.pendingEditEpoch?.incrementAndGet()
            val edit = NativeEdit(
                startByte = change.rangeOffset.toLong() * 2L,
                oldEndByte = (change.rangeOffset.toLong() + change.deletedTextLength) * 2L,
                insertedUtf16Le = bytes,
                insertedChars = inserted.length,
                deletedChars = change.deletedTextLength
            )
            // Keep immutable display captures until the native edit transaction
            // reports which viewport dependencies remain reusable. While the edit is
            // pending, styleLine refuses to read the old revision.
            pendingNativeEdits += edit
        }
    }

    override fun onEditFinished() {
        val active: LargeSession?
        val edits: List<NativeEdit>
        var editEpoch = 0L
        synchronized(nativeEditLock) {
            active = largeSession
            if (active == null) {
                // Large-document initialization may still be consuming its original
                // immutable snapshot. Keep transactions queued for it to replay.
                if (!parsing) {
                    pendingNativeEdits.clear()
                    host?.buffer?.let(::scheduleParse)
                }
                return
            }
            edits = pendingNativeEdits.toList()
            pendingNativeEdits.clear()
            editEpoch = active.pendingEditEpoch.get()
        }
        if (edits.isEmpty()) return
        applyNativeEdits(active ?: return, edits, editEpoch)
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
        val queryEnd = lineStartOffset.toLong() + visibleEnd.toLong() +
            visibleLineBreakLength(bufferLine, visibleEnd, text.length).toLong()
        if (queryStart > Int.MAX_VALUE || queryEnd > Int.MAX_VALUE) {
            Log.e(TAG, "Editor offset exceeds the 32-bit capture index: start=$queryStart end=$queryEnd")
            return null
        }
        val active = largeSession ?: return null
        // Serialize revision observation and cache reads with edit admission. The
        // edit callback increments pendingEditEpoch and invalidates display spans
        // under this same lock, so a renderer cannot read a pre-edit cache entry
        // after the editor has already moved to the new document coordinates.
        val spans = synchronized(nativeEditLock) {
            if (largeSession !== active || generation.get() != active.ticket) {
                return@synchronized emptyList()
            }
            val editPending =
                active.pendingEditEpoch.get() != active.appliedEditEpoch.get()

            // During an edit the native length intentionally remains at the last
            // committed revision. Display coordinates already follow the editor's
            // applied edit, so do not clamp them to that older native length.
            val boundedStart = if (editPending) queryStart
                else queryStart.coerceAtMost(active.lengthChars.get())
            val boundedEnd = if (editPending) queryEnd
                else queryEnd.coerceAtMost(active.lengthChars.get())
            if (boundedStart >= boundedEnd) emptyList()
            else {
                val requested = CaptureRangeKey(
                    active.cacheSessionId, boundedStart.toInt(), boundedEnd.toInt())
                val cached = if (editPending) null else queryCachedCaptures(
                    requested, boundedStart.toInt(), boundedEnd.toInt()
                )
                if (cached != null) cached else {
                    if (!editPending) enqueueViewportRange(requested)
                    emptyList()
                }
            }
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
        val queryEnd = lineStartOffset.toLong() + end.toLong() +
            visibleLineBreakLength(bufferLine, end, host?.buffer?.getLineLength(bufferLine) ?: end).toLong()
        return synchronized(nativeEditLock) {
            if (largeSession !== active || generation.get() != active.ticket) {
                return@synchronized false
            }
            val editPending =
                active.pendingEditEpoch.get() != active.appliedEditEpoch.get()
            val length = if (editPending) Long.MAX_VALUE else active.lengthChars.get()
            if (queryStart >= length) return@synchronized true
            if (queryStart > Int.MAX_VALUE || queryEnd > Int.MAX_VALUE ||
                queryEnd < queryStart) return@synchronized false
            val boundedStart = queryStart.coerceAtLeast(0L)
            val boundedEnd = queryEnd.coerceAtMost(length)
            if (boundedStart >= boundedEnd) return@synchronized true
            val key = CaptureRangeKey(
                active.cacheSessionId, boundedStart.toInt(), boundedEnd.toInt())
            if (!editPending && hasCachedCoverage(key)) {
                return@synchronized true
            }
            if (!editPending) enqueueViewportRange(key)
            false
        }
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
            val resolved = synchronized(nativeEditLock) {
                if (largeSession !== active || generation.get() != active.ticket ||
                    active.pendingEditEpoch.get() != active.appliedEditEpoch.get()) {
                    // Never answer scope queries from a stale document revision.
                    return@synchronized null
                }
                if (offset < 0 || offset.toLong() >= active.lengthChars.get() ||
                    offset == Int.MAX_VALUE) return@synchronized emptyList()
                val key = CaptureRangeKey(active.cacheSessionId, offset, offset + 1)
                val cached = queryCachedCaptures(key, offset, offset + 1)
                if (cached == null) {
                    structureRefreshPending.set(true)
                    enqueueViewportRange(key)
                }
                cached
            } ?: return true
            resolved
        } else {
            // There is no Kotlin-side full-document capture index anymore.
            // Until the native document is published, treat lexical scope as
            // unknown and avoid inserting bracket pairs into comments/strings.
            return true
        }
        return spans.any { span ->
            val style = stylesById[span.styleId]
            excludedScopes.any { scope ->
                style?.tags?.contains(scope) == true || style?.scope?.startsWith(scope) == true
            }
        }
    }

    /** Theme changes only invalidate drawing; range captures remain valid. */
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
        // Snapshot creation and edit callbacks are serialized on the editor thread.
        // This is important: queued native edits that are already represented by
        // this snapshot must be discarded, while edits after it must remain queued
        // for replay into the new native document.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main.immediate) { scheduleParse(buffer) }
            return
        }
        // PieceTreeBuffer snapshots retain their backing chunks. The unified
        // native pipeline never calls getText() or snapshot.toString() for the
        // complete document, regardless of its size.
        val snapshot = buffer.createTextSnapshot()
        structureRefreshPending.set(false)
        synchronized(nativeEditLock) { pendingNativeEdits.clear() }
        val ticket = generation.incrementAndGet()
        parseJob?.cancel(CancellationException("Superseded by newer document snapshot"))
        largeSession?.let(::disposeSession)
        largeSession = null
        parsing = true

        parseJob = scope.launch {
            delay(45)
            initializeLargeSession(snapshot, ticket)
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

            // java.io.tmpdir commonly resolves to /tmp on Android and is not
            // an app-writable storage contract. Use the owning app cache directory.
            check((temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) &&
                temporaryDirectory.canWrite()) {
                "No writable app-private cache directory for native TSH document"
            }
            val reader = NativeTextWindowReader { byteOffset, maxBytes, destination ->
                require(byteOffset >= 0L && (byteOffset and 1L) == 0L && maxBytes >= 2)
                destination.clear()
                destination.limit(maxBytes)
                val start = (byteOffset / 2L).toInt()
                val text = snapshot.getNearestChunk(start, maxBytes / 2)
                for (character in text) {
                    val codeUnit = character.code
                    destination.put((codeUnit and 0xFF).toByte())
                    destination.put((codeUnit ushr 8).toByte())
                }
                destination.position()
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
                    // Consume the initial replay's accumulated native invalidations
                    // before publishing the session. Subsequent UI edits can then
                    // enforce a strict native-version progression without mixing
                    // snapshot initialization with live edit transactions.
                    val initialDirty = engine.consumeNativeDirtyLineRanges(nativeHandle)
                    check(initialDirty.documentVersion == queued.size.toLong()) {
                        "Native document version mismatch after edit replay: " +
                            "expected=${queued.size} actual=${initialDirty.documentVersion}"
                    }
                    active.nativeDocumentVersion.set(initialDirty.documentVersion)
                    Log.d(
                        TAG,
                        "Initial native edit replay committed: edits=${queued.size} " +
                            "version=${initialDirty.documentVersion} dirtyLines=${initialDirty.ranges.size}"
                    )
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

    /**
     * Include the actual source line terminator when the painted slice reaches EOL.
     * This lets adjacent visible lines coalesce naturally, including CRLF, while
     * folded-away source ranges remain gaps and are never swept into the request.
     */
    private fun visibleLineBreakLength(bufferLine: Int, end: Int, lineLength: Int): Int {
        if (end < lineLength) return 0
        val buffer = host?.buffer ?: return 0
        if (bufferLine >= buffer.getLineCount()) return 0
        return buffer.getEOL().length
    }

    /**
     * Read a requested viewport from the single immutable capture cache.
     * The former display cache held the exact same CaptureIntervalIndex objects
     * under a second LRU/index and was merged back with LinkedHashSet on every
     * line draw. That duplicated accounting and lookup work without adding data.
     */
    private fun queryCachedCaptures(
        key: CaptureRangeKey,
        startUtf16: Int,
        endUtf16: Int
    ): List<WindbellTshEngine.HighlightSpan>? {
        val keys = captureRangeIndex.findOverlappingRanges(
            key.sessionId, startUtf16, endUtf16
        )
        if (keys.isEmpty()) return null

        val entries = ArrayList<CaptureIntervalIndex>(keys.size)
        var coveredUntil = startUtf16
        for (cachedKey in keys) {
            if (cachedKey.startUtf16 > coveredUntil) return null
            val index = captureCache.get(cachedKey)
            if (index == null) {
                captureRangeIndex.remove(cachedKey)
                continue
            }
            entries += index
            coveredUntil = maxOf(coveredUntil, cachedKey.endUtf16)
            if (coveredUntil >= endUtf16) break
        }
        if (coveredUntil < endUtf16) return null

        // Cache admission stores only uncovered, half-open gaps and every native
        // result is clipped to that gap. Therefore entries cannot duplicate the
        // same capture; append directly instead of allocating a LinkedHashSet.
        val spans = ArrayList<WindbellTshEngine.HighlightSpan>()
        entries.forEach { it.appendQuery(startUtf16, endUtf16, spans) }
        return spans
    }

    /** Source intervals already represented by the capture cache. */
    private fun cachedRanges(key: CaptureRangeKey): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        for (cachedKey in captureRangeIndex.findOverlappingRanges(
            key.sessionId, key.startUtf16, key.endUtf16
        )) {
            if (captureCache.get(cachedKey) != null) {
                ranges += cachedKey.startUtf16 to cachedKey.endUtf16
            } else captureRangeIndex.remove(cachedKey)
        }
        return ranges.sortedBy { it.first }
    }

    /** Computes only the gaps that have not already been captured and cached. */
    private fun uncoveredViewportRanges(key: CaptureRangeKey): List<Pair<Int, Int>> {
        val ranges = cachedRanges(key)
        val gaps = ArrayList<Pair<Int, Int>>()
        var cursor = key.startUtf16
        for ((rangeStart, rangeEnd) in ranges) {
            if (rangeEnd <= cursor) continue
            if (rangeStart > cursor) gaps += cursor to minOf(rangeStart, key.endUtf16)
            cursor = maxOf(cursor, rangeEnd)
            if (cursor >= key.endUtf16) break
        }
        if (cursor < key.endUtf16) gaps += cursor to key.endUtf16
        return gaps
    }

    private fun hasCachedCoverage(key: CaptureRangeKey): Boolean =
        uncoveredViewportRanges(key).isEmpty()

    private fun enqueueViewportRange(key: CaptureRangeKey) {
        if (key.startUtf16 >= key.endUtf16) return
        val active = largeSession ?: return
        if (generation.get() != active.ticket || active.cacheSessionId != key.sessionId) return
        if (hasCachedCoverage(key)) return
        val flushNow = synchronized(viewportRequestLock) {
            if (pendingViewportRanges.none {
                it.sessionId == key.sessionId && it.startUtf16 <= key.startUtf16 && it.endUtf16 >= key.endUtf16
            }) pendingViewportRanges += key
            !viewportFrameActive
        }
        if (flushNow) flushPendingViewportRanges()
    }

    private fun flushPendingViewportRanges() {
        val pending = synchronized(viewportRequestLock) {
            pendingViewportRanges.toList().also { pendingViewportRanges.clear() }
        }
        submitCoalescedViewportRanges(pending)
    }

    /**
     * Coalesce the actual source intervals collected for one draw pass. Only the newest
     * not-yet-running viewport batch is retained while native work is busy; rapid scrolling
     * therefore cannot queue an unbounded sequence of obsolete viewport jobs.
     */
    private fun submitCoalescedViewportRanges(ranges: List<CaptureRangeKey>) {
        if (ranges.isEmpty()) return
        val sorted = ranges.sortedWith(compareBy<CaptureRangeKey> { it.sessionId }
            .thenBy { it.startUtf16 }.thenBy { it.endUtf16 })
        val merged = ArrayList<CaptureRangeKey>()
        var current = sorted.first()
        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.sessionId == current.sessionId && next.startUtf16 <= current.endUtf16) {
                current = current.copy(endUtf16 = maxOf(current.endUtf16, next.endUtf16))
            } else {
                merged += current
                current = next
            }
        }
        merged += current

        val startWorker = synchronized(viewportRequestLock) {
            // Replace, rather than append, batches that have not started. This is a
            // freshness policy, not a row/character limit: each batch is the live viewport.
            queuedViewportRanges = merged
            if (viewportWorkerActive) false else {
                viewportWorkerActive = true
                true
            }
        }
        if (startWorker) startViewportWorker()
    }

    private fun startViewportWorker() {
        scope.launch(Dispatchers.IO) {
            var drainedNormally = false
            try {
                while (true) {
                    val batch = synchronized(viewportRequestLock) {
                        if (queuedViewportRanges.isEmpty()) {
                            // Publish idle before returning. A new frame can now start
                            // another worker; this worker's finally must not reset it.
                            viewportWorkerActive = false
                            drainedNormally = true
                            return@launch
                        }
                        queuedViewportRanges.also { queuedViewportRanges = emptyList() }
                    }
                    var batchUpdated = false
                    val completedRanges = ArrayList<CaptureRangeKey>(batch.size)
                    try {
                        for (key in batch) {
                            currentCoroutineContext().ensureActive()
                            // A newer draw pass supersedes the rest of this batch. Finish
                            // the in-flight native call, then jump directly to the newest
                            // viewport instead of parsing rows the user has scrolled past.
                            val superseded = synchronized(viewportRequestLock) {
                                queuedViewportRanges.isNotEmpty()
                            }
                            if (superseded) break
                            if (executeNativeRangeNow(key)) {
                                batchUpdated = true
                                completedRanges += key
                            }
                        }
                    } finally {
                        // A viewport may contain several disjoint source ranges. Publish
                        // their captures together so a frame causes at most one style and
                        // one structure invalidation, even if cancellation/supersession
                        // interrupts the remainder of the batch.
                        if (batchUpdated) {
                            invalidateViewportStyles(active, completedRanges)
                            if (structureRefreshPending.getAndSet(false)) {
                                host?.invalidateStructure()
                            }
                        }
                    }
                }
            } finally {
                if (!drainedNormally) {
                    val restart = synchronized(viewportRequestLock) {
                        viewportWorkerActive = false
                        if (queuedViewportRanges.isNotEmpty()) {
                            viewportWorkerActive = true
                            true
                        } else false
                    }
                    if (restart) startViewportWorker()
                }
            }
        }
    }

    /**
     * Capture completion changes only the source lines covered by the completed
     * viewport batch. A document-wide stylesGeneration bump forces every recorded
     * row through styleLine again and makes already-painted ATS symbols appear to
     * be cleared/re-captured during ordinary scrolling.
     *
     * Resolve offsets on the main thread, where EditorDocument mutations are
     * serialized. If an edit has started since the native result was admitted,
     * its edit transaction owns the next invalidation instead.
     */
    private fun invalidateViewportStyles(active: LargeSession, batch: List<CaptureRangeKey>) {
        if (batch.isEmpty()) return
        val startOffset = batch.minOfOrNull { it.startUtf16 } ?: return
        val endOffset = batch.maxOfOrNull { it.endUtf16 } ?: return
        scope.launch(Dispatchers.Main.immediate) {
            val currentHost = host ?: return@launch
            val currentBuffer = currentHost.buffer
            val canInvalidate = synchronized(nativeEditLock) {
                largeSession === active &&
                    generation.get() == active.ticket &&
                    active.pendingEditEpoch.get() == active.appliedEditEpoch.get()
            }
            if (!canInvalidate || currentBuffer.length <= 0) return@launch
            val safeStart = startOffset.coerceIn(0, currentBuffer.length - 1)
            val safeEnd = (endOffset - 1).coerceIn(safeStart, currentBuffer.length - 1)
            val firstLine = currentBuffer.getPositionAt(safeStart).line
            val lastLine = currentBuffer.getPositionAt(safeEnd).line
            if (firstLine > 0 && lastLine >= firstLine) {
                currentHost.invalidateStyles(firstLine, lastLine)
            }
        }
    }

    private suspend fun executeNativeRangeNow(key: CaptureRangeKey): Boolean {
        val active = largeSession ?: return false
        if (generation.get() != active.ticket || active.cacheSessionId != key.sessionId ||
            uncoveredViewportRanges(key).isEmpty()) return false
        val existing = active.rangeWork[key]
        val work = if (existing == null) {
            val initial = RangeWork()
            if (active.rangeWork.putIfAbsent(key, initial) != null) return false
            initial.also { trimRangeWork(active) }
        } else {
            if (existing.state != RangeWorkState.FAILED || System.nanoTime() < existing.retryAfterNanos) return false
            val retry = RangeWork(existing.attempt + 1)
            if (!active.rangeWork.replace(key, existing, retry)) return false
            trimRangeWork(active)
            retry
        }
        val requestedRevision = active.documentRevision.get()
        try {
            if (generation.get() != active.ticket || largeSession !== active) {
                work.state = RangeWorkState.CANCELLED
                return false
            }
            work.state = RangeWorkState.RUNNING
            val missingRanges = uncoveredViewportRanges(key)
            for ((missingStart, missingEnd) in missingRanges) {
                currentCoroutineContext().ensureActive()
                val missingKey = CaptureRangeKey(key.sessionId, missingStart, missingEnd)
                val flat = active.nativeMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (generation.get() != active.ticket || largeSession !== active ||
                        active.documentRevision.get() != requestedRevision) {
                        throw CancellationException("Stale native viewport request")
                    }
                    engine.highlightNativeDocument(
                        documentHandle = active.nativeHandle,
                        startByte = missingStart.toLong() * 2L,
                        endByte = missingEnd.toLong() * 2L
                    ) ?: throw IllegalStateException("Native viewport TSH returned null for visible range")
                }
                val spans = decodeNativeSpans(flat)
                val admitted = synchronized(nativeEditLock) {
                    if (generation.get() == active.ticket && largeSession === active &&
                        active.documentRevision.get() == requestedRevision &&
                        active.pendingEditEpoch.get() == active.appliedEditEpoch.get()) {
                        val index = CaptureIntervalIndex(spans)
                        captureCache.put(missingKey, index)
                        true
                    } else false
                }
                if (!admitted) {
                    work.state = RangeWorkState.CANCELLED
                    break
                }
            }
            if (work.state != RangeWorkState.CANCELLED) {
                work.state = RangeWorkState.SUCCEEDED
            }
        } catch (cancelled: CancellationException) {
            work.state = RangeWorkState.CANCELLED
            throw cancelled
        } catch (error: Throwable) {
            work.state = RangeWorkState.FAILED
            val shift = (work.attempt - 1).coerceIn(0, 5)
            val retryDelayNanos = (1L shl shift) * 1_000_000_000L
            work.retryAfterNanos = System.nanoTime() + retryDelayNanos
            if (generation.get() == active.ticket && largeSession === active) {
                Log.e(TAG, "Native viewport TSH failed for ${key.startUtf16}..${key.endUtf16}", error)
                // A failed range has no cache entry, so the renderer will keep asking
                // for it. Do not invalidate immediately (that would defeat backoff and
                // can create a hot redraw loop); wake the renderer once the retry window
                // expires so a stationary viewport can recover without requiring a
                // scroll, edit, or manual theme refresh.
                work.retryWakeJob = scope.launch {
                    delay((retryDelayNanos / 1_000_000L).coerceAtLeast(1L))
                    if (generation.get() == active.ticket && largeSession === active &&
                        work.state == RangeWorkState.FAILED &&
                        active.rangeWork[key] === work &&
                        uncoveredViewportRanges(key).isNotEmpty()
                    ) {
                        invalidateViewportStyles(active, listOf(key))
                    }
                }
            }
        } finally {
            if (work.state != RangeWorkState.FAILED) {
                work.retryWakeJob?.cancel()
                active.rangeWork.remove(key, work)
            }
        }
        return work.state == RangeWorkState.SUCCEEDED
    }

    /**
     * Mirrors NativeHighlightCache::rebaseAfterEdit for the requested viewport.
     * The native cache remains the authority for dependency safety; this transform
     * only maps a retained viewport's UTF-16LE coordinates through the same edits.
     */
    private fun transformCachedByteRange(
        startByte: Long,
        endByte: Long,
        edit: NativeEdit
    ): Pair<Long, Long>? {
        if (startByte < 0L || endByte < startByte) return null
        val editStart = edit.startByte
        val oldEditEnd = edit.oldEndByte
        if (oldEditEnd < editStart) return null
        val insertedBytes = edit.insertedUtf16Le.size.toLong()
        val delta = insertedBytes - (oldEditEnd - editStart)

        if (oldEditEnd > editStart &&
            startByte < oldEditEnd && editStart < endByte) return null
        if (oldEditEnd == editStart &&
            startByte < editStart && endByte > editStart) return null
        if (endByte <= editStart) return startByte to endByte
        if (startByte >= oldEditEnd) {
            val shiftedStart = startByte + delta
            val shiftedEnd = endByte + delta
            if (shiftedStart < 0L || shiftedEnd < shiftedStart ||
                shiftedEnd > 0xFFFF_FFFFL) return null
            return shiftedStart to shiftedEnd
        }
        return null
    }

    private fun rebaseCachedEntry(
        key: CaptureRangeKey,
        index: CaptureIntervalIndex,
        edits: List<NativeEdit>,
        reusableNativeRanges: Set<Pair<Long, Long>>
    ): Pair<CaptureRangeKey, CaptureIntervalIndex>? {
        val originalStart = key.startUtf16.toLong() * 2L
        val originalEnd = key.endUtf16.toLong() * 2L
        var startByte = originalStart
        var endByte = originalEnd
        for (edit in edits) {
            val transformed = transformCachedByteRange(startByte, endByte, edit) ?: return null
            startByte = transformed.first
            endByte = transformed.second
        }
        if ((startByte and 1L) != 0L || (endByte and 1L) != 0L ||
            startByte > endByte || endByte > 0xFFFF_FFFFL ||
            startByte / 2L > Int.MAX_VALUE.toLong() ||
            endByte / 2L > Int.MAX_VALUE.toLong() ||
            (startByte to endByte) !in reusableNativeRanges) return null

        val delta = startByte - originalStart
        val rebasedIndex = if (delta == 0L) index else {
            var valid = true
            val mapped = index.mapSpans { span ->
                val shiftedStart = span.startByteUnsigned + delta
                val shiftedEnd = span.endByteUnsigned + delta
                if (shiftedStart < 0L || shiftedEnd < shiftedStart ||
                    shiftedEnd > 0xFFFF_FFFFL) {
                    valid = false
                    null
                } else {
                    span.copy(
                        startByte = shiftedStart.toInt(),
                        endByte = shiftedEnd.toInt()
                    )
                }
            }
            if (!valid || mapped.size != index.size) return null
            mapped
        }
        return CaptureRangeKey(
            key.sessionId, (startByte / 2L).toInt(), (endByte / 2L).toInt()
        ) to rebasedIndex
    }

    /**
     * Maps a previously painted viewport through an edit even when its captures
     * cannot be reused. The resulting range tells the renderer exactly which old
     * rows must be re-recorded; a viewport intersecting the edit expands around
     * the inserted/replacement text instead of forcing a document-wide refresh.
     */
    private fun transformViewportRangeForInvalidation(
        startByte: Long,
        endByte: Long,
        edit: NativeEdit
    ): Pair<Long, Long> {
        val editStart = edit.startByte
        val oldEditEnd = edit.oldEndByte
        val insertedBytes = edit.insertedUtf16Le.size.toLong()
        val insertedEnd = editStart + insertedBytes
        val delta = insertedBytes - (oldEditEnd - editStart)

        if (endByte <= editStart) return startByte to endByte
        if (startByte >= oldEditEnd) {
            return (startByte + delta) to (endByte + delta)
        }

        val mappedStart = minOf(startByte, editStart)
        val mappedEnd = if (endByte > oldEditEnd) {
            endByte + delta
        } else {
            insertedEnd
        }
        return mappedStart to maxOf(mappedStart, mappedEnd)
    }

    private fun rebaseCaptureCaches(
        sessionId: Long,
        edits: List<NativeEdit>,
        reusableNativeRanges: Set<Pair<Long, Long>>
    ): List<Pair<Long, Long>> {
        val invalidatedRanges = ArrayList<Pair<Long, Long>>()
        captureCache.transformEntries(
            predicate = { key, _ -> key.sessionId == sessionId },
            transform = { key, index ->
                val next = rebaseCachedEntry(key, index, edits, reusableNativeRanges)
                if (next == null) {
                    var startByte = key.startUtf16.toLong() * 2L
                    var endByte = key.endUtf16.toLong() * 2L
                    for (edit in edits) {
                        val mapped = transformViewportRangeForInvalidation(
                            startByte, endByte, edit
                        )
                        startByte = mapped.first
                        endByte = mapped.second
                    }
                    if (startByte < endByte) invalidatedRanges += startByte to endByte
                }
                next
            }
        )
        return invalidatedRanges
    }

    /**
     * Re-record only lines whose native dirty intervals or previously painted
     * viewport dependencies were invalidated by this edit transaction.
     */
    private fun invalidateEditedStyles(
        active: LargeSession,
        dirtyRanges: List<Pair<Long, Long>>,
        invalidatedCaptureRanges: List<Pair<Long, Long>>
    ) {
        val ranges = ArrayList<Pair<Long, Long>>(
            dirtyRanges.size + invalidatedCaptureRanges.size
        )
        ranges += dirtyRanges
        ranges += invalidatedCaptureRanges
        scope.launch(Dispatchers.Main.immediate) {
            val currentHost = host ?: return@launch
            if (largeSession !== active || generation.get() != active.ticket) return@launch
            val buffer = currentHost.buffer
            val length = buffer.length
            if (length <= 0) {
                currentHost.invalidateStyles(1, 1)
                currentHost.invalidateStructure()
                return@launch
            }

            val lineRanges = ArrayList<Pair<Int, Int>>()
            for ((startByte, endByte) in ranges) {
                if (startByte < 0L || endByte < startByte) continue
                val startUtf16 = (startByte / 2L).coerceIn(0L, length.toLong()).toInt()
                val endUtf16 = (endByte / 2L).coerceIn(0L, length.toLong()).toInt()
                val safeStart = startUtf16.coerceAtMost(length - 1)
                val safeEnd = (if (endUtf16 > startUtf16) endUtf16 - 1 else safeStart)
                    .coerceIn(safeStart, length - 1)
                val firstLine = buffer.getPositionAt(safeStart).line
                val lastLine = buffer.getPositionAt(safeEnd).line
                if (firstLine > 0 && lastLine >= firstLine) {
                    lineRanges += firstLine to lastLine
                }
            }

            if (lineRanges.isEmpty()) {
                // A deletion can leave an empty post-edit byte interval. If no
                // cached viewport described it, fall back to the exact dirty
                // document envelope rather than invalidating every row.
                currentHost.invalidateStyles(1, 1)
            } else {
                val sorted = lineRanges.sortedBy { it.first }
                var first = sorted.first().first
                var last = sorted.first().second
                for (index in 1 until sorted.size) {
                    val next = sorted[index]
                    if (next.first <= last + 1) {
                        last = maxOf(last, next.second)
                    } else {
                        currentHost.invalidateStyles(first, last)
                        first = next.first
                        last = next.second
                    }
                }
                currentHost.invalidateStyles(first, last)
            }
            currentHost.invalidateStructure()
        }
    }

    private fun applyNativeEdits(active: LargeSession, edits: List<NativeEdit>, editEpoch: Long) {
        // Atomically append this transaction to the per-document edit chain.
        // Reading the previous job and publishing the new one in separate critical
        // sections allowed two onEditFinished callbacks to launch out of order.
        val job = synchronized(nativeEditLock) {
            val previous = nativeEditJob
            scope.launch(
                Dispatchers.IO,
                start = kotlinx.coroutines.CoroutineStart.LAZY
            ) {
                previous?.join()
            if (generation.get() != active.ticket || largeSession !== active) return@launch
            try {
                active.nativeMutex.withLock {
                    for (edit in edits) {
                        check(engine.applyNativeDocumentEdit(
                            active.nativeHandle, edit.startByte, edit.oldEndByte, edit.insertedUtf16Le
                        )) { "Native incremental Tree-sitter edit failed" }
                        active.lengthChars.addAndGet(
                            edit.insertedChars.toLong() - edit.deletedChars.toLong()
                        )
                    }
                    // Dirty intervals are versioned native transaction output, not
                    // inferred from the UI edit offsets. Consume only after every edit
                    // in this serialized batch commits, so multiple edits are observed
                    // together in the final document coordinate space.
                    val dirtySnapshot = engine.consumeNativeDirtyLineRanges(active.nativeHandle)
                    val expectedNativeVersion =
                        active.nativeDocumentVersion.get() + edits.size.toLong()
                    check(dirtySnapshot.documentVersion == expectedNativeVersion) {
                        "Native edit version mismatch: expected=$expectedNativeVersion " +
                            "actual=${dirtySnapshot.documentVersion}"
                    }
                    active.nativeDocumentVersion.set(dirtySnapshot.documentVersion)
                    Log.v(
                        TAG,
                        "Native dirty snapshot committed: version=${dirtySnapshot.documentVersion} " +
                            "edits=${edits.size} dirtyLines=${dirtySnapshot.ranges.size}"
                    )
                    // Native cache rebasing has already retained only viewports whose
                    // AST/Query/Regex dependency envelopes survived every edit. Reuse that
                    // exact retained-key set to rebase the Kotlin interval/display caches;
                    // do not clear every token when one local region changes.
                    val reusableNativeRanges = engine.reusableNativeHighlightRanges(
                        active.nativeHandle
                    ).mapTo(HashSet()) { it.startByte to it.endByte }
                    active.documentRevision.incrementAndGet()
                    val invalidatedCaptureRanges = rebaseCaptureCaches(
                        active.cacheSessionId, edits, reusableNativeRanges
                    )
                    active.rangeWork.clear()
                    synchronized(viewportRequestLock) {
                        pendingViewportRanges.clear()
                        queuedViewportRanges = emptyList()
                    }
                    val dirtyStartByte = dirtySnapshot.byteRanges.minOfOrNull { it.startByte }
                    val dirtyStartLine = dirtySnapshot.ranges.minOfOrNull { it.startLine }
                    Log.v(
                        TAG,
                        "Native dirty snapshot applied: version=${dirtySnapshot.documentVersion} " +
                            "byteRanges=${dirtySnapshot.byteRanges.size} lineRanges=${dirtySnapshot.ranges.size} " +
                            "firstDirtyByte=$dirtyStartByte firstDirtyLine=$dirtyStartLine " +
                            "reusableNativeViewports=${reusableNativeRanges.size} " +
                            "captureCacheEntries=${captureCache.size}"
                    )
                    // Publish the new revision only after its stale cache entries are gone.
                    active.appliedEditEpoch.set(editEpoch)
                    if (generation.get() == active.ticket && largeSession === active) {
                        val documentBytes = active.lengthChars.get() * 2L
                        val dirtyByteRanges = dirtySnapshot.byteRanges.map { range ->
                            if (range.endByte > range.startByte) {
                                range.startByte to range.endByte
                            } else if (range.startByte < documentBytes) {
                                range.startByte to minOf(documentBytes, range.startByte + 2L)
                            } else {
                                maxOf(0L, range.startByte - 2L) to range.startByte
                            }
                        }.ifEmpty {
                            // Preserve a correct local redraw even if the native
                            // parser reports no byte interval for a boundary-only edit.
                            edits.mapIndexed { index, edit ->
                                val insertedBytes = edit.insertedUtf16Le.size.toLong()
                                var range = when {
                                    insertedBytes > 0L ->
                                        edit.startByte to (edit.startByte + insertedBytes)
                                    edit.startByte > 0L ->
                                        (edit.startByte - 2L) to edit.startByte
                                    else ->
                                        0L to minOf(documentBytes, 2L)
                                }
                                for (later in edits.drop(index + 1)) {
                                    range = transformViewportRangeForInvalidation(
                                        range.first, range.second, later
                                    )
                                }
                                range
                            }
                        }
                        invalidateEditedStyles(
                            active, dirtyByteRanges, invalidatedCaptureRanges
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Native incremental edit failed; rebuilding the native session", error)
                // A batch may have applied earlier edits before a later transaction
                // failed. Invalidate this session immediately so queued successor
                // batches cannot mutate its partially-updated tree. Rebuild on the
                // editor thread from one authoritative snapshot; edits queued before
                // that snapshot are discarded, later edits are queued for replay.
                if (generation.compareAndSet(active.ticket, active.ticket + 1L)) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation.get() == active.ticket + 1L && largeSession === active) {
                            synchronized(nativeEditLock) { pendingNativeEdits.clear() }
                            host?.buffer?.let(::scheduleParse)
                        }
                    }
                }
            }
            }.also { nativeEditJob = it }
        }
        job.start()
    }

    /**
     * Only FAILED entries survive a viewport attempt. Evict the oldest failures
     * before they can accumulate without bound during repeated scrolling failures;
     * queued/running work is never selected for eviction.
     */
    private fun trimRangeWork(active: LargeSession) {
        val overflow = active.rangeWork.size - MAX_RANGE_WORK_ENTRIES
        if (overflow <= 0) return
        active.rangeWork.entries.asSequence()
            .filter { it.value.state == RangeWorkState.FAILED }
            .sortedBy { it.value.lastAttemptNanos }
            .take(overflow)
            .forEach { entry ->
                if (active.rangeWork.remove(entry.key, entry.value)) {
                    // The delayed wake-up is part of the failed record's lifetime.
                    // Cancel it when the bounded bookkeeping evicts that record, or
                    // repeated failures while scrolling can accumulate orphan timers.
                    entry.value.retryWakeJob?.cancel()
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

    private fun disposeSession(active: LargeSession) {
        // Retry wake-ups retain their LargeSession until their delay expires. A
        // detached/replaced document must release those jobs immediately rather
        // than keeping up to 256 stale session closures alive for the backoff window.
        active.rangeWork.values.forEach { it.retryWakeJob?.cancel() }
        active.rangeWork.clear()
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
