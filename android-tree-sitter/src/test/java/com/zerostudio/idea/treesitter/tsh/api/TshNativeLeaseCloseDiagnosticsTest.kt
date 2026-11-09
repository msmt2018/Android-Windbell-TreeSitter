package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSNativeObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshNativeLeaseCloseDiagnosticsTest {
    private class FailingNative : TSNativeObject(1L) {
        override fun closeNativeObj() {
            throw IllegalStateException("simulated native close failure")
        }
    }

    @Test
    fun nativeResourceCloseFailureIsRetainedAsDiagnostic() {
        val store = TshNativeResourceLeaseStore()
        val id = TshObjectId(77)
        store.register(id, FailingNative())
        assertTrue(store.retire(id))
        val diagnostic = store.closeDiagnostics().single()
        assertEquals("TSH-NATIVE-LEASE-001", diagnostic.code)
        assertEquals(id, diagnostic.objectId)
        assertNotNull(diagnostic.cause)
    }

    @Test
    fun exclusiveLeasePreventsConcurrentNativeReadersAndMutations() {
        val store = TshNativeResourceLeaseStore()
        val id = TshObjectId(88)
        val handle = store.register(id, FailingNative())
        val reader = store.acquire(handle)!!
        assertNull(store.acquireExclusive(handle))
        reader.close()

        val writer = store.acquireExclusive(handle)!!
        assertNull(store.acquire(handle))
        writer.close()

        val nextReader = store.acquire(handle)!!
        assertTrue(store.retire(id))
        assertNull(store.acquire(handle))
        nextReader.close()
        assertEquals(1, store.closeDiagnostics().size)
    }

    @Test
    fun ownerScopeReportsCleanupFailuresInsteadOfSilentlySwallowingThem() {
        val scope = TshOwnerScope("failing-scope")
        scope.registerCleanup { error("simulated cleanup failure") }
        val diagnostics = scope.closeWithReport()
        assertEquals("TSH-OWNER-001", diagnostics.single().code)
        assertEquals(diagnostics, scope.closeDiagnostics())
    }
}
