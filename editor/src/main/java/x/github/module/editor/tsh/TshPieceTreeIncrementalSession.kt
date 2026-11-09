package x.github.module.editor.tsh

import com.zerostudio.idea.treesitter.tsh.api.TshOwnedSyntaxTree
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import com.zerostudio.idea.treesitter.tsh.api.TshTextChange
import com.zerostudio.idea.treesitter.tsh.api.TshTextSnapshotLease
import com.zerostudio.idea.treesitter.tsh.api.TshTreeSitterIncrementalUpdate
import com.zerostudio.idea.treesitter.tsh.api.TshTreeSitterParserOwner
import x.github.module.editor.view.EditorView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes PieceTree edit transactions and Tree-sitter updates for one EditorView document.
 *
 * All Native parser/tree state is touched on one worker. The EditorView callback only pins
 * immutable before/after snapshots and enqueues their leases; it never performs Native parsing.
 * The session owns the worker and the currently published syntax tree, but not ParserOwner.
 *
 * A failed incremental edit is handled according to the tree state:
 * - if the tree was not advanced, the session falls back to a full parse of the after snapshot;
 * - if the tree reached EDITED_PENDING_PARSE, the session retries parsing that edited tree;
 * - a failed parse never publishes a pending/stale tree as READY.
 *
 * Native parse is synchronous and is not force-cancelled by Thread.interrupt. close() prevents
 * queued work from starting and retires the tree after any currently running Native call returns.
 */
