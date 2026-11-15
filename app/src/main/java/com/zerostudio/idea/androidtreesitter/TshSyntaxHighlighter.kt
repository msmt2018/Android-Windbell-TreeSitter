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
        // Capture storage is byte-budgeted; execution ranges come from the live renderer.
        private const val CAPTURE_CACHE_BYTES = 32L * 1024L * 1024L
        private val nextSessionId = AtomicLong(1L)
        private val captureCache = ByteBudgetLruCache<CaptureRangeKey, CaptureIntervalIndex>(
            CAPTURE_CACHE_BYTES,
            { index -> 96L + index.size.toLong() * 96L }
        )
    }

    private data class CaptureRangeKey(val sessionId: Long, val startUtf16: Int, val endUtf16: Int)

    private data class LargeSession(
        /** Native-owned PieceTreeSource + incremental Tree-sitter tree. */
        val nativeHandle: Long,
        val lengthChars: AtomicLong,
        val cacheSessionId: Long,
        val ticket: Long,
        val documentRevision: AtomicLong = AtomicLong(),
        // Distinguish edits received by the UI from edits committed to the native tree.
        // While these epochs differ, cached spans belong to an older document revision.
        val pendingEditEpoch: AtomicLong = AtomicLong(),
        val appliedEditEpoch: AtomicLong = AtomicLong(),
        val nativeMutex: Mutex = Mutex(),
        val rangeWork: ConcurrentHashMap<CaptureRangeKey, RangeWork> = ConcurrentHashMap()
    )

    private enum class RangeWorkState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private class RangeWork(val attempt: Int = 0) {
        @Volatile var state: RangeWorkState = PartitionWorkState.QUEUED
        @Volatile var retryAfterNanos: Long = 0L
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
        parseJob?.cancel(CancellationException("TSH highlighter detached"))
        parseJob = null
        largeSession?.let(::disposeSession)
        largeSession = null
        parsing = false
        synchronized(nativeEditLock) { pendingNativeEdits.clear() }
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
        val queryEnd = lineStartOffset.toLong() + visibleEnd.toLong()
        if (queryStart > Int.MAX_VALUE || queryEnd > Int.MAX_VALUE) {
            Log.e(TAG, "Editor offset exceeds the 32-bit capture index: start=$queryStart end=$queryEnd")
            return null
        }
        val active = largeSession ?: return null
        if (active.pendingEditEpoch.get() != active.appliedEditEpoch.get()) return null

        val boundedStart = queryStart.coerceAtMost(active.lengthChars.get())
        val boundedEnd = queryEnd.coerceAtMost(active.lengthChars.get())
        val spans = if (boundedStart >= boundedEnd) emptyList()
        else queryLargeCaptures(
            active, boundedStart, boundedEnd, boundedStart.toInt(), boundedEnd.toInt()
        )

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
        if (active.pendingEditEpoch.get() != active.appliedEditEpoch.get()) return false
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
        val key = CaptureRangeKey(active.cacheSessionId, boundedStart.toInt(), boundedEnd.toInt())
        if (captureCache.get(key) != null) return true
        requestLargeRange(active, key)
        return false
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
            if (active.pendingEditEpoch.get() != active.appliedEditEpoch.get()) {
                // Never answer scope queries from a pre-edit token snapshot.
                return true
            }
            if (offset < 0 || offset.toLong() >= active.lengthChars.get() || offset == Int.MAX_VALUE) {
                return false
            }
            val key = CaptureRangeKey(active.cacheSessionId, offset, offset + 1)
            val cached = captureCache.get(key)
            if (cached == null) {
                requestLargeRange(active, key)
                return true
            }
            cached.query(offset, offset + 1)
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

    private fun queryLargeCaptures(
        active: LargeSession,
        start: Long,
        end: Long,
        queryStartUtf16: Int,
        queryEndUtf16: Int
    ): List<WindbellTshEngine.HighlightSpan> {
        if (generation.get() != active.ticket || largeSession !== active) return emptyList()
        val key = CaptureRangeKey(active.cacheSessionId, start.toInt(), end.toInt())
        val index = captureCache.get(key) ?: run {
            requestLargeRange(active, key)
            return emptyList()
        }
        return ArrayList<WindbellTshEngine.HighlightSpan>().also {
            index.appendQuery(queryStartUtf16, queryEndUtf16, it)
        }
    }

    private fun requestLargeRange(active: LargeSession, key: CaptureRangeKey) {
        if (generation.get() != active.ticket || largeSession !== active) return
        if (key.startUtf16 >= key.endUtf16 || captureCache.get(key) != null) return
        val existing = active.rangeWork[key]
        val work = if (existing == null) {
            val initial = RangeWork()
            if (active.rangeWork.putIfAbsent(key, initial) != null) return
            initial
        } else {
            if (existing.state != PartitionWorkState.FAILED ||
                System.nanoTime() < existing.retryAfterNanos) return
            val retry = RangeWork(existing.attempt + 1)
            if (!active.rangeWork.replace(key, existing, retry)) return
            retry
        }
        val requestedRevision = active.documentRevision.get()
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
                        startByte = key.startUtf16.toLong() * 2L,
                        endByte = key.endUtf16.toLong() * 2L
                    ) ?: throw IllegalStateException("Native viewport TSH returned null for visible range")
                }
                val spans = decodeNativeSpans(flat)
                if (generation.get() == active.ticket && largeSession === active &&
                    active.documentRevision.get() == requestedRevision) {
                    captureCache.put(key, CaptureIntervalIndex(spans))
                    work.state = PartitionWorkState.SUCCEEDED
                    host?.invalidateStyles()
                    host?.invalidateStructure()
                } else {
                    work.state = PartitionWorkState.CANCELLED
                }
            } catch (_: CancellationException) {
                work.state = PartitionWorkState.CANCELLED
            } catch (error: Throwable) {
                work.state = PartitionWorkState.FAILED
                val backoffShift = (work.attempt - 1).coerceIn(0, 5)
                work.retryAfterNanos = System.nanoTime() + (1L shl backoffShift) * 1_000_000_000L
                if (generation.get() == active.ticket && largeSession === active) {
                    Log.e(TAG, "Native viewport TSH failed for visible range", error)
                }
            } finally {
                if (work.state != PartitionWorkState.FAILED) active.rangeWork.remove(key, work)
            }
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
                    val firstInvalid = earliestOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    // Captures may begin before an edit and extend through it; invalidate
                    // every cached viewport that intersects the changed suffix.
                    captureCache.invalidate { key, _ ->
                        key.sessionId == active.cacheSessionId && key.endUtf16 > firstInvalid
                    }
                    active.rangeWork.keys.filter { it.endUtf16 > firstInvalid }
                        .forEach(active.rangeWork::remove)
                    // Publish the new revision only after its stale cache entries are gone.
                    active.appliedEditEpoch.set(editEpoch)
                    if (generation.get() == active.ticket && largeSession === active) {
                        host?.invalidateStyles()
                        host?.invalidateStructure()
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
