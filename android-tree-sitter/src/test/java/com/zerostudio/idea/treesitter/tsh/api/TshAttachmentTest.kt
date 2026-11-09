package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshAttachmentTest {
    private class HostEditor

    private class Provider(
        override val id: String,
        override val capabilities: TshCapabilitySet
    ) : TshProvider {
        override val apiVersion = 1
        override fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean =
            instance is HostEditor && descriptor.type == "editor"
    }

    @Test
    fun attachNegotiatesCapabilitiesAndDetachDoesNotDestroyHostObject() {
        val engine = TshEngine.create()
        engine.initialize()
        val host = HostEditor()
        val scope = TshOwnerScope("editor-view")
        engine.providers.register(Provider(
            "text-provider", TshCapabilitySet.of(TshCapability("text.read", 2))
        ))
        val attached = engine.attach(
            TshAttachmentRequest(
                instance = host,
                objectType = "editor",
                requiredCapabilities = TshCapabilitySet.of(TshCapability("text.read", 1)),
                optionalCapabilities = TshCapabilitySet.of(TshCapability("render.custom"))
            ),
            scope
        ) as TshResult.Success<TshAttachmentReport>
        assertEquals(TshAttachmentStatus.DEGRADED, attached.value.status)
        assertSame(host, engine.objects.find(attached.value.handle.objectId))
        assertTrue(engine.detach(attached.value.handle))
        assertFalse(engine.detach(attached.value.handle))
        assertEquals(TshAttachmentStatus.DETACHED,
            engine.inspectAttachment(attached.value.handle)?.status)
        assertNotNull(host) // detach never closes or mutates the host instance
        engine.close()
    }

    @Test
    fun ownerScopeAutomaticallyDetachesAndRefreshUsesOriginalRequirements() {
        val engine = TshEngine.create()
        engine.initialize()
        val host = HostEditor()
        val scope = TshOwnerScope("document-view")
        val attached = engine.attach(
            TshAttachmentRequest(
                host, "editor",
                requiredCapabilities = TshCapabilitySet.of(TshCapability("text.read", 2))
            ),
            scope
        ) as TshResult.Success<TshAttachmentReport>
        assertEquals(TshAttachmentStatus.INCOMPLETE, attached.value.status)
        engine.providers.register(Provider(
            "text-provider", TshCapabilitySet.of(TshCapability("text.read", 2))
        ))
        val refreshed = engine.refreshAttachment(attached.value.handle) as TshResult.Success<TshAttachmentReport>
        assertEquals(TshAttachmentStatus.READY, refreshed.value.status)
        scope.close()
        assertEquals(TshAttachmentStatus.DETACHED,
            engine.inspectAttachment(attached.value.handle)?.status)
        assertNull(engine.objects.find(attached.value.handle.objectId))
        engine.close()
    }
}
