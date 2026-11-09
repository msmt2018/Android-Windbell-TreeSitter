package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshDslSchemaRepositoryTest {
    @Test
    fun schemaAppliesDefaultsAndValidatesTypedProperties() {
        val registry = TshSchemaRegistry()
        assertTrue(registry.register(TshDeclarationSchema(
            kind = TshDslDeclarationKind.FONT,
            properties = mapOf(
                "size" to TshPropertySchema(
                    name = "size",
                    type = TshDslType.MeasureType(setOf(TshDslUnit.SP, TshDslUnit.PX)),
                    required = true,
                    minimum = 1.0
                ),
                "weight" to TshPropertySchema(
                    name = "weight",
                    type = TshDslType.IntegerType,
                    defaultValue = TshDslValue.IntegerValue(400),
                    minimum = 100.0,
                    maximum = 900.0
                )
            )
        )) is TshResult.Success)

        val declaration = TshDslDeclaration(
            kind = TshDslDeclarationKind.FONT,
            id = "editor",
            properties = mapOf("size" to TshDslValue.MeasureValue(14.0, TshDslUnit.SP))
        )
        val result = registry.validate(declaration, TshDslVersion(1))
        assertTrue(result is TshResult.Success)
        val normalized = (result as TshResult.Success).value.normalizedProperties
        assertEquals(TshDslValue.IntegerValue(400), normalized["weight"])
        assertEquals(TshDslValue.MeasureValue(14.0, TshDslUnit.SP), normalized["size"])
    }

    @Test
    fun schemaRejectsWrongUnitsUnknownPropertiesAndMissingCapabilities() {
        val registry = TshSchemaRegistry()
        registry.register(TshDeclarationSchema(
            kind = TshDslDeclarationKind.STYLE,
            properties = mapOf(
                "fontSize" to TshPropertySchema(
                    name = "fontSize",
                    type = TshDslType.MeasureType(setOf(TshDslUnit.SP)),
                    required = true,
                    requiredCapability = "font.variable"
                )
            )
        ))
        val declaration = TshDslDeclaration(
            kind = TshDslDeclarationKind.STYLE,
            id = "keyword",
            properties = mapOf(
                "fontSize" to TshDslValue.MeasureValue(12.0, TshDslUnit.PX),
                "mystery" to TshDslValue.BooleanValue(true)
            )
        )
        val result = registry.validate(declaration, TshDslVersion(1))
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-SCHEMA-003" })
        assertTrue(result.diagnostics().any { it.code == "TSH-SCHEMA-007" })
    }

    @Test
    fun schemaRejectsMissingRequiredCapability() {
        val registry = TshSchemaRegistry()
        registry.register(TshDeclarationSchema(
            kind = TshDslDeclarationKind.STYLE,
            properties = mapOf(
                "fontSize" to TshPropertySchema(
                    name = "fontSize",
                    type = TshDslType.MeasureType(setOf(TshDslUnit.SP)),
                    required = true,
                    requiredCapability = "font.variable"
                )
            )
        ))
        val result = registry.validate(
            TshDslDeclaration(
                TshDslDeclarationKind.STYLE, "keyword",
                mapOf("fontSize" to TshDslValue.MeasureValue(12.0, TshDslUnit.SP))
            ),
            TshDslVersion(1)
        )
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-SCHEMA-011" })
    }

    @Test
    fun lenientModeDowngradesUnknownPropertiesToWarnings() {
        val registry = TshSchemaRegistry()
        registry.register(TshDeclarationSchema(
            kind = TshDslDeclarationKind.COLOR,
            properties = emptyMap()
        ))
        val result = registry.validate(
            TshDslDeclaration(
                TshDslDeclarationKind.COLOR, "accent",
                mapOf("vendorExtension" to TshDslValue.StringValue("x"))
            ),
            TshDslVersion(1),
            TshValidationMode.LENIENT
        )
        assertTrue(result is TshResult.Success)
        assertEquals(TshDiagnosticSeverity.WARNING, (result as TshResult.Success).diagnostics.single().severity)
    }

    @Test
    fun nullableIsIndependentFromOptionalTypeAndDefaultsAreValidated() {
        val registry = TshSchemaRegistry()
        registry.register(TshDeclarationSchema(
            kind = TshDslDeclarationKind.COLOR,
            properties = mapOf(
                "nullableText" to TshPropertySchema(
                    name = "nullableText", type = TshDslType.StringType, nullable = true
                ),
                "badDefault" to TshPropertySchema(
                    name = "badDefault", type = TshDslType.IntegerType,
                    defaultValue = TshDslValue.StringValue("not-an-int")
                )
            )
        ))
        val nullResult = registry.validate(
            TshDslDeclaration(TshDslDeclarationKind.COLOR, "accent",
                mapOf("nullableText" to TshDslValue.NullValue)),
            TshDslVersion(1)
        )
        assertTrue(nullResult is TshResult.Failure)
        assertTrue(nullResult.diagnostics().any { it.code == "TSH-SCHEMA-013" })
    }

    @Test
    fun ignorePolicyNeverSilentlyDropsUnknownProperties() {
        val registry = TshSchemaRegistry()
        registry.register(TshDeclarationSchema(
            TshDslDeclarationKind.COLOR, emptyMap(), TshSchemaUnknownPropertyPolicy.IGNORE
        ))
        val result = registry.validate(
            TshDslDeclaration(TshDslDeclarationKind.COLOR, "x",
                mapOf("extension" to TshDslValue.BooleanValue(true))),
            TshDslVersion(1)
        )
        assertTrue(result is TshResult.Success)
        assertEquals(TshDiagnosticSeverity.WARNING,
            (result as TshResult.Success).diagnostics.single().severity)
    }

    @Test
    fun measureRangesRequireAndEnforceAnExplicitUnit() {
        assertThrows(IllegalArgumentException::class.java) {
            TshPropertySchema(
                name = "size", type = TshDslType.MeasureType(setOf(TshDslUnit.SP, TshDslUnit.PX)),
                minimum = 1.0
            )
        }
        val registry = TshSchemaRegistry()
        registry.register(TshDeclarationSchema(
            TshDslDeclarationKind.FONT,
            mapOf("size" to TshPropertySchema(
                name = "size", type = TshDslType.MeasureType(setOf(TshDslUnit.SP, TshDslUnit.PX)),
                minimum = 1.0, rangeUnit = TshDslUnit.SP
            ))
        ))
        val result = registry.validate(
            TshDslDeclaration(TshDslDeclarationKind.FONT, "editor",
                mapOf("size" to TshDslValue.MeasureValue(12.0, TshDslUnit.PX))),
            TshDslVersion(1)
        )
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-SCHEMA-014" })
    }

    @Test
    fun configRepositoryUsesOptimisticRevisionReplacement() {
        val repository = TshConfigRepository()
        fun artifact(revision: String) = TshConfigArtifact(
            configId = "java",
            revision = revision,
            sourceId = "java.tsh",
            sourceText = "name: [\"Java\"]",
            document = TshDslDocument(TshDslVersion(1), listOf(
                TshDslDeclaration(TshDslDeclarationKind.LANGUAGE, "java", emptyMap())
            ))
        )

        assertTrue(repository.install(artifact("r1")) is TshResult.Success)
        assertEquals("r1", repository.get("java")?.revision)
        assertTrue(repository.replace(artifact("r2"), "r1") is TshResult.Success)
        assertTrue(repository.replace(artifact("r3"), "r1") is TshResult.Failure)
        assertEquals("r2", repository.get("java")?.revision)
        assertTrue(repository.unload("java", "r2") is TshResult.Success)
        assertNull(repository.get("java"))
    }
}