class TshPieceTreeIncrementalSession private constructor(
    private val parserOwner: TshTreeSitterParserOwner,
    private val provider: TshPieceTreeTextAccessProvider,
    private val onTreeUpdated: (TshOwnedSyntaxTree) -> Unit,
    private val onFailure: (TshResult.Failure) -> Unit,
    private val worker: ExecutorService
) : AutoCloseable {
    lateinit var binding: TshPieceTreeEditorBinding
        private set

    @Volatile
    private var currentTree: TshOwnedSyntaxTree? = null

    @Volatile
    private var treeRevision: Long = 0L

    private val closed = AtomicBoolean(false)

    private fun attach(
        view: EditorView,
        documentId: String,
        languageId: String?
    ) {
        binding = TshPieceTreeEditorBinding.attach(
            view = view,
            documentId = documentId,
            languageId = languageId,
            provider = provider,
            onTransaction = { change, before, after ->
                enqueueChange(change, before, after)
            },
            onInvalidated = { failure -> reportFailure(failure) }
        )
    }

    private fun startInitialParse() {
        val lease = when (val acquired = provider.acquireSnapshot(binding.document)) {
            is TshResult.Success -> acquired.value
            is TshResult.Failure -> {
                reportFailure(acquired)
                return
            }
        }
        if (!enqueue {
            try {
                if (closed.get()) return@enqueue
                when (val parsed = parserOwner.parse(
                    lease,
                    provider,
                    treeRevision,
                    oldTree = null
                )) {
                    is TshResult.Success -> publish(parsed.value)
                    is TshResult.Failure -> reportFailure(parsed)
                }
            } finally {
                lease.close()
            }
        }) {
            lease.close()
        }
    }

    private fun enqueueChange(
        change: TshTextChange,
        before: TshTextSnapshotLease,
        after: TshTextSnapshotLease
    ) {
        if (!enqueue {
            try {
                if (closed.get()) return@enqueue
                val nextRevision = Math.addExact(treeRevision, 1L)
                val oldTree = currentTree
                if (oldTree == null ||
                    oldTree.snapshot.documentVersion != change.beforeVersion ||
                    oldTree.snapshot.documentId != change.documentId
                ) {
                    parseFull(after, oldTree, nextRevision)
                    return@enqueue
                }

                when (val updated = TshTreeSitterIncrementalUpdate.apply(
                    parserOwner = parserOwner,
                    textProvider = provider,
                    oldTree = oldTree,
                    beforeSnapshotLease = before,
                    change = change,
                    afterSnapshotLease = after,
                    targetTreeRevision = nextRevision
                )) {
                    is TshResult.Success -> {
                        treeRevision = nextRevision
                        publish(updated.value)
                    }
                    is TshResult.Failure -> {
                        reportFailure(updated)
                        val treeNow = oldTree.snapshot
                        if (treeNow.documentVersion == change.afterVersion &&
                            oldTree.registration.descriptor.metadata["treeState"] == "EDITED_PENDING_PARSE"
                        ) {
                            // Native edits completed; retry only the parse stage against the
                            // already-edited tree. Do not apply the same edits a second time.
                            parseEditedPending(after, oldTree, nextRevision)
                        } else {
                            parseFull(after, oldTree, nextRevision)
                        }
                    }
                }
            } catch (error: Exception) {
                reportFailure(failure(
                    "TSH-PIECETREE-SESSION-001",
                    "Incremental session worker failed: ${error.message}",
                    error
                ))
            } finally {
                before.close()
                after.close()
            }
        }) {
            before.close()
            after.close()
        }
    }

    private fun parseEditedPending(
        after: TshTextSnapshotLease,
        editedTree: TshOwnedSyntaxTree,
        nextRevision: Long
    ) {
        when (val parsed = parserOwner.parse(
            after,
            provider,
            nextRevision,
            oldTree = editedTree
        )) {
            is TshResult.Success -> {
                parserOwner.retireTree(editedTree)
                treeRevision = nextRevision
                publish(parsed.value)
            }
            is TshResult.Failure -> {
                treeRevision = nextRevision
                reportFailure(parsed)
                // Keep EDITED_PENDING_PARSE registered. A later document change can continue
                // editing it and parse the latest version, or the host can call resync().
            }
        }
    }

    private fun parseFull(
        after: TshTextSnapshotLease,
        oldTree: TshOwnedSyntaxTree?,
        nextRevision: Long
    ) {
        when (val parsed = parserOwner.parse(
            after,
            provider,
            nextRevision,
            oldTree = null
        )) {
            is TshResult.Success -> {
                oldTree?.let(parserOwner::retireTree)
                treeRevision = nextRevision
                publish(parsed.value)
            }
            is TshResult.Failure -> {
                treeRevision = nextRevision
                reportFailure(parsed)
            }
        }
    }

    /**
     * Rebuilds from the current document snapshot after an external buffer replacement or a
     * binding invalidation. Call on the EditorView edit thread so PieceTree snapshot creation
     * cannot race an in-progress edit.
     */
    fun resync(): Boolean {
        if (closed.get()) return false
        val lease = when (val acquired = provider.acquireSnapshot(binding.document)) {
            is TshResult.Success -> acquired.value
            is TshResult.Failure -> {
                reportFailure(acquired)
                return false
            }
        }
        if (!enqueue {
            try {
                if (closed.get()) return@enqueue
                val nextRevision = Math.addExact(treeRevision, 1L)
                parseFull(lease, currentTree, nextRevision)
            } finally {
                lease.close()
            }
        }) {
            lease.close()
            return false
        }
        return true
    }

    private fun publish(tree: TshOwnedSyntaxTree) {
        currentTree = tree
        runCatching { onTreeUpdated(tree) }
    }

    private fun reportFailure(result: TshResult.Failure) {
        runCatching { onFailure(result) }
    }

    private fun enqueue(task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            worker.execute(task)
            true
        } catch (error: RejectedExecutionException) {
            reportFailure(failure(
                "TSH-PIECETREE-SESSION-002",
                "Incremental session worker rejected a task",
                error
            ))
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        binding.close()
        try {
            worker.execute {
                currentTree?.let(parserOwner::retireTree)
                currentTree = null
                worker.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            currentTree?.let(parserOwner::retireTree)
            currentTree = null
            worker.shutdown()
        }
    }

    companion object {
        @JvmStatic
        fun attach(
            view: EditorView,
            documentId: String,
            languageId: String?,
            parserOwner: TshTreeSitterParserOwner,
            provider: TshPieceTreeTextAccessProvider = TshPieceTreeTextAccessProvider(),
            onTreeUpdated: (TshOwnedSyntaxTree) -> Unit = {},
            onFailure: (TshResult.Failure) -> Unit = {}
        ): TshPieceTreeIncrementalSession {
            require(documentId.isNotBlank()) { "documentId must not be blank" }
            val worker = Executors.newSingleThreadExecutor { task ->
                Thread(task, "tsh-piecetree-${documentId.take(48)}").apply { isDaemon = true }
            }
            val session = TshPieceTreeIncrementalSession(
                parserOwner,
                provider,
                onTreeUpdated,
                onFailure,
                worker
            )
            session.attach(view, documentId, languageId)
            session.startInitialParse()
            return session
        }

        private fun failure(
            code: String,
            message: String,
            cause: Throwable? = null
        ) = TshResult.Failure(listOf(com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic(
            code = code,
            severity = com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity.ERROR,
            stage = com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage.PARSE,
            message = message,
            cause = cause
        )))
    }
}
