package com.zerostudio.idea.treesitter.largefile

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptureAndByteBudgetCacheTest {

    @Test fun byteLruUsesFrozenWeightsAndDoesNotOverflow() {
        data class Weighted(var bytes: Long)
        val evicted = mutableListOf<Weighted>()
        val cache = ByteBudgetLruCache<String, Weighted>(
            Long.MAX_VALUE,
            { it.bytes },
            { evicted += it }
        )

        val huge = Weighted(Long.MAX_VALUE)
        cache.put("huge", huge)
        assertEquals(Long.MAX_VALUE, cache.bytesUsed)

        // This admission must evict before adding; Long.MAX_VALUE + 1 must
        // never wrap negative and accidentally bypass the budget.
        val small = Weighted(1L)
        cache.put("small", small)
        assertEquals(1L, cache.bytesUsed)
        assertNull(cache.get("huge"))
        assertSame(huge, evicted.single())

        // Mutating a cached value cannot change the weight charged at admission.
        small.bytes = Long.MAX_VALUE
        assertSame(small, cache.remove("small"))
        assertEquals(0L, cache.bytesUsed)
        cache.close()
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
