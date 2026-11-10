package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSQuery
import com.zerostudio.idea.treesitter.TSTree
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun engineClosePermanentlyClosesExposedRegistries() {
        val engine = TshEngine.create()
        engine.close()
        try {
            engine.objects.register(Any(), "editor")
            fail("Expected closed object registry to reject registration")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        try {
            engine.providers.register(object : TshProvider {
                override val id = "late-provider"
                override val apiVersion = 1
                override val capabilities = TshCapabilitySet.EMPTY
                override fun supports(instance: Any, descriptor: TshObjectDescriptor) = true
            })
            fail("Expected closed provider registry to reject registration")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun engineCloseWaitsForAnInFlightProviderResolution() {
        val engine = TshEngine.create()
        engine.initialize()
        val host = Any()
        val descriptor = engine.objects.register(host, "editor")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        engine.providers.register(object : TshProvider {
            override val id = "blocking-provider"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.EMPTY
            override fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return instance === host
            }
        })
        val resolveThread = Thread {
            engine.resolveProviders(host, descriptor)
        }
        val closeThread = Thread {
            engine.close()
            closeFinished.countDown()
        }
        resolveThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        closeThread.start()
        assertFalse("close must wait for the in-flight operation", closeFinished.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        resolveThread.join(5000)
        closeThread.join(5000)
        assertEquals(TshEngineState.CLOSED, engine.state)
        assertEquals(0L, closeFinished.count)
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
        assertTrue(language.canAccess())
    }
}\n