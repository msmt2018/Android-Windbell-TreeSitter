package x.github.module.editor.tsh

import android.os.Handler
import android.os.Looper
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import com.zerostudio.idea.treesitter.tsh.api.TshTextChange
import com.zerostudio.idea.treesitter.tsh.api.TshTextEdit
import com.zerostudio.idea.treesitter.tsh.api.TshTextSnapshotLease
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.PieceTreeBuffer
import x.github.module.editor.view.EditorView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the bridge between one EditorView edit transaction and the TSH PieceTree provider.
 *
 * The EditorView invokes callbacks on its edit thread (normally the main thread). A before
 * snapshot is pinned before the first mutation; one after snapshot is pinned after the entire
 * batch. The consumer receives both leases and must close them after incremental parsing finishes.
 *
 * This class does not parse on the UI thread and does not flatten large TextSlices into Strings.
 */
class TshPieceTreeEditorBinding private constructor(
    private val view: EditorView,
    val document: TshPieceTreeDocument,
    private val provider: TshPieceTreeTextAccessProvider,
    private val onTransaction: (TshTextChange, TshTextSnapshotLease, TshTextSnapshotLease) -> Unit,
    private val onInvalidated: (TshResult.Failure) -> Unit,
    private val onBackingBufferReplaced: () -> Unit
) : AutoCloseable {
    private var beforeLease: TshTextSnapshotLease? = null
    private var beforeCoordinatesSupported: Boolean = false
    private var lastVersion: Long = -1L
    private val closed = AtomicBoolean(false)

    private val callback = object : EditorDocument.Callback {
        override fun beforeTextChanged(
            edits: List<com.zerostudio.idea.piecetable.SliceEdit>
        ) {
            releasePendingBefore()
            if (closed.get()) return
            beforeCoordinatesSupported = runCatching {
                val buffer = view.getTextBuffer()
                buffer.isEOLNormalized() &&
                    (buffer.getEOL() == "\n" || buffer.getEOL() == "\r\n")
            }.getOrDefault(false)
            when (val acquired = provider.acquireSnapshot(document)) {
                is TshResult.Success -> {
                    beforeLease = acquired.value
                    lastVersion = acquired.value.snapshot.version
                }
                is TshResult.Failure -> onInvalidated(acquired)
            }
        }

        override fun onBufferReplaced(previous: EditorBuffer, current: EditorBuffer) {
            if (closed.get()) return
            val notify = {
                if (!closed.get()) {
                    if (current is PieceTreeBuffer) {
                        lastVersion = current.pieceTree.getVersionId().toLong()
                    }
                    // The previous tree belongs to a different backing storage generation.
                    // Rebuild from the new authoritative snapshot; never synthesize an edit.
                    onBackingBufferReplaced()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                notify()
            } else {
                Handler(Looper.getMainLooper()).post(notify)
            }
        }

        override fun onTextChanged(change: EditorDocument.AppliedChange) = Unit

        override fun afterTextChanged(
            changes: List<EditorDocument.AppliedChange>,
            caretLineNumber: Int,
            caretColumn: Int
        ) {
            if (closed.get()) {
                releasePendingBefore()
                return
            }
            val before = beforeLease
            beforeLease = null
            if (before == null || before.isClosed) {
                invalidate(
                    "TSH-PIECETREE-BINDING-001",
                    "The before-version PieceTree snapshot was unavailable for this edit transaction"
                )
                return
            }

            val after = when (val acquired = provider.acquireSnapshot(document)) {
                is TshResult.Success -> acquired.value
                is TshResult.Failure -> {
                    before.close()
                    onInvalidated(acquired)
                    return
                }
            }
            val beforeVersion = before.snapshot.version
            val afterVersion = after.snapshot.version
            lastVersion = afterVersion
            if (afterVersion == beforeVersion) {
                // No text changed (for example, a history-only/no-op transaction).
                before.close()
                after.close()
                return
            }
            if (afterVersion < beforeVersion) {
                before.close()
                after.close()
                invalidate(
                    "TSH-PIECETREE-BINDING-002",
                    "PieceTree version moved backwards; a full parse/rebind is required"
                )
                return
            }

            val changeResult = editorChangesToTshChange(
                documentId = document.documentId,
                beforeVersion = beforeVersion,
                afterVersion = afterVersion,
                changes = changes,
                useEditorLineCoordinates = beforeCoordinatesSupported
            )
            if (changeResult is TshResult.Failure) {
                before.close()
                after.close()
                onInvalidated(changeResult)
                return
            }
            val change = (changeResult as TshResult.Success<TshTextChange>).value

            try {
                document.publishChange(change)
                // The callback may hand both leases to a worker. It owns them after this call
                // and must close them in a finally block when parsing/retry work is finished.
                onTransaction(change, before, after)
                lastVersion = afterVersion
            } catch (error: Exception) {
                before.close()
                after.close()
                invalidate(
                    "TSH-PIECETREE-BINDING-005",
                    "Publishing the PieceTree change failed: ${error.message}",
                    error
                )
            }
        }
    }

    init {
        view.addEditCallback(callback)
        // Version metadata is cheap to read; do not create a throwaway O(piece-count) Snapshot
        // merely to initialize the final-version value used by dispose().
        lastVersion = view.getTextBuffer().getVersionId().toLong()
    }

    /** The host calls this on the EditorView edit/main thread when the document is permanently closed. */
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TshPieceTreeEditorBinding.close must run on the EditorView main thread"
        }
        if (!closed.compareAndSet(false, true)) return
        runCatching { view.removeEditCallback(callback) }
        releasePendingBefore()
        runCatching { document.dispose(lastVersion.coerceAtLeast(0L)) }
    }

    private fun releasePendingBefore() {
        val lease = beforeLease
        beforeLease = null
        runCatching { lease?.close() }
    }

    private fun invalidate(code: String, message: String, cause: Throwable? = null) {
        onInvalidated(TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.BINDING,
            message = message,
            cause = cause
        ))))
    }

    companion object {
        /**
         * Attaches to the existing EditorView. Snapshot creation happens inside its edit callbacks,
         * while the editor is on the thread that applies edits; later snapshot reads are immutable.
         */
        @JvmStatic
        fun attach(
            view: EditorView,
            documentId: String,
            languageId: String?,
            provider: TshPieceTreeTextAccessProvider = TshPieceTreeTextAccessProvider(),
            onTransaction: (TshTextChange, TshTextSnapshotLease, TshTextSnapshotLease) -> Unit = { _, before, after ->
                before.close()
                after.close()
            },
            onInvalidated: (TshResult.Failure) -> Unit = {},
            onBufferReplaced: () -> Unit = {}
        ): TshPieceTreeEditorBinding {
            require(documentId.isNotBlank()) { "documentId must not be blank" }
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "TshPieceTreeEditorBinding.attach must run on the EditorView main thread"
            }
            require(view.getBuffer() is PieceTreeBuffer) {
                "TshPieceTreeEditorBinding requires an EditorView backed by PieceTreeBuffer"
            }
            val document = TshPieceTreeDocument(
                documentId = documentId,
                languageId = languageId,
                snapshotFactory = { view.getTextBuffer().createSnapshot() },
                snapshotThread = Thread.currentThread()
            )
            return TshPieceTreeEditorBinding(
                view, document, provider, onTransaction, onInvalidated, onBufferReplaced
            )
        }
    }
}


