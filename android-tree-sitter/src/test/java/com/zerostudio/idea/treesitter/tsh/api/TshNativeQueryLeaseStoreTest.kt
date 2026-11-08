package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSQuery
import org.junit.Assert.*
import org.junit.Test

class TshNativeQueryLeaseStoreTest {
    private class FakeQuery : TSQuery(1L) {
        var closeCalls = 0
        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun retirementDefersNativeCloseUntilLastLeaseIsReleased() {
        val store = TshNativeQueryLeaseStore()
        val query = FakeQuery()
        val id = TshObjectId(1L)
        store.register("q", id, query)

        val lease = store.acquire(id)!!
        assertEquals(1, store.activeLeaseCount(id))

        assertTrue(store.retire("q"))
        assertNull(store.acquire(id))
        assertEquals(0, query.closeCalls)

        lease.close()
        lease.close()

        assertEquals(1, query.closeCalls)
        assertEquals(0, store.activeLeaseCount(id))
    }

    @Test
    fun replacementRetiresOldRevisionWithoutClosingItsActiveLease() {
        val store = TshNativeQueryLeaseStore()
        val oldQuery = FakeQuery()
        val newQuery = FakeQuery()
        val oldId = TshObjectId(10L)
        val newId = TshObjectId(11L)

        store.register("same-query", oldId, oldQuery)
        val oldLease = store.acquire(oldId)!!
        store.register("same-query", newId, newQuery)

        assertNull(store.acquire(oldId))
        val newLease = store.acquire(newId)!!
        assertNotNull(newLease)
        assertEquals(0, oldQuery.closeCalls)

        newLease.close()
        oldLease.close()
        assertEquals(1, oldQuery.closeCalls)
        assertEquals(0, newQuery.closeCalls)
    }

    @Test
    fun retireAllClosesUnleasedQueriesAndDefersLeasedOnes() {
        val store = TshNativeQueryLeaseStore()
        val leased = FakeQuery()
        val idle = FakeQuery()
        val leasedId = TshObjectId(20L)
        val idleId = TshObjectId(21L)
        store.register("leased", leasedId, leased)
        store.register("idle", idleId, idle)
        val lease = store.acquire(leasedId)!!

        assertEquals(2, store.retireAll())
        assertEquals(0, leased.closeCalls)
        assertEquals(1, idle.closeCalls)

        lease.close()
        assertEquals(1, leased.closeCalls)
    }
}
