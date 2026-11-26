package com.zerostudio.idea.androidtreesitter

import android.content.Context
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.util.Log
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.largefile.ByteBudgetLruCache
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import com.zerostudio.idea.treesitter.tsh.HighlightCaptureBatch
import com.zerostudio.idea.treesitter.tsh.NativeTextWindowReader
import com.zerostudio.idea.treesitter.tsh.TshDocumentRuntime
import com.zerostudio.idea.treesitter.tsh.TshParserFactory
import com.zerostudio.idea.treesitter.tsh.TshGrammarProvider
import com.zerostudio.idea.treesitter.tsh.TshGrammarBinding
import com.zerostudio.idea.treesitter.tsh.TshGrammarRegistry
import com.zerostudio.idea.treesitter.tsh.TshLanguageRequest
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
import java.nio.ByteOrder
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

    // Validate the selected .tsh binding before any native document is created.
    private val grammarBinding = TshGrammarBinding.bind(language, extension, engine)

    companion object {
        private const val TAG = "WindbellTSH"
        // One immutable interval payload is shared by scope lookups and drawing.
        // Keeping duplicate semantic/display LRUs charged the same payload twice
        // and doubled index maintenance without providing an independent lifetime.
        private const val CAPTURE_CACHE_BYTES = 32L * 1024L * 1024L
        private const val DISPLAY_RETENTION_CACHE_BYTES = 8L * 1024L * 1024L
        private const val PRIMARY_CAPTURE_CACHE_BYTES =
            CAPTURE_CACHE_BYTES - DISPLAY_RETENTION_CACHE_BYTES
        private const val MAX_RANGE_WORK_ENTRIES = 256
        private val nextSessionId = AtomicLong(1L)
        private val captureRangeIndex = CaptureRangeLookupIndex()
        // Recently capacity-evicted pages retain their primitive captures. Both
        // tiers share one hard budget; eviction is storage-only, never semantic invalidation.
        private val displayRetentionCache =
            ByteBudgetLruCache<CaptureRangeKey, CaptureIntervalIndex>(
                DISPLAY_RETENTION_CACHE_BYTES,
                { index -> 160L + index.size.toLong() * 32L },
                onEntryAdded = { key, _ -> captureRangeIndex.add(key) },
                onEntryRemoved = { key, _ -> captureRangeIndex.remove(key) }
            )
        private val captureCache =
            ByteBudgetLruCache<CaptureRangeKey, CaptureIntervalIndex>(
                PRIMARY_CAPTURE_CACHE_BYTES,
                { index -> 160L + index.size.toLong() * 32L },
                onEntryAdded = { key, _ -> captureRangeIndex.add(key) },
                onEntryRemoved = { key, _ -> captureRangeIndex.remove(key) }
            ).also { cache ->
                cache.setCapacityEvictionHandler { key, index ->
                    displayRetentionCache.put(key, index)
                }
            }

        /**
         * Resolves the file's Grammar through APK/external providers, then enters
         * the same binding-validated highlighter and Native ParserSession pipeline.
         * Returns null when no compatible Grammar can be resolved.
         */
        fun forFile(
            context: Context,
            request: TshLanguageRequest,
            temporaryDirectory: File,
            externalGrammarDirectories: List<File> = emptyList(),
            engine: WindbellTshEngine = WindbellTshEngine.instance
        ): TshSyntaxHighlighter? {
            val provider = TshGrammarProvider(context, TshGrammarRegistry(), engine)
            provider.discover(externalGrammarDirectories)
            val binding = provider.resolve(request) ?: return null
            return TshSyntaxHighlighter(
                language = binding.language,
                extension = binding.extension,
                engine = engine,
                temporaryDirectory = temporaryDirectory
            )
        }
    }

    private data class LargeSession(
        /** Native-owned PieceTreeSource + incremental Tree-sitter tree. */
        val nativeHandle: Long,
        val documentRuntime: TshDocumentRuntime,
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

    private data class CaptureQueryResult(
        val captures: HighlightCaptureBatch,
        val complete: Boolean
    )

    /**
     * Native records the dependency proof for the original viewport query. When
     * Kotlin stores that result as clipped child pages, each child is reusable
     * whenever one surviving native proof fully contains it.
     */
    private class NativeReusableRangeIndex(ranges: Collection<Pair<Long, Long>>) {
        private val starts: LongArray
        private val prefixMaxEnds: LongArray
        val size: Int get() = starts.size

        init {
            val sorted = ranges.sortedBy { it.first }
            starts = LongArray(sorted.size)
            prefixMaxEnds = LongArray(sorted.size)
            var maximumEnd = Long.MIN_VALUE
            for (index in sorted.indices) {
                starts[index] = sorted[index].first
                maximumEnd = maxOf(maximumEnd, sorted[index].second)
                prefixMaxEnds[index] = maximumEnd
            }
        }

        fun covers(startByte: Long, endByte: Long): Boolean {
            if (startByte < 0L || endByte < startByte || starts.isEmpty()) return false
            var low = 0
            var high = starts.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (starts[middle] <= startByte) low = middle + 1 else high = middle
            }
            val candidate = low - 1
            return candidate >= 0 && prefixMaxEnds[candidate] >= endByte
        }
    }

    private enum class RangeWorkState { QUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

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
    // Display-only snapshots of edits not yet committed to the native tree. They
    // let unchanged rows keep their old captures while semantic caches are rebased.
    private val pendingDisplayEdits = ArrayList<CaptureDisplayRebaser.PendingEdit>()
    // Ranges temporarily drawn from old captures while edits are pending. They are
    // invalidated once the final queued native revision is committed and ready.
    private val pendingDisplayRanges = ArrayList<CaptureDisplayRebaser.SourceRange>()
    private var nativeEditJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()
    private val firstStyleCallLogged = AtomicBoolean(false)
    // Lightweight stage counters distinguish cache churn from actual native
    // work. They are sampled at a low rate and never drive cache validity.
    private val styleLineCalls = AtomicLong()
    private val styleLineNanos = AtomicLong()
    // Separate cache-to-span projection from Android span/sweep painting. A
    // cache hit is not evidence that repeated line rendering is inexpensive.
    private val captureProjectionNanos = AtomicLong()
    private val captureProjectionSpans = AtomicLong()
    private val androidPaintNanos = AtomicLong()
    private val androidPaintRuns = AtomicLong()
    private val cachedCaptureHits = AtomicLong()
    private val cachedCaptureMisses = AtomicLong()
    private val nativeRangeQueries = AtomicLong()
    private val nativeRangeWaitNanos = AtomicLong()
    private val nativeRangeQueryNanos = AtomicLong()
    private val lastPipelineMetricsLogNanos = AtomicLong()

    // One-shot first-open timeline. These timestamps are diagnostic only and do
    // not affect scheduling, cache validity, or rendering decisions.
    private val firstOpenStartNanos = AtomicLong()
    private val firstSnapshotReadyNanos = AtomicLong()
    private val firstNativeCreateStartedNanos = AtomicLong()
    private val firstStyleCallbackNanos = AtomicLong()
    private val firstNativeReadyNanos = AtomicLong()
    private val firstViewportQueuedNanos = AtomicLong()
    private val firstViewportQueryCompletedNanos = AtomicLong()
    private val firstViewportCaptureCount = AtomicLong(-1L)
    private val firstStyledLineNanos = AtomicLong()
    private val firstOpenMetricsLogged = AtomicBoolean(false)

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
        synchronized(nativeEditLock) {
            pendingNativeEdits.clear()
            pendingDisplayEdits.clear()
            pendingDisplayRanges.clear()
        }
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
            val edit = NativeEdit(
                startByte = change.rangeOffset.toLong() * 2L,
                oldEndByte = (change.rangeOffset.toLong() + change.deletedTextLength) * 2L,
                insertedUtf16Le = bytes,
                insertedChars = inserted.length,
                deletedChars = change.deletedTextLength
            )
            // Keep the edit in the native replay queue. If a published document
            // exists, also retain a display-only coordinate transform so unaffected
            // captures can remain painted while semantic invalidation is in flight.
            largeSession?.let { active ->
                active.pendingEditEpoch.incrementAndGet()
                val displayEdit = CaptureDisplayRebaser.PendingEdit(
                    startUtf16 = change.rangeOffset,
                    deletedChars = change.deletedTextLength,
                    insertedChars = inserted.length
                )
                // Move any previously deferred viewport ranges through this next
                // edit. Ranges touched by it are dropped; their rows are covered by
                // the native dirty interval for the edit itself.
                val shiftedDisplayRanges = pendingDisplayRanges.mapNotNull { range ->
                    CaptureDisplayRebaser.mapOriginalCaptureToCurrent(
                        range.startUtf16, range.endUtf16, listOf(displayEdit)
                    )
                }
                pendingDisplayRanges.clear()
                pendingDisplayRanges.addAll(shiftedDisplayRanges)
                pendingDisplayEdits += displayEdit
            }
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
        val styleStartedNanos = System.nanoTime()
        firstStyleCallbackNanos.compareAndSet(0L, styleStartedNanos)
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
        // Serialize revision observation, pending-edit coordinate mapping and
        // cache reads. While a native edit is pending, only unchanged display
        // captures are mapped from the old revision; semantic consumers never see
        // these provisional spans.
        val projectionStartedNanos = System.nanoTime()
        val spans = synchronized(nativeEditLock) {
            if (largeSession !== active || generation.get() != active.ticket) {
                return@synchronized HighlightCaptureBatch.empty()
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
            if (boundedStart >= boundedEnd) HighlightCaptureBatch.empty()
            else {
                val requested = CaptureRangeKey(
                    active.cacheSessionId, boundedStart.toInt(), boundedEnd.toInt())
                val cached = if (editPending) {
                    // Do not blank every visible row while one local edit is waiting
                    // for native parsing. Map untouched current ranges back to the
                    // previous cache coordinate space, then shift only captures that
                    // are wholly outside every replaced/inserted interval.
                    val displayEdits = pendingDisplayEdits.toList()
                    val original = CaptureDisplayRebaser.mapCurrentRangeToOriginal(
                        boundedStart.toInt(), boundedEnd.toInt(), displayEdits
                    )
                    if (original == null) null else {
                        val oldKey = CaptureRangeKey(
                            active.cacheSessionId, original.startUtf16, original.endUtf16
                        )
                        queryCachedCaptures(
                            oldKey, original.startUtf16, original.endUtf16
                        )?.let { old ->
                            val oldCaptures = old.captures
                            var retainedCount = 0
                            for (captureIndex in 0 until oldCaptures.size) {
                                if (CaptureDisplayRebaser.mapOriginalCaptureToCurrentPacked(
                                        (oldCaptures.startByteUnsignedAt(captureIndex) / 2L).toInt(),
                                        (oldCaptures.endByteUnsignedAt(captureIndex) / 2L).toInt(),
                                        displayEdits
                                    ) != -1L) retainedCount++
                            }
                            val rebasedCaptures = HighlightCaptureBatch.allocate(retainedCount)
                            var outputIndex = 0
                            for (captureIndex in 0 until oldCaptures.size) {
                                val mapped = CaptureDisplayRebaser.mapOriginalCaptureToCurrentPacked(
                                    (oldCaptures.startByteUnsignedAt(captureIndex) / 2L).toInt(),
                                    (oldCaptures.endByteUnsignedAt(captureIndex) / 2L).toInt(),
                                    displayEdits
                                )
                                if (mapped == -1L) continue
                                rebasedCaptures.copyRecordFrom(
                                    outputIndex++, oldCaptures, captureIndex,
                                    (mapped ushr 32).toInt() * 2, mapped.toInt() * 2
                                )
                            }
                            CaptureQueryResult(
                                captures = rebasedCaptures,
                                // Display continuity only; native semantics are refreshed after commit.
                                complete = false
                            ).also {
                                val displayRange = CaptureDisplayRebaser.SourceRange(
                                    boundedStart.toInt(), boundedEnd.toInt()
                                )
                                if (displayRange !in pendingDisplayRanges) {
                                    pendingDisplayRanges += displayRange
                                }
                            }
                        }
                    }
                } else {
                    queryCachedCaptures(requested, boundedStart.toInt(), boundedEnd.toInt())
                }
                if (!editPending && cached?.complete != true) {
                    enqueueViewportRange(requested)
                }
                cached?.captures ?: HighlightCaptureBatch.empty()
            }
        }
        captureProjectionNanos.addAndGet(System.nanoTime() - projectionStartedNanos)
        captureProjectionSpans.addAndGet(spans.size.toLong())

        if (firstStyleCallLogged.compareAndSet(false, true)) {
            Log.i(TAG, "Editor renderer invoked styleLine: extension=.$extension line=$bufferLine range=$start..$end captures=${spans.size}")
        }
        if (spans.size == 0) return null

        val styled = SpannableStringBuilder(text)
        val paintStartedNanos = System.nanoTime()
        engine.applyHighlightCaptureBatchToSpannable(
            batch = spans,
            documentOffset = lineStartOffset,
            text = text,
            extension = extension,
            spannable = styled,
            start = visibleStart,
            end = visibleEnd
        )
        androidPaintNanos.addAndGet(System.nanoTime() - paintStartedNanos)
        val applied = styled.getSpans(visibleStart, visibleEnd, CharacterStyle::class.java)
        androidPaintRuns.addAndGet(applied.size.toLong())
        if (applied.isEmpty()) {
            Log.w(TAG, "No Android CharacterStyle applied: extension=.$extension line=$bufferLine captures=${spans.size}")
            return null
        }
        val styledAtNanos = System.nanoTime()
        if (firstStyledLineNanos.compareAndSet(0L, styledAtNanos)) {
            logFirstOpenColorReady(
                bufferLine, spans.size, applied.size, styledAtNanos
            )
        }
        styleLineCalls.incrementAndGet()
        styleLineNanos.addAndGet(styledAtNanos - styleStartedNanos)
        logPipelineMetricsIfDue()
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
                    offset == Int.MAX_VALUE) return@synchronized HighlightCaptureBatch.empty()
                val key = CaptureRangeKey(active.cacheSessionId, offset, offset + 1)
                val cached = queryCachedCaptures(key, offset, offset + 1)
                if (cached?.complete != true) {
                    structureRefreshPending.set(true)
                    enqueueViewportRange(key)
                    null
                } else {
                    cached.captures
                }
            } ?: return true
            resolved
        } else {
            // There is no Kotlin-side full-document capture index anymore.
            // Until the native document is published, treat lexical scope as
            // unknown and avoid inserting bracket pairs into comments/strings.
            return true
        }
        for (index in 0 until spans.size) {
            val style = stylesById[spans.styleIdAt(index)]
            if (excludedScopes.any { scope ->
                    style?.tags?.contains(scope) == true || style?.scope?.startsWith(scope) == true
                }) return true
        }
        return false
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
        val openStartedNanos = System.nanoTime()
        firstOpenStartNanos.set(openStartedNanos)
        firstSnapshotReadyNanos.set(0L)
        firstNativeCreateStartedNanos.set(0L)
        firstStyleCallbackNanos.set(0L)
        firstNativeReadyNanos.set(0L)
        firstViewportQueuedNanos.set(0L)
        firstViewportQueryCompletedNanos.set(0L)
        firstViewportCaptureCount.set(-1L)
        firstStyledLineNanos.set(0L)
        firstOpenMetricsLogged.set(false)

        // PieceTreeBuffer snapshots retain their backing chunks. The unified
        // native pipeline never calls getText() or snapshot.toString() for the
        // complete document, regardless of its size.
        val snapshot = buffer.createTextSnapshot()
        firstSnapshotReadyNanos.set(System.nanoTime())
        structureRefreshPending.set(false)
        synchronized(nativeEditLock) {
            pendingNativeEdits.clear()
            pendingDisplayEdits.clear()
            pendingDisplayRanges.clear()
        }
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
        firstNativeCreateStartedNanos.compareAndSet(0L, System.nanoTime())
        var nativeHandle = 0L
        var documentRuntime: TshDocumentRuntime? = null
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
                require(text.length <= maxBytes / 2) {
                    "Piece Tree snapshot returned more UTF-16 code units than the native window"
                }
                // Write UTF-16 code units through a little-endian direct-buffer view.
                // This preserves lone surrogates exactly while avoiding two Kotlin
                // ByteBuffer.put calls and bit conversions for every document code unit.
                val byteCount = text.length * 2
                destination.order(ByteOrder.LITTLE_ENDIAN).asCharBuffer().put(text)
                destination.position(byteCount)
                byteCount
            }
            val parserSession = TshParserFactory(engine).create(
                binding = grammarBinding,
                bytes = snapshot.length.toLong() * 2L,
                directory = temporaryDirectory,
                reader = reader
            )
            nativeHandle = parserSession.handle
            documentRuntime = TshDocumentRuntime(grammarBinding, parserSession)
            check(nativeHandle != 0L) { "Native TSH document creation returned a null handle" }
            currentCoroutineContext().ensureActive()
            if (generation.get() != ticket) throw CancellationException("Stale native document")

            cacheSessionId = nextSessionId.getAndIncrement()
            val active = LargeSession(
                nativeHandle, checkNotNull(documentRuntime),
                AtomicLong(snapshot.length.toLong()), cacheSessionId, ticket
            )
            if (generation.get() == ticket) {
                synchronized(nativeEditLock) {
                    val queued = pendingNativeEdits.toList()
                    pendingNativeEdits.clear()
                    for (edit in queued) {
                        checkNotNull(documentRuntime).applyEdit(
                            edit.startByte, edit.oldEndByte, edit.insertedUtf16Le
                        )
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
                    firstNativeReadyNanos.compareAndSet(0L, System.nanoTime())
                }
                nativeHandle = 0L
                documentRuntime = null
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
            documentRuntime?.close() ?: run {
                if (nativeHandle != 0L) engine.closeNativeDocument(nativeHandle)
            }
            if (cacheSessionId != 0L) {
                captureCache.invalidate { key, _ -> key.sessionId == cacheSessionId }
                displayRetentionCache.invalidate { key, _ -> key.sessionId == cacheSessionId }
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
     * Read a requested viewport across the primary capture LRU and its bounded
     * display-retention tier. Both tiers share one range index and one total byte
     * budget; no capture objects or duplicate page copies are created on lookup.
     */
    private fun logFirstOpenColorReady(
        line: Int,
        lineCaptureCount: Int,
        androidSpanCount: Int,
        styledAtNanos: Long
    ) {
        if (!firstOpenMetricsLogged.compareAndSet(false, true)) return
        val started = firstOpenStartNanos.get()
        if (started <= 0L) return
        fun elapsedMillis(end: Long): Long =
            if (end > 0L && end >= started) (end - started) / 1_000_000L else -1L

        val snapshotReady = firstSnapshotReadyNanos.get()
        val createStarted = firstNativeCreateStartedNanos.get()
        val nativeReady = firstNativeReadyNanos.get()
        val rendererCallback = firstStyleCallbackNanos.get()
        val viewportQueued = firstViewportQueuedNanos.get()
        val queryCompleted = firstViewportQueryCompletedNanos.get()
        val nativeReadyToQueueMs =
            if (nativeReady > 0L && viewportQueued >= nativeReady) {
                (viewportQueued - nativeReady) / 1_000_000L
            } else -1L
        val queueToQueryMs =
            if (viewportQueued > 0L && queryCompleted >= viewportQueued) {
                (queryCompleted - viewportQueued) / 1_000_000L
            } else -1L
        val queryToSpansMs =
            if (queryCompleted > 0L && styledAtNanos >= queryCompleted) {
                (styledAtNanos - queryCompleted) / 1_000_000L
            } else -1L

        Log.i(
            TAG,
            "firstOpenColorReady extension=.$extension line=$line " +
                "totalMs=${elapsedMillis(styledAtNanos)} " +
                "snapshotMs=${if (snapshotReady > 0L) (snapshotReady - started) / 1_000_000L else -1L} " +
                "scheduleToNativeStartMs=${if (createStarted > 0L) (createStarted - started) / 1_000_000L else -1L} " +
                "nativeReadyMs=${elapsedMillis(nativeReady)} " +
                "firstRendererCallbackMs=${elapsedMillis(rendererCallback)} " +
                "nativeReadyToViewportQueueMs=$nativeReadyToQueueMs " +
                "viewportNativeQueryMs=$queueToQueryMs queryToAndroidSpansMs=$queryToSpansMs " +
                "viewportCaptures=${firstViewportCaptureCount.get()} " +
                "lineCaptures=$lineCaptureCount androidSpans=$androidSpanCount"
        )
    }

    private fun logPipelineMetricsIfDue() {
        val now = System.nanoTime()
        val previous = lastPipelineMetricsLogNanos.get()
        if (now - previous < 5_000_000_000L ||
            !lastPipelineMetricsLogNanos.compareAndSet(previous, now)) return
        Log.i(
            TAG,
            "pipelineMetrics extension=.$extension " +
                "styleLineCalls=${styleLineCalls.get()} styleLineMs=${styleLineNanos.get() / 1_000_000L} " +
                "captureProjectionMs=${captureProjectionNanos.get() / 1_000_000L} " +
                "captureProjectionSpans=${captureProjectionSpans.get()} " +
                "androidPaintMs=${androidPaintNanos.get() / 1_000_000L} " +
                "androidPaintRuns=${androidPaintRuns.get()} " +
                "captureCacheHits=${cachedCaptureHits.get()} captureCacheMisses=${cachedCaptureMisses.get()} " +
                "nativeRanges=${nativeRangeQueries.get()} nativeLockWaitMs=${nativeRangeWaitNanos.get() / 1_000_000L} " +
                "nativeQueryMs=${nativeRangeQueryNanos.get() / 1_000_000L} " +
                "cacheEntries=${captureCache.size + displayRetentionCache.size} " +
                "cacheBytes=${captureCache.bytesUsed + displayRetentionCache.bytesUsed} " +
                "cacheEvictions=${captureCache.evictionCount + displayRetentionCache.evictionCount} " +
                "cacheRejected=${captureCache.rejectedAdmissionCount + displayRetentionCache.rejectedAdmissionCount}"
        )
    }

    private fun queryCachedCaptures(
        key: CaptureRangeKey,
        startUtf16: Int,
        endUtf16: Int
    ): CaptureQueryResult? {
        val keys = captureRangeIndex.findOverlappingRanges(
            key.sessionId, startUtf16, endUtf16
        )
        if (keys.isEmpty()) {
            cachedCaptureMisses.incrementAndGet()
            logPipelineMetricsIfDue()
            return null
        }

        var coveredUntil = startUtf16
        var complete = true
        var foundEntry = false
        var captureCount = 0L
        val indexes = ArrayList<CaptureIntervalIndex>(keys.size)
        for (cachedKey in keys) {
            if (cachedKey.startUtf16 > coveredUntil) complete = false
            val index = captureCache.get(cachedKey) ?: displayRetentionCache.get(cachedKey)
            if (index == null) {
                captureRangeIndex.remove(cachedKey)
                continue
            }
            foundEntry = true
            indexes += index
            captureCount += index.countQuery(startUtf16, endUtf16).toLong()
            if (captureCount > Int.MAX_VALUE) {
                throw IllegalStateException("Viewport capture count exceeds the primitive batch index domain")
            }
            if (cachedKey.endUtf16 > coveredUntil) coveredUntil = cachedKey.endUtf16
            if (coveredUntil >= endUtf16) break
        }
        if (coveredUntil < endUtf16) complete = false
        if (!foundEntry) {
            cachedCaptureMisses.incrementAndGet()
            logPipelineMetricsIfDue()
            return null
        }

        val captures = HighlightCaptureBatch.allocate(captureCount.toInt())
        var outputOffset = 0
        for (index in indexes) {
            outputOffset += index.writeQuery(startUtf16, endUtf16, captures, outputOffset)
        }
        check(outputOffset == captures.size) {
            "Capture index changed while projecting an immutable cache batch"
        }
        cachedCaptureHits.incrementAndGet()
        logPipelineMetricsIfDue()
        return CaptureQueryResult(captures, complete)
    }


    /** Source intervals already represented by the capture cache. */
    private fun cachedRanges(key: CaptureRangeKey): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        for (cachedKey in captureRangeIndex.findOverlappingRanges(
            key.sessionId, key.startUtf16, key.endUtf16
        )) {
            if (captureCache.get(cachedKey) != null || displayRetentionCache.get(cachedKey) != null) {
                ranges += cachedKey.startUtf16 to cachedKey.endUtf16
            } else captureRangeIndex.remove(cachedKey)
        }
        // findOverlappingRanges is already returned in BST in-order
        // (start/end) order. Do not sort the same viewport ranges a second time.
        return ranges
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
        firstViewportQueuedNanos.compareAndSet(0L, System.nanoTime())
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
        // Capture the session before dispatching. The worker must never resolve
        // an unbound/stale session from mutable state while publishing results.
        val active = largeSession ?: run {
            synchronized(viewportRequestLock) { viewportWorkerActive = false }
            return
        }
        if (generation.get() != active.ticket) {
            synchronized(viewportRequestLock) { viewportWorkerActive = false }
            return
        }
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
                    val batchRevision = active.documentRevision.get()
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
                            val capturedRanges = executeNativeRangeNow(key)
                            if (capturedRanges.isNotEmpty()) {
                                batchUpdated = true
                                completedRanges += capturedRanges
                            }
                        }
                    } finally {
                        // A viewport may contain several disjoint source ranges. Publish
                        // their captures together so a frame causes at most one style and
                        // one structure invalidation, even if cancellation/supersession
                        // interrupts the remainder of the batch.
                        if (batchUpdated) {
                            invalidateViewportStyles(active, completedRanges, batchRevision)
                            val canRefreshStructure =
                                largeSession === active &&
                                    generation.get() == active.ticket &&
                                    active.documentRevision.get() == batchRevision &&
                                    active.pendingEditEpoch.get() == active.appliedEditEpoch.get()
                            if (canRefreshStructure &&
                                structureRefreshPending.compareAndSet(true, false)) {
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
    private fun invalidateViewportStyles(
        active: LargeSession,
        batch: List<CaptureRangeKey>,
        expectedRevision: Long
    ) {
        if (batch.isEmpty()) return
        scope.launch(Dispatchers.Main.immediate) {
            val currentHost = host ?: return@launch
            val currentBuffer = currentHost.buffer
            val canInvalidate = synchronized(nativeEditLock) {
                largeSession === active &&
                    generation.get() == active.ticket &&
                    active.documentRevision.get() == expectedRevision &&
                    active.pendingEditEpoch.get() == active.appliedEditEpoch.get()
            }
            if (!canInvalidate || currentBuffer.length <= 0) return@launch

            // A batch may contain disjoint viewport requests. Collapsing them to
            // min(start)..max(end) invalidated every row between distant ranges,
            // causing unrelated ATS rows to be re-recorded and their cache misses
            // to trigger another native query. Keep the invalidation set sparse.
            val lineRanges = ArrayList<Pair<Int, Int>>(batch.size)
            for (range in batch) {
                if (range.startUtf16 >= range.endUtf16) continue
                val safeStart = range.startUtf16.coerceIn(0, currentBuffer.length - 1)
                val safeEnd = (range.endUtf16 - 1).coerceIn(
                    safeStart, currentBuffer.length - 1
                )
                val firstLine = currentBuffer.getPositionAt(safeStart).lineNumber
                val lastLine = currentBuffer.getPositionAt(safeEnd).lineNumber
                if (firstLine > 0 && lastLine >= firstLine) {
                    lineRanges += firstLine to lastLine
                }
            }
            if (lineRanges.isEmpty()) return@launch

            val sorted = lineRanges.sortedBy { it.first }
            val merged = ArrayList<Pair<Int, Int>>(sorted.size)
            var first = sorted.first().first
            var last = sorted.first().second
            for (index in 1 until sorted.size) {
                val next = sorted[index]
                if (next.first <= last + 1) {
                    last = maxOf(last, next.second)
                } else {
                    merged += first to last
                    first = next.first
                    last = next.second
                }
            }
            merged += first to last
            currentHost.invalidateStyles(merged)
        }
    }

    /**
     * Admits one Native result as a cache page or as scalar-aligned child pages.
     * All children are clipped from the same flat result, preserving spans that
     * cross split boundaries without repeating the expensive Native query.
     *
     * true = all pages admitted; false = subdivision exhausted; null = stale revision.
     */
    private fun admitNativeCaptureRange(
        active: LargeSession,
        rootKey: CaptureRangeKey,
        key: CaptureRangeKey,
        flat: IntArray,
        expectedRevision: Long,
        admittedRanges: MutableList<CaptureRangeKey>
    ): Boolean? {
        if (key == rootKey) {
            val rootBytes = 160L +
                countNativeCaptures(flat, key.startUtf16, key.endUtf16).toLong() * 32L
            // Splitting cannot reduce total capture storage; reject before planning
            // if even the unsplit representation exceeds the combined hard budget.
            if (rootBytes > CAPTURE_CACHE_BYTES) return false
            val totalBytes = estimatePartitionedCaptureBytes(
                flat, key.startUtf16, key.endUtf16, DISPLAY_RETENTION_CACHE_BYTES
            )
            // Account for duplicated captures crossing child boundaries and page overhead.
            if (totalBytes > CAPTURE_CACHE_BYTES) return false
        }

        val pageBytes = 160L +
            countNativeCaptures(flat, key.startUtf16, key.endUtf16).toLong() * 32L
        if (pageBytes > DISPLAY_RETENTION_CACHE_BYTES) {
            val split = chooseScalarSafeSplit(key.startUtf16, key.endUtf16) ?: return false
            val left = CaptureRangeKey(key.sessionId, key.startUtf16, split)
            val right = CaptureRangeKey(key.sessionId, split, key.endUtf16)
            val leftResult = admitNativeCaptureRange(
                active, rootKey, left, flat, expectedRevision, admittedRanges
            ) ?: return null
            if (!leftResult) return false
            return admitNativeCaptureRange(
                active, rootKey, right, flat, expectedRevision, admittedRanges
            )
        }

        val index = CaptureIntervalIndex(
            flat, key.startUtf16.toLong() * 2L, key.endUtf16.toLong() * 2L
        )
        val admitted = synchronized(nativeEditLock) {
            if (generation.get() == active.ticket && largeSession === active &&
                active.documentRevision.get() == expectedRevision &&
                active.pendingEditEpoch.get() == active.appliedEditEpoch.get()) {
                captureCache.put(key, index)
            } else null
        } ?: return null
        if (!admitted) return false
        admittedRanges += key
        return true
    }

    private fun estimatePartitionedCaptureBytes(
        flat: IntArray,
        startUtf16: Int,
        endUtf16: Int,
        targetPageBytes: Long
    ): Long {
        val pageBytes = 160L +
            countNativeCaptures(flat, startUtf16, endUtf16).toLong() * 32L
        if (pageBytes <= targetPageBytes) return pageBytes
        val split = chooseScalarSafeSplit(startUtf16, endUtf16) ?: return Long.MAX_VALUE
        val left = estimatePartitionedCaptureBytes(flat, startUtf16, split, targetPageBytes)
        if (left > CAPTURE_CACHE_BYTES) return Long.MAX_VALUE
        val right = estimatePartitionedCaptureBytes(flat, split, endUtf16, targetPageBytes)
        if (right > CAPTURE_CACHE_BYTES || left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }

    private fun countNativeCaptures(flat: IntArray, startUtf16: Int, endUtf16: Int): Int {
        require(flat.size % 6 == 0) { "Native capture payload must contain six integers per capture" }
        val startByte = (startUtf16.toLong() * 2L).toInt()
        val endByte = (endUtf16.toLong() * 2L).toInt()
        var count = 0
        var offset = 0
        while (offset < flat.size) {
            if (Integer.compareUnsigned(flat[offset], endByte) < 0 &&
                Integer.compareUnsigned(flat[offset + 1], startByte) > 0) count++
            offset += 6
        }
        return count
    }

    private fun chooseScalarSafeSplit(startUtf16: Int, endUtf16: Int): Int? {
        if (endUtf16 - startUtf16 <= 1) return null
        var split = startUtf16 + ((endUtf16 - startUtf16) ushr 1)
        if (split <= startUtf16 || split >= endUtf16) return null
        val buffer = host?.buffer ?: return null
        val before: Int
        val after: Int
        try {
            before = buffer.getCharCode(split - 1)
            after = buffer.getCharCode(split)
        } catch (_: Throwable) {
            return null
        }
        if (before in 0xD800..0xDBFF && after in 0xDC00..0xDFFF) split++
        return split.takeIf { it > startUtf16 && it < endUtf16 }
    }

    private suspend fun executeNativeRangeNow(
        key: CaptureRangeKey
    ): List<CaptureRangeKey> {
        val active = largeSession ?: return emptyList()
        if (generation.get() != active.ticket || active.cacheSessionId != key.sessionId ||
            uncoveredViewportRanges(key).isEmpty()) return emptyList()
        val existing = active.rangeWork[key]
        val work = if (existing == null) {
            val initial = RangeWork()
            if (active.rangeWork.putIfAbsent(key, initial) != null) return emptyList()
            initial.also { trimRangeWork(active) }
        } else {
            when (existing.state) {
                RangeWorkState.FAILED -> {
                    if (System.nanoTime() < existing.retryAfterNanos) return emptyList()
                    val retry = RangeWork(existing.attempt + 1)
                    if (!active.rangeWork.replace(key, existing, retry)) return emptyList()
                    trimRangeWork(active)
                    retry
                }
                RangeWorkState.SUCCEEDED, RangeWorkState.CANCELLED -> {
                    // SUCCEEDED records completion, not permanent ownership.
                    // Primary-cache eviction transfers the immutable page into the
                    // bounded display-retention tier; only eviction from both tiers
                    // creates a true miss. A stale SUCCEEDED marker must not suppress
                    // that eventual recapture. Cancelled work may also be resubmitted.
                    val retry = RangeWork(existing.attempt)
                    if (!active.rangeWork.replace(key, existing, retry)) return emptyList()
                    trimRangeWork(active)
                    retry
                }
                RangeWorkState.QUEUED, RangeWorkState.RUNNING, RangeWorkState.BLOCKED -> return emptyList()
            }
        }
        val requestedRevision = active.documentRevision.get()
        val capturedRanges = ArrayList<CaptureRangeKey>()
        try {
            if (generation.get() != active.ticket || largeSession !== active) {
                work.state = RangeWorkState.CANCELLED
                return emptyList()
            }
            work.state = RangeWorkState.RUNNING
            val missingRanges = uncoveredViewportRanges(key)
            for ((missingStart, missingEnd) in missingRanges) {
                currentCoroutineContext().ensureActive()
                val missingKey = CaptureRangeKey(key.sessionId, missingStart, missingEnd)
                val lockWaitStarted = System.nanoTime()
                val flat = active.nativeMutex.withLock {
                    nativeRangeWaitNanos.addAndGet(System.nanoTime() - lockWaitStarted)
                    currentCoroutineContext().ensureActive()
                    if (generation.get() != active.ticket || largeSession !== active ||
                        active.documentRevision.get() != requestedRevision) {
                        throw CancellationException("Stale native viewport request")
                    }
                    val queryStarted = System.nanoTime()
                    try {
                        val result = active.documentRuntime.parser.highlight(
                            start = missingStart.toLong() * 2L,
                            end = missingEnd.toLong() * 2L
                        ) ?: throw IllegalStateException("Native viewport TSH returned null for visible range")
                        if (firstViewportQueryCompletedNanos.compareAndSet(0L, System.nanoTime())) {
                            firstViewportCaptureCount.set((result.size / 6).toLong())
                        }
                        result
                    } finally {
                        nativeRangeQueries.incrementAndGet()
                        nativeRangeQueryNanos.addAndGet(System.nanoTime() - queryStarted)
                        logPipelineMetricsIfDue()
                    }
                }
                // Split an oversized result from this one Native query. Child
                // pages are clipped from the same flat payload; Native is not rerun.
                val admitted = admitNativeCaptureRange(
                    active, missingKey, missingKey, flat, requestedRevision, capturedRanges
                )
                if (admitted == null) {
                    work.state = RangeWorkState.CANCELLED
                    break
                }
                if (!admitted) {
                    // The minimum scalar-aligned child still cannot fit. Block this
                    // exact work item rather than repeatedly querying it forever.
                    work.state = RangeWorkState.BLOCKED
                    Log.w(
                        TAG,
                        "Viewport capture is not cache-admissible after bounded subdivision: " +
                            "${missingKey.startUtf16}..${missingKey.endUtf16}"
                    )
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
                        invalidateViewportStyles(
                            active, listOf(key), active.documentRevision.get()
                        )
                    }
                }
            }
        } finally {
            if (work.state != RangeWorkState.FAILED && work.state != RangeWorkState.BLOCKED) {
                work.retryWakeJob?.cancel()
                active.rangeWork.remove(key, work)
            }
        }
        return capturedRanges
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
        reusableNativeRanges: NativeReusableRangeIndex
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
            !reusableNativeRanges.covers(startByte, endByte)) return null

        val delta = startByte - originalStart
        val rebasedIndex = index.shiftBytes(delta) ?: return null
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
        reusableNativeRanges: NativeReusableRangeIndex
    ): List<Pair<Long, Long>> {
        val invalidatedRanges = ArrayList<Pair<Long, Long>>()
        fun rebaseCache(cache: ByteBudgetLruCache<CaptureRangeKey, CaptureIntervalIndex>) {
            cache.transformEntries(
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
        }
        rebaseCache(captureCache)
        rebaseCache(displayRetentionCache)
        return invalidatedRanges
    }

    /**
     * Re-record only lines whose native dirty intervals or previously painted
     * viewport dependencies were invalidated by this edit transaction.
     */
    private fun invalidateEditedStyles(
        active: LargeSession,
        expectedRevision: Long,
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
            if (largeSession !== active || generation.get() != active.ticket ||
                active.documentRevision.get() != expectedRevision ||
                active.pendingEditEpoch.get() != active.appliedEditEpoch.get()) return@launch
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
                val firstLine = buffer.getPositionAt(safeStart).lineNumber
                val lastLine = buffer.getPositionAt(safeEnd).lineNumber
                if (firstLine > 0 && lastLine >= firstLine) {
                    lineRanges += firstLine to lastLine
                }
            }

            if (lineRanges.isEmpty()) {
                // An empty document still has one drawable line.
                currentHost.invalidateStyles(1, 1)
            } else {
                val sorted = lineRanges.sortedBy { it.first }
                val mergedRanges = ArrayList<Pair<Int, Int>>()
                var first = sorted.first().first
                var last = sorted.first().second
                for (index in 1 until sorted.size) {
                    val next = sorted[index]
                    if (next.first <= last + 1) {
                        last = maxOf(last, next.second)
                    } else {
                        mergedRanges += first to last
                        first = next.first
                        last = next.second
                    }
                }
                mergedRanges += first to last
                currentHost.invalidateStyles(mergedRanges)
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
                        active.documentRuntime.applyEdit(
                            edit.startByte, edit.oldEndByte, edit.insertedUtf16Le
                        )
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
                    // exact retained-key set to rebase the Kotlin capture cache;
                    // do not clear every token when one local region changes.
                    val reusableNativeRanges = NativeReusableRangeIndex(
                        engine.reusableNativeHighlightRanges(active.nativeHandle)
                            .map { it.startByte to it.endByte }
                    )
                    // Publish cache rebasing and the matching edit epoch atomically
                    // with styleLine's cache reads. Remove only this committed batch:
                    // newer edits may already be queued against the resulting revision.
                    val captureCacheEntriesBefore = captureCache.size + displayRetentionCache.size
                    val (invalidatedCaptureRanges, readyDisplayRanges) =
                        synchronized(nativeEditLock) {
                            active.documentRevision.incrementAndGet()
                            val invalidated = rebaseCaptureCaches(
                                active.cacheSessionId, edits, reusableNativeRanges
                            )
                            repeat(edits.size) {
                                if (pendingDisplayEdits.isNotEmpty()) pendingDisplayEdits.removeAt(0)
                            }
                            val ready = if (pendingDisplayEdits.isEmpty()) {
                                pendingDisplayRanges.toList().also { pendingDisplayRanges.clear() }
                            } else emptyList()
                            active.appliedEditEpoch.set(editEpoch)
                            invalidated to ready
                        }
                    // Failed-range retry jobs retain their session closure until
                    // their delay expires. An edit invalidates their old coordinates,
                    // so cancel them before dropping the failed-work records.
                    active.rangeWork.values.forEach { it.retryWakeJob?.cancel() }
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
                            "captureCacheEntries=$captureCacheEntriesBefore->${captureCache.size + displayRetentionCache.size} " +
                            "captureCacheBytes=${captureCache.bytesUsed + displayRetentionCache.bytesUsed} " +
                            "captureCacheEvictions=${captureCache.evictionCount + displayRetentionCache.evictionCount} " +
                            "captureCacheRejected=${captureCache.rejectedAdmissionCount + displayRetentionCache.rejectedAdmissionCount} " +
                            "invalidatedCaptureRanges=${invalidatedCaptureRanges.size} " +
                            "deferredDisplayRanges=${readyDisplayRanges.size} " +
                            "pendingDisplayEdits=${synchronized(nativeEditLock) { pendingDisplayEdits.size }}"
                    )
                    // The current edit epoch is now committed; any later queued edit
                    // remains pending and retains its own display transform.
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
                        // readyDisplayRanges were painted from old-revision captures
                        // only to bridge the edit transaction. The native dependency
                        // rebase above is already the authoritative validity decision:
                        // retained ranges remain drawable, while rejected ranges are
                        // present in invalidatedCaptureRanges. Invalidating every
                        // display bridge again here caused unchanged rows to lose their
                        // colors and immediately recapture after each local edit.
                        // Incomplete retained coverage is handled by styleLine's normal
                        // gap scheduler without blanking the captures that survived.
                        invalidateEditedStyles(
                            active,
                            active.documentRevision.get(),
                            dirtyByteRanges,
                            invalidatedCaptureRanges
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
            .filter {
                it.value.state == RangeWorkState.FAILED ||
                    it.value.state == RangeWorkState.BLOCKED
            }
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

    private fun disposeSession(active: LargeSession) {
        // Retry wake-ups retain their LargeSession until their delay expires. A
        // detached/replaced document must release those jobs immediately rather
        // than keeping up to 256 stale session closures alive for the backoff window.
        active.rangeWork.values.forEach { it.retryWakeJob?.cancel() }
        active.rangeWork.clear()
        scope.launch(Dispatchers.IO) {
            runCatching {
                active.nativeMutex.withLock {
                    active.documentRuntime.close()
                }
            }.onFailure { Log.w(TAG, "Failed to close native TSH document", it) }
            captureCache.invalidate { key, _ -> key.sessionId == active.cacheSessionId }
            displayRetentionCache.invalidate { key, _ -> key.sessionId == active.cacheSessionId }
        }
    }
}
