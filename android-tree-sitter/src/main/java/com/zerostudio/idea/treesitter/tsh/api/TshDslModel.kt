package com.zerostudio.idea.treesitter.tsh.api

/** Version of the public .tsh language contract. */
data class TshDslVersion(val major: Int, val minor: Int = 0) : Comparable<TshDslVersion> {
    init {
        require(major >= 1) { "DSL major version must be >= 1" }
        require(minor >= 0) { "DSL minor version must be >= 0" }
    }
    override fun compareTo(other: TshDslVersion): Int =
        compareValuesBy(this, other, TshDslVersion::major, TshDslVersion::minor)
    override fun toString(): String = "$major.$minor"
}

enum class TshDslDeclarationKind {
    META, LANGUAGE, GRAMMAR, QUERY, REGEX, SCOPE, SELECTOR,
    ATTRIBUTE, THEME, STYLE, FONT, COLOR, DECORATION, INJECTION, VARIABLE, INCLUDE
}
enum class TshDslUnit { PX, DP, SP, PT, EM, REM, PERCENT, MS, SECOND, DEGREE, RADIAN }
enum class TshColorSpace { SRGB, DISPLAY_P3, LINEAR_SRGB }
enum class TshAttributeMergePolicy { REPLACE, FALLBACK, APPEND, SET_UNION, NUMERIC_COMPOSE }

/** Typed value tree produced by the DSL parser. */
sealed interface TshDslValue {
    data object NullValue : TshDslValue
    data class BooleanValue(val value: Boolean) : TshDslValue
    data class IntegerValue(val value: Long) : TshDslValue
    data class DecimalValue(val value: Double) : TshDslValue {
        init { require(value.isFinite()) { "Decimal DSL values must be finite" } }
    }
    data class StringValue(val value: String) : TshDslValue
    data class IdentifierValue(val value: String) : TshDslValue {
        init { require(value.isNotBlank()) { "Identifier must not be blank" } }
    }
    data class ColorValue(val argb: UInt, val colorSpace: TshColorSpace = TshColorSpace.SRGB) : TshDslValue
    data class MeasureValue(val value: Double, val unit: TshDslUnit) : TshDslValue {
        init { require(value.isFinite()) { "Measure must be finite" } }
    }
    data class FontFamilyValue(val families: List<String>) : TshDslValue {
        init {
            require(families.isNotEmpty()) { "Font family list must not be empty" }
            require(families.none { it.isBlank() }) { "Font family names must not be blank" }
        }
    }
    data class RegexValue(val pattern: String, val options: Set<String> = emptySet()) : TshDslValue
    data class QueryValue(val source: String, val grammarId: String) : TshDslValue {
        init { require(grammarId.isNotBlank()) { "Query grammar id must not be blank" } }
    }
    data class ReferenceValue(val targetKind: TshDslDeclarationKind, val targetId: String) : TshDslValue {
        init { require(targetId.isNotBlank()) { "Reference target must not be blank" } }
    }
    data class ArrayValue(val values: List<TshDslValue>) : TshDslValue
    data class ObjectValue(val values: Map<String, TshDslValue>) : TshDslValue {
        init { require(values.keys.none { it.isBlank() }) { "Object keys must not be blank" } }
    }
}

/** Parser-neutral declaration node. Lexer/parser owns source ranges and duplicate-key diagnostics. */
data class TshDslDeclaration(
    val kind: TshDslDeclarationKind,
    val id: String,
    val properties: Map<String, TshDslValue>,
    val sourceRange: TshSourceRange? = null
) {
    init {
        require(id.isNotBlank()) { "Declaration id must not be blank" }
        require(properties.keys.none { it.isBlank() }) { "Property names must not be blank" }
    }
}
data class TshDslDocument(
    val version: TshDslVersion,
    val declarations: List<TshDslDeclaration>,
    val sourceId: String? = null
)
