package com.zerostudio.idea.treesitter.tsh.api

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Identity-based registry for host-owned instances.
 * Strong references are held until unregister/clear; ownership is explicit.
 */
class TshObjectRegistry {
    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val byInstance = IdentityHashMap<Any, TshObjectDescriptor>()
    private val byId = LinkedHashMap<TshObjectId, Any>()

    fun register(
        instance: Any,
        type: String,
        capabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
        metadata: Map<String, String> = emptyMap()
    ): TshObjectDescriptor {
        require(type.isNotBlank()) { "type must not be blank" }
        synchronized(lock) {
            val existing = byInstance[instance]
            if (existing != null) {
                require(existing.type == type) {
                    "The same object instance is already registered with a different type"
                }
                return existing.copy(
                    capabilities = capabilities,
                    metadata = metadata.toMap()
                ).also { byInstance[instance] = it }
            }

            val descriptor = TshObjectDescriptor(
                id = TshObjectId(nextId.getAndIncrement()),
                type = type,
                capabilities = capabilities,
                metadata = metadata.toMap(),
                registeredAtNanos = System.nanoTime()
            )
            byInstance[instance] = descriptor
            byId[descriptor.id] = instance
            return descriptor
        }
    }

    fun find(instance: Any): TshObjectDescriptor? =
        synchronized(lock) { byInstance[instance] }

    fun find(id: TshObjectId): Any? =
        synchronized(lock) { byId[id] }

    fun descriptor(id: TshObjectId): TshObjectDescriptor? =
        synchronized(lock) {
            val instance = byId[id] ?: return@synchronized null
            byInstance[instance]
        }

    fun unregister(instance: Any): Boolean = synchronized(lock) {
        val descriptor = byInstance.remove(instance) ?: return@synchronized false
        byId.remove(descriptor.id)
        true
    }

    fun unregister(id: TshObjectId): Boolean = synchronized(lock) {
        val instance = byId.remove(id) ?: return@synchronized false
        byInstance.remove(instance)
        true
    }

    fun descriptors(): List<TshObjectDescriptor> =
        synchronized(lock) { byInstance.values.toList() }

    fun size(): Int = synchronized(lock) { byInstance.size }

    fun clear() = synchronized(lock) {
        byInstance.clear()
        byId.clear()
    }
}
