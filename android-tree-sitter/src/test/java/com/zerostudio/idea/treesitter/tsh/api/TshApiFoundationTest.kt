package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshApiFoundationTest {
    @Test
    fun objectRegistryUsesInstanceIdentityAndSupportsUnregister() {
        val registry = TshObjectRegistry()
        val first = String(charArrayOf('e', 'd', 'i', 't', 'o', 'r'))
        val second = String(charArrayOf('e', 'd', 'i', 't', 'o', 'r'))
        assertEquals(first, second)
        assertNotSame(first, second)

        val descriptor = registry.register(
            first, "editor", TshCapabilitySet.of(TshCapability("text.read"))
        )
        assertSame(descriptor, registry.find(first))
        assertNull(registry.find(second))
        assertTrue(descriptor.capabilities.contains("text.read"))
        assertSame(first, registry.find(descriptor.id))
        assertTrue(registry.unregister(descriptor.id))
        assertEquals(0, registry.size())
    }

    @Test
    fun engineLifecycleIsExplicitAndReleasesRegistries() {
        val engine = TshEngine.create()
        assertEquals(TshEngineState.CREATED, engine.state)
        assertTrue(engine.initialize().isSuccess())
        engine.objects.register(Any(), "editor")
        assertEquals(1, engine.objects.size())

        engine.close()
        assertEquals(TshEngineState.CLOSED, engine.state)
        assertEquals(0, engine.objects.size())
        assertTrue(engine.initialize() is TshResult.Failure)
    }

    @Test
    fun providerDiscoveryUsesRegisteredCapabilityProvider() {
        val provider = object : TshProvider {
            override val id = "test.canvas"
            override val apiVersion = 1
            override val capabilities = TshCapabilitySet.of(TshCapability("render.canvas"))
            override fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean =
                descriptor.type == "canvas-target"
        }
        val target = Any()
        val descriptor = TshObjectRegistry().register(target, "canvas-target")
        val registry = TshProviderRegistry()
        registry.register(provider)
        assertEquals(listOf(provider), registry.findFor(target, descriptor))
    }
}
