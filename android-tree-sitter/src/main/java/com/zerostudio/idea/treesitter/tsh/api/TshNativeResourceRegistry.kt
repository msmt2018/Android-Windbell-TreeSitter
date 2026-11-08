package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSNativeObject

/**
 * Coordinated object-index and native-ownership entry point for Tree/Language resources.
 *
 * Owners should register and retire through this class rather than independently mutating
 * TshObjectRegistry and TshNativeResourceLeaseStore. Acquire resolves the indexed instance and
 * obtains its lease; a concurrent retirement either wins before acquire (and acquire fails) or
 * follows it (and the acquired lease keeps the resource alive).
 */
class TshNativeResourceRegistry(
    private val objects: TshObjectRegistry,
    private val leases: TshNativeResourceLeaseStore
) {
    private val lock = Any()

    data class Registration<T : TSNativeObject>(
        val descriptor: TshObjectDescriptor,
        val handle: TshNativeResourceLeaseStore.Handle<T>
    )

    /**
     * Transfers close responsibility to the lease store and publishes the object descriptor.
     * If lease registration fails, the object-index entry is rolled back; ownership remains
     * with the caller because the lease store did not accept the resource.
     */
    fun <T : TSNativeObject> register(
        resource: T,
        type: String,
        capabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
        metadata: Map<String, String> = emptyMap()
    ): Registration<T> = synchronized(lock) {
        check(objects.find(resource) == null) {
            "Native resource is already published; retire it before registering again"
        }
        val descriptor = objects.register(resource, type, capabilities, metadata)
        try {
            Registration(descriptor, leases.register(descriptor.id, resource))
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
        val resource = objects.find(objectId) ?: return@synchronized null
        if (!expectedType.isInstance(resource)) return@synchronized null
        val typed = expectedType.cast(resource)
        leases.acquire(TshNativeResourceLeaseStore.Handle(objectId, typed))
    }

    /**
     * Stops new acquisitions before removing the public object index. Existing leases defer close.
     */
    fun retire(objectId: TshObjectId): Boolean = synchronized(lock) {
        val retired = leases.retire(objectId)
        if (retired) objects.unregister(objectId)
        retired
    }

    /** Number of active operations retaining this resource. */
    fun activeLeaseCount(objectId: TshObjectId): Int = leases.activeLeaseCount(objectId)
}
