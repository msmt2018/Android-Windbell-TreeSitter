package com.zerostudio.idea.treesitter.tsh.api

/** Extension contract for host/editor capabilities. */
interface TshProvider {
    val id: String
    val apiVersion: Int
    val capabilities: TshCapabilitySet
    /** Higher values win when providers offer the same capability. */
    val priority: Int get() = 0

    /** True only when this provider can safely operate on this exact instance. */
    fun supports(instance: Any, descriptor: TshObjectDescriptor): Boolean
}

data class TshProviderApiRange(val minimumInclusive: Int = 1, val maximumInclusive: Int = Int.MAX_VALUE) {
    init {
        require(minimumInclusive > 0 && maximumInclusive >= minimumInclusive)
    }
    fun contains(version: Int): Boolean = version in minimumInclusive..maximumInclusive
}

enum class TshDegradationLevel { NONE, OPTIONAL_CAPABILITIES_MISSING, REQUIRED_CAPABILITIES_MISSING, NO_COMPATIBLE_PROVIDER }

data class TshProviderRejection(val providerId: String, val reason: String)

data class TshProviderResolution(
    val selectedProviders: List<TshProvider>,
    val rejectedProviders: List<TshProviderRejection>,
    val missingRequiredCapabilities: Set<String>,
    val missingOptionalCapabilities: Set<String>,
    val incompatibleCapabilities: Set<String>,
    val diagnostics: List<TshDiagnostic>,
    val degradationLevel: TshDegradationLevel
) {
    val isSatisfiable: Boolean get() = missingRequiredCapabilities.isEmpty()
}

class TshProviderRegistry {
    private val lock = Any()
    private val providers = LinkedHashMap<String, TshProvider>()

    fun register(provider: TshProvider) {
        require(provider.id.isNotBlank()) { "provider id must not be blank" }
        require(provider.apiVersion > 0) { "provider apiVersion must be positive" }
        synchronized(lock) {
            val previous = providers[provider.id]
            require(previous == null || previous === provider) {
                "A different provider already uses this id"
            }
            providers[provider.id] = provider
        }
    }

    fun unregister(id: String): Boolean = synchronized(lock) {
        providers.remove(id) != null
    }

    /** Compatibility helper. A faulty provider is excluded rather than crashing discovery. */
    fun findFor(instance: Any, descriptor: TshObjectDescriptor): List<TshProvider> {
        val snapshot = synchronized(lock) { providers.values.toList() }
        return snapshot.filter { provider ->
            try { provider.supports(instance, descriptor) } catch (_: Throwable) { false }
        }
    }

    /**
     * Resolves capabilities deterministically. Third-party code is always called outside
     * the registry lock. Capability versions are minimum required versions.
     */
    fun resolveFor(
        instance: Any,
        descriptor: TshObjectDescriptor,
        required: TshCapabilitySet = TshCapabilitySet.EMPTY,
        optional: TshCapabilitySet = TshCapabilitySet.EMPTY,
        apiRange: TshProviderApiRange = TshProviderApiRange()
    ): TshProviderResolution {
        val snapshot = synchronized(lock) { providers.values.toList() }
        val diagnostics = ArrayList<TshDiagnostic>()
        val rejected = ArrayList<TshProviderRejection>()
        val compatible = ArrayList<TshProvider>()

        for (provider in snapshot) {
            if (!apiRange.contains(provider.apiVersion)) {
                rejected += TshProviderRejection(provider.id, "API version ${provider.apiVersion} is outside ${apiRange.minimumInclusive}..${apiRange.maximumInclusive}")
                continue
            }
            val supported = try {
                provider.supports(instance, descriptor)
            } catch (error: Throwable) {
                rejected += TshProviderRejection(provider.id, "supports() threw ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                diagnostics += TshDiagnostic(
                    code = "TSH-PROVIDER-001",
                    severity = TshDiagnosticSeverity.WARNING,
                    stage = TshDiagnosticStage.PROVIDER,
                    message = "Provider '${provider.id}' failed supports() and was excluded",
                    objectId = descriptor.id,
                    cause = error
                )
                false
            }
            if (supported) compatible += provider
            else if (rejected.none { it.providerId == provider.id }) {
                rejected += TshProviderRejection(provider.id, "Provider does not support this object instance")
            }
        }

        val requested = required.all().associateBy { it.id }
        val optionalRequested = optional.all().associateBy { it.id }
        val candidates = compatible.sortedWith(
            compareByDescending<TshProvider> { it.priority }.thenBy { it.id }
        )
        val selected = LinkedHashMap<String, TshProvider>()
        val incompatible = LinkedHashSet<String>()

        for ((id, requirement) in requested + optionalRequested) {
            val matching = candidates.filter { provider ->
                val capability = provider.capabilities.get(id)
                capability != null && capability.version >= requirement.version
            }
            if (matching.isEmpty()) {
                val hasOlder = candidates.any { it.capabilities.get(id) != null }
                if (hasOlder) incompatible += id
                continue
            }
            val winner = matching.first()
            selected[winner.id] = winner
            val tied = matching.filter { it.priority == winner.priority }
            if (tied.size > 1) {
                diagnostics += TshDiagnostic(
                    code = "TSH-PROVIDER-002",
                    severity = TshDiagnosticSeverity.WARNING,
                    stage = TshDiagnosticStage.PROVIDER,
                    message = "Capability '$id' has equal-priority providers ${tied.joinToString { it.id }}; selected '${winner.id}' by stable provider-id order",
                    objectId = descriptor.id
                )
            }
        }

        val selectedCaps = selected.values.flatMap { it.capabilities.all() }.associateBy { it.id }
        val missingRequired = requested.keys - selectedCaps.keys
        val missingOptional = optionalRequested.keys - selectedCaps.keys
        if (missingRequired.isNotEmpty()) diagnostics += TshDiagnostic(
            code = "TSH-PROVIDER-003",
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.PROVIDER,
            message = "Required provider capabilities are missing: ${missingRequired.sorted().joinToString()}",
            objectId = descriptor.id
        )
        if (missingOptional.isNotEmpty()) diagnostics += TshDiagnostic(
            code = "TSH-PROVIDER-004",
            severity = TshDiagnosticSeverity.WARNING,
            stage = TshDiagnosticStage.PROVIDER,
            message = "Optional provider capabilities are missing: ${missingOptional.sorted().joinToString()}",
            objectId = descriptor.id
        )
        if (incompatible.isNotEmpty()) diagnostics += TshDiagnostic(
            code = "TSH-PROVIDER-005",
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.PROVIDER,
            message = "No provider satisfies required capability versions: ${incompatible.sorted().joinToString()}",
            objectId = descriptor.id
        )

        val level = when {
            missingRequired.isNotEmpty() -> TshDegradationLevel.REQUIRED_CAPABILITIES_MISSING
            missingOptional.isNotEmpty() -> TshDegradationLevel.OPTIONAL_CAPABILITIES_MISSING
            requested.isEmpty() && optionalRequested.isEmpty() -> TshDegradationLevel.NONE
            compatible.isEmpty() -> TshDegradationLevel.NO_COMPATIBLE_PROVIDER
            else -> TshDegradationLevel.NONE
        }
        return TshProviderResolution(
            selected.values.toList(), rejected, missingRequired, missingOptional,
            incompatible, diagnostics, level
        )
    }

    fun all(): List<TshProvider> = synchronized(lock) { providers.values.toList() }
    fun clear() = synchronized(lock) { providers.clear() }
}
