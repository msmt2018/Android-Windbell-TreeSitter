package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSNativeObject

/**
 * Ownership-transfer registry for native resources shared by TSH operations.
 *
 * Register transfers close responsibility to this store. Owners must call retire instead of
 * closing the resource directly. A retired resource rejects new leases and is closed exactly once
 * after its last active lease is released.
 */
class TshNativeResourceLeaseStore {
    data class Handle<T : TSNativeObject>(
        val objectId: TshObjectId,
        val resource: T
    )

    internal class Entry<T : TSNativeObject>(
        val objectId: TshObjectId,
        val resource: T,
        val dependencies: List<AutoCloseable>
    ) {
        var leases = 0
        var retired = false
        var closed = false
    }

    inner class Lease<T : TSNativeObject> internal constructor(
        private val entry: Entry<T>
    ) : AutoCloseable {
        val resource: T get() = entry.resource
        val objectId: TshObjectId get() = entry.objectId
        private var released = false

        override fun close() {
            val closeNow = synchronized(lock) {
                if (released) return
                released = true
                check(entry.leases > 0) { "Native resource lease count underflow" }
                entry.leases--
                takeForCloseIfReady(entry)
            }
            closeNow?.closeSafely()
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<TshObjectId, Entry<out TSNativeObject>>()

    fun <T : TSNativeObject> register(
        objectId: TshObjectId,
        resource: T,
        dependencies: List<AutoCloseable> = emptyList()
    ): Handle<T> {
        require(resource.canAccess()) { "Cannot register a closed native resource" }
        synchronized(lock) {
            check(entries[objectId] == null) { "Native object ID is already registered" }
            entries[objectId] = Entry(objectId, resource, dependencies.toList())
        }
        return Handle(objectId, resource)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : TSNativeObject> acquire(handle: Handle<T>): Lease<T>? = synchronized(lock) {
        val entry = entries[handle.objectId] as? Entry<T> ?: return@synchronized null
        if (entry.resource !== handle.resource || entry.retired || entry.closed ||
            !entry.resource.canAccess()) return@synchronized null
        entry.leases++
        Lease(entry)
    }

    fun retire(objectId: TshObjectId): Boolean {
        val closeNow = synchronized(lock) {
            val entry = entries[objectId] ?: return false
            if (entry.retired) return false
            entry.retired = true
            takeForCloseIfReady(entry)
        }
        closeNow?.closeSafely()
        return true
    }

    /** Retires all resources; active leases defer close until their final release. */
    fun retireAll(): Int {
        val closeNow = ArrayList<Entry<out TSNativeObject>>()
        val retiredCount = synchronized(lock) {
            var count = 0
            for (entry in entries.values.toList()) {
                if (!entry.retired) {
                    entry.retired = true
                    count++
                }
                takeForCloseIfReady(entry)?.let(closeNow::add)
            }
            count
        }
        closeNow.forEach { it.closeSafely() }
        return retiredCount
    }

    fun activeLeaseCount(objectId: TshObjectId): Int = synchronized(lock) {
        entries[objectId]?.leases ?: 0
    }

    private fun takeForCloseIfReady(entry: Entry<out TSNativeObject>): Entry<out TSNativeObject>? {
        if (!entry.retired || entry.leases != 0 || entry.closed) return null
        entry.closed = true
        entries.remove(entry.objectId, entry)
        return entry
    }

    private fun Entry<out TSNativeObject>.closeSafely() {
        runCatching { resource.close() }
        dependencies.asReversed().forEach { dependency -> runCatching { dependency.close() } }
    }
}
