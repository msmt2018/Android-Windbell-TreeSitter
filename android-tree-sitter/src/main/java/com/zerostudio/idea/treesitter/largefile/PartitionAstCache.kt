package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSTree

/**
 * Cache identity for a regional syntax tree. The context window is part of the key:
 * two partitions parsed with different overlap/context must never share an AST.
 */
data class PartitionAstKey(
    val documentVersion: Long,
    val partitionIndex: Int,
    val startUtf16: Long,
    val endUtf16: Long,
    val contextStartUtf16: Long,
    val contextEndUtf16: Long,
    val languageRevision: Long
) {
    init {
        require(documentVersion >= 0L && partitionIndex >= 0)
        require(startUtf16 >= 0L && endUtf16 >= startUtf16)
        require(contextStartUtf16 in 0L..startUtf16)
        require(contextEndUtf16 >= endUtf16)
    }
}

/**
 * Reference-counted LRU for native TSTree handles.
 *
 * [estimatedBytes] is deliberately caller-supplied: Tree-sitter does not expose
 * exact retained allocation size. Use a conservative estimate and enforce the
 * budget before admitting a tree. A leased tree is never closed underneath a
 * query; eviction is deferred until the last lease is released.
 */
class PartitionAstCache(
    private val maxEstimatedBytes: Long,
    private val estimatedBytes: (sourceUtf16Length: Long) -> Long
) : AutoCloseable {
    private data class Entry(
        val tree: TSTree,
        val weight: Long,
        var leases: Int = 0,
        var evictWhenReleased: Boolean = false
    )

    inner class Lease internal constructor(
        val tree: TSTree,
        private val key: PartitionAstKey
    ) : AutoCloseable {
        private var released = false
        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                releaseLocked(key)
            }
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<PartitionAstKey, Entry>(16, 0.75f, true)
    private var usedBytes = 0L
    private var closed = false

    init { require(maxEstimatedBytes >= 0L) }

    val bytesUsed: Long get() = synchronized(lock) { usedBytes }
    val size: Int get() = synchronized(lock) { entries.size }

    /** Takes ownership of [tree] whether admitted or rejected. */
    fun put(key: PartitionAstKey, tree: TSTree, sourceUtf16Length: Long): Boolean {
        require(sourceUtf16Length >= 0L)
        val weight = estimatedBytes(sourceUtf16Length).coerceAtLeast(1L)
        synchronized(lock) {
            if (closed || weight > maxEstimatedBytes) {
                tree.close()
                return false
            }
            removeLocked(key)
            evictUntilFitsLocked(weight)
            if (usedBytes + weight > maxEstimatedBytes) {
                tree.close()
                return false
            }
            entries[key] = Entry(tree, weight)
            usedBytes += weight
            return true
        }
    }

    fun acquire(key: PartitionAstKey): Lease? = synchronized(lock) {
        if (closed) return@synchronized null
        val entry = entries[key] ?: return@synchronized null
        entry.leases++
        Lease(entry.tree, key)
    }

    fun invalidate(predicate: (PartitionAstKey) -> Boolean) {
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val (key, entry) = iterator.next()
                if (!predicate(key)) continue
                iterator.remove()
                usedBytes -= entry.weight
                if (entry.leases == 0) entry.tree.close() else entry.evictWhenReleased = true
            }
        }
    }

    private fun evictUntilFitsLocked(incoming: Long) {
        val iterator = entries.entries.iterator()
        while (usedBytes + incoming > maxEstimatedBytes && iterator.hasNext()) {
            val entry = iterator.next().value
            iterator.remove()
            usedBytes -= entry.weight
            if (entry.leases == 0) entry.tree.close() else entry.evictWhenReleased = true
        }
    }

    private fun removeLocked(key: PartitionAstKey) {
        val previous = entries.remove(key) ?: return
        usedBytes -= previous.weight
        if (previous.leases == 0) previous.tree.close() else previous.evictWhenReleased = true
    }

    private fun releaseLocked(key: PartitionAstKey) {
        val entry = entries[key] ?: return
        check(entry.leases > 0) { "Unbalanced AST lease release" }
        entry.leases--
        if (entry.leases == 0 && entry.evictWhenReleased) {
            entries.remove(key)
            usedBytes -= entry.weight
            entry.tree.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.leases == 0) {
                    entry.tree.close()
                    iterator.remove()
                    usedBytes -= entry.weight
                } else {
                    entry.evictWhenReleased = true
                }
            }
        }
    }
}

/** One bounded result page; [nextStartUtf16] is null when the range is exhausted. */
data class CapturePage<T : Any>(
    val key: CaptureCacheKey,
    val pageIndex: Int,
    val startUtf16: Long,
    val endUtf16: Long,
    val captures: List<T>,
    val nextStartUtf16: Long?
) {
    init {
        require(pageIndex >= 0 && startUtf16 >= 0L && endUtf16 >= startUtf16)
        require(nextStartUtf16 == null || nextStartUtf16 >= endUtf16)
    }
}

/**
 * Versioned page cache for native Query/Regex/Monarch results. Pages are immutable
 * snapshots and are discarded automatically when any cache-key revision changes.
 */
class CapturePageCache<T : Any>(
    maxBytes: Long,
    private val weighCapture: (T) -> Long
) : AutoCloseable {
    private val cache = BoundedLruCache<CapturePageKey, CapturePage<T>>(
        maxBytes,
        { page -> 64L + page.captures.sumOf { weighCapture(it).coerceAtLeast(0L) } }
    )

    private data class CapturePageKey(val identity: CaptureCacheKey, val pageIndex: Int)

    fun get(identity: CaptureCacheKey, pageIndex: Int): CapturePage<T>? =
        cache.get(CapturePageKey(identity, pageIndex))

    fun put(page: CapturePage<T>) {
        cache.put(CapturePageKey(page.key, page.pageIndex), page)
    }

    fun invalidateDocumentVersion(version: Long) {
        cache.invalidate { key, _ -> key.identity.documentVersion != version }
    }

    fun invalidateGrammar(grammarRevision: Long) {
        cache.invalidate { key, _ -> key.identity.grammarRevision != grammarRevision }
    }

    fun invalidateTheme(themeRevision: Long) {
        cache.invalidate { key, _ -> key.identity.themeRevision != themeRevision }
    }

    val bytesUsed: Long get() = cache.bytesUsed
    val size: Int get() = cache.size
    override fun close() = cache.close()
}
