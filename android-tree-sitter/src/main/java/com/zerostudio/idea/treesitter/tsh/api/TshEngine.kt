package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicReference

enum class TshEngineState { CREATED, INITIALIZED, CLOSED }

/**
 * Platform-neutral public entry point.
 * Parsing/highlighting continues to use WindbellTshEngine during staged migration.
 */
class TshEngine private constructor(val options: TshEngineOptions) : AutoCloseable {
    private val stateRef = AtomicReference(TshEngineState.CREATED)
    /** Linearizes public operations against close() so no resource can publish after shutdown. */
    private val lifecycleLock = Any()

    val objects: TshObjectRegistry = TshObjectRegistry()
    val providers: TshProviderRegistry = TshProviderRegistry()
    /** Manages host attachment records without taking ownership of host objects. */
    val attachments: TshAttachmentManager = TshAttachmentManager(objects, providers)
    private val nativeResourceLeases = TshNativeResourceLeaseStore()
    /** Shared query catalog and Native query leases for every adapter created by this Engine. */
    val compiledQueries: TshCompiledQueryRegistry = TshCompiledQueryRegistry()
    val nativeQueryLeases: TshNativeQueryLeaseStore = TshNativeQueryLeaseStore()
    /** Engine-owned coordinator; resources registered here are retired when the engine closes. */
    val nativeResources: TshNativeResourceRegistry =
        TshNativeResourceRegistry(objects, nativeResourceLeases)
    /** Shared accounting budget for parser input, optional Native-tree estimates, and other TSH subsystems. */
    val memoryBudget: TshMemoryBudget = TshMemoryBudget(options.memoryBudgetOptions)

    val state: TshEngineState
        get() = stateRef.get()

    /** Native close failures captured during Engine shutdown; available after close(). */
    val shutdownDiagnostics: List<TshDiagnostic>
        get() = nativeQueryLeases.closeDiagnostics() + nativeResources.closeDiagnostics()

    fun initialize(): TshResult<TshEngine> = synchronized(lifecycleLock) {
        when (stateRef.get()) {
            TshEngineState.CREATED -> {
                stateRef.set(TshEngineState.INITIALIZED)
                TshResult.Success(this)
            }
            TshEngineState.INITIALIZED -> TshResult.Success(this)
            TshEngineState.CLOSED -> engineFailure(
                "TSH-ENGINE-001", "A closed TSH engine cannot be initialized again"
            )
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
    ): TshTreeSitterQueryCompiler = synchronized(lifecycleLock) {
        check(state == TshEngineState.INITIALIZED) {
            "TSH engine must be initialized before creating a query compiler"
        }
        TshTreeSitterQueryCompiler(
            languages = languages, objects = objects, queries = compiledQueries,
            leases = nativeQueryLeases, languageLeases = nativeResourceLeases,
            compilerVersion = compilerVersion, resourceRegistry = nativeResources
        )
    }

    /** Opens a Parser whose Grammar dependency is managed by this Engine's resource coordinator. */
    @JvmOverloads
    fun openTreeSitterParser(
        languageHandle: TshNativeResourceLeaseStore.Handle<com.zerostudio.idea.treesitter.TSLanguage>,
        grammar: TshGrammarDescriptor,
        nativeTreeMemoryEstimator: TshNativeTreeMemoryEstimator? = null
    ): TshResult<TshTreeSitterParserOwner> = synchronized(lifecycleLock) {
        if (state != TshEngineState.INITIALIZED) {
            return@synchronized engineFailure(
                "TSH-ENGINE-002", "TSH engine must be initialized before opening a Tree-sitter parser"
            )
        }
        TshTreeSitterParserOwner.open(
            nativeResources, languageHandle, grammar, memoryBudget, nativeTreeMemoryEstimator
        )
    }

    /** Creates an executor sharing the exact Native query/resource stores owned by this Engine. */
    fun createTreeSitterQueryExecutor(
        predicateHandlers: List<com.zerostudio.idea.treesitter.predicate.TSPredicateHandler> = emptyList()
    ): TshTreeSitterQueryExecutor = synchronized(lifecycleLock) {
        check(state == TshEngineState.INITIALIZED) {
            "TSH engine must be initialized before creating a query executor"
        }
        TshTreeSitterQueryExecutor(
            objects = objects, queries = compiledQueries, leases = nativeQueryLeases,
            treeLeases = nativeResourceLeases, predicateHandlers = predicateHandlers,
            resourceRegistry = nativeResources
        )
    }

    /** Attaches a host object without requiring inheritance or transferring host ownership. */
    fun attach(
        request: TshAttachmentRequest,
        ownerScope: TshOwnerScope
    ): TshResult<TshAttachmentReport> = synchronized(lifecycleLock) {
        if (state != TshEngineState.INITIALIZED) return@synchronized engineFailure(
            "TSH-ENGINE-002", "TSH engine must be initialized before attaching host objects"
        )
        val attached = attachments.attach(request, ownerScope)
        if (attached is TshResult.Failure) return@synchronized attached
        val success = attached as TshResult.Success<TshAttachmentReport>
        val report = success.value
        if (options.strictProviderValidation && report.status == TshAttachmentStatus.INCOMPLETE) {
            attachments.detach(report.handle)
            return@synchronized providerResolutionFailure(report.resolution, report.descriptor.id)
        }
        val diagnostics = if (options.enableDiagnostics) success.diagnostics else
            success.diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR }
        TshResult.Success(report, diagnostics)
    }

