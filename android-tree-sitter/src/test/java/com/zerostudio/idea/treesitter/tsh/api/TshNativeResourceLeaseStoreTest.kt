package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSTree
import org.junit.Assert.*
import org.junit.Test

class TshNativeResourceLeaseStoreTest {
    private class FakeTree : TSTree(1L) {
        var closeCalls = 0
        override fun close() {
            closeCalls++
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
}
