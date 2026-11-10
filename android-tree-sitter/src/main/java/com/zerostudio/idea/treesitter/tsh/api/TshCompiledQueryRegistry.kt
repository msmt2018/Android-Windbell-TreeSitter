package com.zerostudio.idea.treesitter.tsh.api

/**
 * Thread-safe catalog for compiled Tree-sitter/ATS queries.
 * It does not own native query destruction; a native provider must manage leases separately.
 */
class TshCompiledQueryRegistry {
    @PublishedApi
    internal val lock = Any()
    private val queries = LinkedHashMap<String, TshCompiledQuery>()
    private var closed = false

    internal inline fun <T> withLock(action: () -> T): T = synchronized(lock, action)

    fun register(query: TshCompiledQuery, replace: Boolean = false): TshResult<TshCompiledQuery> =
        synchronized(lock) {
            if (closed) return@synchronized failure(
                "TSH-QUERY-REGISTRY-004", "Compiled query registry is closed"
            )
            val current = queries[query.queryId]
            if (current != null && !replace) {
                return@synchronized failure(
                    "TSH-QUERY-REGISTRY-001",
                    "Query '${query.queryId}' is already registered"
                )
            }
            queries[query.queryId] = query
            TshResult.Success(query)
        }

    fun get(queryId: String): TshCompiledQuery? =
        synchronized(lock) { queries[queryId] }

    fun list(): List<TshCompiledQuery> =
        synchronized(lock) { queries.values.toList() }

    fun findCompatible(
        queryId: String,
        grammar: TshGrammarDescriptor
    ): TshResult<TshCompiledQuery> {
        val query = get(queryId)
            ?: return failure("TSH-QUERY-REGISTRY-002", "Query '$queryId' is not registered")
        if (!query.isCompatibleWith(grammar)) {
            return failure(
                "TSH-QUERY-REGISTRY-003",
                "Query '$queryId' was compiled for an incompatible grammar revision"
            )
        }
        return TshResult.Success(query)
    }

    fun unregister(queryId: String): TshCompiledQuery? =
        synchronized(lock) { queries.remove(queryId) }

    fun clear(): List<TshCompiledQuery> = synchronized(lock) {
        val removed = queries.values.toList()
        queries.clear()
        removed
    }

    /** Permanently closes the catalog and rejects future query publication. */
    fun close(): List<TshCompiledQuery> = synchronized(lock) {
        closed = true
        val removed = queries.values.toList()
        queries.clear()
        removed
    }

    private fun failure(code: String, message: String) =
        TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.ATS,
            message = message
        )))
}
