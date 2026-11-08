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
