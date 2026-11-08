package com.zerostudio.idea.treesitter.tsh.api

/** Extension contract for host/editor capabilities. */
interface TshProvider {
    val id: String
    val apiVersion: Int
    val capabilities: TshCapabilitySet

    /** True only when this provider can safely operate on this exact instance. */
    fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean
}

class TshProviderRegistry {
    private val lock = Any()
    private val providers = LinkedHashMap<String, TshProvider>()

    fun register(provider: TshProvider) {
        require(provider.id.isNotBlank()) { "provider id must not be blank" }
        require(provider.apiVersion > 0) { "provider apiVersion must be positive" }
        synchronized(lock) {
            val previous = providers[provider.id]
            require(previous == null || previous === provider) {
                "A different provider already uses this id"
            }
            providers[provider.id] = provider
        }
    }

    fun unregister(id: String): Boolean = synchronized(lock) {
        providers.remove(id) != null
    }

    fun findFor(instance: Any, descriptor: TshObjectDescriptor): List<TshProvider> =
        synchronized(lock) {
            providers.values.filter { it.supports(instance, descriptor) }
        }

    fun all(): List<TshProvider> = synchronized(lock) { providers.values.toList() }

    fun clear() = synchronized(lock) { providers.clear() }
}
