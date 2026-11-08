package com.zerostudio.idea.treesitter.tsh.api

sealed class TshDslType {
    object AnyType : TshDslType()
    object BooleanType : TshDslType()
    object IntegerType : TshDslType()
    object DecimalType : TshDslType()
    object NumberType : TshDslType()
    object StringType : TshDslType()
    object IdentifierType : TshDslType()
    object ColorType : TshDslType()
    object FontFamilyType : TshDslType()
    data class MeasureType(val units: Set<TshDslUnit>) : TshDslType()
    object RegexType : TshDslType()
    object QueryType : TshDslType()
    data class ReferenceType(val targetKind: TshDslDeclarationKind) : TshDslType()
    data class ArrayType(val elementType: TshDslType) : TshDslType()
    data class MapType(val valueType: TshDslType) : TshDslType()
    data class OptionalType(val valueType: TshDslType) : TshDslType()
    data class UnionType(val alternatives: List<TshDslType>) : TshDslType() {
        init { require(alternatives.isNotEmpty()) { "Union requires at least one alternative" } }
    }
}

enum class TshSchemaUnknownPropertyPolicy { ERROR, WARNING, IGNORE }

data class TshPropertySchema(
    val name: String,
    val type: TshDslType,
    val required: Boolean = false,
    val nullable: Boolean = false,
    val defaultValue: TshDslValue? = null,
    val allowedValues: Set<TshDslValue> = emptySet(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val inheritable: Boolean = false,
    val mergePolicy: TshAttributeMergePolicy = TshAttributeMergePolicy.REPLACE,
    val introducedIn: TshDslVersion = TshDslVersion(1),
    val deprecatedIn: TshDslVersion? = null,
    val requiredCapability: String? = null
) {
    init {
        require(name.isNotBlank()) { "Property schema name must not be blank" }
        require(minimum == null || maximum == null || minimum <= maximum) {
            "Property minimum must not exceed maximum"
        }
        require(requiredCapability == null || requiredCapability.isNotBlank())
    }
}

data class TshDeclarationSchema(
    val kind: TshDslDeclarationKind,
    val properties: Map<String, TshPropertySchema>,
    val unknownPropertyPolicy: TshSchemaUnknownPropertyPolicy = TshSchemaUnknownPropertyPolicy.ERROR
) {
    init {
        require(properties.all { (key, value) -> key == value.name }) {
            "Property schema map keys must match property names"
        }
    }
}

enum class TshValidationMode { STRICT, LENIENT }

data class TshSchemaValidationResult(
    val declaration: TshDslDeclaration,
    val normalizedProperties: Map<String, TshDslValue>,
    val diagnostics: List<TshDiagnostic>
) {
    val isValid: Boolean
        get() = diagnostics.none { it.severity == TshDiagnosticSeverity.ERROR }
}

/** Thread-safe registry. Unknown DSL properties are never silently treated as supported. */
class TshSchemaRegistry {
    private val lock = Any()
    private val schemas = LinkedHashMap<TshDslDeclarationKind, TshDeclarationSchema>()

    fun register(schema: TshDeclarationSchema, replace: Boolean = false): TshResult<TshDeclarationSchema> =
        synchronized(lock) {
            val existing = schemas[schema.kind]
            if (existing != null && !replace) {
                return@synchronized failure("TSH-SCHEMA-001", "A schema for \${schema.kind} is already registered")
            }
            schemas[schema.kind] = schema
            TshResult.Success(schema)
        }

    fun get(kind: TshDslDeclarationKind): TshDeclarationSchema? =
        synchronized(lock) { schemas[kind] }

    fun list(): List<TshDeclarationSchema> = synchronized(lock) { schemas.values.toList() }
    fun unregister(kind: TshDslDeclarationKind): Boolean = synchronized(lock) { schemas.remove(kind) != null }

    fun validate(
        declaration: TshDslDeclaration,
        version: TshDslVersion,
        mode: TshValidationMode = TshValidationMode.STRICT,
        availableCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY
    ): TshResult<TshSchemaValidationResult> {
        val schema = get(declaration.kind)
            ?: return failure("TSH-SCHEMA-002", "No schema registered for \${declaration.kind}")
        val diagnostics = ArrayList<TshDiagnostic>()
        val normalized = LinkedHashMap<String, TshDslValue>()

        for ((name, value) in declaration.properties) {
            val property = schema.properties[name]
            if (property == null) {
                val severity = when (schema.unknownPropertyPolicy) {
                    TshSchemaUnknownPropertyPolicy.ERROR -> TshDiagnosticSeverity.ERROR
                    TshSchemaUnknownPropertyPolicy.WARNING -> TshDiagnosticSeverity.WARNING
                    TshSchemaUnknownPropertyPolicy.IGNORE -> null
                }
                if (severity != null) diagnostics += diagnostic(
                    "TSH-SCHEMA-003", severity, "Unknown property '\$name' in \${declaration.kind}"
                )
                continue
            }
            if (version < property.introducedIn) {
                diagnostics += diagnostic("TSH-SCHEMA-004", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' requires DSL \${property.introducedIn} or newer")
                continue
            }
            if (property.deprecatedIn != null && version >= property.deprecatedIn) {
                diagnostics += diagnostic("TSH-SCHEMA-005", TshDiagnosticSeverity.WARNING,
                    "Property '\$name' is deprecated since DSL \${property.deprecatedIn}")
            }
            if (value === TshDslValue.NullValue && !property.nullable) {
                diagnostics += diagnostic("TSH-SCHEMA-006", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' does not accept null")
                continue
            }
            if (!matches(value, property.type)) {
                diagnostics += diagnostic("TSH-SCHEMA-007", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' does not match declared type \${property.type}")
                continue
            }
            if (property.allowedValues.isNotEmpty() && value !in property.allowedValues) {
                diagnostics += diagnostic("TSH-SCHEMA-008", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' has a value outside its allowed set")
                continue
            }
            val numeric = numericValue(value)
            if (numeric != null && property.minimum != null && numeric < property.minimum) {
                diagnostics += diagnostic("TSH-SCHEMA-009", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' is below minimum \${property.minimum}")
                continue
            }
            if (numeric != null && property.maximum != null && numeric > property.maximum) {
                diagnostics += diagnostic("TSH-SCHEMA-010", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' exceeds maximum \${property.maximum}")
                continue
            }
            if (property.requiredCapability != null && !availableCapabilities.contains(property.requiredCapability)) {
                diagnostics += diagnostic("TSH-SCHEMA-011", TshDiagnosticSeverity.ERROR,
                    "Property '\$name' requires capability '\${property.requiredCapability}'")
                continue
            }
            normalized[name] = value
        }

        for ((name, property) in schema.properties) {
            if (name !in declaration.properties) {
                when {
                    property.defaultValue != null -> normalized[name] = property.defaultValue
                    property.required -> diagnostics += diagnostic("TSH-SCHEMA-012", TshDiagnosticSeverity.ERROR,
                        "Required property '\$name' is missing")
                }
            }
        }

        val result = TshSchemaValidationResult(declaration, normalized.toMap(), diagnostics.toList())
        return if (result.isValid) TshResult.Success(result, diagnostics)
        else TshResult.Failure(diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR })
    }

    private fun matches(value: TshDslValue, type: TshDslType): Boolean = when (type) {
        TshDslType.AnyType -> true
        TshDslType.BooleanType -> value is TshDslValue.BooleanValue
        TshDslType.IntegerType -> value is TshDslValue.IntegerValue
        TshDslType.DecimalType -> value is TshDslValue.DecimalValue
        TshDslType.NumberType -> value is TshDslValue.IntegerValue || value is TshDslValue.DecimalValue
        TshDslType.StringType -> value is TshDslValue.StringValue
        TshDslType.IdentifierType -> value is TshDslValue.IdentifierValue
        TshDslType.ColorType -> value is TshDslValue.ColorValue
        TshDslType.FontFamilyType -> value is TshDslValue.FontFamilyValue
        is TshDslType.MeasureType -> value is TshDslValue.MeasureValue && value.unit in type.units
        TshDslType.RegexType -> value is TshDslValue.RegexValue
        TshDslType.QueryType -> value is TshDslValue.QueryValue
        is TshDslType.ReferenceType -> value is TshDslValue.ReferenceValue && value.targetKind == type.targetKind
        is TshDslType.ArrayType -> value is TshDslValue.ArrayValue && value.values.all { matches(it, type.elementType) }
        is TshDslType.MapType -> value is TshDslValue.ObjectValue && value.values.values.all { matches(it, type.valueType) }
        is TshDslType.OptionalType -> value === TshDslValue.NullValue || matches(value, type.valueType)
        is TshDslType.UnionType -> type.alternatives.any { matches(value, it) }
    }

    private fun numericValue(value: TshDslValue): Double? = when (value) {
        is TshDslValue.IntegerValue -> value.value.toDouble()
        is TshDslValue.DecimalValue -> value.value
        is TshDslValue.MeasureValue -> value.value
        else -> null
    }

    private fun diagnostic(code: String, severity: TshDiagnosticSeverity, message: String) =
        TshDiagnostic(code, severity, TshDiagnosticStage.SCHEMA, message)

    private fun failure(code: String, message: String) =
        TshResult.Failure(listOf(diagnostic(code, TshDiagnosticSeverity.ERROR, message)))
}
