package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture

/** Host-owned lifecycle scope. Closing a scope runs registered detach actions exactly once. */
class TshOwnerScope(val id: String = "tsh-owner-${NEXT_ID.incrementAndGet()}") : AutoCloseable {
    private val lock = Any()
    private val nextActionId = AtomicLong(1L)
    private val actions = LinkedHashMap<Long, () -> Unit>()
    @Volatile private var closed = false
    @Volatile private var closeFailures: List<TshDiagnostic> = emptyList()
    private var closeCompletion: CompletableFuture<List<TshDiagnostic>>? = null

    init { require(id.isNotBlank()) { "OwnerScope id must not be blank" } }

    val isClosed: Boolean get() = closed

    /** Returns null when the scope is already closed. */
    fun registerCleanup(action: () -> Unit): AutoCloseable? {
        val actionId = nextActionId.getAndIncrement()
        synchronized(lock) {
            if (!closed) {
                actions[actionId] = action
                return AutoCloseable { synchronized(lock) { actions.remove(actionId) } }
            }
        }
        return null
    }

    fun closeDiagnostics(): List<TshDiagnostic> = closeFailures

    fun closeWithReport(): List<TshDiagnostic> {
        val (completion, pending) = synchronized(lock) {
            val existing = closeCompletion
            if (existing != null) {
                existing to null
            } else {
                closed = true
                val created = CompletableFuture<List<TshDiagnostic>>()
                closeCompletion = created
                created to actions.values.toList().also { actions.clear() }
            }
        }
        if (pending != null) {
            val failures = ArrayList<TshDiagnostic>()
            pending.forEach { action ->
                try {
                    action()
                } catch (error: Throwable) {
                    failures += TshDiagnostic(
                        code = "TSH-OWNER-001",
                        severity = TshDiagnosticSeverity.ERROR,
                        stage = TshDiagnosticStage.LIFECYCLE,
                        message = "OwnerScope cleanup action failed",
                        cause = error
                    )
                }
            }
            closeFailures = failures.toList()
            completion.complete(closeFailures)
        }
        return completion.join()
    }

    override fun close() {
        closeWithReport()
    }

    companion object {
        private val NEXT_ID = AtomicLong(0L)
    }
}

data class TshAttachmentRequest(
    val instance: Any,
    val objectType: String,
    val capabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
    val metadata: Map<String, String> = emptyMap(),
    val requiredCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
    val optionalCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
    val providerApiRange: TshProviderApiRange = TshProviderApiRange()
) {
    init {
        require(objectType.isNotBlank()) { "Attachment objectType must not be blank" }
        require(requiredCapabilities.all().none { required -> optionalCapabilities.contains(required.id) }) {
            "A capability cannot be both required and optional"
        }
    }
}

@JvmInline
value class TshAttachmentId(val value: Long)

enum class TshAttachmentStatus { READY, DEGRADED, INCOMPLETE, DETACHED }

data class TshAttachmentHandle(
    val id: TshAttachmentId,
    val objectId: TshObjectId,
    val ownerScopeId: String
)

data class TshAttachmentReport(
    val handle: TshAttachmentHandle,
    val descriptor: TshObjectDescriptor,
    val resolution: TshProviderResolution,
    val status: TshAttachmentStatus,
    val diagnostics: List<TshDiagnostic>,
    val attachedAtNanos: Long,
    val updatedAtNanos: Long
)

/**
 * Owns only TSH's registration/binding records. It never closes or mutates host instances.
 */
