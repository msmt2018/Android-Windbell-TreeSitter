package com.zerostudio.idea.treesitter.tsh.api

/**
 * Immutable view of one document version. Offsets are UTF-16 code units and ranges are
 * half-open. Implementations must keep the snapshot readable until its lease is closed.
 */
interface TshTextSnapshot {
    val documentId: String
    val version: Long
    val lengthUtf16: Long

    fun readUtf16(start: Long, end: Long): CharSequence
}

/** A stable reference to a snapshot. close() releases only the snapshot lease, never the host document. */
interface TshTextSnapshotLease : AutoCloseable {
    val snapshot: TshTextSnapshot
    /** Providers must expose the closed state so TSH can reject use-after-close. */
    val isClosed: Boolean

    /**
     * Creates an independent lease for the same immutable snapshot.
     *
     * Implementations backed by mutable host storage must override this and retain the exact
     * persistent root/version. The compatibility default is explicitly unsupported: returning
     * the same lease would let one task close another task's resource.
     */
    fun retain(): TshResult<TshTextSnapshotLease> = TshResult.Failure(listOf(TshDiagnostic(
        code = "TSH-TEXT-LEASE-001",
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.LIFECYCLE,
        message = "This Snapshot Lease does not support independent retention"
    )))

    override fun close()
}

/**
 * Reference-counted lease implementation for immutable snapshots or persistent host roots.
 *
 * Each returned handle closes independently. [onFinalRelease] runs exactly once after the last
 * handle closes, outside the state monitor. The callback must release only the pinned snapshot
 * root/resource; it must never close the host document itself.
 */
class TshSharedTextSnapshotLease private constructor(
    private val state: SharedState,
    private val handleClosed: java.util.concurrent.atomic.AtomicBoolean
) : TshTextSnapshotLease {
    private class SharedState(
        val snapshot: TshTextSnapshot,
        val onFinalRelease: () -> Unit
    ) {
        var references: Int = 1
        var released: Boolean = false
        var releaseDiagnostics: List<TshDiagnostic> = emptyList()
    }

    override val snapshot: TshTextSnapshot
        get() {
            check(!isClosed) { "Snapshot Lease is closed" }
            return state.snapshot
        }

    override val isClosed: Boolean get() = handleClosed.get()

    /** Diagnostics produced by the final host-root release, visible on every retained handle. */
    val finalReleaseDiagnostics: List<TshDiagnostic>
        get() = synchronized(state) { state.releaseDiagnostics }

    override fun retain(): TshResult<TshTextSnapshotLease> {
        if (handleClosed.get()) return failure(
            "TSH-TEXT-LEASE-002", "Cannot retain a closed Snapshot Lease"
        )
        synchronized(state) {
            if (handleClosed.get() || state.released || state.references <= 0) {
                return failure("TSH-TEXT-LEASE-003", "Snapshot root has already been released")
            }
            if (state.references == Int.MAX_VALUE) {
                return failure("TSH-TEXT-LEASE-004", "Snapshot Lease reference count overflow")
            }
            state.references++
            return TshResult.Success(TshSharedTextSnapshotLease(
                state,
                java.util.concurrent.atomic.AtomicBoolean(false)
            ))
        }
    }

    override fun close() {
        if (!handleClosed.compareAndSet(false, true)) return
        val release = synchronized(state) {
            if (state.references <= 0) return
            state.references--
            if (state.references == 0 && !state.released) {
                state.released = true
                true
            } else false
        }
        if (!release) return
        try {
            state.onFinalRelease()
        } catch (error: Exception) {
            synchronized(state) {
                state.releaseDiagnostics = listOf(TshDiagnostic(
                    code = "TSH-TEXT-LEASE-005",
                    severity = TshDiagnosticSeverity.WARNING,
                    stage = TshDiagnosticStage.LIFECYCLE,
                    message = "Final Snapshot root release failed: ${error.message}",
                    cause = error
                ))
            }
        } catch (error: LinkageError) {
            synchronized(state) {
                state.releaseDiagnostics = listOf(TshDiagnostic(
                    code = "TSH-TEXT-LEASE-006",
                    severity = TshDiagnosticSeverity.WARNING,
                    stage = TshDiagnosticStage.LIFECYCLE,
                    message = "Final Snapshot root release failed with a linkage error",
                    cause = error
                ))
            }
        }
    }

    companion object {
        /** The initial returned handle owns one reference. */
        fun acquire(snapshot: TshTextSnapshot, onFinalRelease: () -> Unit = {}): TshSharedTextSnapshotLease {
            require(snapshot.documentId.isNotBlank()) { "Snapshot documentId must not be blank" }
            require(snapshot.version >= 0L) { "Snapshot version must be non-negative" }
            require(snapshot.lengthUtf16 >= 0L) { "Snapshot length must be non-negative" }
            return TshSharedTextSnapshotLease(
                SharedState(snapshot, onFinalRelease),
                java.util.concurrent.atomic.AtomicBoolean(false)
            )
        }

        private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.LIFECYCLE,
            message = message
        )))
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.LIFECYCLE,
        message = message
    )))
}

