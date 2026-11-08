package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSQuery
import com.zerostudio.idea.treesitter.TSTree
import org.junit.Assert.*
import org.junit.Test

class TshEngineNativeResourceLifecycleTest {
    private class FakeLanguage : TSLanguage("fake", 1L)

    private class FakeQuery : TSQuery(1L) {
        var closeCalls = 0
        override fun close() {
            closeCalls++
        }
    }

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

    @Test
    fun engineCloseRetiresQueriesBeforeGrammarResourcesAndDefersActiveQuery() {
        val engine = TshEngine.create()
        val query = FakeQuery()
        var grammarLeaseCloseCalls = 0
        val id = TshObjectId(900L)
        engine.nativeQueryLeases.register(
            "engine-query",
            id,
            query,
            dependencies = listOf(AutoCloseable { grammarLeaseCloseCalls++ })
        )
        val active = engine.nativeQueryLeases.acquire(id)!!

        engine.close()

        assertEquals(0, query.closeCalls)
        assertEquals(0, grammarLeaseCloseCalls)
        assertNull(engine.nativeQueryLeases.acquire(id))

        active.close()
        assertEquals(1, query.closeCalls)
        assertEquals(1, grammarLeaseCloseCalls)
    }

    @Test
    fun engineCannotCreateQueryAdaptersAfterClose() {
        val engine = TshEngine.create()
        engine.close()
        try {
            engine.createTreeSitterQueryExecutor()
            fail("Expected a closed Engine to reject adapter creation")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun engineCannotOpenParserAfterClose() {
        val engine = TshEngine.create()
        val language = FakeLanguage()
        val handle = TshNativeResourceLeaseStore.Handle(TshObjectId(901L), language)
        val grammar = TshGrammarDescriptor("fake", "1", 15, "fake-fingerprint")

        engine.close()

        val result = engine.openTreeSitterParser(handle, grammar)
        assertTrue(result is TshResult.Failure)
        assertFalse(language.canAccess().not())
    }
}\n