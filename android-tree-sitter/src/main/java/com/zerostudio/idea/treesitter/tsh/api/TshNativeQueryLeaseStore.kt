package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSQuery

/**
 * Owns the close lifecycle of compiled native queries and prevents close while executions hold leases.
 *
 * A store instance must be shared by the compiler and every executor that uses its queries.
 * The store does not own TshObjectRegistry entries; the compiler removes those when retirement
 * completes. All native close operations happen after leaving the store monitor.
 */
class TshNativeQueryLeaseStore {
    internal class Entry(
        val queryId: String,
        val objectId: TshObjectId,
        val query: TSQuery,
        val dependencies: List<AutoCloseable>
    ) {
        var activeLeases: Int = 0
        var retired: Boolean = false
        var closed: Boolean = false
    }

    inner class Lease internal constructor(private val entry: Entry) : AutoCloseable {
        internal val query: TSQuery get() = entry.query
        val objectId: TshObjectId get() = entry.objectId
        private var released = false

        override fun close() {
            val closeNow = synchronized(lock) {
                if (released) return
                released = true
                check(entry.activeLeases > 0) { "Native query lease count underflow" }
                entry.activeLeases--
                takeForCloseIfReady(entry)
            }
            closeNow?.closeSafely()
        }
    }

    private val lock = Any()
    private val byObjectId = LinkedHashMap<TshObjectId, Entry>()
    private val byQueryId = LinkedHashMap<String, Entry>()
    private var closed = false
    private val closeFailures = ArrayList<TshDiagnostic>()

    /** Registers a newly-created query. The same query ID may replace a retired entry only. */
    fun register(
        queryId: String,
        objectId: TshObjectId,
        query: TSQuery,
        dependencies: List<AutoCloseable> = emptyList()
    ) {
        registerDeferred(queryId, objectId, query, dependencies)?.close()
    }

    /**
     * Publishes a revision and returns a deferred close action for an immediately-retirable
     * previous revision. Callers coordinating a higher-level transaction must invoke the action
     * only after leaving that transaction's lock.
     */
    internal fun registerDeferred(
        queryId: String,
        objectId: TshObjectId,
        query: TSQuery,
        dependencies: List<AutoCloseable> = emptyList()
    ): AutoCloseable? {
        require(queryId.isNotBlank())
        require(query.canAccess()) { "Cannot register a closed native query" }
        val closeNow = synchronized(lock) {
            check(!closed) { "Native query lease store is closed" }
            check(byObjectId[objectId] == null) { "Native object ID is already leased" }
            val current = byQueryId[queryId]
            val retired = if (current != null) {
                current.retired = true
                byQueryId.remove(queryId)
                takeForCloseIfReady(current)
            } else null
            val entry = Entry(queryId, objectId, query, dependencies.toList())
            byObjectId[objectId] = entry
            byQueryId[queryId] = entry
            retired
        }
        return closeNow?.let { entry -> AutoCloseable { entry.closeSafely() } }
    }

    /** Atomically obtains a strong execution lease unless the query has begun retirement. */
    fun acquire(objectId: TshObjectId): Lease? = synchronized(lock) {
        val entry = byObjectId[objectId] ?: return@synchronized null
        if (entry.retired || entry.closed) return@synchronized null
        if (!entry.query.canAccess()) return@synchronized null
        entry.activeLeases++
        Lease(entry)
    }

    /**
     * Marks the current query revision as retired. Existing executions may finish; new ones fail.
     * Returns false when no active entry exists for this query ID.
     */
    fun retire(queryId: String): Boolean {
        val (retired, closeAction) = retireDeferred(queryId)
        closeAction?.close()
        return retired
    }

    /** Marks a query retired without running its Native close action in the caller's lock scope. */
    internal fun retireDeferred(queryId: String): Pair<Boolean, AutoCloseable?> {
        val closeNow = synchronized(lock) {
            val entry = byQueryId[queryId] ?: return false to null
            if (entry.retired) return false to null
            entry.retired = true
            if (byQueryId[queryId] === entry) byQueryId.remove(queryId)
            takeForCloseIfReady(entry)
        }
        return true to closeNow?.let { entry -> AutoCloseable { entry.closeSafely() } }
    }


    /** Permanently closes the store and retires all registered native queries. */
    fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) retireAll()
    }

    /** Retires every query. Entries with active leases close when their final lease is released. */
    fun retireAll(): Int {
        val closeNow = ArrayList<Entry>()
        val retiredCount = synchronized(lock) {
            val entries = byObjectId.values.toList()
            var count = 0
            for (entry in entries) {
                if (!entry.retired) {
                    entry.retired = true
                    count++
                }
                if (byQueryId[entry.queryId] === entry) byQueryId.remove(entry.queryId)
                takeForCloseIfReady(entry)?.let(closeNow::add)
            }
            count
        }
        closeNow.forEach { it.closeSafely() }
        return retiredCount
    }

    fun closeDiagnostics(): List<TshDiagnostic> = synchronized(lock) { closeFailures.toList() }

    fun activeLeaseCount(objectId: TshObjectId): Int = synchronized(lock) {
        byObjectId[objectId]?.activeLeases ?: 0
    }

    private fun takeForCloseIfReady(entry: Entry): Entry? {
        if (!entry.retired || entry.activeLeases != 0 || entry.closed) return null
        entry.closed = true
        byObjectId.remove(entry.objectId, entry)
        return entry
    }

    private fun Entry.closeSafely() {
        try {
            query.close()
        } catch (error: Exception) {
            recordCloseFailure(objectId, "Native query close failed", error)
        }
        dependencies.asReversed().forEach { dependency ->
            try {
                dependency.close()
            } catch (error: Exception) {
                recordCloseFailure(objectId, "Native query dependency close failed", error)
            }
        }
    }

    private fun recordCloseFailure(objectId: TshObjectId, message: String, error: Throwable) {
        synchronized(lock) {
            closeFailures += TshDiagnostic(
                code = "TSH-NATIVE-QUERY-001",
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.LIFECYCLE,
                message = message,
                objectId = objectId,
                cause = error
            )
        }
    }
}
