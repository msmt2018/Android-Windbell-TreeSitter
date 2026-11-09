package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicReference

enum class TshEngineState { CREATED, INITIALIZED, CLOSED }

/**
 * Platform-neutral public entry point.
 * Parsing/highlighting continues to use WindbellTshEngine during staged migration.
 */
class TshEngine private constructor(val options: TshEngineOptions) : AutoCloseable {
    private val stateRef = AtomicReference(TshEngineState.CREATED)

    val objects: TshObjectRegistry = TshObjectRegistry()
    val providers: TshProviderRegistry = TshProviderRegistry()
    private val nativeResourceLeases = TshNativeResourceLeaseStore()
    /** Shared query catalog and Native query leases for every adapter created by this Engine. */
    val compiledQueries: TshCompiledQueryRegistry = TshCompiledQueryRegistry()
    val nativeQueryLeases: TshNativeQueryLeaseStore = TshNativeQueryLeaseStore()
    /** Engine-owned coordinator; resources registered here are retired when the engine closes. */
    val nativeResources: TshNativeResourceRegistry =
        TshNativeResourceRegistry(objects, nativeResourceLeases)

    val state: TshEngineState
        get() = stateRef.get()

    fun initialize(): TshResult<TshEngine> {
        while (true) {
            when (val current = stateRef.get()) {
                TshEngineState.CREATED -> {
                    if (stateRef.compareAndSet(current, TshEngineState.INITIALIZED)) {
                        return TshResult.Success(this)
                    }
                }
                TshEngineState.INITIALIZED -> return TshResult.Success(this)
                TshEngineState.CLOSED -> return TshResult.Failure(
                    listOf(
                        TshDiagnostic(
                            code = "TSH-ENGINE-001",
                            severity = TshDiagnosticSeverity.ERROR,
                            stage = TshDiagnosticStage.ENGINE,
                            message = "A closed TSH engine cannot be initialized again"
                        )
                    )
                )
            }
        }
    }

    /**
     * Creates a compiler wired to this Engine's object index, query catalog, Native query lease
     * store, and Native resource coordinator. Do not construct a second independent store for
     * queries belonging to this Engine.
     */
    fun createTreeSitterQueryCompiler(
        languages: TshGrammarLanguageResolver,
        compilerVersion: String = "treesitter-adapter-1"
    ): TshTreeSitterQueryCompiler {
        check(state == TshEngineState.INITIALIZED) {
            "TSH engine must be initialized before creating a query compiler"
        }
        return TshTreeSitterQueryCompiler(
            languages = languages,
            objects = objects,
            queries = compiledQueries,
            leases = nativeQueryLeases,
            languageLeases = nativeResourceLeases,
            compilerVersion = compilerVersion,
            resourceRegistry = nativeResources
        )
    }

    /** Opens a Parser whose Grammar dependency is managed by this Engine's resource coordinator. */
    fun openTreeSitterParser(
        languageHandle: TshNativeResourceLeaseStore.Handle<com.zerostudio.idea.treesitter.TSLanguage>,
        grammar: TshGrammarDescriptor
    ): TshResult<TshTreeSitterParserOwner> {
        if (state != TshEngineState.INITIALIZED) {
            return TshResult.Failure(listOf(TshDiagnostic(
                code = "TSH-ENGINE-002",
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.ENGINE,
                message = "TSH engine must be initialized before opening a Tree-sitter parser"
            )))
        }
        return TshTreeSitterParserOwner.open(nativeResources, languageHandle, grammar)
    }

    /** Creates an executor sharing the exact Native query/resource stores owned by this Engine. */
    fun createTreeSitterQueryExecutor(
        predicateHandlers: List<com.zerostudio.idea.treesitter.predicate.TSPredicateHandler> = emptyList()
    ): TshTreeSitterQueryExecutor {
        check(state == TshEngineState.INITIALIZED) {
            "TSH engine must be initialized before creating a query executor"
        }
        return TshTreeSitterQueryExecutor(
            objects = objects,
            queries = compiledQueries,
            leases = nativeQueryLeases,
            treeLeases = nativeResourceLeases,
            predicateHandlers = predicateHandlers,
            resourceRegistry = nativeResources
        )
    }

    /**
     * Resolves host providers using this Engine's strictness and diagnostics options.
     * Strict mode rejects missing required capabilities; lenient mode returns a degraded report.
     */
    fun resolveProviders(
        instance: Any,
        descriptor: TshObjectDescriptor,
        required: TshCapabilitySet = TshCapabilitySet.EMPTY,
        optional: TshCapabilitySet = TshCapabilitySet.EMPTY,
        apiRange: TshProviderApiRange = TshProviderApiRange()
    ): TshResult<TshProviderResolution> {
        if (state != TshEngineState.INITIALIZED) {
            return TshResult.Failure(listOf(TshDiagnostic(
                code = "TSH-ENGINE-002",
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.ENGINE,
                message = "TSH engine must be initialized before provider resolution"
            )))
        }
        val resolution = providers.resolveFor(instance, descriptor, required, optional, apiRange)
        if (options.strictProviderValidation && !resolution.isSatisfiable) {
            return TshResult.Failure(resolution.diagnostics.filter {
                it.severity == TshDiagnosticSeverity.ERROR
            }.ifEmpty { listOf(TshDiagnostic(
                code = "TSH-PROVIDER-006",
                severity = TshDiagnosticSeverity.ERROR,
                stage = TshDiagnosticStage.PROVIDER,
                message = "Provider resolution is not satisfiable",
                objectId = descriptor.id
            )) })
        }
        val visibleDiagnostics = if (options.enableDiagnostics) resolution.diagnostics else
            resolution.diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR }
        return TshResult.Success(resolution, visibleDiagnostics)
    }

    fun requireInitialized(): TshResult<TshEngine> =
        if (state == TshEngineState.INITIALIZED) {
            TshResult.Success(this)
        } else {
            TshResult.Failure(
                listOf(
                    TshDiagnostic(
                        code = "TSH-ENGINE-002",
                        severity = TshDiagnosticSeverity.ERROR,
                        stage = TshDiagnosticStage.ENGINE,
                        message = "TSH engine must be initialized before this operation"
                    )
                )
            )
        }

    override fun close() {
        val previous = stateRef.getAndSet(TshEngineState.CLOSED)
        if (previous != TshEngineState.CLOSED) {
            // Retire queries first: each compiled query may retain a Grammar Language lease.
            // Then retire Trees, Parsers and Languages owned by the resource coordinator.
            nativeQueryLeases.close()
            compiledQueries.clear()
            nativeResources.close()
            providers.clear()
            objects.clear()
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(options: TshEngineOptions = TshEngineOptions()): TshEngine =
            TshEngine(options)
    }
}

data class TshEngineOptions(
    val enableDiagnostics: Boolean = true,
    val strictProviderValidation: Boolean = true
)