/** Host-owned document abstraction; storage may be String, Rope, Piece Tree or stream. */
interface TshTextDocument {
    val documentId: String
    val languageId: String?
    val version: Long

    /** Snapshot must remain logically consistent for its reported version. */
    fun snapshot(): TshTextSnapshot
}

/** One versioned text replacement expressed in UTF-16 code-unit coordinates. */
data class TshTextEdit(
    val startUtf16: Long,
    val endUtf16: Long,
    val replacement: String
) {
    init {
        require(startUtf16 >= 0L) { "startUtf16 must be >= 0" }
        require(endUtf16 >= startUtf16) { "endUtf16 must be >= startUtf16" }
        require(TshUnicode.isWellFormedUtf16(replacement)) {
            "Replacement contains an unpaired UTF-16 surrogate"
        }
    }
}

data class TshTextChange(
    val documentId: String,
    val beforeVersion: Long,
    val afterVersion: Long,
    /** Edits are ascending by start offset and must not overlap in the before-version snapshot. */
    val edits: List<TshTextEdit>
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(beforeVersion >= 0L && afterVersion > beforeVersion) {
            "Text change must advance the document version"
        }
        require(edits.zipWithNext().all { (left, right) ->
            left.startUtf16 < right.startUtf16 && left.endUtf16 <= right.startUtf16
        }) { "Edits must be ascending and non-overlapping in before-version coordinates" }
    }
}

data class TshTextChunk(
    val documentId: String,
    val documentVersion: Long,
    val startUtf16: Long,
    val endUtf16: Long,
    val text: CharSequence
) {
    init {
        require(documentId.isNotBlank())
        require(documentVersion >= 0L)
        require(startUtf16 >= 0L && endUtf16 >= startUtf16)
        require(text.length.toLong() == endUtf16 - startUtf16) {
            "Chunk length must equal its UTF-16 range length"
        }
    }
}

enum class TshTextAccessThreading { UNKNOWN, ANY_THREAD, UI_THREAD_ONLY, SERIALIZED }

fun interface TshTextDocumentDisposedListener {
    fun onDisposed(documentId: String, finalVersion: Long)
}

fun interface TshTextChangeListener {
    fun onChanged(change: TshTextChange)
}

interface TshTextSubscription : AutoCloseable {
    override fun close()
}

/**
 * Optional host adapter for reading text without flattening the whole document.
 * Methods are safe defaults so existing providers remain source-compatible.
 */
interface TshTextAccessProvider : TshProvider {
    override val id: String
    override val apiVersion: Int get() = 1
    val supportsChangeSubscriptions: Boolean get() = false
    val supportsDisposalNotifications: Boolean get() = false
    override val capabilities: TshCapabilitySet
        get() = TshCapabilitySet.from(buildList {
            add(TshCapability("text.snapshot", 1))
            add(TshCapability("text.chunk", 1))
            if (supportsChangeSubscriptions) add(TshCapability("text.change-subscription", 1))
            if (supportsDisposalNotifications) add(TshCapability("text.disposal-notification", 1))
        })
    override fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean =
        supports(instance)
    /** UNKNOWN requires the host integration layer to choose a safe dispatcher explicitly. */
    val threading: TshTextAccessThreading get() = TshTextAccessThreading.UNKNOWN
    /** Conservative default: providers must opt in before TSH performs concurrent snapshot reads. */
    val supportsConcurrentReads: Boolean get() = false
    /** Providers with UI_THREAD_ONLY or UNKNOWN policies must override this explicitly. */
    fun isAccessAllowedOnCurrentThread(): Boolean =
        threading == TshTextAccessThreading.ANY_THREAD ||
            threading == TshTextAccessThreading.SERIALIZED
    /** Serializes provider calls when concurrent reads are not explicitly supported. */
    fun <T> withReadAccess(block: () -> T): T =
        if (supportsConcurrentReads && threading != TshTextAccessThreading.SERIALIZED) block() else synchronized(this, block)
    fun supports(document: Any): Boolean
    fun snapshot(document: Any): TshResult<TshTextSnapshot>

