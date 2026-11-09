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
    fun multipleScopedAttachmentsShareOneHostObjectUntilTheLastDetach() {
        val engine = TshEngine.create()
        engine.initialize()
        val host = HostEditor()
        val firstScope = TshOwnerScope("editor-role")
        val secondScope = TshOwnerScope("renderer-role")
        val request = TshAttachmentRequest(host, "editor")

        val first = (engine.attach(request, firstScope) as TshResult.Success).value
        val second = (engine.attach(request, secondScope) as TshResult.Success).value
        assertEquals(first.handle.objectId, second.handle.objectId)
        assertEquals(1, engine.objects.size())

        assertTrue(engine.detach(first.handle))
        assertNotNull(engine.objects.find(host))
        assertTrue(engine.detach(second.handle))
        assertNull(engine.objects.find(host))
        firstScope.close()
        secondScope.close()
        engine.close()
    }

    @Test
    fun externallyRegisteredHostObjectRemainsRegisteredAfterAttachmentDetach() {
        val engine = TshEngine.create()
        engine.initialize()
        val host = HostEditor()
        val original = engine.objects.register(host, "editor")
        val scope = TshOwnerScope("external-owner")
        val attached = (engine.attach(
            TshAttachmentRequest(host, "editor"), scope
        ) as TshResult.Success).value

        engine.detach(attached.handle)

        assertEquals(original.id, engine.objects.find(host)?.id)
        assertEquals(1, engine.objects.size())
        scope.close()
        engine.close()
    }

    @Test
    fun strictAttachRejectsAndRollsBackMissingRequiredCapabilities() {
        val engine = TshEngine.create()
        engine.initialize()
        val host = HostEditor()
        val scope = TshOwnerScope("strict-editor")
        val result = engine.attach(
            TshAttachmentRequest(
                host, "editor",
                requiredCapabilities = TshCapabilitySet.of(TshCapability("text.read", 1))
            ),
            scope
        )
        assertTrue(result is TshResult.Failure)
        assertNull(engine.objects.find(host))
        assertEquals(0, engine.objects.size())
        scope.close()
        engine.close()
    }

    @Test
    fun ownerScopeAutomaticallyDetachesAndRefreshUsesOriginalRequirements() {
        val engine = TshEngine.create(TshEngineOptions(strictProviderValidation = false))
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
