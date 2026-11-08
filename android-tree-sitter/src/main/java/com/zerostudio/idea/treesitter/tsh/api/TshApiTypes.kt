package com.zerostudio.idea.treesitter.tsh.api

/** Stable public API primitives shared by TSH services. */
enum class TshDiagnosticSeverity { ERROR, WARNING, INFO, HINT }

enum class TshDiagnosticStage {
    ENGINE, CONFIG, LEX, PARSE, SCHEMA, SEMANTIC, COMPILE,
    SYNTAX, ATS, REGEX, COORDINATE, HIGHLIGHT, BINDING,
    RENDER, JNI, LIFECYCLE, PROVIDER
}

enum class TshCoordinateSystem {
    UTF8_BYTE, UTF16_CODE_UNIT, UNICODE_CODE_POINT,
    GRAPHEME_CLUSTER, LINE_COLUMN, SCREEN_PIXEL
}

data class TshSourcePosition(
    val offset: Int,
    val line: Int? = null,
    val column: Int? = null,
    val coordinateSystem: TshCoordinateSystem
) {
    init {
        require(offset >= 0) { "offset must be >= 0" }
        require(line == null || line >= 0) { "line must be >= 0" }
        require(column == null || column >= 0) { "column must be >= 0" }
    }
}

data class TshSourceRange(
    val start: TshSourcePosition,
    val end: TshSourcePosition,
    val documentVersion: Long? = null
) {
    init {
        require(start.coordinateSystem == end.coordinateSystem) {
            "Range endpoints must use the same coordinate system"
        }
        require(end.offset >= start.offset) { "Range end must not precede start" }
    }
}

@JvmInline
value class TshObjectId(val value: Long) {
    override fun toString(): String = "tsh-object-$value"
}

data class TshDiagnostic(
    val code: String,
    val severity: TshDiagnosticSeverity,
    val stage: TshDiagnosticStage,
    val message: String,
    val source: String? = null,
    val range: TshSourceRange? = null,
    val objectId: TshObjectId? = null,
    val cause: Throwable? = null,
    val relatedInformation: List<String> = emptyList()
) {
    init {
        require(code.isNotBlank()) { "diagnostic code must not be blank" }
        require(message.isNotBlank()) { "diagnostic message must not be blank" }
    }
}

sealed class TshResult<out T> {
    data class Success<T>(
        val value: T,
        val diagnostics: List<TshDiagnostic> = emptyList()
    ) : TshResult<T>()

    data class Failure(val diagnostics: List<TshDiagnostic>) : TshResult<Nothing>() {
        init {
            require(diagnostics.isNotEmpty()) { "Failure requires at least one diagnostic" }
        }
    }

    fun isSuccess(): Boolean = this is Success<*>

    fun diagnostics(): List<TshDiagnostic> = when (this) {
        is Success -> diagnostics
        is Failure -> diagnostics
    }
}

data class TshCapability(
    val id: String,
    val version: Int = 1,
    val description: String = ""
) {
    init {
        require(id.isNotBlank()) { "capability id must not be blank" }
        require(version > 0) { "capability version must be positive" }
    }
}

data class TshCapabilitySet private constructor(
    private val entries: Map<String, TshCapability>
) {
    fun contains(id: String): Boolean = entries.containsKey(id)
    fun get(id: String): TshCapability? = entries[id]
    fun all(): Set<TshCapability> = entries.values.toSet()
    fun isEmpty(): Boolean = entries.isEmpty()

    companion object {
        @JvmField val EMPTY = TshCapabilitySet(emptyMap())

        @JvmStatic
        fun of(vararg capabilities: TshCapability): TshCapabilitySet =
            TshCapabilitySet(capabilities.associateBy { it.id })

        @JvmStatic
        fun from(capabilities: Collection<TshCapability>): TshCapabilitySet =
            TshCapabilitySet(capabilities.associateBy { it.id })
    }
}

data class TshObjectDescriptor(
    val id: TshObjectId,
    val type: String,
    val capabilities: TshCapabilitySet,
    val metadata: Map<String, String> = emptyMap(),
    val registeredAtNanos: Long
) {
    init {
        require(type.isNotBlank()) { "object type must not be blank" }
    }
}
