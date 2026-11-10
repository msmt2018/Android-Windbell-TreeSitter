package x.github.module.editor.tsh

import com.zerostudio.idea.treesitter.tsh.api.TshCoordinateSystem
import com.zerostudio.idea.treesitter.tsh.api.TshDocumentId
import com.zerostudio.idea.treesitter.tsh.api.TshRenderInstruction
import com.zerostudio.idea.treesitter.tsh.api.TshRenderPlan
import com.zerostudio.idea.treesitter.tsh.api.TshRenderPlanId
import com.zerostudio.idea.treesitter.tsh.api.TshRenderRevisions
import com.zerostudio.idea.treesitter.tsh.api.TshResolvedStyle
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import com.zerostudio.idea.treesitter.tsh.api.TshSourcePosition
import com.zerostudio.idea.treesitter.tsh.api.TshSourceRange
import com.zerostudio.idea.piecetable.common.Range
import x.github.module.editor.model.EditorDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TshRenderPlanSyntaxHighlighterTest {
    private val documentId = TshDocumentId("document-1")

    private fun plan(version: Long, document: TshDocumentId = documentId) = TshRenderPlan(
        id = TshRenderPlanId("plan-$version"),
        revisions = TshRenderRevisions(
            documentId = document,
            documentVersion = version,
            configRevision = "config-1",
            themeRevision = "theme-1",
            viewportRevision = "viewport-1",
            rendererRevision = "renderer-1"
        ),
        instructions = listOf(
            TshRenderInstruction(
                range = TshSourceRange(
                    start = TshSourcePosition(2L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                    end = TshSourcePosition(8L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
                    documentVersion = version
                ),
                style = TshResolvedStyle(foregroundArgb = 0xFFFF0000u)
            )
        )
    )

    @Test
    fun acceptsPlanForCurrentDocumentVersion() {
        val highlighter = TshRenderPlanSyntaxHighlighter(documentId) { 7L }
        val result = highlighter.updateRenderPlan(plan(7L))

        assertTrue(result is TshResult.Success)
        assertTrue(highlighter.diagnostics.isEmpty())
    }

    @Test
    fun rejectsPlanForStaleDocumentVersion() {
        val highlighter = TshRenderPlanSyntaxHighlighter(documentId) { 8L }
        val result = highlighter.updateRenderPlan(plan(7L))

        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-EDITOR-RENDER-004", (result as TshResult.Failure).diagnostics.single().code)
    }

    @Test
    fun rejectsPlanForAnotherDocument() {
        val highlighter = TshRenderPlanSyntaxHighlighter(documentId) { 7L }
        val result = highlighter.updateRenderPlan(plan(7L, TshDocumentId("another-document")))

        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-EDITOR-RENDER-001", (result as TshResult.Failure).diagnostics.single().code)
    }
    @Test
    fun dropsPreviouslyInstalledPlanWhenDocumentVersionAdvances() {
        var version = 7L
        val highlighter = TshRenderPlanSyntaxHighlighter(documentId) { version }
        assertTrue(highlighter.updateRenderPlan(plan(7L)) is TshResult.Success)
        assertTrue(highlighter.isStyleReady(1, 0, 0, 1))

        version = 8L
        assertFalse(highlighter.isStyleReady(1, 0, 0, 1))
    }

    @Test
    fun refusesPlanPublicationDuringAnEditTransaction() {
        val highlighter = TshRenderPlanSyntaxHighlighter(documentId) { 7L }
        highlighter.onTextChanged(
            EditorDocument.AppliedChange(
                range = Range(1, 1, 1, 2),
                rangeOffset = 0,
                insertedLinesCnt = 0,
                insertedTextLength = 0,
                deletedLinesCnt = 0,
                deletedTextLength = 1,
                finalLineNumber = 1,
                finalColumn = 1
            )
        )

        val result = highlighter.updateRenderPlan(plan(7L))
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-EDITOR-RENDER-008", (result as TshResult.Failure).diagnostics.single().code)
    }

}
