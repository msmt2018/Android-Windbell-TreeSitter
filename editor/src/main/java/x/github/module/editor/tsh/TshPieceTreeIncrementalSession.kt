package x.github.module.editor.tsh

import android.os.Handler
import android.os.Looper
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage
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
 * Pending work is coalesced to at most one transaction. If the worker falls behind, intermediate
 * pending snapshots are released and the latest snapshot is reparsed in full instead of retaining
 * an unbounded queue of large PieceTree snapshots.
 *
 * The session owns the worker and the currently published syntax tree, but not ParserOwner.
 * Native parse is synchronous and is not force-cancelled by Thread.interrupt. close() prevents
 * queued work from starting and retires the tree after any currently running Native call returns.
 * Tree/failure callbacks are delivered on the main thread. A queued tree callback is dropped if
 * a newer tree has already replaced it or the session has closed.
 * attach(), resync() and close() must run on the EditorView main thread.
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workLock = Any()
    private var pendingWork: PendingWork? = null
    private var drainScheduled = false

    private sealed class PendingWork : AutoCloseable {
        class Change(
            val change: TshTextChange,
            val before: TshTextSnapshotLease,
            val after: TshTextSnapshotLease,
            val forceFullParse: Boolean
        ) : PendingWork() {
            override fun close() {
                runCatching { before.close() }
                runCatching { after.close() }
            }
        }

        class Snapshot(val lease: TshTextSnapshotLease) : PendingWork() {
            override fun close() {
                runCatching { lease.close() }
            }
        }
    }

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
            onInvalidated = { failure ->
                reportFailure(failure)
                // Once attached, an unrepresentable/invalid change batch must recover from the
                // authoritative current snapshot rather than leave the previous tree stale.
                if (this::binding.isInitialized && !closed.get() &&
                    Looper.myLooper() == Looper.getMainLooper()
                ) {
                    resync()
                }
            },
            onBufferReplaced = {
                // Buffer replacement invalidates every offset and native tree revision. The
                // binding has already switched its snapshotFactory to the new live buffer.
                if (this::binding.isInitialized && !closed.get()) {
                    resync()
                }
            }
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
        enqueueSnapshot(lease)
    }

    private fun enqueueChange(
        change: TshTextChange,
        before: TshTextSnapshotLease,
        after: TshTextSnapshotLease
    ) {
        var discarded: PendingWork? = null
        var schedule = false
        synchronized(workLock) {
            if (closed.get()) {
                discarded = PendingWork.Change(change, before, after, forceFullParse = true)
            } else {
                discarded = pendingWork
                // Any replaced pending item represents an intermediate version we will skip.
                // The latest after Snapshot is authoritative, so force a full parse for it.
                val forceFull = discarded != null
                pendingWork = PendingWork.Change(change, before, after, forceFull)
                if (!drainScheduled) {
                    drainScheduled = true
                    schedule = true
                }
            }
        }
        discarded?.let { runCatching { it.close() } }
        if (schedule) scheduleDrain()
    }

    private fun enqueueSnapshot(lease: TshTextSnapshotLease): Boolean {
        var discarded: PendingWork? = null
        var schedule = false
        synchronized(workLock) {
            if (closed.get()) {
                discarded = PendingWork.Snapshot(lease)
            } else {
                discarded = pendingWork
                pendingWork = PendingWork.Snapshot(lease)
                if (!drainScheduled) {
                    drainScheduled = true
                    schedule = true
                }
            }
        }
        discarded?.let { runCatching { it.close() } }
        if (closed.get()) return false
        if (schedule) scheduleDrain()
        return true
    }

    private fun scheduleDrain() {
        try {
            worker.execute(::drainPendingWork)
        } catch (error: RejectedExecutionException) {
            val abandoned = synchronized(workLock) {
                drainScheduled = false
                pendingWork.also { pendingWork = null }
            }
            abandoned?.let { runCatching { it.close() } }
            reportFailure(failure(
                "TSH-PIECETREE-SESSION-002",
                "Incremental session worker rejected pending work",
                error
            ))
        }
    }

    private fun drainPendingWork() {
        while (true) {
            val work = synchronized(workLock) {
                if (closed.get()) {
                    drainScheduled = false
                    null
                } else {
                    val next = pendingWork
                    pendingWork = null
                    if (next == null) drainScheduled = false
                    next
                }
            } ?: return

            try {
                process(work)
            } catch (error: Exception) {
                reportFailure(failure(
                    "TSH-PIECETREE-SESSION-001",
                    "Incremental session worker failed: ${error.message}",
                    error
                ))
            } finally {
                runCatching { work.close() }
            }
        }
    }

    private fun process(work: PendingWork) {
        if (closed.get()) return
        val nextRevision = Math.addExact(treeRevision, 1L)
        when (work) {
            is PendingWork.Snapshot -> parseFull(work.lease, currentTree, nextRevision)
            is PendingWork.Change -> {
                val oldTree = currentTree
                if (work.forceFullParse || oldTree == null ||
                    oldTree.snapshot.documentVersion != work.change.beforeVersion ||
                    oldTree.snapshot.documentId != work.change.documentId
                ) {
                    parseFull(work.after, oldTree, nextRevision)
                    return
                }

                when (val updated = TshTreeSitterIncrementalUpdate.apply(
                    parserOwner = parserOwner,
                    textProvider = provider,
                    oldTree = oldTree,
                    beforeSnapshotLease = work.before,
                    change = work.change,
                    afterSnapshotLease = work.after,
                    targetTreeRevision = nextRevision
                )) {
                    is TshResult.Success -> {
                        treeRevision = nextRevision
                        publish(updated.value)
                    }
                    is TshResult.Failure -> {
                        reportFailure(updated)
                        val treeNow = oldTree.snapshot
                        if (treeNow.documentVersion == work.change.afterVersion &&
                            oldTree.registration.descriptor.metadata["treeState"] == "EDITED_PENDING_PARSE"
                        ) {
                            // Native edits completed; retry only the parse stage. Do not apply the
                            // same edits twice. If more changes are pending, the next pass may
                            // coalesce them into a full parse of the latest snapshot.
                            parseEditedPending(work.after, oldTree, nextRevision)
                        } else {
                            parseFull(work.after, oldTree, nextRevision)
                        }
                    }
                }
            }
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
                // Keep EDITED_PENDING_PARSE registered. A later change can continue editing it,
                // or a later resync can replace it with a full parse.
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
     * Rebuilds from the current document snapshot after an external buffer replacement.
     * Call on the EditorView main thread so PieceTree snapshot creation cannot race an edit.
     */
    fun resync(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TshPieceTreeIncrementalSession.resync must run on the EditorView main thread"
        }
        if (closed.get()) return false
        val lease = when (val acquired = provider.acquireSnapshot(binding.document)) {
            is TshResult.Success -> acquired.value
            is TshResult.Failure -> {
                reportFailure(acquired)
                return false
            }
        }
        return enqueueSnapshot(lease)
    }

    private fun publish(tree: TshOwnedSyntaxTree) {
        if (closed.get()) {
            parserOwner.retireTree(tree)
            return
        }
        currentTree = tree
        mainHandler.post {
            if (!closed.get() && currentTree === tree) {
                runCatching { onTreeUpdated(tree) }
            }
        }
    }

    private fun reportFailure(result: TshResult.Failure) {
        if (!closed.get()) {
            mainHandler.post {
                if (!closed.get()) runCatching { onFailure(result) }
            }
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TshPieceTreeIncrementalSession.close must run on the EditorView main thread"
        }
        if (!closed.compareAndSet(false, true)) return
        runCatching { binding.close() }
        val abandoned = synchronized(workLock) {
            pendingWork.also {
                pendingWork = null
            }
        }
        abandoned?.let { runCatching { it.close() } }
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
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "TshPieceTreeIncrementalSession.attach must run on the EditorView main thread"
            }
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
            try {
                session.attach(view, documentId, languageId)
                session.startInitialParse()
                return session
            } catch (error: Throwable) {
                worker.shutdownNow()
                throw error
            }
        }

        private fun failure(
            code: String,
            message: String,
            cause: Throwable? = null
        ) = TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.PARSE,
            message = message,
            cause = cause
        )))
    }
}
