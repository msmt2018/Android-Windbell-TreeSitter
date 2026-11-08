package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSQuery
import com.zerostudio.idea.treesitter.TSQueryCursor
import com.zerostudio.idea.treesitter.TSQueryError
import com.zerostudio.idea.treesitter.TSTree
import com.zerostudio.idea.treesitter.predicate.TSPredicateHandler
import java.security.MessageDigest

/** Resolves a registered, lease-managed language handle for a grammar descriptor. */
fun interface TshGrammarLanguageResolver {
    /** Returns a registered handle; the resolver retains no right to close it after registration. */
    fun resolve(grammar: TshGrammarDescriptor): TshNativeResourceLeaseStore.Handle<TSLanguage>?
}

/**
 * Compiles native Tree-sitter S-expression queries using the repository's existing TSQuery.
 *
 * ATS input is intentionally rejected here until its dedicated lexer/parser/compiler is added.
 * This prevents silently treating ATS syntax as native S-expression syntax.
 */
class TshTreeSitterQueryCompiler(
    private val languages: TshGrammarLanguageResolver,
    private val objects: TshObjectRegistry,
    private val queries: TshCompiledQueryRegistry,
    private val leases: TshNativeQueryLeaseStore,
    private val languageLeases: TshNativeResourceLeaseStore = TshNativeResourceLeaseStore(),
    private val compilerVersion: String = "treesitter-adapter-1",
    private val resourceRegistry: TshNativeResourceRegistry? = null
) : TshAtsCompiler {

    override fun compile(
        source: TshAtsSource,
        grammar: TshGrammarDescriptor,
        options: TshQueryCompileOptions
    ): TshResult<TshCompiledQuery> {
        if (source.language != TshQuerySourceLanguage.TREE_SITTER) {
            return failure(
                "TSH-ATS-COMPILER-001",
                "ATS source requires the ATS parser/compiler; native Tree-sitter query adapter will not reinterpret ATS syntax",
                TshDiagnosticStage.ATS
            )
        }

        val languageHandle = try {
            languages.resolve(grammar)
        } catch (error: Throwable) {
            return failure("TSH-QUERY-GRAMMAR-001", "Grammar resolver failed: ${error.message}", cause = error)
        } ?: return failure("TSH-QUERY-GRAMMAR-002", "No live Tree-sitter language is registered for '${grammar.languageId}'")
        val languageLease = if (resourceRegistry != null) {
            resourceRegistry.acquire(languageHandle)
        } else {
            languageLeases.acquire(languageHandle)
        } ?: return failure("TSH-QUERY-GRAMMAR-003", "Resolved Tree-sitter language is retired, closed, or not registered")
        var languageLeaseTransferred = false
        try {
            val language = languageLease.resource
            val nativeQuery = try {
                TSQuery.create(language, source.text)
            } catch (error: Throwable) {
                return failure("TSH-QUERY-COMPILE-001", "Tree-sitter query creation failed: ${error.message}", cause = error)
            }

        if (!nativeQuery.canAccess()) {
            val nativeError = runCatching { nativeQuery.errorType }.getOrNull()
            val offset = runCatching { nativeQuery.errorOffset }.getOrNull() ?: -1
            nativeQuery.close()
            return failure(
                "TSH-QUERY-COMPILE-002",
                "Tree-sitter rejected query (error=${nativeError ?: "unknown"}, byteOffset=$offset)",
                range = if (offset >= 0) TshSourceRange(
                    TshSourcePosition(offset, coordinateSystem = TshCoordinateSystem.UTF8_BYTE),
                    TshSourcePosition(offset, coordinateSystem = TshCoordinateSystem.UTF8_BYTE)
                ) else null
            )
        }

        val patternCount = try {
            nativeQuery.patternCount
        } catch (error: Throwable) {
            nativeQuery.close()
            return failure("TSH-QUERY-COMPILE-003", "Cannot inspect compiled query: ${error.message}", cause = error)
        }

        if (patternCount > options.maxPatternCount) {
            nativeQuery.close()
            return failure(
                "TSH-QUERY-COMPILE-004",
                "Compiled query has $patternCount patterns; configured maximum is ${options.maxPatternCount}"
            )
        }

        val hash = sha256(source.text)
        val queryId = source.sourceId?.takeIf { it.isNotBlank() } ?: "tsq:$hash"
        val revision = hash
        queries.withLock {
            val previous = queries.get(queryId)
            val objectDescriptor = try {
                objects.register(nativeQuery, "tree-sitter-query")
            } catch (error: Throwable) {
                nativeQuery.close()
                return failure("TSH-QUERY-REGISTRY-004", "Cannot register native query: ${error.message}", cause = error)
            }
            try {
                leases.register(queryId, objectDescriptor.id, nativeQuery, dependencies = listOf(languageLease))
                languageLeaseTransferred = true
            } catch (error: Throwable) {
                objects.unregister(nativeQuery)
                nativeQuery.close()
                return failure("TSH-QUERY-LEASE-001", "Cannot register native query ownership: ${error.message}", cause = error)
            }
            val compiled = TshCompiledQuery(
                queryId = queryId,
                revision = revision,
                grammar = grammar,
                source = source,
                sourceHash = hash,
                compilerVersion = compilerVersion,
                options = options,
                nativeObjectId = objectDescriptor.id
            )
            when (val registered = queries.register(compiled, replace = true)) {
                is TshResult.Success -> {
                    val oldId = previous?.nativeObjectId
                    if (oldId != null && oldId != objectDescriptor.id) {
                        objects.unregister(oldId)
                    }
                    registered
                }
                is TshResult.Failure -> {
                    objects.unregister(nativeQuery)
                    leases.retire(queryId)
                    registered
                }
            }
        }
        } finally {
            if (!languageLeaseTransferred) {
                languageLease.close()
            }
        }
    }

    /**
     * Retires a compiled query. Active executions retain leases; native close is deferred until
     * the final execution lease is released.
     */
    fun release(queryId: String): Boolean = queries.withLock {
        val compiled = queries.unregister(queryId) ?: return@withLock false
        val id = compiled.nativeObjectId ?: return@withLock true
        objects.unregister(id)
        leases.retire(queryId)
        true
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Executes compiled queries through TSQueryCursor. Query and tree remain caller/registry-owned;
 * only the per-execution cursor is created and closed by this adapter.
 */
class TshTreeSitterQueryExecutor(
    private val objects: TshObjectRegistry,
    private val queries: TshCompiledQueryRegistry,
    private val leases: TshNativeQueryLeaseStore,
    private val treeLeases: TshNativeResourceLeaseStore = TshNativeResourceLeaseStore(),
    private val predicateHandlers: List<TSPredicateHandler> = emptyList(),
    private val resourceRegistry: TshNativeResourceRegistry? = null
) : TshQueryExecutor {

    override fun execute(
        query: TshCompiledQuery,
        tree: TshSyntaxTreeSnapshot,
        range: TshByteRange?,
        budget: TshQueryExecutionBudget,
        cancellation: TshQueryCancellation
    ): TshResult<TshQueryResult> {
        if (!query.isCompatibleWith(tree.grammar)) {
            return failure("TSH-QUERY-EXEC-001", "Compiled query grammar does not match syntax tree grammar")
        }
        if (query.nativeObjectId == null) {
            return failure("TSH-QUERY-EXEC-002", "Compiled query has no registered native query handle")
        }
        val acquisition = queries.withLock {
            val registered = queries.get(query.queryId)
            val isCurrent = registered?.revision == query.revision &&
                registered.nativeObjectId == query.nativeObjectId
            isCurrent to if (isCurrent) leases.acquire(query.nativeObjectId) else null
        }
        if (!acquisition.first) {
            return failure("TSH-QUERY-EXEC-003", "Compiled query is not the active registered revision")
        }
        val queryLease = acquisition.second
            ?: return failure("TSH-QUERY-EXEC-004", "Native query is missing, retired, or currently unavailable")
        try {
        val nativeQuery = queryLease.query
        val treeLease = if (resourceRegistry != null) {
            resourceRegistry.acquire(tree.nativeObjectId, TSTree::class.java)
        } else {
            val treeHandle = objects.find(tree.nativeObjectId) as? TSTree
                ?: return failure("TSH-QUERY-EXEC-005", "Native syntax tree handle is missing or has the wrong type")
            treeLeases.acquire(TshNativeResourceLeaseStore.Handle(tree.nativeObjectId, treeHandle))
        } ?: return failure("TSH-QUERY-EXEC-007", "Native syntax tree is retired, closed, or not registered")
        try {
        val nativeTree = treeLease.resource

        if (!nativeQuery.canAccess()) return failure("TSH-QUERY-EXEC-006", "Native query has been closed")

        val hasPredicates = try {
            (0 until nativeQuery.patternCount).any {
                nativeQuery.getPredicatesForPattern(it).isNotEmpty()
            }
        } catch (error: Throwable) {
            return failure("TSH-QUERY-EXEC-018", "Cannot inspect query predicates: ${error.message}", cause = error)
        }
        if (hasPredicates && query.options.enablePredicates) {
            val declared = predicateHandlers.flatMap { handler ->
                runCatching { handler.supportedPredicates.toList() }.getOrDefault(emptyList())
            }.map { it.removePrefix("#") }.toSet()
            val required = buildSet {
                for (pattern in 0 until nativeQuery.patternCount) {
                    val steps = nativeQuery.predicatesForPattern(pattern)
                    var index = 0
                    while (index < steps.size) {
                        val nameStep = steps[index]
                        if (nameStep.type == com.zerostudio.idea.treesitter.TSQueryPredicateStep.Type.String) {
                            add(nativeQuery.getStringValueForId(nameStep.valueId).removePrefix("#"))
                        }
                        while (index < steps.size &&
                            steps[index].type != com.zerostudio.idea.treesitter.TSQueryPredicateStep.Type.Done) {
                            index++
                        }
                        index++
                    }
                }
            }
            val missing = required - declared
            if (missing.isNotEmpty()) {
                return failure(
                    "TSH-QUERY-EXEC-019",
                    "No registered TSPredicateHandler declares support for: ${missing.sorted().joinToString()}"
                )
            }
        }

        val root = try {
            nativeTree.rootNode
        } catch (error: Throwable) {
            return failure("TSH-QUERY-EXEC-008", "Cannot access syntax tree root: ${error.message}", cause = error)
        }
        if (!root.canAccess()) return failure("TSH-QUERY-EXEC-009", "Syntax tree root node is not accessible")

        val effectiveRange = range ?: tree.rootByteRange
        if (effectiveRange.startByte < tree.rootByteRange.startByte ||
            effectiveRange.endByte > tree.rootByteRange.endByte) {
            return failure("TSH-QUERY-EXEC-010", "Requested query range is outside the syntax tree root range")
        }

        val cursor = try {
            TSQueryCursor.create()
        } catch (error: Throwable) {
            return failure("TSH-QUERY-EXEC-011", "Cannot create Tree-sitter query cursor: ${error.message}", cause = error)
        }

        try {
            // Tree-sitter's match limit bounds pending in-progress matches, not total results.
            // Enforce maxMatches while draining the cursor instead.
            cursor.setByteRange(effectiveRange.startByte, effectiveRange.endByte)
            cursor.setPredicateEvaluationEnabled(query.options.enablePredicates)
            if (query.options.enablePredicates) {
                predicateHandlers.forEach(cursor::addPredicateHandler)
            }
            cursor.exec(nativeQuery, root)

            val captures = ArrayList<TshCapture>()
            var matchesSeen = 0
            var truncated = false
            while (true) {
                if (cancellation.isCancelled()) {
                    return failure("TSH-QUERY-EXEC-012", "Query cancelled between native match retrievals")
                }
                val match = cursor.nextMatch() ?: break
                if (matchesSeen >= budget.maxMatches) {
                    truncated = true
                    break
                }
                matchesSeen++
                for (captureIndex in match.captures.indices) {
                    val nativeCapture = match.getCapture(captureIndex)
                    val node = nativeCapture.node
                    if (!node.canAccess()) {
                        return failure("TSH-QUERY-EXEC-013", "Native query returned an inaccessible capture node")
                    }
                    val captureName = nativeQuery.getCaptureNameForId(nativeCapture.index)
                        ?: return failure("TSH-QUERY-EXEC-014", "Native query returned an unknown capture id")
                    captures += TshCapture(
                        name = captureName,
                        nodeType = node.type,
                        byteRange = TshByteRange(node.startByte, node.endByte),
                        nodeId = node.nodeId,
                        patternIndex = match.patternIndex
                    )
                }
            }

            val exceeded = cursor.didExceedMatchLimit()
            val complete = !exceeded && !truncated
            TshResult.Success(
                TshQueryResult(
                    queryId = query.queryId,
                    queryRevision = query.revision,
                    documentId = tree.documentId,
                    documentVersion = tree.documentVersion,
                    treeRevision = tree.treeRevision,
                    grammarFingerprint = tree.grammar.fingerprint,
                    captures = captures.toList(),
                    // TSQueryCursor does not expose VM step counts; zero means "not reported".
                    stepsUsed = 0L,
                    isComplete = complete
                ),
                diagnostics = buildList {
                    if (!complete) add(diagnostic(
                        "TSH-QUERY-EXEC-015",
                        "Query result is partial because the total match budget was reached or Tree-sitter dropped pending matches",
                        TshDiagnosticSeverity.WARNING
                    ))
                    add(diagnostic(
                        "TSH-QUERY-EXEC-017",
                        "The current TSQueryCursor API does not expose VM step counts; maxSteps is not enforced and stepsUsed is reported as 0",
                        TshDiagnosticSeverity.INFO
                    ))
                }
            )
        } catch (error: Throwable) {
            failure("TSH-QUERY-EXEC-016", "Tree-sitter query execution failed: ${error.message}", cause = error)
        } finally {
            runCatching { cursor.close() }
        }
        } finally {
            treeLease.close()
        }
        } finally {
            queryLease.close()
        }
    }
}

private fun failure(
    code: String,
    message: String,
    stage: TshDiagnosticStage = TshDiagnosticStage.ATS,
    range: TshSourceRange? = null,
    cause: Throwable? = null
): TshResult.Failure = TshResult.Failure(listOf(TshDiagnostic(
    code = code,
    severity = TshDiagnosticSeverity.ERROR,
    stage = stage,
    message = message,
    range = range,
    cause = cause
)))

private fun diagnostic(
    code: String,
    message: String,
    severity: TshDiagnosticSeverity
) = TshDiagnostic(
    code = code,
    severity = severity,
    stage = TshDiagnosticStage.ATS,
    message = message
)
