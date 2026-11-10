package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicBoolean

enum class TshMemoryCategory {
    TEXT_SNAPSHOT, PARSER_INPUT, SYNTAX_TREE, QUERY_RESULT, REGEX_WINDOW,
    MATCH_SET, RENDER_PLAN, COORDINATE_INDEX, OTHER
}

data class TshMemoryBudgetOptions @JvmOverloads constructor(
    val targetBytes: Long = DEFAULT_TARGET_BYTES,
    val hardLimitBytes: Long = DEFAULT_HARD_LIMIT_BYTES,
    val availableMemoryBytes: Long? = null,
    val availableMemoryFraction: Int = DEFAULT_AVAILABLE_MEMORY_DIVISOR,
    /** Bounds the reservation ledger itself, including tiny reservations. */
    val maxReservations: Int = DEFAULT_MAX_RESERVATIONS
) {
    init {
        require(targetBytes > 0L)
        require(hardLimitBytes in 1L..ABSOLUTE_HARD_LIMIT_BYTES)
        require(targetBytes <= hardLimitBytes)
        require(availableMemoryBytes == null || availableMemoryBytes > 0L)
        require(availableMemoryFraction > 0)
        require(maxReservations in 1..ABSOLUTE_MAX_RESERVATIONS)
    }

    val effectiveLimitBytes: Long
        get() = minOf(
            targetBytes,
            hardLimitBytes,
            availableMemoryBytes?.div(availableMemoryFraction.toLong()) ?: Long.MAX_VALUE
        ).coerceAtLeast(1L)

    companion object {
        const val MIB: Long = 1024L * 1024L
        const val DEFAULT_TARGET_BYTES: Long = 100L * MIB
        const val DEFAULT_HARD_LIMIT_BYTES: Long = 512L * MIB
        const val ABSOLUTE_HARD_LIMIT_BYTES: Long = 512L * MIB
        const val DEFAULT_AVAILABLE_MEMORY_DIVISOR: Int = 4
        const val DEFAULT_MAX_RESERVATIONS: Int = 65_536
        const val ABSOLUTE_MAX_RESERVATIONS: Int = 262_144
    }
}

data class TshMemoryBudgetSnapshot(
    val limitBytes: Long,
    val hardLimitBytes: Long,
    val reservedBytes: Long,
    val highWatermarkBytes: Long,
    val reservations: Int,
    val maxReservations: Int,
    val bytesByCategory: Map<TshMemoryCategory, Long>,
    val closed: Boolean
) {
    /** Source-compatible constructor retained for callers predating maxReservations. */
    constructor(
        limitBytes: Long,
        hardLimitBytes: Long,
        reservedBytes: Long,
        highWatermarkBytes: Long,
        reservations: Int,
        bytesByCategory: Map<TshMemoryCategory, Long>,
        closed: Boolean
    ) : this(
        limitBytes, hardLimitBytes, reservedBytes, highWatermarkBytes,
        reservations, TshMemoryBudgetOptions.DEFAULT_MAX_RESERVATIONS,
        bytesByCategory, closed
    )
}

/**
 * Reservation ledger for TSH-managed or explicitly estimated memory.
 * It does not measure or guarantee the size of allocations internal to Tree-sitter Native code.
 */
class TshMemoryBudget(
    val options: TshMemoryBudgetOptions = TshMemoryBudgetOptions()
) : AutoCloseable {
    private val lock = Any()
    private val active = LinkedHashMap<Long, Entry>()
    private val byCategory = TshMemoryCategory.values().associateWith { 0L }.toMutableMap()
    private var nextId = 1L
    private var reserved = 0L
    private var highWatermark = 0L
    private var closed = false

    val limitBytes: Long get() = options.effectiveLimitBytes
    val hardLimitBytes: Long get() = options.hardLimitBytes
    val maxReservations: Int get() = options.maxReservations

    fun reserve(category: TshMemoryCategory, bytes: Long, ownerId: String? = null): TshResult<TshMemoryReservation> {
        if (bytes <= 0L) return failure("TSH-MEMORY-001", "Reservation size must be positive")
        if (ownerId != null && ownerId.isBlank()) return failure("TSH-MEMORY-002", "ownerId must be null or non-blank")
        synchronized(lock) {
            if (closed) return failure("TSH-MEMORY-003", "Memory budget is closed")
            if (active.size >= maxReservations) {
                return failure(
                    "TSH-MEMORY-010",
                    "Reservation count reached the configured limit ($maxReservations)"
                )
            }
            if (reserved > limitBytes || bytes > limitBytes - reserved) {
                return failure("TSH-MEMORY-004", "Reservation exceeds the remaining memory budget")
            }
            if (nextId == Long.MAX_VALUE) return failure("TSH-MEMORY-005", "Reservation identity space is exhausted")
            val id = nextId++
            active[id] = Entry(category, bytes, ownerId)
            reserved += bytes
            byCategory[category] = (byCategory[category] ?: 0L) + bytes
            highWatermark = maxOf(highWatermark, reserved)
            return TshResult.Success(TshMemoryReservation(id, category, bytes, ownerId) { release(id) })
        }
    }

    fun snapshot(): TshMemoryBudgetSnapshot = synchronized(lock) {
        TshMemoryBudgetSnapshot(
            limitBytes, hardLimitBytes, reserved, highWatermark, active.size,
            maxReservations, byCategory.toMap(), closed
        )
    }

    override fun close() {
        synchronized(lock) { closed = true }
    }

    private fun release(id: Long) {
        synchronized(lock) {
            val entry = active.remove(id) ?: return
            reserved -= entry.bytes
            byCategory[entry.category] = (byCategory[entry.category] ?: 0L) - entry.bytes
        }
    }

    private data class Entry(val category: TshMemoryCategory, val bytes: Long, val ownerId: String?)

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(
        TshDiagnostic(code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.MEMORY, message)
    ))
}

class TshMemoryReservation internal constructor(
    val id: Long,
    val category: TshMemoryCategory,
    val bytes: Long,
    val ownerId: String?,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    val isClosed: Boolean get() = released.get()
    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}
