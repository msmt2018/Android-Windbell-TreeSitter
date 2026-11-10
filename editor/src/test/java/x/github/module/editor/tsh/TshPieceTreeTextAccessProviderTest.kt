package x.github.module.editor.tsh

import com.zerostudio.idea.piecetable.PieceTreeTextBufferBuilder
import com.zerostudio.idea.treesitter.tsh.api.TshCapability
import com.zerostudio.idea.treesitter.tsh.api.TshCapabilitySet
import com.zerostudio.idea.treesitter.tsh.api.TshObjectRegistry
import com.zerostudio.idea.treesitter.tsh.api.TshProviderRegistry
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TshPieceTreeTextAccessProviderTest {
    private fun document(
        text: String,
        documentId: String = "piece-tree-doc"
    ): Pair<com.zerostudio.idea.piecetable.PieceTreeTextBuffer, TshPieceTreeDocument> {
        val buffer = PieceTreeTextBufferBuilder(text).build()
        // This test has no concurrent editor. Real editor integrations must take their existing
        // document read lock inside this factory before calling createSnapshot().
        return buffer to TshPieceTreeDocument(documentId, "text") { buffer.createSnapshot() }
    }

    @Test
    fun readsBoundedRangesFromPieceTreeSnapshotWithoutFlatteningBuffer() {
        val (_, document) = document("prefix-😀-suffix")
        val provider = TshPieceTreeTextAccessProvider()
        val lease = (provider.acquireSnapshot(document) as TshResult.Success).value

        val chunk = provider.readChunk(lease, 7L, 2) as TshResult.Success
        assertEquals("😀", chunk.value.text.toString())
        assertEquals(7L, chunk.value.startUtf16)
        assertEquals(9L, chunk.value.endUtf16)
        assertEquals(lease.snapshot.version, chunk.value.documentVersion)
        lease.close()
    }

    @Test
    fun snapshotRemainsReadableAfterLivePieceTreeChangesEol() {
        val (buffer, document) = document("first\nsecond")
        val provider = TshPieceTreeTextAccessProvider()
        val lease = (provider.acquireSnapshot(document) as TshResult.Success).value
        val oldVersion = lease.snapshot.version

        buffer.setEOL("\r\n")

        assertTrue(buffer.getVersionId().toLong() > oldVersion)
        assertEquals("first\nsecond", lease.snapshot.readUtf16(0L, lease.snapshot.lengthUtf16).toString())
        assertFalse(lease.isClosed)
        lease.close()
    }

    @Test
    fun snapshotExposesPieceTreeIndexedCoordinatesWithoutReadingText() {
        val (buffer, document) = document("first\\n😀tail\\nend")
        buffer.getPieceTree().insert(buffer.length, "typed\\n", eolNormalized = true)
        val provider = TshPieceTreeTextAccessProvider()
        val snapshot = (provider.snapshot(document) as TshResult.Success).value
        val oldEndPosition = buffer.getPositionAt(buffer.length)

        assertEquals(
            com.zerostudio.idea.treesitter.tsh.api.TshTextPosition(1, 0L),
            snapshot.lineColumnAtUtf16(6L)
        )
        assertEquals(
            com.zerostudio.idea.treesitter.tsh.api.TshTextPosition(1, 2L),
            snapshot.lineColumnAtUtf16(8L)
        )
        assertEquals(null, snapshot.lineColumnAtUtf16(7L))

        buffer.applyEdits(
            listOf(com.zerostudio.idea.piecetable.common.SingleEditOperation(
                com.zerostudio.idea.piecetable.common.Range(1, 1, 1, 1), "changed\\n"
            )),
            false,
            false
        )
        // The snapshot's coordinate index remains pinned to the before-version.
        assertEquals(
            com.zerostudio.idea.treesitter.tsh.api.TshTextPosition(1, 0L),
            snapshot.lineColumnAtUtf16(6L)
        )
        assertEquals(
            com.zerostudio.idea.treesitter.tsh.api.TshTextPosition(
                oldEndPosition.lineNumber - 1,
                (oldEndPosition.column - 1).toLong()
            ),
            snapshot.lineColumnAtUtf16(snapshot.lengthUtf16)
        )
    }

    @Test
    fun providerRejectsNonPieceTreeDocument() {
        val result = TshPieceTreeTextAccessProvider().snapshot(Any())
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-PIECETREE-001", (result as TshResult.Failure).diagnostics.single().code)
    }


    @Test
    fun providerParticipatesInNormalTshCapabilityNegotiation() {
        val (_, document) = document("hello")
        val provider = TshPieceTreeTextAccessProvider()
        val objects = TshObjectRegistry()
        val providers = TshProviderRegistry()
        providers.register(provider)
        val descriptor = objects.register(document, "editor-document")

        val resolution = providers.resolveFor(
            document,
            descriptor,
            required = TshCapabilitySet.of(TshCapability("text.snapshot", 1))
        )

        assertTrue(resolution.isSatisfiable)
        assertTrue(resolution.selectedProviders.any { it === provider })
    }

    @Test
    fun providerPreservesDocumentIdentityAndLanguageOutsideSnapshotStorage() {
        val (_, document) = document("hello", "doc-42")
        val provider = TshPieceTreeTextAccessProvider()
        val snapshot = (provider.snapshot(document) as TshResult.Success).value

        assertEquals("doc-42", snapshot.documentId)
        assertEquals("hello", snapshot.readUtf16(0L, snapshot.lengthUtf16).toString())
    }
    @Test
    fun providerDeliversChangeAndDisposalSubscriptionsExactlyUntilClosed() {
        val (_, document) = document("before")
        val provider = TshPieceTreeTextAccessProvider()
        var changes = 0
        var disposed = 0
        val changeListener = com.zerostudio.idea.treesitter.tsh.api.TshTextChangeListener { changes++ }
        val disposedListener = com.zerostudio.idea.treesitter.tsh.api.TshTextDocumentDisposedListener { id, version ->
            assertEquals("piece-tree-doc", id)
            assertEquals(9L, version)
            disposed++
        }
        val changeSubscription = (provider.subscribe(document, changeListener) as TshResult.Success).value
        val disposedSubscription = (provider.subscribeDisposed(document, disposedListener) as TshResult.Success).value

        document.publishChange(
            com.zerostudio.idea.treesitter.tsh.api.TshTextChange(
                "piece-tree-doc", 1L, 2L, emptyList()
            )
        )
        changeSubscription.close()
        document.publishChange(
            com.zerostudio.idea.treesitter.tsh.api.TshTextChange(
                "piece-tree-doc", 2L, 3L, emptyList()
            )
        )
        document.dispose(9L)
        disposedSubscription.close()

        assertEquals(1, changes)
        assertEquals(1, disposed)
        assertTrue(document.isDisposed())
    }

    @Test
    fun convertsAppliedChangesWithoutMaterializingLargeTextSlices() {
        val changes = listOf(
            x.github.module.editor.model.EditorDocument.AppliedChange(
                range = com.zerostudio.idea.piecetable.common.Range(2, 3, 2, 5),
                rangeOffset = 7,
                insertedLinesCnt = 0,
                insertedTextLength = 40_000,
                deletedLinesCnt = 0,
                deletedTextLength = 2,
                finalLineNumber = 2,
                finalColumn = 40_003,
                content = com.zerostudio.idea.piecetable.TextSlice.of("x".repeat(40_000))
            ),
            x.github.module.editor.model.EditorDocument.AppliedChange(
                range = com.zerostudio.idea.piecetable.common.Range(1, 2, 1, 2),
                rangeOffset = 1,
                insertedLinesCnt = 1,
                insertedTextLength = 13,
                deletedLinesCnt = 0,
                deletedTextLength = 0,
                finalLineNumber = 2,
                finalColumn = 12,
                content = com.zerostudio.idea.piecetable.TextSlice.of("a\nbbbbbbbbbbb")
            )
        )
        val result = editorChangesToTshChange("piece-tree-doc", 10L, 11L, changes)
        assertTrue(result is TshResult.Success)
        val edits = (result as TshResult.Success).value.edits
        assertEquals(listOf(1L, 7L), edits.map { it.startUtf16 })
        assertEquals(listOf(0, 1), edits.map { it.startLine })
        assertEquals(listOf(1L, 2L), edits.map { it.startColumnUtf16 })
        assertEquals(listOf(13L, 40_000L), edits.map { it.replacementLengthUtf16 })
        assertTrue(edits.all { it.replacement.isEmpty() })
        assertEquals(listOf(1, 0), edits.map { it.replacementLineFeedCount })
        assertEquals(listOf(11L, 40_000L), edits.map { it.replacementTrailingUtf16Length })

        val fallback = editorChangesToTshChange(
            "piece-tree-doc", 10L, 11L, changes, useEditorLineCoordinates = false
        ) as TshResult.Success
        assertTrue(fallback.value.edits.all { it.startLine == null && it.oldEndLine == null })
    }

    @Test
    fun providerReportsDocumentDisposalToSubscribers() {
        val (_, document) = document("hello")
        val provider = TshPieceTreeTextAccessProvider()
        var finalVersion = -1L
        val listener = com.zerostudio.idea.treesitter.tsh.api.TshTextDocumentDisposedListener { _, version ->
            finalVersion = version
        }
        val subscription = (provider.subscribeDisposed(document, listener) as TshResult.Success).value
        document.dispose(17L)
        subscription.close()
        assertEquals(17L, finalVersion)
        assertTrue(document.isDisposed())
        assertTrue(provider.snapshot(document) is TshResult.Failure)
    }

    @Test
    fun editorBoundSnapshotFactoryRejectsCallsFromOtherThreads() {
        val buffer = PieceTreeTextBufferBuilder("thread-bound").build()
        val document = TshPieceTreeDocument(
            "thread-bound-doc",
            "text",
            snapshotFactory = { buffer.createSnapshot() },
            snapshotThread = Thread.currentThread()
        )
        val provider = TshPieceTreeTextAccessProvider()
        var result: TshResult<com.zerostudio.idea.treesitter.tsh.api.TshTextSnapshot>? = null
        val worker = Thread { result = provider.snapshot(document) }
        worker.start()
        worker.join()

        assertTrue(result is TshResult.Failure)
        assertEquals(
            "TSH-PIECETREE-004",
            (result as TshResult.Failure).diagnostics.single().code
        )
    }

}
