package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSTree

/** A syntax tree registered with the Engine resource coordinator and ready for query execution. */
data class TshOwnedSyntaxTree(
    val snapshot: TshSyntaxTreeSnapshot,
    val registration: TshNativeResourceRegistry.Registration<TSTree>
)

/**
 * Owns one Tree-sitter parser and its Grammar dependency.
 *
 * The parser's Language lease is retained for the parser's entire registered lifetime. Each parsed
 * tree receives its own Language lease, so a tree can safely outlive both the parser and a grammar
 * provider's external ownership. All resources are registered with the same Engine coordinator
 * used by the query compiler/executor.
 */
class TshTreeSitterParserOwner private constructor(
    private val resources: TshNativeResourceRegistry,
    private val parserLanguageLease: TshNativeResourceLeaseStore.Lease<TSLanguage>,
    private val grammar: TshGrammarDescriptor,
    private val parser: TshNativeResourceRegistry.Registration<TSParser>
) : AutoCloseable {

    /**
     * Parses a document and transfers the resulting tree plus a Language dependency lease to the
     * resource registry. The caller retires the tree through [TshNativeResourceRegistry.retire]
     * when the document/tree revision is no longer needed.
     */
    fun parse(
        documentId: String,
        documentVersion: Long,
        treeRevision: Long,
        source: String
    ): TshResult<TshOwnedSyntaxTree> {
        if (documentId.isBlank() || documentVersion < 0 || treeRevision < 0) {
            return failure(
                "TSH-PARSER-OWNER-001",
                "Document identity, document version, and tree revision are invalid",
                TshDiagnosticStage.PARSE
            )
        }

        val parserLease = resources.acquire(parser.handle)
            ?: return failure(
                "TSH-PARSER-OWNER-002",
                "Parser is retired, closed, or unavailable",
                TshDiagnosticStage.LIFECYCLE,
                parser.descriptor.id
            )
        // Retain from the Parser's existing dependency lease. This remains legal after the
        // Grammar provider retires its public handle, while preventing resurrection from the store.
        val languageLease = parserLanguageLease.retain()
            ?: run {
                parserLease.close()
                return failure(
                    "TSH-PARSER-OWNER-003",
                    "Parser's retained Grammar language lease is no longer available",
                    TshDiagnosticStage.LIFECYCLE,
                    parser.descriptor.id
                )
            }

        var tree: TSTree? = null
        var languageLeaseTransferred = false
        try {
            val parsedTree = parserLease.resource.parseString(source)
                ?: return failure(
                    "TSH-PARSER-OWNER-004",
                    "Tree-sitter parser returned no syntax tree",
                    TshDiagnosticStage.PARSE,
                    parser.descriptor.id
                )
            tree = parsedTree
            if (!parsedTree.canAccess()) {
                return failure(
                    "TSH-PARSER-OWNER-005",
                    "Tree-sitter returned an inaccessible syntax tree",
                    TshDiagnosticStage.PARSE,
                    parser.descriptor.id
                )
            }

            val root = parsedTree.rootNode
            if (!root.canAccess()) {
                return failure(
                    "TSH-PARSER-OWNER-006",
                    "Parsed syntax tree root node is inaccessible",
                    TshDiagnosticStage.PARSE,
                    parser.descriptor.id
                )
            }
            val rootRange = TshByteRange(root.startByte, root.endByte)
            val registration = resources.register(
                resource = parsedTree,
                type = "syntax-tree",
                metadata = mapOf(
                    "documentId" to documentId,
                    "documentVersion" to documentVersion.toString(),
                    "treeRevision" to treeRevision.toString(),
                    "grammarFingerprint" to grammar.fingerprint
                ),
                dependencies = listOf(languageLease)
            )
            languageLeaseTransferred = true
            tree = null // ownership moved to the Engine resource registry
            val snapshot = TshSyntaxTreeSnapshot(
                documentId = documentId,
                documentVersion = documentVersion,
                treeRevision = treeRevision,
                grammar = grammar,
                rootByteRange = rootRange,
                nativeObjectId = registration.descriptor.id,
                inputEncoding = TshTreeSitterInputEncoding.UTF16_LE
            )
            return TshResult.Success(TshOwnedSyntaxTree(snapshot, registration))
        } catch (error: Throwable) {
            return failure(
                "TSH-PARSER-OWNER-007",
                "Tree-sitter parse or tree publication failed: ${error.message}",
                TshDiagnosticStage.PARSE,
                parser.descriptor.id,
                error
            )
        } finally {
            tree?.let { runCatching { it.close() } }
            if (!languageLeaseTransferred) languageLease.close()
            parserLease.close()
        }
    }

    /** Retires the parser; an active parse retains it until that operation finishes. */
    override fun close() {
        resources.retire(parser.descriptor.id)
    }

    companion object {
        /**
         * Creates a parser and transfers its Language lease into the parser's resource entry.
         * The supplied Language handle must belong to [resources].
         */
        @JvmStatic
        fun open(
            resources: TshNativeResourceRegistry,
            languageHandle: TshNativeResourceLeaseStore.Handle<TSLanguage>,
            grammar: TshGrammarDescriptor
        ): TshResult<TshTreeSitterParserOwner> {
            val languageLease = resources.acquire(languageHandle)
                ?: return failure(
                    "TSH-PARSER-OWNER-008",
                    "Cannot acquire the Grammar language for parser creation",
                    TshDiagnosticStage.LIFECYCLE
                )

            var nativeParser: TSParser? = null
            var ownershipTransferred = false
            try {
                val parser = TSParser.create()
                nativeParser = parser
                parser.setLanguage(languageLease.resource)
                val registration = resources.register(
                    resource = parser,
                    type = "tree-sitter-parser",
                    metadata = mapOf(
                        "languageId" to grammar.languageId,
                        "grammarFingerprint" to grammar.fingerprint
                    ),
                    dependencies = listOf(languageLease)
                )
                ownershipTransferred = true
                return TshResult.Success(
                    TshTreeSitterParserOwner(resources, languageLease, grammar, registration)
                )
            } catch (error: Throwable) {
                return failure(
                    "TSH-PARSER-OWNER-009",
                    "Cannot create or register Tree-sitter parser: ${error.message}",
                    TshDiagnosticStage.LIFECYCLE,
                    cause = error
                )
            } finally {
                if (!ownershipTransferred) {
                    nativeParser?.let { runCatching { it.close() } }
                    languageLease.close()
                }
            }
        }
    }
}

private fun failure(
    code: String,
    message: String,
    stage: TshDiagnosticStage,
    objectId: TshObjectId? = null,
    cause: Throwable? = null
): TshResult.Failure = TshResult.Failure(
    listOf(
        TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = stage,
            message = message,
            objectId = objectId,
            cause = cause
        )
    )
)
