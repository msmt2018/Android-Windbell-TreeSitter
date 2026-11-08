package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSQuery
import com.zerostudio.idea.treesitter.TSTree
import org.junit.Assert.*
import org.junit.Test

class TshNativeResourceRegistryTest {
    private class FakeTree : TSTree(1L) {
        var closeCalls = 0
        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun registerAcquireAndRetireCoordinateObjectAndNativeOwnership() {
        val objects = TshObjectRegistry()
        val leases = TshNativeResourceLeaseStore()
        val registry = TshNativeResourceRegistry(objects, leases)
        val tree = FakeTree()
        val registration = registry.register(tree, "syntax-tree")

        assertSame(tree, objects.find(registration.descriptor.id))
        val lease = registry.acquire(registration.descriptor.id, TSTree::class.java)!!
        assertEquals(1, registry.activeLeaseCount(registration.descriptor.id))

        assertTrue(registry.retire(registration.descriptor.id))
        assertNull(objects.find(registration.descriptor.id))
        assertNull(registry.acquire(registration.descriptor.id, TSTree::class.java))
        assertEquals(0, tree.closeCalls)

        lease.close()
        assertEquals(1, tree.closeCalls)
        assertFalse(registry.retire(registration.descriptor.id))
    }

    @Test
    fun acquireRejectsWrongNativeType() {
        val objects = TshObjectRegistry()
        val leases = TshNativeResourceLeaseStore()
        val registry = TshNativeResourceRegistry(objects, leases)
        val registration = registry.register(FakeTree(), "syntax-tree")

        assertNull(registry.acquire(registration.descriptor.id, TSQuery::class.java))
        val lease = registry.acquire(registration.descriptor.id, TSTree::class.java)
        assertNotNull(lease)
        lease!!.close()
        assertTrue(registry.retire(registration.descriptor.id))
    }
}
