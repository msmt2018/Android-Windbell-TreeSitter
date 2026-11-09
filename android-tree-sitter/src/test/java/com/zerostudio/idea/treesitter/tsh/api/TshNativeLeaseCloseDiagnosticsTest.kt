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
    fun linkageErrorDoesNotPreventDependencyRelease() {
        val native = object : TSNativeObject(2L) {
            override fun closeNativeObj() {
                throw UnsatisfiedLinkError("simulated missing JNI library")
            }
        }
        val store = TshNativeResourceLeaseStore()
        val id = TshObjectId(78)
        var dependencyClosed = false
        store.register(id, native, listOf(AutoCloseable { dependencyClosed = true }))
        assertTrue(store.retire(id))
        assertTrue(dependencyClosed)
        assertEquals("TSH-NATIVE-LEASE-001", store.closeDiagnostics().single().code)
        assertTrue(store.closeDiagnostics().single().cause is UnsatisfiedLinkError)
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
    fun nativeResourceMetadataCanAdvanceWithoutChangingObjectIdentity() {
        val objects = TshObjectRegistry()
        val resources = TshNativeResourceRegistry(objects, TshNativeResourceLeaseStore())
        val registration = resources.register(FailingNative(), "fake-native")
        val updated = resources.updateMetadata(
            registration.handle,
            mapOf("documentId" to "doc", "documentVersion" to "12", "treeRevision" to "8")
        ) as TshResult.Success
        assertEquals(registration.descriptor.id, updated.value.id)
        assertEquals("12", objects.descriptor(registration.descriptor.id)?.metadata?.get("documentVersion"))
        assertTrue(resources.retire(registration.descriptor.id))
        assertEquals("TSH-NATIVE-LEASE-001", resources.closeDiagnostics().single().code)
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
