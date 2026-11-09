package x.github.module.editor.tsh

import com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import com.zerostudio.idea.treesitter.tsh.api.TshSharedTextSnapshotLease
import com.zerostudio.idea.treesitter.tsh.api.TshTextChange
import com.zerostudio.idea.treesitter.tsh.api.TshTextEdit
import com.zerostudio.idea.treesitter.tsh.api.TshTextSnapshotLease
import com.zerostudio.idea.treesitter.tsh.api.TshTextSubscription
import x.github.module.editor.model.EditorDocument
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
    private val onInvalidated: (TshResult.Failure) -> Unit
) : AutoCloseable {
    private var beforeLease: TshTextSnapshotLease? = null
    private var lastVersion: Long = -1L
    private val closed = AtomicBoolean(false)

    private val callback = object : EditorDocument.Callback {
        override fun beforeTextChanged(
            edits: List<com.zerostudio.idea.piecetable.SliceEdit>
        ) {
            releasePendingBefore()
            if (closed.get()) return
            when (val acquired = provider.acquireSnapshot(document)) {
                is TshResult.Success -> {
                    beforeLease = acquired.value
                    lastVersion = acquired.value.snapshot.version
                }
                is TshResult.Failure -> onInvalidated(acquired)
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

            val edits = try {
                // EditorDocument reports applied edits bottom-up. Reverse to the ascending
                // before-version order required by TshTextChange. The native compiler reverses
                // them again for safe Tree-sitter application.
                changes.asReversed().map { applied ->
                    TshTextEdit(
                        startUtf16 = applied.rangeOffset.toLong(),
                        endUtf16 = Math.addExact(
                            applied.rangeOffset.toLong(),
                            applied.deletedTextLength.toLong()
                        ),
                        replacement = "",
                        replacementLengthUtf16 = applied.insertedTextLength.toLong(),
                        newEndPointRow = applied.finalLineNumber - 1,
                        newEndPointColumnBytes = Math.multiplyExact(
                            applied.finalColumn - 1,
                            2
                        )
                    )
                }
            } catch (error: Exception) {
                before.close()
                after.close()
                invalidate(
                    "TSH-PIECETREE-BINDING-003",
                    "Cannot convert EditorDocument changes to TSH edits: ${error.message}",
                    error
                )
                return
            }

            val change = try {
                TshTextChange(
                    documentId = document.documentId,
                    beforeVersion = beforeVersion,
                    afterVersion = afterVersion,
                    edits = edits
                )
            } catch (error: Exception) {
                before.close()
                after.close()
                invalidate(
                    "TSH-PIECETREE-BINDING-004",
                    "EditorDocument emitted an edit batch that violates TSH change invariants: ${error.message}",
                    error
                )
                return
            }

            try {
                document.publishChange(change)
                // Ownership of both leases transfers to the first transaction listener. When
                // there is no listener, the binding releases them immediately.
                val listeners = changeListenerCount()
                if (listeners == 0) {
                    before.close()
                    after.close()
                }
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
    }

    /** The host calls this when the editor/document is permanently closed. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        view.removeEditCallback(callback)
        releasePendingBefore()
        document.dispose(lastVersion.coerceAtLeast(0L))
    }

    private fun releasePendingBefore() {
        beforeLease?.close()
        beforeLease = null
    }

    private fun changeListenerCount(): Int = document.changeListenerCount()

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
            onInvalidated: (TshResult.Failure) -> Unit = {}
        ): TshPieceTreeEditorBinding {
            require(documentId.isNotBlank()) { "documentId must not be blank" }
            val document = TshPieceTreeDocument(
                documentId = documentId,
                languageId = languageId,
                snapshotFactory = { view.getTextBuffer().createSnapshot() }
            )
            return TshPieceTreeEditorBinding(view, document, provider, onInvalidated)
        }
    }
}
