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
        val tree = FakeTree()
        val registration = registry.register(tree, "syntax-tree")

        assertNull(registry.acquire(registration.descriptor.id, TSQuery::class.java))
        assertNull(registry.acquire(TshNativeResourceLeaseStore.Handle(registration.descriptor.id, FakeTree())))
        val lease = registry.acquire(registration.descriptor.id, TSTree::class.java)
        assertNotNull(lease)
        lease!!.close()
        assertTrue(registry.retire(registration.descriptor.id))
    }
    @Test
    fun retireAllOnlyRetiresResourcesOwnedByThisCoordinator() {
        val objects = TshObjectRegistry()
        val leases = TshNativeResourceLeaseStore()
        val firstRegistry = TshNativeResourceRegistry(objects, leases)
        val secondRegistry = TshNativeResourceRegistry(objects, leases)
        val first = FakeTree()
        val second = FakeTree()
        val firstRegistration = firstRegistry.register(first, "syntax-tree")
        val secondRegistration = secondRegistry.register(second, "syntax-tree")
        val active = secondRegistry.acquire(secondRegistration.descriptor.id, TSTree::class.java)!!
        assertNull(firstRegistry.acquire(secondRegistration.descriptor.id, TSTree::class.java))

        assertEquals(1, firstRegistry.retireAll())
        assertNull(objects.find(firstRegistration.descriptor.id))
        assertNotNull(objects.find(secondRegistration.descriptor.id))
        assertEquals(1, first.closeCalls)
        assertEquals(0, second.closeCalls)

        active.close()
        assertEquals(0, second.closeCalls)
        assertTrue(secondRegistry.retire(secondRegistration.descriptor.id))
        assertEquals(1, second.closeCalls)
    }

}
