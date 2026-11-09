package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshDslRuntimeDispatcherTest {
    @Test
    fun schemaValidDoesNotMeanRuntimeAppliedWithoutConsumer() {
        val schemas = TshSchemaRegistry()
        schemas.register(TshDeclarationSchema(
            TshDslDeclarationKind.COLOR,
            mapOf(
                "foreground" to TshPropertySchema("foreground", TshDslType.ColorType),
                "background" to TshPropertySchema("background", TshDslType.ColorType)
            )
        ))
        val declaration = TshDslDeclaration(
            TshDslDeclarationKind.COLOR, "editor",
            mapOf(
                "foreground" to TshDslValue.ColorValue(0xFFFFFFFFu),
                "background" to TshDslValue.ColorValue(0xFF000000u)
            )
        )
        val validation = (schemas.validate(declaration, TshDslVersion(2)) as TshResult.Success).value
        assertTrue(validation.isValid)
        assertEquals(TshDslVersion(2), validation.dslVersion)

        val consumers = TshRuntimeConsumerRegistry()
        var appliedColor: TshDslValue? = null
        assertTrue(consumers.register(TshRuntimeConsumerRegistration(
            id = "color.foreground",
            key = TshRuntimePropertyKey(TshDslDeclarationKind.COLOR, "foreground"),
            requiredCapabilities = TshCapabilitySet.EMPTY,
            consumer = TshRuntimePropertyConsumer { _, value ->
                appliedColor = value
                TshResult.Success(Unit)
            }
        )) is TshResult.Success)

        val report = (TshDslRuntimeDispatcher(consumers).apply(validation) as TshResult.Success).value
        assertEquals(TshDslValue.ColorValue(0xFFFFFFFFu), appliedColor)
        assertEquals(setOf("foreground"), report.appliedProperties)
        assertEquals(setOf("background"), report.unappliedProperties.keys)
        assertFalse(report.isComplete)
        assertTrue(report.diagnostics.any { it.code == "TSH-DSL-RUNTIME-003" })
    }

    @Test
    fun consumerCapabilityAndRuntimeFailureAreObservable() {
        val schemas = TshSchemaRegistry()
        schemas.register(TshDeclarationSchema(
            TshDslDeclarationKind.COLOR,
            mapOf("foreground" to TshPropertySchema("foreground", TshDslType.ColorType))
        ))
        val validation = (schemas.validate(
            TshDslDeclaration(
                TshDslDeclarationKind.COLOR, "editor",
                mapOf("foreground" to TshDslValue.ColorValue(0xFFFFFFFFu))
            ), TshDslVersion(1)
        ) as TshResult.Success).value
        val consumers = TshRuntimeConsumerRegistry()
        consumers.register(TshRuntimeConsumerRegistration(
            "color.consumer",
            TshRuntimePropertyKey(TshDslDeclarationKind.COLOR, "foreground"),
            TshCapabilitySet.of(TshCapability("render.color", 2)),
            TshRuntimePropertyConsumer { _, _ ->
                TshResult.Failure(listOf(TshDiagnostic(
                    "TEST-CONSUMER-001", TshDiagnosticSeverity.ERROR,
                    TshDiagnosticStage.SEMANTIC, "simulated consumer failure"
                )))
            }
        ))
        val report = (TshDslRuntimeDispatcher(consumers).apply(
            validation, TshCapabilitySet.of(TshCapability("render.color", 1))
        ) as TshResult.Success).value
        assertTrue(report.appliedProperties.isEmpty())
        assertTrue(report.unappliedProperties.containsKey("foreground"))
        assertTrue(report.diagnostics.any { it.code == "TSH-DSL-RUNTIME-004" })

        val consumerFailure = (TshDslRuntimeDispatcher(consumers).apply(
            validation, TshCapabilitySet.of(TshCapability("render.color", 2))
        ) as TshResult.Success).value
        assertTrue(consumerFailure.appliedProperties.isEmpty())
        assertTrue(consumerFailure.diagnostics.any { it.code == "TEST-CONSUMER-001" })
    }
}
