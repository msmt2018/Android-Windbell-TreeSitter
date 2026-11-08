package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PartitionCacheTest {
    private fun key(version: Long = 1L, index: Int = 0) = PartitionAstKey(
        version, index, index * 100L, (index + 1L) * 100L,
        (index * 100L - 20L).coerceAtLeast(0L), (index + 1L) * 100L + 20L, 1L
    )

    @Test fun astLeaseKeepsEvictedTreeAliveUntilRelease() {
        val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
        val tree = parser.parseString("class A {}")!!
        val cache = PartitionAstCache(1024, { 100L })
        assertTrue(cache.put(key(), tree, 10))
        val lease = cache.acquire(key())!!
        cache.invalidate { true }
        assertEquals(0, cache.size)
        // The lease remains valid through invalidation; releasing it owns final close.
        assertNotNull(lease.tree.rootNode)
        lease.close()
        lease.close()
        cache.close()
        parser.close()
    }

    @Test fun capturePagesAreVersionedAndBudgeted() {
        val cache = CapturePageCache<String>(300) { it.length.toLong() }
        val identity = CaptureCacheKey(1, 0, 0, 100, 2, 3)
        cache.put(CapturePage(identity, 0, 0, 50, listOf("one", "two"), 50))
        assertEquals(listOf("one", "two"), cache.get(identity, 0)?.captures)
        cache.invalidateGrammar(9)
        assertNull(cache.get(identity, 0))
        cache.put(CapturePage(identity, 1, 50, 100, listOf("capture".repeat(100)), null))
        assertTrue(cache.bytesUsed <= 300)
        cache.close()
        assertEquals(0, cache.bytesUsed)
    }
}
