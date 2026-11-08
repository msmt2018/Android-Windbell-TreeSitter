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
    private val ownedIds = LinkedHashSet<TshObjectId>()

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
            val handle = leases.register(descriptor.id, resource)
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
        if (objectId !in ownedIds) return@synchronized null
        val resource = objects.find(objectId) ?: return@synchronized null
        if (!expectedType.isInstance(resource)) return@synchronized null
        val typed = expectedType.cast(resource)
        leases.acquire(TshNativeResourceLeaseStore.Handle(objectId, typed))
    }

    /** Acquires a previously published handle and verifies both ID and instance identity. */
    fun <T : TSNativeObject> acquire(
        handle: TshNativeResourceLeaseStore.Handle<T>
    ): TshNativeResourceLeaseStore.Lease<T>? = synchronized(lock) {
        if (handle.objectId !in ownedIds) return@synchronized null
        val resource = objects.find(handle.objectId) ?: return@synchronized null
        if (resource !== handle.resource) return@synchronized null
        leases.acquire(handle)
    }

    /**
     * Stops new acquisitions before removing the public object index. Existing leases defer close.
     */
    fun retire(objectId: TshObjectId): Boolean = synchronized(lock) {
        val retired = leases.retire(objectId)
        if (retired) {
            objects.unregister(objectId)
            ownedIds.remove(objectId)
        }
        retired
    }

    /**
     * Retires every resource published through this coordinator. This deliberately does not call
     * the underlying store's retireAll(), because that store may be shared with another owner.
     */
    fun retireAll(): Int = synchronized(lock) {
        val ids = ownedIds.toList()
        var retiredCount = 0
        for (id in ids) {
            if (leases.retire(id)) retiredCount++
            objects.unregister(id)
            ownedIds.remove(id)
        }
        retiredCount
    }

    /** Number of active operations retaining this resource. */
    fun activeLeaseCount(objectId: TshObjectId): Int = leases.activeLeaseCount(objectId)
}