class TshAttachmentManager(
    private val objects: TshObjectRegistry,
    private val providers: TshProviderRegistry
) : AutoCloseable {
    private data class Entry(
        var report: TshAttachmentReport,
        var scopeRegistration: AutoCloseable?,
        val request: TshAttachmentRequest
    )

    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val entries = LinkedHashMap<TshAttachmentId, Entry>()
    private var closed = false

    fun attach(request: TshAttachmentRequest, ownerScope: TshOwnerScope): TshResult<TshAttachmentReport> {
        if (ownerScope.isClosed) return failure(
            "TSH-ATTACH-001", "Cannot attach through a closed OwnerScope"
        )
        val descriptor = try {
            objects.registerUnique(
                request.instance, request.objectType, request.capabilities, request.metadata
            )
        } catch (error: Throwable) {
            return failure("TSH-ATTACH-002", "Host object is already registered or invalid: ${error.message}", error)
        }

        val resolution = providers.resolveFor(
            request.instance, descriptor,
            request.requiredCapabilities, request.optionalCapabilities,
            request.providerApiRange
        )
        val id = TshAttachmentId(nextId.getAndIncrement())
        val handle = TshAttachmentHandle(id, descriptor.id, ownerScope.id)
        val now = System.nanoTime()
        val report = report(handle, descriptor, resolution, now, now)

        synchronized(lock) {
            if (closed) {
                objects.unregister(descriptor.id)
                return failure("TSH-ATTACH-003", "Attachment manager is closed")
            }
            entries[id] = Entry(report, null, request)
        }

        val registration = ownerScope.registerCleanup { detach(handle) }
        if (registration == null) {
            detach(handle)
            return failure("TSH-ATTACH-004", "OwnerScope closed while attachment was being registered")
        }
        val closeRegistration = synchronized(lock) {
            val entry = entries[id]
            if (entry == null || entry.report.status == TshAttachmentStatus.DETACHED || closed) {
                true
            } else {
                entry.scopeRegistration = registration
                false
            }
        }
        if (closeRegistration) runCatching { registration.close() }
        val current = inspect(handle) ?: report.copy(status = TshAttachmentStatus.DETACHED)
        return TshResult.Success(current, current.diagnostics)
    }

    fun inspect(handle: TshAttachmentHandle): TshAttachmentReport? = synchronized(lock) {
        entries[handle.id]?.report?.takeIf { it.handle == handle }
    }

    /** Re-runs provider negotiation after providers are installed, removed or upgraded. */
    fun refresh(handle: TshAttachmentHandle): TshResult<TshAttachmentReport> {
        val entrySnapshot = synchronized(lock) {
            entries[handle.id]?.takeIf { it.report.handle == handle }
        } ?: return failure("TSH-ATTACH-005", "Unknown attachment handle")
        val current = entrySnapshot.report
        if (current.status == TshAttachmentStatus.DETACHED) {
            return failure("TSH-ATTACH-006", "Cannot refresh a detached attachment")
        }
        val instance = objects.find(handle.objectId)
            ?: return failure("TSH-ATTACH-007", "Host object was unregistered outside its attachment")
        val request = entrySnapshot.request
        val resolution = providers.resolveFor(
            instance, current.descriptor,
            request.requiredCapabilities, request.optionalCapabilities, request.providerApiRange
        )
        val updated = report(
            handle, current.descriptor, resolution,
            current.attachedAtNanos, System.nanoTime()
        )
        synchronized(lock) {
            val entry = entries[handle.id] ?: return failure("TSH-ATTACH-005", "Unknown attachment handle")
            if (entry.report.status == TshAttachmentStatus.DETACHED) {
                return failure("TSH-ATTACH-006", "Attachment was detached during refresh")
            }
            entry.report = updated
        }
        return TshResult.Success(updated, updated.diagnostics)
    }

    /** Idempotent; unregisters only TSH's identity entry and never closes the host object. */
    fun detach(handle: TshAttachmentHandle): Boolean {
        val entry = synchronized(lock) {
            val found = entries[handle.id] ?: return false
            if (found.report.handle != handle || found.report.status == TshAttachmentStatus.DETACHED) return false
            found.report = found.report.copy(
                status = TshAttachmentStatus.DETACHED,
                updatedAtNanos = System.nanoTime()
            )
            found
        }
        objects.unregister(handle.objectId)
        entry.scopeRegistration?.let { runCatching { it.close() } }
        entry.scopeRegistration = null
        return true
    }

    override fun close() {
        val handles = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.filter { it.report.status != TshAttachmentStatus.DETACHED }
                .map { it.report.handle }
        }
        handles.forEach(::detach)
    }

    private fun report(
        handle: TshAttachmentHandle,
        descriptor: TshObjectDescriptor,
        resolution: TshProviderResolution,
        attachedAt: Long,
        updatedAt: Long
    ): TshAttachmentReport {
        val status = when {
            !resolution.isSatisfiable -> TshAttachmentStatus.INCOMPLETE
            resolution.degradationLevel != TshDegradationLevel.NONE -> TshAttachmentStatus.DEGRADED
            else -> TshAttachmentStatus.READY
        }
        return TshAttachmentReport(
            handle, descriptor, resolution, status,
            resolution.diagnostics, attachedAt, updatedAt
        )
    }

    private fun failure(
        code: String,
        message: String,
        cause: Throwable? = null
    ) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.BINDING,
        message = message,
        cause = cause
    )))
}
