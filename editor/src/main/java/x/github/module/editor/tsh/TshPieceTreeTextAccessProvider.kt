package x.github.module.editor.tsh

import com.zerostudio.idea.piecetable.PieceTreeSnapshot
import com.zerostudio.idea.treesitter.tsh.api.TshCapability
import com.zerostudio.idea.treesitter.tsh.api.TshCapabilitySet
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage
import com.zerostudio.idea.treesitter.tsh.api.TshObjectDescriptor
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import com.zerostudio.idea.treesitter.tsh.api.TshTextAccessProvider
import com.zerostudio.idea.treesitter.tsh.api.TshTextAccessThreading
import com.zerostudio.idea.treesitter.tsh.api.TshTextSnapshot
import com.zerostudio.idea.treesitter.tsh.api.TshTextChange
import com.zerostudio.idea.treesitter.tsh.api.TshTextChangeListener
import com.zerostudio.idea.treesitter.tsh.api.TshTextDocumentDisposedListener
import com.zerostudio.idea.treesitter.tsh.api.TshTextSubscription
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host document facade for the repository's PieceTreeTextBuffer.
 *
 * [snapshotFactory] MUST create the PieceTreeSnapshot while the host excludes concurrent edits
 * to the live PieceTreeTextBuffer. After it returns, PieceTreeSnapshot is immutable and can be
 * read concurrently while the live buffer changes.
 *
 * The factory is intentionally supplied by the host rather than locking on the buffer here:
 * synchronizing on an object does not coordinate with editor mutations that use another lock.
 */
class TshPieceTreeDocument(
    val documentId: String,
    val languageId: String?,
    private val snapshotFactory: () -> PieceTreeSnapshot
) {
    private val changeListeners = CopyOnWriteArrayList<TshTextChangeListener>()
    private val disposedListeners = CopyOnWriteArrayList<TshTextDocumentDisposedListener>()
    private val disposed = AtomicBoolean(false)

    fun createSnapshot(): PieceTreeSnapshot {
        check(!disposed.get()) { "PieceTree document $documentId is disposed" }
        return snapshotFactory()
    }

    fun publishChange(change: TshTextChange) {
        check(!disposed.get()) { "Cannot publish a change for a disposed document" }
        require(change.documentId == documentId) { "Change belongs to another document" }
        changeListeners.forEach { listener -> runCatching { listener.onChanged(change) } }
    }

    fun dispose(finalVersion: Long) {
        if (!disposed.compareAndSet(false, true)) return
        changeListeners.clear()
        disposedListeners.forEach { listener -> listener.onDisposed(documentId, finalVersion) }
        disposedListeners.clear()
    }

    fun isDisposed(): Boolean = disposed.get()

    internal fun addChangeListener(listener: TshTextChangeListener) {
        check(!disposed.get()) { "Cannot subscribe to a disposed document" }
        changeListeners.addIfAbsent(listener)
    }

    internal fun removeChangeListener(listener: TshTextChangeListener) {
        changeListeners.remove(listener)
    }

    internal fun addDisposedListener(listener: TshTextDocumentDisposedListener) {
        check(!disposed.get()) { "Cannot subscribe to a disposed document" }
        disposedListeners.addIfAbsent(listener)
    }

    internal fun removeDisposedListener(listener: TshTextDocumentDisposedListener) {
        disposedListeners.remove(listener)
    }
}

/**
 * TSH TextAccessProvider for the project's native PieceTreeSnapshot implementation.
 *
 * It never calls PieceTreeTextBuffer.toString(). Each readUtf16 call copies only the requested
 * range from the immutable snapshot; the original file chunks remain shared by PieceTreeSnapshot.
 */
