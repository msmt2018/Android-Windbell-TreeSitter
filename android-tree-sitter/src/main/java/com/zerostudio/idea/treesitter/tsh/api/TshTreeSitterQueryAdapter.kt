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
) : TshQueryCompiler {

    override fun compile(
        source: TshAtsSource,
        grammar: TshGrammarDescriptor,
        options: TshQueryCompileOptions
    ): TshResult<TshCompiledQuery> {
        if (options.strictNodeValidation) {
            return failure(
                "TSH-QUERY-COMPILE-005",
                "strictNodeValidation requires a Grammar Node Schema Provider, which is not installed"
            )
        }
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
        var nativeQueryTransferred = false
        var nativeQueryForCleanup: TSQuery? = null
        try {
            val language = languageLease.resource
            val nativeQuery = try {
                TSQuery.create(language, source.text).also { nativeQueryForCleanup = it }
            } catch (error: Throwable) {
                return failure("TSH-QUERY-COMPILE-001", "Tree-sitter query creation failed: ${error.message}", cause = error)
            }

        if (!nativeQuery.canAccess()) {
            val nativeError = runCatching { nativeQuery.errorType }.getOrNull()
            val offset = runCatching { nativeQuery.errorOffset }.getOrNull() ?: -1
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
            return failure("TSH-QUERY-COMPILE-003", "Cannot inspect compiled query: ${error.message}", cause = error)
        }

        if (patternCount > options.maxPatternCount) {
            return failure(
                "TSH-QUERY-COMPILE-004",
                "Compiled query has $patternCount patterns; configured maximum is ${options.maxPatternCount}"
            )
        }

        val hash = sha256(source.text)
        val queryId = source.sourceId?.takeIf { it.isNotBlank() } ?: "tsq:$hash"
        val revision = hash
        val objectDescriptor = try {
            objects.register(nativeQuery, "tree-sitter-query")
        } catch (error: Throwable) {
            return failure("TSH-QUERY-REGISTRY-004", "Cannot register native query: ${error.message}", cause = error)
        }
        val deferredCloseActions = ArrayList<AutoCloseable>(2)
        try {
            return queries.withLock {
                val previous = queries.get(queryId)
                try {
                    leases.registerDeferred(
                        queryId,
                        objectDescriptor.id,
                        nativeQuery,
                        dependencies = listOf(languageLease)
                    )?.let(deferredCloseActions::add)
                    nativeQueryTransferred = true
                    languageLeaseTransferred = true
                } catch (error: Throwable) {
                    objects.unregister(nativeQuery)
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
                        leases.retireDeferred(queryId).second?.let(deferredCloseActions::add)
                        registered
                    }
                }
            }
        } finally {
            // Query replacement may retire the previous Native query immediately. Run all close
            // actions only after the compiled-query transaction lock has been released.
            deferredCloseActions.forEach { runCatching { it.close() } }
        }
        } finally {
            if (!nativeQueryTransferred) {
                nativeQueryForCleanup?.let { runCatching { it.close() } }
            }
            if (!languageLeaseTransferred) {
                languageLease.close()
            }
        }
    }

    /**
     * Retires a compiled query. Active executions retain leases; native close is deferred until
     * the final execution lease is released.
     */
    fun release(queryId: String): Boolean {
        var deferredClose: AutoCloseable? = null
        val released = queries.withLock {
            val compiled = queries.unregister(queryId) ?: return@withLock false
            val id = compiled.nativeObjectId ?: return@withLock true
            objects.unregister(id)
            val retirement = leases.retireDeferred(queryId)
            deferredClose = retirement.second
            true
        }
        // Native query and its retained Language lease are closed outside the query catalog lock.
        deferredClose?.let { runCatching { it.close() } }
        return released
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
        if (budget.maxSteps != null) {
            return failure(
                "TSH-QUERY-EXEC-021",
                "maxSteps cannot be enforced by the current TSQueryCursor API; omit maxSteps or use a future step-budget capable adapter"
            )
        }
        if (!query.isCompatibleWith(tree.grammar)) {
            return failure("TSH-QUERY-EXEC-001", "Compiled query grammar does not match syntax tree grammar")
        }
        if (query.nativeObjectId == null) {
            return failure("TSH-QUERY-EXEC-002", "Compiled query has no registered native query handle")
        }
        // Reject an edited-but-not-reparsed tree before acquiring either Native resource.
        val preflightTreeMetadata = objects.descriptor(tree.nativeObjectId)?.metadata
        if (preflightTreeMetadata?.get("treeState") == "EDITED_PENDING_PARSE") {
            return failure(
                "TSH-QUERY-EXEC-031",
                "Syntax tree has been edited for a newer document version and cannot be queried until incremental parsing publishes a replacement tree"
            )
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
        return try {
        val nativeQuery = queryLease.query
        val treeLease = if (resourceRegistry != null) {
            resourceRegistry.acquire(tree.nativeObjectId, TSTree::class.java)
        } else {
            val treeHandle = objects.find(tree.nativeObjectId) as? TSTree
                ?: return failure("TSH-QUERY-EXEC-005", "Native syntax tree handle is missing or has the wrong type")
            treeLeases.acquire(TshNativeResourceLeaseStore.Handle(tree.nativeObjectId, treeHandle))
        } ?: return failure("TSH-QUERY-EXEC-007", "Native syntax tree is retired, closed, or not registered")
        try {
        val treeDescriptor = objects.descriptor(tree.nativeObjectId)
            ?: return failure("TSH-QUERY-EXEC-022", "Syntax tree descriptor is no longer registered")
        val metadata = treeDescriptor.metadata
        if (metadata["treeState"] == "EDITED_PENDING_PARSE") {
            return failure(
                "TSH-QUERY-EXEC-031",
                "Syntax tree has been edited for a newer document version and cannot be queried until incremental parsing publishes a replacement tree"
            )
        }
        if (metadata["documentId"] != null && metadata["documentId"] != tree.documentId) {
            return failure("TSH-QUERY-EXEC-023", "Syntax tree document identity is stale")
        }
        if (metadata["documentVersion"] != null &&
            metadata["documentVersion"] != tree.documentVersion.toString()) {
            return failure("TSH-QUERY-EXEC-024", "Syntax tree document version is stale; refresh the OwnedSyntaxTree snapshot")
        }
        if (metadata["treeRevision"] != null &&
            metadata["treeRevision"] != tree.treeRevision.toString()) {
            return failure("TSH-QUERY-EXEC-025", "Syntax tree revision is stale; refresh the OwnedSyntaxTree snapshot")
        }
        if (metadata["grammarFingerprint"] != null &&
            metadata["grammarFingerprint"] != tree.grammar.fingerprint) {
            return failure("TSH-QUERY-EXEC-026", "Syntax tree Grammar metadata does not match the supplied snapshot")
        }
        val nativeTree = treeLease.resource

        if (!nativeQuery.canAccess()) return failure("TSH-QUERY-EXEC-006", "Native query has been closed")

        // Inspect the original S-expression source instead of depending on optional
        // TSQuery predicate-introspection methods that are not present in every supported
        // Tree-sitter binding artifact. The scanner ignores strings and ';' comments.
        val predicateNames = extractTreeSitterPredicateNames(query.source.text)
        val hasPredicates = predicateNames.isNotEmpty()
        if (hasPredicates && query.options.enablePredicates) {
            val declared = LinkedHashSet<String>()
            for (handler in predicateHandlers) {
                try {
                    handler.supportedPredicates.forEach { declared += it.removePrefix("#") }
                } catch (error: Exception) {
                    return failure(
                        "TSH-QUERY-EXEC-029",
                        "Cannot inspect predicate declarations from handler ${handler.javaClass.name}: ${error.message}",
                        cause = error
                    )
                } catch (error: LinkageError) {
                    return failure(
                        "TSH-QUERY-EXEC-030",
                        "Predicate handler declarations failed with a linkage error: ${error.message}",
                        cause = error
                    )
                }
            }
            val builtInPredicates = setOf(
                "eq?", "not-eq?", "any-eq?", "any-not-eq?",
                "match?", "not-match?", "any-match?", "any-not-match?",
                "any-of?", "not-any-of?", "is?", "is-not?", "set!"
            )
            val required = predicateNames - builtInPredicates
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

        if (effectiveRange.startByte > Int.MAX_VALUE.toLong() ||
            effectiveRange.endByte > Int.MAX_VALUE.toLong()) {
            return failure(
                "TSH-QUERY-EXEC-020",
                "Native TSQueryCursor byte-range ABI is Int-limited; requested range exceeds Int.MAX_VALUE"
            )
        }

        val cursor = try {
            TSQueryCursor.create()
        } catch (error: Throwable) {
            return failure("TSH-QUERY-EXEC-011", "Cannot create Tree-sitter query cursor: ${error.message}", cause = error)
        }

        try {
            // Tree-sitter's match limit bounds pending in-progress matches, not total results.
            // Enforce maxMatches while draining the cursor instead.
            cursor.setByteRange(effectiveRange.startByte.toInt(), effectiveRange.endByte.toInt())
            cursor.setMatchLimit(budget.maxMatches)
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
                    // Null means the native cursor does not expose VM step counts.
                    stepsUsed = null,
                    isComplete = complete,
                    inputEncoding = tree.inputEncoding
                ),
                diagnostics = buildList {
                    if (!complete) add(diagnostic(
                        "TSH-QUERY-EXEC-015",
                        "Query result is partial because the total match budget was reached or Tree-sitter dropped pending matches",
                        TshDiagnosticSeverity.WARNING
                    ))
                    // Step budgets are rejected before entering Native execution; no false stepsUsed value is reported.
                }
            )
        } catch (error: Throwable) {
            failure("TSH-QUERY-EXEC-016", "Tree-sitter query execution failed: ${error.message}", cause = error)
        } finally {
            cursor.close()
        }
        } finally {
            treeLease.close()
        }
        } catch (error: Exception) {
            failure(
                "TSH-QUERY-EXEC-027",
                "Tree-sitter query resource cleanup failed: ${error.message}",
                cause = error
            )
        } catch (error: LinkageError) {
            failure(
                "TSH-QUERY-EXEC-028",
                "Tree-sitter query cleanup failed with a Native linkage error: ${error.message}",
                cause = error
            )
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


/**
 * Reads Tree-sitter predicate identifiers from query source without matching text inside
 * string literals or line comments. Native TSQuery compilation remains the syntax authority.
 */
internal fun extractTreeSitterPredicateNames(source: String): Set<String> {
    val names = LinkedHashSet<String>()
    var index = 0
    var inString = false
    var escaped = false
    var inComment = false

    while (index < source.length) {
        val character = source[index]
        if (inComment) {
            if (character == '\\n') inComment = false
            index++
            continue
        }
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\\\' -> escaped = true
                character == '"' -> inString = false
            }
            index++
            continue
        }

        when (character) {
            ';' -> {
                inComment = true
                index++
            }
            '"' -> {
                inString = true
                index++
            }
            '#' -> {
                val start = index + 1
                var end = start
                while (end < source.length) {
                    val next = source[end]
                    if (next.isLetterOrDigit() || next == '_' || next == '-' || next == '?' || next == '!') {
                        end++
                    } else {
                        break
                    }
                }
                if (end > start) names += source.substring(start, end)
                index = end.coerceAtLeast(index + 1)
            }
            else -> index++
        }
    }
    return names
}
