package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSInputEdit

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
        val expectedAfterLength = try {
            change.edits.fold(before.lengthUtf16) { length, edit ->
                Math.addExact(
                    Math.subtractExact(length, edit.endUtf16 - edit.startUtf16),
                    edit.replacementLengthUtf16
                )
            }
        } catch (error: ArithmeticException) {
            return failure(
                "TSH-INCREMENTAL-UPDATE-004",
                "TextChange length arithmetic overflowed"
            )
        }
        if (expectedAfterLength != after.lengthUtf16) {
            return failure(
                "TSH-INCREMENTAL-UPDATE-005",
                "TextChange predicts UTF-16 length $expectedAfterLength but after Snapshot has ${after.lengthUtf16}"
            )
        }

        val compiledResult = TshTreeSitterEditCompiler.compile(before, change)
        if (compiledResult is TshResult.Failure) return compiledResult
        val compiled = compiledResult as TshResult.Success<List<TSInputEdit>>
        val nativeEdits = compiled.value
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

        val parsedSuccess = parsed as TshResult.Success<TshOwnedSyntaxTree>
        val replacementTree = parsedSuccess.value
        // parse() has released its old-tree read lease before returning, so retirement is safe.
        parserOwner.retireTree(oldTree)
        val appliedSuccess = applied as TshResult.Success<Unit>
        return TshResult.Success(
            replacementTree,
            compiled.diagnostics + appliedSuccess.diagnostics + parsedSuccess.diagnostics
        )
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
