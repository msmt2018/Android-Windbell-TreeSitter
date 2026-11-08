package x.github.module.editor.model

import com.zerostudio.idea.piecetable.PieceTreeTextBufferBuilder
import com.zerostudio.idea.piecetable.common.Range
import com.zerostudio.idea.piecetable.common.SingleEditOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EditorBufferSnapshotTest {
    @Test
    fun pieceTreeSnapshotReadsBoundedChunksWithoutFlatteningAndSurvivesEdits() {
        val original = buildString {
            repeat(20_000) { append("line-").append(it).append("-😀\n") }
        }
        val tree = PieceTreeTextBufferBuilder().apply { acceptChunk(original) }.build()
        val buffer = PieceTreeBuffer(tree)

        val snapshot = buffer.createTextSnapshot()
        val originalVersion = snapshot.version
        assertEquals(original.length, snapshot.length)
        assertEquals(original.substring(0, 128), snapshot.getNearestChunk(0, 128))
        assertEquals(original.substring(4096, 8192), snapshot.substring(4096, 8192))

        buffer.applyEdits(
            listOf(SingleEditOperation(Range(1, 1, 1, 1), "changed:")),
            computeUndoEdits = false
        )

        assertEquals(originalVersion, snapshot.version)
        assertEquals(original, snapshot.toString())
        assertNotEquals(originalVersion, buffer.createTextSnapshot().version)
    }

    @Test
    fun boundedChunkReadAtEndReturnsEmptyAndHonorsRequestedSize() {
        val tree = PieceTreeTextBufferBuilder().apply { acceptChunk("a😀bc") }.build()
        val snapshot = PieceTreeBuffer(tree).createTextSnapshot()

        assertEquals("😀b", snapshot.getNearestChunk(1, 3))
        assertEquals("", snapshot.getNearestChunk(snapshot.length, 32))
    }
}