    fun inspectAttachment(handle: TshAttachmentHandle): TshAttachmentReport? =
        synchronized(lifecycleLock) {
            if (state == TshEngineState.CLOSED) null else attachments.inspect(handle)
        }

    fun refreshAttachment(handle: TshAttachmentHandle): TshResult<TshAttachmentReport> =
        synchronized(lifecycleLock) {
            if (state != TshEngineState.INITIALIZED) return@synchronized engineFailure(
                "TSH-ENGINE-002", "TSH engine must be initialized before refreshing attachments"
            )
            val refreshed = attachments.refresh(handle)
            if (refreshed is TshResult.Failure) return@synchronized refreshed
            val success = refreshed as TshResult.Success<TshAttachmentReport>
            if (options.strictProviderValidation && success.value.status == TshAttachmentStatus.INCOMPLETE) {
                return@synchronized providerResolutionFailure(
                    success.value.resolution, success.value.descriptor.id
                )
            }
            val diagnostics = if (options.enableDiagnostics) success.diagnostics else
                success.diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR }
            TshResult.Success(success.value, diagnostics)
        }

    fun detach(handle: TshAttachmentHandle): Boolean = synchronized(lifecycleLock) {
        state != TshEngineState.CLOSED && attachments.detach(handle)
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
    ): TshResult<TshProviderResolution> = synchronized(lifecycleLock) {
        if (state != TshEngineState.INITIALIZED) return@synchronized engineFailure(
            "TSH-ENGINE-002", "TSH engine must be initialized before provider resolution"
        )
        val resolution = providers.resolveFor(instance, descriptor, required, optional, apiRange)
        if (options.strictProviderValidation && !resolution.isSatisfiable) {
            return@synchronized providerResolutionFailure(resolution, descriptor.id)
        }
        val visibleDiagnostics = if (options.enableDiagnostics) resolution.diagnostics else
            resolution.diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR }
        TshResult.Success(resolution, visibleDiagnostics)
    }

    fun requireInitialized(): TshResult<TshEngine> = synchronized(lifecycleLock) {
        if (state == TshEngineState.INITIALIZED) TshResult.Success(this)
        else engineFailure("TSH-ENGINE-002", "TSH engine must be initialized before this operation")
    }

    private fun providerResolutionFailure(
        resolution: TshProviderResolution,
        objectId: TshObjectId
    ) = TshResult.Failure(
        resolution.diagnostics.filter { it.severity == TshDiagnosticSeverity.ERROR }.ifEmpty {
            listOf(TshDiagnostic(
                "TSH-PROVIDER-006", TshDiagnosticSeverity.ERROR, TshDiagnosticStage.PROVIDER,
                "Provider resolution is not satisfiable", objectId = objectId
            ))
        }
    )

    private fun engineFailure(code: String, message: String) = TshResult.Failure(listOf(
        TshDiagnostic(
            code = code, severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.ENGINE, message = message
        )
    ))

    override fun close() = synchronized(lifecycleLock) {
        if (stateRef.get() != TshEngineState.CLOSED) {
            stateRef.set(TshEngineState.CLOSED)
            // Retire queries first: each compiled query may retain a Grammar Language lease.
            // Then retire Trees, Parsers and Languages owned by the resource coordinator.
            attachments.close()
            nativeQueryLeases.close()
            compiledQueries.close()
            nativeResources.close()
            // Close admission only after retiring resources; outstanding reservations can still release.
            memoryBudget.close()
            providers.close()
            objects.close()
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
    /** Controls non-fatal diagnostics returned by Engine.resolveProviders; errors are never suppressed. */
    val enableDiagnostics: Boolean = true,
    /** Makes Engine.attach/refresh/resolveProviders return Failure when a required capability is unavailable; failed strict attach is detached atomically. */
    val strictProviderValidation: Boolean = true,
    /** Platform-neutral shared memory accounting policy used by Engine-owned parser operations. */
    val memoryBudgetOptions: TshMemoryBudgetOptions = TshMemoryBudgetOptions()
) {
    /** Preserves the original two-argument Java/Kotlin construction form. */
    constructor(enableDiagnostics: Boolean, strictProviderValidation: Boolean) : this(
        enableDiagnostics, strictProviderValidation, TshMemoryBudgetOptions()
    )
}