/**
 * Converts EditorDocument's bottom-up applied changes into the ascending TSH before-version
 * contract. Replacement contents remain in PieceTree storage; only lengths and exact endpoint
 * coordinates cross the boundary.
 */
internal fun editorChangesToTshChange(
    documentId: String,
    beforeVersion: Long,
    afterVersion: Long,
    changes: List<EditorDocument.AppliedChange>,
    useEditorLineCoordinates: Boolean = true
): TshResult<TshTextChange> = try {
    val edits = changes.asReversed().map { applied ->
        var lineFeeds = 0
        var trailingLength = 0L
        var scannedLength = 0L
        applied.content?.forEachChunk { chunk ->
            for (character in chunk) {
                scannedLength = Math.addExact(scannedLength, 1L)
                if (character == '\n') {
                    lineFeeds = Math.addExact(lineFeeds, 1)
                    trailingLength = 0L
                } else {
                    trailingLength = Math.addExact(trailingLength, 1L)
                }
            }
        }
        require(scannedLength == applied.insertedTextLength.toLong()) {
            "TextSlice length does not match AppliedChange.insertedTextLength"
        }
        TshTextEdit(
            startUtf16 = applied.rangeOffset.toLong(),
            endUtf16 = Math.addExact(
                applied.rangeOffset.toLong(),
                applied.deletedTextLength.toLong()
            ),
            replacement = "",
            replacementLengthUtf16 = applied.insertedTextLength.toLong(),
            startLine = if (useEditorLineCoordinates) applied.range.startLine - 1 else null,
            startColumnUtf16 = if (useEditorLineCoordinates) applied.range.startColumn - 1L else null,
            oldEndLine = if (useEditorLineCoordinates) applied.range.endLine - 1 else null,
            oldEndColumnUtf16 = if (useEditorLineCoordinates) applied.range.endColumn - 1L else null,
            replacementLineFeedCount = lineFeeds,
            replacementTrailingUtf16Length = trailingLength
        )
    }
    TshResult.Success(TshTextChange(documentId, beforeVersion, afterVersion, edits))
} catch (error: Exception) {
    TshResult.Failure(listOf(TshDiagnostic(
        code = "TSH-PIECETREE-BINDING-003",
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.BINDING,
        message = "EditorDocument change batch cannot be represented by TSH: ${error.message}",
        cause = error
    )))
}
