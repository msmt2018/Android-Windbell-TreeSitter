package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSNativeObject

/**
 * Coordinates the public object index with native-resource ownership.
 *
 * The coordinator never invokes native close while holding its own lock. Removing an ID from
 * [ownedIds] and [TshObjectRegistry] is the acquisition barrier; the lease store is retired only
 * after that barrier has been published. Leases acquired before the barrier keep the resource alive.
 */
class TshNativeResourceRegistry(
    private val objects: TshObjectRegistry,
    private val leases: TshNativeResourceLeaseStore
) : AutoCloseable {
    private val lock = Any()
    private val ownedIds = LinkedHashSet<TshObjectId>()
    private var closed = false

    data class Registration<T : TSNativeObject>(
        val descriptor: TshObjectDescriptor,
        val handle: TshNativeResourceLeaseStore.Handle<T>
    )

    /** Transfers close responsibility to the lease store and publishes the object descriptor. */
    fun <T : TSNativeObject> register(
        resource: T,
        type: String,
        capabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
        metadata: Map<String, String> = emptyMap(),
        dependencies: List<AutoCloseable> = emptyList()
    ): Registration<T> = synchronized(lock) {
        check(!closed) { "Native resource registry is closed" }
        val descriptor = objects.registerUnique(resource, type, capabilities, metadata)
        try {
            val handle = leases.register(descriptor.id, resource, dependencies)
            ownedIds.add(descriptor.id)
            Registration(descriptor, handle)
        } catch (error: Throwable) {
            objects.unregister(descriptor.id)
            throw error
        }
    }

    /** Resolves by ID and acquires only if the same live instance is still lease-managed. */
    fun <T : TSNativeObject> acquire(
        objectId: TshObjectId,
        expectedType: Class<T>
    ): TshNativeResourceLeaseStore.Lease<T>? = synchronized(lock) {
        if (closed || objectId !in ownedIds) return@synchronized null
        val resource = objects.find(objectId) ?: return@synchronized null
        if (!expectedType.isInstance(resource)) return@synchronized null
        leases.acquire(
            TshNativeResourceLeaseStore.Handle(objectId, expectedType.cast(resource))
        )
    }

    /** Acquires a previously published handle and verifies both ID and instance identity. */
    fun <T : TSNativeObject> acquire(
        handle: TshNativeResourceLeaseStore.Handle<T>
    ): TshNativeResourceLeaseStore.Lease<T>? = synchronized(lock) {
        if (closed || handle.objectId !in ownedIds) return@synchronized null
        val resource = objects.find(handle.objectId) ?: return@synchronized null
        if (resource !== handle.resource) return@synchronized null
        leases.acquire(handle)
    }

    /** Acquires an exclusive mutation lease; it fails while any read/query lease is active. */
    fun <T : TSNativeObject> acquireExclusive(
        handle: TshNativeResourceLeaseStore.Handle<T>
    ): TshNativeResourceLeaseStore.ExclusiveLease<T>? = synchronized(lock) {
        if (closed || handle.objectId !in ownedIds) return@synchronized null
        val resource = objects.find(handle.objectId) ?: return@synchronized null
        if (resource !== handle.resource) return@synchronized null
        leases.acquireExclusive(handle)
    }

    /** Updates metadata while preserving the same resource identity and ownership lease. */
    fun <T : TSNativeObject> updateMetadata(
        handle: TshNativeResourceLeaseStore.Handle<T>,
        metadata: Map<String, String>
    ): TshResult<TshObjectDescriptor> = synchronized(lock) {
        if (closed || handle.objectId !in ownedIds) {
            return@synchronized TshResult.Failure(listOf(TshDiagnostic(
                "TSH-NATIVE-RESOURCE-006", TshDiagnosticSeverity.ERROR,
                TshDiagnosticStage.LIFECYCLE,
                "Cannot update metadata for a retired or unowned Native resource",
                objectId = handle.objectId
            )))
        }
        val resource = objects.find(handle.objectId)
        if (resource !== handle.resource || !handle.resource.canAccess()) {
            return@synchronized TshResult.Failure(listOf(TshDiagnostic(
                "TSH-NATIVE-RESOURCE-007", TshDiagnosticSeverity.ERROR,
                TshDiagnosticStage.LIFECYCLE,
                "Native resource identity or access state changed",
                objectId = handle.objectId
            )))
        }
        val current = objects.descriptor(handle.objectId)
            ?: return@synchronized TshResult.Failure(listOf(TshDiagnostic(
                "TSH-NATIVE-RESOURCE-008", TshDiagnosticSeverity.ERROR,
                TshDiagnosticStage.LIFECYCLE,
                "Native resource descriptor is missing",
                objectId = handle.objectId
            )))
        try {
            TshResult.Success(objects.register(handle.resource, current.type, current.capabilities, metadata))
        } catch (error: Exception) {
            TshResult.Failure(listOf(TshDiagnostic(
                "TSH-NATIVE-RESOURCE-009", TshDiagnosticSeverity.ERROR,
                TshDiagnosticStage.LIFECYCLE,
                "Cannot update Native resource metadata: ${error.message}",
                objectId = handle.objectId,
                cause = error
            )))
        }
    }

    /**
     * Atomically blocks future acquisitions and removes the public index, then retires outside
     * the coordinator lock so resource close callbacks cannot execute under that lock.
     */
    fun retire(objectId: TshObjectId): Boolean {
        val shouldRetire = synchronized(lock) {
            if (!ownedIds.remove(objectId)) return@synchronized false
            objects.unregister(objectId)
            true
        }
        if (!shouldRetire) return false
        return leases.retire(objectId)
    }

    /** Retires only this coordinator's resources; other coordinators may share the lease store. */
    fun retireAll(): Int {
        val ids = synchronized(lock) {
            val snapshot = ownedIds.toList()
            ownedIds.clear()
            snapshot.forEach(objects::unregister)
            snapshot
        }
        var retiredCount = 0
        ids.forEach { if (leases.retire(it)) retiredCount++ }
        return retiredCount
    }

    /** Permanently closes this coordinator and retires all resources it owns. */
    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) retireAll()
    }

    /** Native close/dependency failures captured during retire or close. */
    fun closeDiagnostics(): List<TshDiagnostic> = leases.closeDiagnostics()

    /** Number of active operations retaining this resource. */
    fun activeLeaseCount(objectId: TshObjectId): Int = leases.activeLeaseCount(objectId)
}
