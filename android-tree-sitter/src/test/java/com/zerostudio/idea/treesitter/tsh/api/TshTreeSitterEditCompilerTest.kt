package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSInputEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TshTreeSitterEditCompilerTest {
    private fun snapshot(text: String, version: Long = 4L) = object : TshTextSnapshot {
        override val documentId = "doc"
        override val version = version
        override val lengthUtf16 = text.length.toLong()
        override fun readUtf16(start: Long, end: Long): CharSequence =
            text.substring(start.toInt(), end.toInt())
    }

    @Test
    fun compilesMultipleEditsInDescendingOrderAgainstOneBeforeSnapshot() {
        val before = snapshot("ab\ncd")
        val change = TshTextChange(
            documentId = "doc",
            beforeVersion = 4L,
            afterVersion = 5L,
            edits = listOf(
                TshTextEdit(1L, 2L, "XYZ"),
                TshTextEdit(4L, 5L, "Q\nR")
            )
        )

        val result = TshTreeSitterEditCompiler.compile(before, change)
        assertTrue(result is TshResult.Success)
        val edits = (result as TshResult.Success<List<TSInputEdit>>).value
        assertEquals(2, edits.size)
        assertEquals(8, edits[0].startByte)
        assertEquals(2, edits[1].startByte)
        assertEquals(1, edits[0].startPoint.row)
        assertEquals(2, edits[0].startPoint.column)
        assertEquals(2, edits[0].newEndPoint.row)
        assertEquals(2, edits[0].newEndPoint.column)
    }

    @Test
    fun rejectsAnEditThatSplitsASurrogatePair() {
        val result = TshTreeSitterEditCompiler.compile(
            snapshot("a😀b"),
            TshTextChange("doc", 4L, 5L, listOf(TshTextEdit(2L, 2L, "!")))
        )
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-EDIT-COMPILE-005",
            (result as TshResult.Failure).diagnostics.single().code)
    }

    @Test
    fun rejectsDocumentVersionMismatch() {
        val result = TshTreeSitterEditCompiler.compile(
            snapshot("abc", 4L),
            TshTextChange("doc", 3L, 5L, listOf(TshTextEdit(1L, 2L, "x")))
        )
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-EDIT-COMPILE-001",
            (result as TshResult.Failure).diagnostics.single().code)
    }

    @Test
    fun handlesCrlfAndUtf16ByteColumns() {
        val result = TshTreeSitterEditCompiler.compile(
            snapshot("a\r\nb"),
            TshTextChange("doc", 4L, 5L, listOf(TshTextEdit(3L, 4L, "😀")))
        )
        assertTrue(result is TshResult.Success)
        val edit = (result as TshResult.Success<List<TSInputEdit>>).value.single()
        assertEquals(6, edit.startByte)
        assertEquals(1, edit.startPoint.row)
        assertEquals(0, edit.startPoint.column)
        assertEquals(4, edit.newEndByte - edit.startByte)
    }
    @Test
    fun rejectsEditOutsideBeforeSnapshot() {
        val result = TshTreeSitterEditCompiler.compile(
            snapshot("abc"),
            TshTextChange("doc", 4L, 5L, listOf(TshTextEdit(2L, 4L, "x")))
        )
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-EDIT-COMPILE-004",
            (result as TshResult.Failure).diagnostics.single().code)
    }

    @Test
    fun acceptsEmptyChangeSetWithoutCreatingNativeEdits() {
        val result = TshTreeSitterEditCompiler.compile(
            snapshot("abc"),
            TshTextChange("doc", 4L, 5L, emptyList())
        )
        assertTrue(result is TshResult.Success)
        assertTrue((result as TshResult.Success<List<TSInputEdit>>).value.isEmpty())
    }

    @Test
    fun compilesPieceTreeSliceMetadataWithoutMaterializingReplacementText() {
        val change = TshTextChange(
            documentId = "doc",
            beforeVersion = 4L,
            afterVersion = 5L,
            edits = listOf(
                TshTextEdit(
                    startUtf16 = 1L,
                    endUtf16 = 2L,
                    replacement = "",
                    replacementLengthUtf16 = 1L,
                    replacementLineFeedCount = 0,
                    replacementTrailingUtf16Length = 1L
                )
            )
        )
        val result = TshTreeSitterEditCompiler.compile(snapshot("abc"), change)
        assertTrue(result is TshResult.Success)
        val edit = (result as TshResult.Success<List<TSInputEdit>>).value.single()
        assertEquals(2, edit.startByte)
        assertEquals(4, edit.newEndByte)
        assertEquals(0, edit.newEndPoint.row)
        assertEquals(4, edit.newEndPoint.column)
    }

    @Test
    fun allowsStableSameOffsetInsertionsAndCompilesThemInReverseOrder() {
        val change = TshTextChange(
            documentId = "doc",
            beforeVersion = 4L,
            afterVersion = 5L,
            edits = listOf(
                TshTextEdit(1L, 1L, "A"),
                TshTextEdit(1L, 1L, "B")
            )
        )
        val result = TshTreeSitterEditCompiler.compile(snapshot("abc"), change)
        assertTrue(result is TshResult.Success)
        val edits = (result as TshResult.Success<List<TSInputEdit>>).value
        assertEquals(listOf(2, 2), edits.map { it.startByte })
        assertEquals(listOf(4, 4), edits.map { it.newEndPoint.column })
    }

    @Test
    fun replacementLfMetricsMatchTreeSitterCrLfAndCarriageReturnColumns() {
        val crlfChange = TshTextChange(
            "doc", 4L, 5L,
            listOf(TshTextEdit(
                startUtf16 = 1L,
                endUtf16 = 1L,
                replacement = "",
                replacementLengthUtf16 = 4L,
                replacementLineFeedCount = 1,
                replacementTrailingUtf16Length = 1L
            ))
        )
        val crlfResult = TshTreeSitterEditCompiler.compile(snapshot("abc"), crlfChange)
        assertTrue(crlfResult is TshResult.Success)
        val crlfEdit = (crlfResult as TshResult.Success<List<TSInputEdit>>).value.single()
        assertEquals(1, crlfEdit.newEndPoint.row)
        assertEquals(2, crlfEdit.newEndPoint.column)

        val loneCrChange = TshTextChange(
            "doc", 4L, 5L,
            listOf(TshTextEdit(
                startUtf16 = 1L,
                endUtf16 = 1L,
                replacement = "",
                replacementLengthUtf16 = 3L,
                replacementLineFeedCount = 0,
                replacementTrailingUtf16Length = 3L
            ))
        )
        val loneCrResult = TshTreeSitterEditCompiler.compile(snapshot("abc"), loneCrChange)
        assertTrue(loneCrResult is TshResult.Success)
        val loneCrEdit = (loneCrResult as TshResult.Success<List<TSInputEdit>>).value.single()
        assertEquals(0, loneCrEdit.newEndPoint.row)
        assertEquals(8, loneCrEdit.newEndPoint.column)
    }

    @Test
    fun usesHostBeforeVersionCoordinatesWithoutScanningSnapshot() {
        val change = TshTextChange(
            "doc", 4L, 5L,
            listOf(TshTextEdit(
                startUtf16 = 1L,
                endUtf16 = 2L,
                replacement = "x",
                startLine = 0,
                startColumnUtf16 = 1L,
                oldEndLine = 0,
                oldEndColumnUtf16 = 2L
            ))
        )
        val result = TshTreeSitterEditCompiler.compile(snapshot("abc"), change)
        assertTrue(result is TshResult.Success)
        val edit = (result as TshResult.Success<List<TSInputEdit>>).value.single()
        assertEquals(0, edit.startPoint.row)
        assertEquals(2, edit.startPoint.column)
        assertEquals(0, edit.oldEndPoint.row)
        assertEquals(4, edit.oldEndPoint.column)
    }

}