    /** Default non-owning lease is valid only if the provider's snapshot is intrinsically immutable. */
    fun acquireSnapshot(document: Any): TshResult<TshTextSnapshotLease> {
        if (!isAccessAllowedOnCurrentThread()) {
            return failure("TSH-TEXT-010", "TextAccessProvider snapshot access is not allowed on the current thread")
        }
        return try {
            when (val result = withReadAccess { snapshot(document) }) {
                is TshResult.Success -> {
                    val value = result.value
                    if (value.documentId.isBlank() || value.version < 0L || value.lengthUtf16 < 0L) {
                        failure("TSH-TEXT-011", "Provider returned a Snapshot with invalid identity, version, or length")
                    } else {
                        TshResult.Success(TshSharedTextSnapshotLease.acquire(value), result.diagnostics)
                    }
                }
                is TshResult.Failure -> result
            }
        } catch (error: LinkageError) {
            failure("TSH-TEXT-013", "TextAccessProvider snapshot acquisition failed with a linkage error")
        } catch (error: Exception) {
            TshResult.Failure(listOf(TshDiagnostic(
                code = "TSH-TEXT-012",
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.BINDING,
                message = "TextAccessProvider snapshot acquisition failed: ${error.message}",
                cause = error
            )))
        }
    }

    /** Bounded range read. Large callers should request multiple chunks. */
    fun readChunk(
        lease: TshTextSnapshotLease,
        startUtf16: Long,
        maxUtf16Length: Int
    ): TshResult<TshTextChunk> = withReadAccess {
        readChunkInternal(lease, startUtf16, maxUtf16Length)
    }

    private fun readChunkInternal(
        lease: TshTextSnapshotLease,
        startUtf16: Long,
        maxUtf16Length: Int
    ): TshResult<TshTextChunk> {
        if (!isAccessAllowedOnCurrentThread()) {
            return failure("TSH-TEXT-010", "TextAccessProvider chunk access is not allowed on the current thread")
        }
        if (lease.isClosed) return failure("TSH-TEXT-009", "Snapshot lease is already closed")
        if (startUtf16 < 0L || maxUtf16Length <= 0) return failure(
            "TSH-TEXT-001", "Chunk start must be non-negative and max length must be positive"
        )
        val snapshot = lease.snapshot
        if (startUtf16 > snapshot.lengthUtf16) return failure(
            "TSH-TEXT-002", "Chunk start is outside the snapshot"
        )
        return try {
            var end = startUtf16 + minOf(snapshot.lengthUtf16 - startUtf16, maxUtf16Length.toLong())
            if (startUtf16 > 0L && startUtf16 < snapshot.lengthUtf16) {
                val previous = snapshot.readUtf16(startUtf16 - 1L, startUtf16)
                val first = snapshot.readUtf16(startUtf16, startUtf16 + 1L)
                if (previous.isNotEmpty() && first.isNotEmpty() &&
                    Character.isHighSurrogate(previous[0]) && Character.isLowSurrogate(first[0])) {
                    return failure("TSH-TEXT-005", "Chunk start splits a UTF-16 surrogate pair")
                }
            }
            if (end > startUtf16 && end < snapshot.lengthUtf16) {
                val last = snapshot.readUtf16(end - 1L, end)
                val next = snapshot.readUtf16(end, end + 1L)
                if (last.isNotEmpty() && next.isNotEmpty() &&
                    Character.isHighSurrogate(last[0]) && Character.isLowSurrogate(next[0])) {
                    end--
                }
            }
            if (end == startUtf16 && startUtf16 < snapshot.lengthUtf16) {
                return failure("TSH-TEXT-006", "Chunk limit is too small to include the next Unicode code point")
            }
            val text = snapshot.readUtf16(startUtf16, end)
            if (!TshUnicode.isWellFormedUtf16(text)) {
                return failure("TSH-TEXT-007", "Snapshot chunk contains malformed UTF-16")
            }
            TshResult.Success(TshTextChunk(snapshot.documentId, snapshot.version, startUtf16, end, text))
        } catch (error: Exception) {
            TshResult.Failure(listOf(TshDiagnostic(
                code = "TSH-TEXT-003", severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.BINDING, message = "Snapshot chunk read failed",
                cause = error
            )))
        }
    }

    /** Providers that support live edits override this; default explicitly reports unsupported. */
    fun subscribe(document: Any, listener: TshTextChangeListener): TshResult<TshTextSubscription> =
        TshResult.Failure(listOf(TshDiagnostic(
            code = "TSH-TEXT-004", severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.BINDING,
            message = "This TextAccessProvider does not support change subscriptions"
        )))

    /** Document disposal is separate from its last text change and invalidates all active bindings. */
    fun subscribeDisposed(
        document: Any,
        listener: TshTextDocumentDisposedListener
    ): TshResult<TshTextSubscription> = TshResult.Failure(listOf(TshDiagnostic(
        code = "TSH-TEXT-008", severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.BINDING,
        message = "This TextAccessProvider does not support document disposal notifications"
    )))

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code, severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.BINDING, message = message
    )))
}


/** Shared strict UTF-16 validation for text edits and chunk boundaries. */
internal object TshUnicode {
    fun isWellFormedUtf16(value: CharSequence): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index++
            }
        }
        return true
    }
}
