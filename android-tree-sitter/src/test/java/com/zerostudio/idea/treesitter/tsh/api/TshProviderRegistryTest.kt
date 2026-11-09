package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshProviderRegistryTest {
    private class Provider(
        override val id: String,
        override val apiVersion: Int = 1,
        override val priority: Int = 0,
        override val capabilities: TshCapabilitySet,
        private val support: (Any, TshObjectDescriptor) -> Boolean = { _, _ -> true }
    ) : TshProvider {
        override fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean = support(instance, descriptor)
    }

    private fun descriptor() = TshObjectDescriptor(
        TshObjectId(1), "editor", TshCapabilitySet.EMPTY, registeredAtNanos = 1
    )

    @Test
    fun selectsHighestPriorityProviderAndReportsMissingOptionalCapability() {
        val registry = TshProviderRegistry()
        val low = Provider("low", priority = 1, capabilities = TshCapabilitySet.of(TshCapability("text.read")))
        val high = Provider("high", priority = 10, capabilities = TshCapabilitySet.of(TshCapability("text.read")))
        registry.register(low)
        registry.register(high)
        val result = registry.resolveFor(
            Any(), descriptor(),
            required = TshCapabilitySet.of(TshCapability("text.read")),
            optional = TshCapabilitySet.of(TshCapability("render.custom"))
        )
        assertTrue(result.isSatisfiable)
        assertEquals(listOf("high"), result.selectedProviders.map { it.id })
        assertEquals(setOf("render.custom"), result.missingOptionalCapabilities)
        assertEquals(TshDegradationLevel.OPTIONAL_CAPABILITIES_MISSING, result.degradationLevel)
    }

    @Test
    fun rejectsOldCapabilityAndOutOfRangeProviderApi() {
        val registry = TshProviderRegistry()
        registry.register(Provider(
            "old", apiVersion = 1,
            capabilities = TshCapabilitySet.of(TshCapability("text.read", version = 1))
        ))
        val result = registry.resolveFor(
            Any(), descriptor(),
            required = TshCapabilitySet.of(TshCapability("text.read", version = 2)),
            apiRange = TshProviderApiRange(2, 3)
        )
        assertFalse(result.isSatisfiable)
        assertTrue(result.missingRequiredCapabilities.contains("text.read"))
        assertTrue(result.rejectedProviders.any { it.reason.contains("API version") })
    }

    @Test
    fun selectedProviderCannotAccidentallySatisfyHigherRequiredCapabilityVersion() {
        val registry = TshProviderRegistry()
        registry.register(Provider(
            "editor-provider",
            capabilities = TshCapabilitySet.of(
                TshCapability("editor.attach", 1),
                TshCapability("text.read", 1)
            )
        ))
        val result = registry.resolveFor(
            Any(), descriptor(),
            required = TshCapabilitySet.of(TshCapability("text.read", 2)),
            optional = TshCapabilitySet.of(TshCapability("editor.attach", 1))
        )
        assertFalse(result.isSatisfiable)
        assertTrue(result.missingRequiredCapabilities.contains("text.read"))
    }

    @Test
    fun reportsNoCompatibleProviderAsItsOwnDegradationLevel() {
        val registry = TshProviderRegistry()
        registry.register(Provider(
            "unrelated",
            capabilities = TshCapabilitySet.of(TshCapability("render.color"))
        ) { _, _ -> false })
        val result = registry.resolveFor(
            Any(), descriptor(),
            required = TshCapabilitySet.of(TshCapability("text.snapshot"))
        )
        assertFalse(result.isSatisfiable)
        assertEquals(TshDegradationLevel.NO_COMPATIBLE_PROVIDER, result.degradationLevel)
    }

    @Test
    fun isolatesSupportsExceptionsAndReportsThem() {
        val registry = TshProviderRegistry()
        registry.register(Provider("broken", capabilities = TshCapabilitySet.of(TshCapability("text.read"))) {
            _, _ -> error("provider failure")
        })
        val result = registry.resolveFor(
            Any(), descriptor(), required = TshCapabilitySet.of(TshCapability("text.read"))
        )
        assertFalse(result.isSatisfiable)
        assertTrue(result.diagnostics.any { it.code == "TSH-PROVIDER-001" })
    }
}
