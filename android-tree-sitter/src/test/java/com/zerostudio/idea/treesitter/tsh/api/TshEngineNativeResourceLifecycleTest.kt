package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSTree
import org.junit.Assert.*
import org.junit.Test

class TshEngineNativeResourceLifecycleTest {
    private class FakeTree : TSTree(1L) {
        var closeCalls = 0
        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun engineCloseRetiresOwnedNativeResources() {
        val engine = TshEngine.create()
        val tree = FakeTree()
        val registration = engine.nativeResources.register(tree, "syntax-tree")

        engine.close()

        assertEquals(TshEngineState.CLOSED, engine.state)
        assertEquals(1, tree.closeCalls)
        assertNull(engine.objects.find(registration.descriptor.id))
    }

    @Test
    fun engineCloseDefersNativeCloseUntilOutstandingLeaseIsReleased() {
        val engine = TshEngine.create()
        val tree = FakeTree()
        val registration = engine.nativeResources.register(tree, "syntax-tree")
        val lease = engine.nativeResources.acquire(registration.handle)!!

        engine.close()

        assertEquals(0, tree.closeCalls)
        assertNull(engine.nativeResources.acquire(registration.handle))

        lease.close()
        assertEquals(1, tree.closeCalls)
    }

    @Test
    fun closedEngineCannotPublishNewNativeResources() {
        val engine = TshEngine.create()
        engine.close()

        try {
            engine.nativeResources.register(FakeTree(), "syntax-tree")
            fail("Expected engine-owned resource registry to be closed")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}
