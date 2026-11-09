package x.github.module.editor.tsh

import com.zerostudio.idea.piecetable.PieceTreeTextBufferBuilder
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
    fun providerRejectsNonPieceTreeDocument() {
        val result = TshPieceTreeTextAccessProvider().snapshot(Any())
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-PIECETREE-001", (result as TshResult.Failure).diagnostics.single().code)
    }

    @Test
    fun providerPreservesDocumentIdentityAndLanguageOutsideSnapshotStorage() {
        val (_, document) = document("hello", "doc-42")
        val provider = TshPieceTreeTextAccessProvider()
        val snapshot = (provider.snapshot(document) as TshResult.Success).value

        assertEquals("doc-42", snapshot.documentId)
        assertEquals("hello", snapshot.readUtf16(0L, snapshot.lengthUtf16).toString())
    }
}
