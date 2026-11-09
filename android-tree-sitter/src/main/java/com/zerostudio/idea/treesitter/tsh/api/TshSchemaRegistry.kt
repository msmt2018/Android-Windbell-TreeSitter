package com.zerostudio.idea.treesitter.tsh.api

import java.math.BigDecimal

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
    data class MeasureType(val units: Set<TshDslUnit>) : TshDslType() {
        init { require(units.isNotEmpty()) { "MeasureType requires at least one unit" } }
    }
    object RegexType : TshDslType()
    object QueryType : TshDslType()
    data class ReferenceType(val targetKind: TshDslDeclarationKind) : TshDslType()
    data class ArrayType(val elementType: TshDslType) : TshDslType()
    /** Object keys are strings in the AST; keyType is restricted to StringType or IdentifierType. */
    data class MapType(val valueType: TshDslType, val keyType: TshDslType = StringType) : TshDslType() {
        init { require(keyType === StringType || keyType === IdentifierType) {
            "Map keys must use StringType or IdentifierType"
        } }
    }
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
    /** Required when minimum/maximum constrain a MeasureValue. No implicit unit conversion occurs. */
    val rangeUnit: TshDslUnit? = null,
    val inheritable: Boolean = false,
    val mergePolicy: TshAttributeMergePolicy = TshAttributeMergePolicy.REPLACE,
    val introducedIn: TshDslVersion = TshDslVersion(1),
    val deprecatedIn: TshDslVersion? = null,
    val requiredCapability: String? = null,
    val requiredCapabilityVersion: Int = 1
) {
    init {
        require(name.isNotBlank()) { "Property schema name must not be blank" }
        require(minimum == null || minimum.isFinite())
        require(maximum == null || maximum.isFinite())
        require(minimum == null || maximum == null || minimum <= maximum) {
            "Property minimum must not exceed maximum"
        }
        require(requiredCapability == null || requiredCapability.isNotBlank())
        require(requiredCapabilityVersion > 0) { "requiredCapabilityVersion must be positive" }
        require((minimum == null && maximum == null) || type !is TshDslType.MeasureType || rangeUnit != null) {
            "Measure range constraints require an explicit rangeUnit"
        }
        require(rangeUnit == null || (type is TshDslType.MeasureType && rangeUnit in type.units)) {
            "rangeUnit must be one of the MeasureType units"
        }
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
    val diagnostics: List<TshDiagnostic>,
    /** Unknown/unsupported values are retained here for diagnostics, migration or extension handling. */
    val unappliedProperties: Map<String, TshDslValue> = emptyMap(),
    val dslVersion: TshDslVersion = TshDslVersion(1)
) {
    val isValid: Boolean
        get() = diagnostics.none { it.severity == TshDiagnosticSeverity.ERROR }
}

/** Thread-safe registry. Unknown properties are never silently discarded. */
class TshSchemaRegistry {
    private val lock = Any()
    private val schemas = LinkedHashMap<TshDslDeclarationKind, TshDeclarationSchema>()

    fun register(schema: TshDeclarationSchema, replace: Boolean = false): TshResult<TshDeclarationSchema> =
        synchronized(lock) {
            val existing = schemas[schema.kind]
            if (existing != null && !replace) {
                return@synchronized failure("TSH-SCHEMA-001", "A schema for ${schema.kind} is already registered")
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
            ?: return failure("TSH-SCHEMA-002", "No schema registered for ${declaration.kind}")
        val diagnostics = ArrayList<TshDiagnostic>()
        val normalized = LinkedHashMap<String, TshDslValue>()
        val unapplied = LinkedHashMap<String, TshDslValue>()

        for ((name, value) in declaration.properties) {
            val property = schema.properties[name]
            if (property == null) {
                // IGNORE is a compatibility policy, never a silent-drop policy.
                val severity = when {
                    mode == TshValidationMode.LENIENT -> TshDiagnosticSeverity.WARNING
                    schema.unknownPropertyPolicy == TshSchemaUnknownPropertyPolicy.WARNING ||
                        schema.unknownPropertyPolicy == TshSchemaUnknownPropertyPolicy.IGNORE ->
                        TshDiagnosticSeverity.WARNING
                    else -> TshDiagnosticSeverity.ERROR
                }
                diagnostics += diagnostic(
                    "TSH-SCHEMA-003", severity,
                    "Unknown property '$name' in ${declaration.kind}; value was retained as unapplied"
                )
                unapplied[name] = value
                continue
            }
            if (version < property.introducedIn) {
                diagnostics += diagnostic("TSH-SCHEMA-004", TshDiagnosticSeverity.ERROR,
                    "Property '$name' requires DSL ${property.introducedIn} or newer")
                continue
            }
            if (property.deprecatedIn != null && version >= property.deprecatedIn) {
                diagnostics += diagnostic("TSH-SCHEMA-005", TshDiagnosticSeverity.WARNING,
                    "Property '$name' is deprecated since DSL ${property.deprecatedIn}")
            }
            val valueDiagnostics = validateValue(name, value, property, availableCapabilities)
            if (valueDiagnostics.isNotEmpty()) {
                diagnostics += valueDiagnostics
                continue
            }
            normalized[name] = value
        }

        for ((name, property) in schema.properties) {
            if (name in declaration.properties) continue
            val default = property.defaultValue
            if (version < property.introducedIn) {
                if (property.required || default != null) diagnostics += diagnostic(
                    "TSH-SCHEMA-004", TshDiagnosticSeverity.ERROR,
                    "Property '$name' requires DSL ${property.introducedIn} or newer"
                )
                continue
            }
            val numericConstraint = property.minimum != null || property.maximum != null
            val numericType = property.type === TshDslType.IntegerType ||
                property.type === TshDslType.DecimalType || property.type === TshDslType.NumberType ||
                property.type is TshDslType.MeasureType
            if (numericConstraint && !numericType) diagnostics += diagnostic(
                "TSH-SCHEMA-015", TshDiagnosticSeverity.ERROR,
                "Property '$name' declares numeric bounds for a non-numeric type"
            )
            if (default != null) {
                val defaultDiagnostics = validateValue(name, default, property, availableCapabilities)
                if (defaultDiagnostics.isNotEmpty()) {
                    diagnostics += defaultDiagnostics.map {
                        it.copy(code = "TSH-SCHEMA-013", message = "Invalid default for '$name': ${it.message}")
                    }
                } else {
                    normalized[name] = default
                }
            } else if (property.required) {
                diagnostics += diagnostic("TSH-SCHEMA-012", TshDiagnosticSeverity.ERROR,
                    "Required property '$name' is missing")
            }
        }

        val result = TshSchemaValidationResult(declaration, normalized.toMap(), diagnostics.toList(), unapplied.toMap(), version)
        return if (result.isValid) TshResult.Success(result, diagnostics.toList())
        else TshResult.Failure(diagnostics.toList())
    }

    private fun validateValue(
        name: String,
        value: TshDslValue,
        property: TshPropertySchema,
        capabilities: TshCapabilitySet
    ): List<TshDiagnostic> {
        val errors = ArrayList<TshDiagnostic>()
        if (value === TshDslValue.NullValue) {
            if (!property.nullable) errors += diagnostic(
                "TSH-SCHEMA-006", TshDiagnosticSeverity.ERROR, "Property '$name' does not accept null"
            )
            return errors
        }
        if (!matches(value, property.type)) {
            errors += diagnostic("TSH-SCHEMA-007", TshDiagnosticSeverity.ERROR,
                "Property '$name' does not match declared type ${property.type}")
            return errors
        }
        if (property.allowedValues.isNotEmpty() && value !in property.allowedValues) {
            errors += diagnostic("TSH-SCHEMA-008", TshDiagnosticSeverity.ERROR,
                "Property '$name' has a value outside its allowed set")
        }
        val numeric = numericValue(value)
        if ((property.minimum != null || property.maximum != null) && numeric == null) {
            errors += diagnostic("TSH-SCHEMA-015", TshDiagnosticSeverity.ERROR,
                "Property '$name' declares numeric bounds but its value is not numeric")
        }
        val rangeComparable = value !is TshDslValue.MeasureValue ||
            property.rangeUnit == null || value.unit == property.rangeUnit
        if (numeric != null && rangeComparable) {
            val minimum = property.minimum?.let(BigDecimal::valueOf)
            val maximum = property.maximum?.let(BigDecimal::valueOf)
            if (minimum != null && numeric < minimum) errors += diagnostic(
                "TSH-SCHEMA-009", TshDiagnosticSeverity.ERROR,
                "Property '$name' is below minimum ${property.minimum}${property.rangeUnit ?: ""}"
            )
            if (maximum != null && numeric > maximum) errors += diagnostic(
                "TSH-SCHEMA-010", TshDiagnosticSeverity.ERROR,
                "Property '$name' exceeds maximum ${property.maximum}${property.rangeUnit ?: ""}"
            )
        }
        if (value is TshDslValue.MeasureValue && property.rangeUnit != null && value.unit != property.rangeUnit) {
            errors += diagnostic("TSH-SCHEMA-014", TshDiagnosticSeverity.ERROR,
                "Property '$name' range is expressed in ${property.rangeUnit}, but value uses ${value.unit}; convert explicitly")
        }
        if (property.requiredCapability != null && !capabilities.satisfies(
                TshCapability(property.requiredCapability, property.requiredCapabilityVersion)
            )) {
            errors += diagnostic("TSH-SCHEMA-011", TshDiagnosticSeverity.ERROR,
                "Property '$name' requires capability '${property.requiredCapability}' version ${property.requiredCapabilityVersion}")
        }
        return errors
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
        is TshDslType.MapType -> value is TshDslValue.ObjectValue &&
            value.values.keys.all { key -> type.keyType === TshDslType.StringType ||
                (key.isNotBlank() && key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) } &&
            value.values.values.all { matches(it, type.valueType) }
        is TshDslType.OptionalType -> matches(value, type.valueType)
        is TshDslType.UnionType -> type.alternatives.any { matches(value, it) }
    }

    /**
     * Preserve Long integer precision when checking schema bounds. Converting every numeric
     * value to Double incorrectly accepts/rejects values around the 2^53 precision boundary.
     */
    private fun numericValue(value: TshDslValue): BigDecimal? = when (value) {
        is TshDslValue.IntegerValue -> BigDecimal.valueOf(value.value)
        is TshDslValue.DecimalValue -> BigDecimal.valueOf(value.value)
        is TshDslValue.MeasureValue -> BigDecimal.valueOf(value.value)
        else -> null
    }

    private fun diagnostic(code: String, severity: TshDiagnosticSeverity, message: String) =
        TshDiagnostic(code, severity, TshDiagnosticStage.SCHEMA, message)

    private fun failure(code: String, message: String) =
        TshResult.Failure(listOf(diagnostic(code, TshDiagnosticSeverity.ERROR, message)))
}
