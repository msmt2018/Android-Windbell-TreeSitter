package com.zerostudio.idea.treesitter.tsh.api

data class TshRuntimePropertyKey(
    val declarationKind: TshDslDeclarationKind,
    val propertyName: String
) {
    init { require(propertyName.isNotBlank()) }
}

data class TshRuntimePropertyContext(
    val declarationKind: TshDslDeclarationKind,
    val declarationId: String,
    val dslVersion: TshDslVersion
)

fun interface TshRuntimePropertyConsumer {
    /** Runs on the dispatcher's caller thread and must not retain mutable parser/AST objects. */
    fun apply(context: TshRuntimePropertyContext, value: TshDslValue): TshResult<Unit>
}

data class TshRuntimeConsumerRegistration(
    val id: String,
    val key: TshRuntimePropertyKey,
    val requiredCapabilities: TshCapabilitySet,
    val consumer: TshRuntimePropertyConsumer
) {
    init { require(id.isNotBlank()) }
}

/** Thread-safe registry; consumer callbacks are never invoked while holding its lock. */
class TshRuntimeConsumerRegistry {
    private val lock = Any()
    private val byKey = LinkedHashMap<TshRuntimePropertyKey, TshRuntimeConsumerRegistration>()
    private val byId = LinkedHashMap<String, TshRuntimeConsumerRegistration>()

    fun register(registration: TshRuntimeConsumerRegistration): TshResult<TshRuntimeConsumerRegistration> =
        synchronized(lock) {
            if (byId.containsKey(registration.id) || byKey.containsKey(registration.key)) {
                return@synchronized failure(
                    "TSH-DSL-RUNTIME-001",
                    "Runtime consumer id or property key is already registered"
                )
            }
            byId[registration.id] = registration
            byKey[registration.key] = registration
            TshResult.Success(registration)
        }

    fun unregister(id: String): Boolean = synchronized(lock) {
        val registration = byId.remove(id) ?: return@synchronized false
        byKey.remove(registration.key)
        true
    }

    fun find(key: TshRuntimePropertyKey): TshRuntimeConsumerRegistration? =
        synchronized(lock) { byKey[key] }

    fun all(): List<TshRuntimeConsumerRegistration> = synchronized(lock) {
        byId.values.toList()
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.SEMANTIC,
        message = message
    )))
}

data class TshRuntimeApplyReport(
    val declarationId: String,
    val appliedProperties: Set<String>,
    val unappliedProperties: Map<String, TshDslValue>,
    val diagnostics: List<TshDiagnostic>
) {
    val isComplete: Boolean get() = unappliedProperties.isEmpty() &&
        diagnostics.none { it.severity == TshDiagnosticSeverity.ERROR }
}

/**
 * Applies schema-normalized properties through explicit runtime consumers.
 * Missing consumers and consumer failures remain visible in the report; validation alone never
 * implies that a property has taken effect.
 */
class TshDslRuntimeDispatcher(
    private val consumers: TshRuntimeConsumerRegistry
) {
    fun apply(
        validation: TshSchemaValidationResult,
        availableCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY
    ): TshResult<TshRuntimeApplyReport> {
        val applied = LinkedHashSet<String>()
        val unapplied = LinkedHashMap<String, TshDslValue>()
        unapplied.putAll(validation.unappliedProperties)
        val diagnostics = ArrayList<TshDiagnostic>()
        diagnostics += validation.diagnostics

        if (!validation.isValid) {
            unapplied.putAll(validation.normalizedProperties)
            diagnostics += TshDiagnostic(
                code = "TSH-DSL-RUNTIME-002",
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.SEMANTIC,
                message = "Invalid schema result cannot be applied"
            )
            return TshResult.Success(
                TshRuntimeApplyReport(
                    validation.declaration.id, applied, unapplied.toMap(), diagnostics.toList()
                ),
                diagnostics.toList()
            )
        }

        val context = TshRuntimePropertyContext(
            validation.declaration.kind, validation.declaration.id, validation.dslVersion
        )
        for ((name, value) in validation.normalizedProperties) {
            val registration = consumers.find(TshRuntimePropertyKey(validation.declaration.kind, name))
            if (registration == null) {
                unapplied[name] = value
                diagnostics += TshDiagnostic(
                    code = "TSH-DSL-RUNTIME-003",
                    severity = TshDiagnosticSeverity.ERROR,
                    stage = TshDiagnosticStage.SEMANTIC,
                    message = "No runtime consumer is registered for ${validation.declaration.kind}.$name"
                )
                continue
            }
            val missingCapabilities = registration.requiredCapabilities.all().filterNot {
                availableCapabilities.satisfies(it)
            }
            if (missingCapabilities.isNotEmpty()) {
                unapplied[name] = value
                diagnostics += TshDiagnostic(
                    code = "TSH-DSL-RUNTIME-004",
                    severity = TshDiagnosticSeverity.ERROR,
                    stage = TshDiagnosticStage.SEMANTIC,
                    message = "Runtime consumer '${registration.id}' requires capabilities: ${missingCapabilities.joinToString { it.id }}"
                )
                continue
            }
            val result = try {
                registration.consumer.apply(context, value)
            } catch (error: Exception) {
                TshResult.Failure(listOf(TshDiagnostic(
                    code = "TSH-DSL-RUNTIME-005",
                    severity = TshDiagnosticSeverity.ERROR,
                    stage = TshDiagnosticStage.SEMANTIC,
                    message = "Runtime consumer '${registration.id}' threw an exception",
                    cause = error
                )))
            }
            when (result) {
                is TshResult.Success -> {
                    diagnostics += result.diagnostics
                    if (result.diagnostics.any { it.severity == TshDiagnosticSeverity.ERROR }) {
                        unapplied[name] = value
                    } else {
                        applied += name
                    }
                }
                is TshResult.Failure -> {
                    unapplied[name] = value
                    diagnostics += result.diagnostics
                }
            }
        }

        val report = TshRuntimeApplyReport(
            validation.declaration.id, applied, unapplied.toMap(), diagnostics.toList()
        )
        return TshResult.Success(report, diagnostics.toList())
    }
}
