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
    data class Registration<T : TSNativeObject>(
        val descriptor: TshObjectDescriptor,
        val handle: TshNativeResourceLeaseStore.Handle<T>
    )

    /**
     * Transfers close responsibility to the lease store and publishes the object descriptor.
     * If publication fails, the just-registered native resource is retired and closed.
     */
    fun <T : TSNativeObject> register(
        resource: T,
        type: String,
        capabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
        metadata: Map<String, String> = emptyMap()
    ): Registration<T> {
        check(objects.find(resource) == null) {
            "Native resource is already published; retire it before registering again"
        }
        val descriptor = objects.register(resource, type, capabilities, metadata)
        return try {
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
    ): TshNativeResourceLeaseStore.Lease<T>? {
        val resource = objects.find(objectId) ?: return null
        if (!expectedType.isInstance(resource)) return null
        val typed = expectedType.cast(resource)
        return leases.acquire(TshNativeResourceLeaseStore.Handle(objectId, typed))
    }

    /**
     * Stops new acquisitions before removing the public object index. Existing leases defer close.
     */
    fun retire(objectId: TshObjectId): Boolean {
        val retired = leases.retire(objectId)
        if (retired) objects.unregister(objectId)
        return retired
    }

    /** Number of active operations retaining this resource. */
    fun activeLeaseCount(objectId: TshObjectId): Int = leases.activeLeaseCount(objectId)
}