class TshPieceTreeTextAccessProvider(
    override val id: String = "tsh.piecetree.snapshot"
) : TshTextAccessProvider {
    override val apiVersion: Int get() = 1
    override val threading: TshTextAccessThreading get() = TshTextAccessThreading.ANY_THREAD
    override val supportsConcurrentReads: Boolean get() = true
    override val supportsChangeSubscriptions: Boolean get() = true
    override val supportsDisposalNotifications: Boolean get() = true
    override val capabilities: TshCapabilitySet
        get() = TshCapabilitySet.of(
            TshCapability("text.snapshot", 1),
            TshCapability("text.chunk", 1)
        )

    override fun supports(instance: Any): Boolean = instance is TshPieceTreeDocument

    override fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean =
        supports(instance)

    override fun snapshot(document: Any): TshResult<TshTextSnapshot> {
        val host = document as? TshPieceTreeDocument ?: return failure(
            "TSH-PIECETREE-001",
            "Document is not a TshPieceTreeDocument"
        )
        return try {
            if (host.documentId.isBlank()) return failure(
                "TSH-PIECETREE-006",
                "PieceTree documentId must not be blank"
            )
            val pieceTreeSnapshot = host.createSnapshot()
            val version = pieceTreeSnapshot.versionId.toLong()
            if (version < 0L) return failure(
                "TSH-PIECETREE-002",
                "PieceTree version is outside the supported non-negative version range"
            )
            TshResult.Success(
                SnapshotView(
                    documentId = host.documentId,
                    version = version,
                    source = pieceTreeSnapshot
                )
            )
        } catch (error: LinkageError) {
            failure(
                "TSH-PIECETREE-003",
                "PieceTree snapshot creation failed with a linkage error",
                error
            )
        } catch (error: Exception) {
            failure(
                "TSH-PIECETREE-004",
                "PieceTree snapshot creation failed: ${error.message}",
                error
            )
        }
    }

    override fun subscribe(
        document: Any,
        listener: TshTextChangeListener
    ): TshResult<TshTextSubscription> {
        val host = document as? TshPieceTreeDocument ?: return failure(
            "TSH-PIECETREE-007",
            "Document is not a TshPieceTreeDocument"
        )
        return try {
            host.addChangeListener(listener)
            TshResult.Success(object : TshTextSubscription {
                private val closed = AtomicBoolean(false)
                override fun close() {
                    if (closed.compareAndSet(false, true)) host.removeChangeListener(listener)
                }
            })
        } catch (error: Exception) {
            failure("TSH-PIECETREE-008", "Cannot subscribe to PieceTree changes", error)
        }
    }

    override fun subscribeDisposed(
        document: Any,
        listener: TshTextDocumentDisposedListener
    ): TshResult<TshTextSubscription> {
        val host = document as? TshPieceTreeDocument ?: return failure(
            "TSH-PIECETREE-009",
            "Document is not a TshPieceTreeDocument"
        )
        return try {
            host.addDisposedListener(listener)
            TshResult.Success(object : TshTextSubscription {
                private val closed = AtomicBoolean(false)
                override fun close() {
                    if (closed.compareAndSet(false, true)) host.removeDisposedListener(listener)
                }
            })
        } catch (error: Exception) {
            failure("TSH-PIECETREE-010", "Cannot subscribe to PieceTree disposal", error)
        }
    }

    private class SnapshotView(
        override val documentId: String,
        override val version: Long,
        private val source: PieceTreeSnapshot
    ) : TshTextSnapshot {
        override val lengthUtf16: Long = source.length.toLong()

        override fun readUtf16(start: Long, end: Long): CharSequence {
            require(start >= 0L && end >= start && end <= lengthUtf16) {
                "Invalid PieceTree snapshot range [$start, $end), length=$lengthUtf16"
            }
            require(start <= Int.MAX_VALUE.toLong() && end <= Int.MAX_VALUE.toLong()) {
                "PieceTreeSnapshot currently exposes Int offsets"
            }
            return source.substring(start.toInt(), end.toInt())
        }
    }

    private fun failure(
        code: String,
        message: String,
        cause: Throwable? = null
    ) = TshResult.Failure(
        listOf(
            TshDiagnostic(
                code = code,
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.BINDING,
                message = message,
                cause = cause
            )
        )
    )
}
