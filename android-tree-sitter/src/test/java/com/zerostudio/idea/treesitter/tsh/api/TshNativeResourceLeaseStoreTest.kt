package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSTree
import org.junit.Assert.*
import org.junit.Test

class TshNativeResourceLeaseStoreTest {
    private class FakeTree : TSTree(1L) {
        var closeCalls = 0
        var onClose: (() -> Unit)? = null
        override fun close() {
            closeCalls++
            onClose?.invoke()
        }
    }

    @Test
    fun retireDefersTreeCloseUntilExecutionLeaseIsReleased() {
        val store = TshNativeResourceLeaseStore()
        val tree = FakeTree()
        val handle = store.register(TshObjectId(101L), tree)

        val lease = store.acquire(handle)!!
        assertEquals(1, store.activeLeaseCount(handle.objectId))

        assertTrue(store.retire(handle.objectId))
        assertNull(store.acquire(handle))
        assertEquals(0, tree.closeCalls)

        lease.close()
        lease.close()

        assertEquals(1, tree.closeCalls)
        assertEquals(0, store.activeLeaseCount(handle.objectId))
    }

    @Test
    fun duplicateRegistrationAndRetirementAreRejected() {
        val store = TshNativeResourceLeaseStore()
        val first = FakeTree()
        val handle = store.register(TshObjectId(102L), first)

        assertThrows(IllegalStateException::class.java) {
            store.register(handle.objectId, FakeTree())
        }
        assertTrue(store.retire(handle.objectId))
        assertFalse(store.retire(handle.objectId))
        assertEquals(1, first.closeCalls)
    }

    @Test
    fun retireAllClosesIdleResourcesAndDefersActiveResources() {
        val store = TshNativeResourceLeaseStore()
        val active = FakeTree()
        val idle = FakeTree()
        val activeHandle = store.register(TshObjectId(201L), active)
        store.register(TshObjectId(202L), idle)
        val lease = store.acquire(activeHandle)!!

        assertEquals(2, store.retireAll())
        assertEquals(0, active.closeCalls)
        assertEquals(1, idle.closeCalls)
        assertNull(store.acquire(activeHandle))

        lease.close()
        assertEquals(1, active.closeCalls)
    }
    @Test
    fun resourceDependenciesCloseAfterResourceAndLastLease() {
        val store = TshNativeResourceLeaseStore()
        val tree = FakeTree()
        var dependencyCloseCalls = 0
        tree.onClose = { assertEquals(0, dependencyCloseCalls) }
        val handle = store.register(
            TshObjectId(301L),
            tree,
            dependencies = listOf(AutoCloseable { dependencyCloseCalls++ })
        )
        val lease = store.acquire(handle)!!

        assertTrue(store.retire(handle.objectId))
        assertEquals(0, tree.closeCalls)
        assertEquals(0, dependencyCloseCalls)

        lease.close()
        assertEquals(1, tree.closeCalls)
        assertEquals(1, dependencyCloseCalls)
    }

}
