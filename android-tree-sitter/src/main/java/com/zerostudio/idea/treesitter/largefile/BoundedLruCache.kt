package com.zerostudio.idea.treesitter.largefile

/**
 * Compatibility facade for the original weighted LRU API. The implementation
 * delegates to the byte-budget cache so accounting and eviction remain unified.
 */
class BoundedLruCache<K : Any, V : Any> private constructor(
    maxWeight: Long,
    weigh: (V) -> Long,
    onEvict: (V) -> Unit,
    @Suppress("UNUSED_PARAMETER") compatibilityMarker: Boolean
) : AutoCloseable {
    constructor(maxWeight: Long, weigh: (V) -> Long) : this(maxWeight, weigh, {}, true)

    constructor(maxWeight: Long, weigh: (V) -> Long, onEvict: (V) -> Unit) :
        this(maxWeight, weigh, onEvict, true)

    private val delegate = ByteBudgetLruCache<K, V>(maxWeight, weigh, onEvict)

    val bytesUsed: Long get() = delegate.bytesUsed
    val size: Int get() = delegate.size

    fun get(key: K): V? = delegate.get(key)
    fun put(key: K, value: V) = delegate.put(key, value)
    fun removeIf(predicate: (K) -> Boolean) = delegate.invalidate { key, _ -> predicate(key) }
    fun weight(): Long = delegate.bytesUsed
    fun clear() = delegate.close()
    override fun close() = delegate.close()
}
