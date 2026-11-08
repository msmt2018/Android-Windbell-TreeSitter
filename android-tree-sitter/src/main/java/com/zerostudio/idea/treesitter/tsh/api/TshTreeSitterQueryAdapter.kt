package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSQuery
import com.zerostudio.idea.treesitter.TSQueryCursor
import com.zerostudio.idea.treesitter.TSQueryError
import com.zerostudio.idea.treesitter.TSTree
import java.security.MessageDigest

/** Resolves a live language handle for a descriptor; the resolver retains language ownership. */
fun interface TshGrammarLanguageResolver {
    fun resolve(grammar: TshGrammarDescriptor): TSLanguage?
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
    private val compilerVersion: String = "treesitter-adapter-1"
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

        val language = try {
            languages.resolve(grammar)
        } catch (error: Throwable) {
            return failure("TSH-QUERY-GRAMMAR-001", "Grammar resolver failed: ${error.message}", cause = error)
        } ?: return failure("TSH-QUERY-GRAMMAR-002", "No live Tree-sitter language is registered for '${grammar.languageId}'")

        if (!language.canAccess()) {
            return failure("TSH-QUERY-GRAMMAR-003", "Resolved Tree-sitter language is closed")
        }

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
        val objectDescriptor = try {
            objects.register(nativeQuery, "tree-sitter-query")
        } catch (error: Throwable) {
            nativeQuery.close()
            return failure("TSH-QUERY-REGISTRY-004", "Cannot register native query: ${error.message}", cause = error)
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
        return when (val registered = queries.register(compiled, replace = true)) {
            is TshResult.Success -> registered
            is TshResult.Failure -> {
                objects.unregister(nativeQuery)
                nativeQuery.close()
                registered
            }
        }
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
    private val queries: TshCompiledQueryRegistry
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
        val registered = queries.get(query.queryId)
        if (registered?.revision != query.revision || registered.nativeObjectId != query.nativeObjectId) {
            return failure("TSH-QUERY-EXEC-003", "Compiled query is not the active registered revision")
        }
        val nativeQuery = objects.find(query.nativeObjectId) as? TSQuery
            ?: return failure("TSH-QUERY-EXEC-004", "Native query handle is missing or has the wrong type")
        val nativeTree = objects.find(tree.nativeObjectId) as? TSTree
            ?: return failure("TSH-QUERY-EXEC-005", "Native syntax tree handle is missing or has the wrong type")

        if (!nativeQuery.canAccess()) return failure("TSH-QUERY-EXEC-006", "Native query has been closed")
        if (!nativeTree.canAccess()) return failure("TSH-QUERY-EXEC-007", "Native syntax tree has been closed")

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
            cursor.setMatchLimit(budget.maxMatches.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            cursor.setByteRange(effectiveRange.startByte, effectiveRange.endByte)
            cursor.exec(nativeQuery, root)

            val captures = ArrayList<TshCapture>()
            while (true) {
                if (cancellation.isCancelled()) {
                    return failure("TSH-QUERY-EXEC-012", "Query cancelled between native match retrievals")
                }
                val match = cursor.nextMatch() ?: break
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
                    if (captures.size >= budget.maxMatches) break
                }
                if (captures.size >= budget.maxMatches) break
            }

            val exceeded = cursor.didExceedMatchLimit()
            val complete = !exceeded && captures.size < budget.maxMatches
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
                diagnostics = if (!complete) listOf(diagnostic(
                    "TSH-QUERY-EXEC-015",
                    "Query result is partial because the match limit was reached",
                    TshDiagnosticSeverity.WARNING
                )) else emptyList()
            )
        } catch (error: Throwable) {
            failure("TSH-QUERY-EXEC-016", "Tree-sitter query execution failed: ${error.message}", cause = error)
        } finally {
            runCatching { cursor.close() }
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
