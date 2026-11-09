package com.zerostudio.idea.treesitter.tsh.api

/**
 * Executes the complete host-snapshot -> edit compiler -> Native tree edit -> incremental parse
 * chain for one already-committed document change.
 *
 * The caller owns both Snapshot Leases and must keep them open for this synchronous operation.
 * On success the old edited tree is retired only after the replacement tree has been registered.
 * If parsing fails after the edit transaction, the old tree remains registered at afterVersion;
 * callers may retry parsing it or retire it, but must not use it with the before-version document.
 */
object TshTreeSitterIncrementalUpdate {
    @JvmStatic
    fun apply(
        parserOwner: TshTreeSitterParserOwner,
        textProvider: TshTextAccessProvider,
        oldTree: TshOwnedSyntaxTree,
        beforeSnapshotLease: TshTextSnapshotLease,
        change: TshTextChange,
        afterSnapshotLease: TshTextSnapshotLease,
        targetTreeRevision: Long,
        chunkSizeBytes: Int = 64 * 1024
    ): TshResult<TshOwnedSyntaxTree> {
        if (beforeSnapshotLease.isClosed || afterSnapshotLease.isClosed) {
            return failure(
                "TSH-INCREMENTAL-UPDATE-001",
                "Before/after Snapshot Lease must remain open during incremental update"
            )
        }
        val before = beforeSnapshotLease.snapshot
        val after = afterSnapshotLease.snapshot
        if (before.documentId != change.documentId ||
            before.version != change.beforeVersion ||
            after.documentId != change.documentId ||
            after.version != change.afterVersion
        ) {
            return failure(
                "TSH-INCREMENTAL-UPDATE-002",
                "Change and before/after Snapshot identities or versions do not match"
            )
        }
        if (oldTree.snapshot.documentId != before.documentId ||
            oldTree.snapshot.documentVersion != before.version
        ) {
            return failure(
                "TSH-INCREMENTAL-UPDATE-003",
                "Old syntax tree is not based on the supplied before-version Snapshot"
            )
        }

        val edits = when (val compiled = TshTreeSitterEditCompiler.compile(before, change)) {
            is TshResult.Success -> compiled
            is TshResult.Failure -> return compiled
        }
        val nativeEdits = (edits as TshResult.Success).value
        val applied = parserOwner.editTreeBatch(
            oldTree,
            nativeEdits,
            change.afterVersion,
            targetTreeRevision
        )
        if (applied is TshResult.Failure) return applied

        val parsed = parserOwner.parse(
            afterSnapshotLease,
            textProvider,
            targetTreeRevision,
            oldTree,
            chunkSizeBytes
        )
        if (parsed is TshResult.Failure) return parsed

        val replacementTree = (parsed as TshResult.Success).value
        // parse() has released its old-tree read lease before returning, so retirement is safe.
        parserOwner.retireTree(oldTree)
        return TshResult.Success(replacementTree, edits.diagnostics + applied.diagnostics + parsed.diagnostics)
    }

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(
        TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.PARSE,
            message = message
        )
    ))
}
