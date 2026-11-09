package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSParser
import com.zerostudio.idea.treesitter.TSTree

/** A syntax tree registered with the Engine resource coordinator and ready for query execution. */
class TshOwnedSyntaxTree(
    snapshot: TshSyntaxTreeSnapshot,
    registration: TshNativeResourceRegistry.Registration<TSTree>
) {
    @Volatile var snapshot: TshSyntaxTreeSnapshot = snapshot
        private set
    @Volatile var registration: TshNativeResourceRegistry.Registration<TSTree> = registration
        private set
    @Volatile internal var lastEditedDocumentVersion: Long? = null

    internal fun updateSnapshot(value: TshSyntaxTreeSnapshot) {
        snapshot = value
    }

    internal fun updateRegistration(value: TshNativeResourceRegistry.Registration<TSTree>) {
        registration = value
    }
}

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
    ): TshResult<TshOwnedSyntaxTree> = parseWithInput(
        documentId, documentVersion, treeRevision
    ) { parser -> parser.parseString(source) }

    /**
     * Parses one pinned host snapshot through Tree-sitter's bounded TSInput callback.
     *
     * The method retains an independent lease before entering Native code and releases that
     * retained handle after parseInput returns. The caller's lease remains independently owned.
     */
    fun parseSnapshot(
        provider: TshTextAccessProvider,
        snapshotLease: TshTextSnapshotLease,
        treeRevision: Long,
        chunkSizeBytes: Int = 64 * 1024
    ): TshResult<TshOwnedSyntaxTree> {
        if (snapshotLease.isClosed) return failure(
            "TSH-PARSER-OWNER-023",
            "Cannot parse a closed TextSnapshot lease",
            TshDiagnosticStage.LIFECYCLE
        )
        if (chunkSizeBytes < 2 || chunkSizeBytes > 1024 * 1024 || (chunkSizeBytes and 1) != 0) {
            return failure(
                "TSH-PARSER-OWNER-024",
                "Tree-sitter input chunk size must be even and between 2 bytes and 1 MiB",
                TshDiagnosticStage.PARSE
            )
        }
        val retained = when (val result = snapshotLease.retain()) {
            is TshResult.Success -> result.value
            is TshResult.Failure -> return TshResult.Failure(result.diagnostics)
        }
        try {
            val snapshot = retained.snapshot
            if (retained.isClosed) return failure(
                "TSH-PARSER-OWNER-025",
                "Retained TextSnapshot lease was closed before parsing",
                TshDiagnosticStage.LIFECYCLE
            )
            val input = TshSnapshotTreeSitterInput(provider, retained)
            return parseWithInput(
                snapshot.documentId,
                snapshot.version,
                treeRevision
            ) { parser -> parser.parseInput(null, input, chunkSizeBytes) }
        } finally {
            retained.close()
        }
    }

    private fun parseWithInput(
        documentId: String,
        documentVersion: Long,
        treeRevision: Long,
        parse: (TSParser) -> TSTree?
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
            val parsedTree = parse(parserLease.resource)
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
            tree = null
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
        } catch (error: LinkageError) {
            return failure(
                "TSH-PARSER-OWNER-022",
                "Tree-sitter Native linkage failed during parse: ${error.message}",
                TshDiagnosticStage.PARSE,
                parser.descriptor.id,
                error
            )
        } catch (error: Exception) {
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

    /**
     * Applies one Tree-sitter input edit without exposing its raw Native handle.
     * Multiple edits for one transaction may use the same target versions. Coordinates must be
     * computed in the current edited tree's UTF-16LE byte/point space.
     */
    fun editTree(
        ownedTree: TshOwnedSyntaxTree,
        edit: com.zerostudio.idea.treesitter.TSInputEdit,
        targetDocumentVersion: Long,
        targetTreeRevision: Long
    ): TshResult<Unit> {
        if (ownedTree.snapshot.grammar.fingerprint != grammar.fingerprint) {
            return failure(
                "TSH-PARSER-OWNER-010",
                "Cannot edit a tree produced by a different Grammar",
                TshDiagnosticStage.PARSE,
                ownedTree.registration.descriptor.id
            )
        }
        val initialSnapshot = ownedTree.snapshot
        if (targetDocumentVersion < initialSnapshot.documentVersion ||
            targetTreeRevision < initialSnapshot.treeRevision ||
            (targetDocumentVersion == initialSnapshot.documentVersion &&
                targetTreeRevision == initialSnapshot.treeRevision &&
                ownedTree.lastEditedDocumentVersion != targetDocumentVersion)) {
            return failure(
                "TSH-PARSER-OWNER-019",
                "Tree edit target revisions must advance or continue an existing edit transaction",
                TshDiagnosticStage.PARSE,
                ownedTree.registration.descriptor.id
            )
        }
        val lease = resources.acquireExclusive(ownedTree.registration.handle)
            ?: return failure(
                "TSH-PARSER-OWNER-011",
                "Cannot edit a retired or inaccessible syntax tree",
                TshDiagnosticStage.LIFECYCLE,
                ownedTree.registration.descriptor.id
            )
        var editAttempted = false
        return try {
            val currentSnapshot = ownedTree.snapshot
            if (currentSnapshot.documentVersion != initialSnapshot.documentVersion ||
                currentSnapshot.treeRevision != initialSnapshot.treeRevision) {
                return failure(
                    "TSH-PARSER-OWNER-021",
                    "Syntax tree changed after this edit was prepared; recompute the TSInputEdit against the current revision",
                    TshDiagnosticStage.PARSE,
                    ownedTree.registration.descriptor.id
                )
            }
            if (targetDocumentVersion < currentSnapshot.documentVersion ||
                targetTreeRevision < currentSnapshot.treeRevision ||
                (targetDocumentVersion == currentSnapshot.documentVersion &&
                    targetTreeRevision == currentSnapshot.treeRevision &&
                    ownedTree.lastEditedDocumentVersion != targetDocumentVersion)) {
                return failure(
                    "TSH-PARSER-OWNER-019",
                    "Tree edit target revisions changed while waiting for the exclusive lease",
                    TshDiagnosticStage.PARSE,
                    ownedTree.registration.descriptor.id
                )
            }
            editAttempted = true
            lease.resource.edit(edit)
            val root = lease.resource.rootNode
            if (!root.canAccess()) {
                resources.retire(ownedTree.registration.descriptor.id)
                return failure(
                    "TSH-PARSER-OWNER-020",
                    "Edited syntax tree root node is inaccessible",
                    TshDiagnosticStage.PARSE,
                    ownedTree.registration.descriptor.id
                )
            }
            val updatedSnapshot = currentSnapshot.copy(
                documentVersion = targetDocumentVersion,
                treeRevision = targetTreeRevision,
                rootByteRange = TshByteRange(root.startByte, root.endByte)
            )
            val metadata = resources.updateMetadata(
                ownedTree.registration.handle,
                mapOf(
                    "documentId" to updatedSnapshot.documentId,
                    "documentVersion" to updatedSnapshot.documentVersion.toString(),
                    "treeRevision" to updatedSnapshot.treeRevision.toString(),
                    "grammarFingerprint" to updatedSnapshot.grammar.fingerprint,
                    "inputMode" to "chunked-utf16le"
                )
            )
            if (metadata is TshResult.Failure) {
                resources.retire(ownedTree.registration.descriptor.id)
                return metadata
            }
            ownedTree.updateRegistration(ownedTree.registration.copy(descriptor = metadata.value))
            ownedTree.updateSnapshot(updatedSnapshot)
            ownedTree.lastEditedDocumentVersion = targetDocumentVersion
            TshResult.Success(Unit)
        } catch (error: Exception) {
            if (editAttempted) resources.retire(ownedTree.registration.descriptor.id)
            failure(
                "TSH-PARSER-OWNER-012",
                "Tree-sitter input edit failed: ${error.message}",
                TshDiagnosticStage.PARSE,
                ownedTree.registration.descriptor.id,
                error
            )
        } catch (error: LinkageError) {
            if (editAttempted) resources.retire(ownedTree.registration.descriptor.id)
            failure(
                "TSH-PARSER-OWNER-023",
                "Tree-sitter Native linkage failed during tree edit: ${error.message}",
                TshDiagnosticStage.PARSE,
                ownedTree.registration.descriptor.id,
                error
            )
        } finally {
            lease.close()
        }
    }

    /**
     * Parses a fixed-version host Snapshot through bounded UTF-16LE TSInput chunks.
     * No full-document String or contiguous Native input buffer is created by this path.
     * The caller retains ownership of snapshotLease and must close it after parsing.
     *
     * When oldTree is supplied, it must belong to the same document/Grammar and have been edited
     * through editTree() before this call.
     */
    fun parse(
        snapshotLease: TshTextSnapshotLease,
        textProvider: TshTextAccessProvider,
        treeRevision: Long,
        oldTree: TshOwnedSyntaxTree? = null,
        chunkSizeBytes: Int = 64 * 1024
    ): TshResult<TshOwnedSyntaxTree> {
        if (snapshotLease.isClosed) {
            return failure(
                "TSH-PARSER-OWNER-013",
                "Cannot parse from a closed TextSnapshot lease",
                TshDiagnosticStage.PARSE
            )
        }
        if (!textProvider.isAccessAllowedOnCurrentThread()) {
            return failure(
                "TSH-PARSER-OWNER-018",
                "TextAccessProvider does not permit reads on the current parse thread",
                TshDiagnosticStage.BINDING
            )
        }
        val snapshot = snapshotLease.snapshot
        if (snapshot.documentId.isBlank() || snapshot.version < 0L || treeRevision < 0L ||
            chunkSizeBytes < 4 || chunkSizeBytes > 1024 * 1024 || (chunkSizeBytes and 1) != 0) {
            return failure(
                "TSH-PARSER-OWNER-014",
                "Snapshot identity/version, tree revision, or chunk size is invalid",
                TshDiagnosticStage.PARSE
            )
        }
        if (oldTree != null && (
            oldTree.snapshot.documentId != snapshot.documentId ||
                oldTree.snapshot.documentVersion != snapshot.version ||
                oldTree.snapshot.grammar.fingerprint != grammar.fingerprint ||
                oldTree.lastEditedDocumentVersion != snapshot.version ||
                treeRevision < oldTree.snapshot.treeRevision
            )) {
            return failure(
                "TSH-PARSER-OWNER-015",
                "Incremental old tree must be edited to this document version and match the Grammar/tree revision",
                TshDiagnosticStage.PARSE,
                oldTree.registration.descriptor.id
            )
        }

        val parserLease = resources.acquire(parser.handle)
            ?: return failure(
                "TSH-PARSER-OWNER-002",
                "Parser is retired, closed, or unavailable",
                TshDiagnosticStage.LIFECYCLE,
                parser.descriptor.id
            )
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
        val oldTreeLease = if (oldTree != null) {
            resources.acquire(oldTree.registration.handle) ?: run {
                languageLease.close()
                parserLease.close()
                return failure(
                    "TSH-PARSER-OWNER-016",
                    "Incremental old tree has already been retired",
                    TshDiagnosticStage.LIFECYCLE,
                    oldTree.registration.descriptor.id
                )
            }
        } else null

        var tree: TSTree? = null
        var languageLeaseTransferred = false
        try {
            val input = TshSnapshotTreeSitterInput(textProvider, snapshotLease)
            val parsedTree = parserLease.resource.parseInput(
                oldTreeLease?.resource, input, chunkSizeBytes
            ) ?: return failure(
                "TSH-PARSER-OWNER-004",
                "Tree-sitter streaming parser returned no syntax tree",
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
            val registration = resources.register(
                resource = parsedTree,
                type = "syntax-tree",
                metadata = mapOf(
                    "documentId" to snapshot.documentId,
                    "documentVersion" to snapshot.version.toString(),
                    "treeRevision" to treeRevision.toString(),
                    "grammarFingerprint" to grammar.fingerprint,
                    "inputMode" to "chunked-utf16le"
                ),
                dependencies = listOf(languageLease)
            )
            languageLeaseTransferred = true
            tree = null
            val syntaxSnapshot = TshSyntaxTreeSnapshot(
                documentId = snapshot.documentId,
                documentVersion = snapshot.version,
                treeRevision = treeRevision,
                grammar = grammar,
                rootByteRange = TshByteRange(root.startByte, root.endByte),
                nativeObjectId = registration.descriptor.id,
                inputEncoding = TshTreeSitterInputEncoding.UTF16_LE
            )
            return TshResult.Success(TshOwnedSyntaxTree(syntaxSnapshot, registration))
        } catch (error: Exception) {
            return failure(
                "TSH-PARSER-OWNER-017",
                "Tree-sitter streaming parse or publication failed: ${error.message}",
                TshDiagnosticStage.PARSE,
                parser.descriptor.id,
                error
            )
        } catch (error: LinkageError) {
            return failure(
                "TSH-PARSER-OWNER-024",
                "Tree-sitter Native linkage failed during streaming parse: ${error.message}",
                TshDiagnosticStage.PARSE,
                parser.descriptor.id,
                error
            )
        } finally {
            tree?.let { runCatching { it.close() } }
            oldTreeLease?.close()
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
