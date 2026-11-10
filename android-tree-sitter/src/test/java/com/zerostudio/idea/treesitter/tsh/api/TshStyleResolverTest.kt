package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshStyleResolverTest {
    @Test
    fun resolvesScopeSelectorsAndMergesStylesIntoRenderPlan() {
        val documentId = TshDocumentId("style-doc")
        val range = TshSourceRange(
            TshSourcePosition(10L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
            TshSourcePosition(18L, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
            documentVersion = 3L
        )
        val path = TshScopePath(listOf(
            TshScopeSegment("source.kotlin", "Kotlin source"),
            TshScopeSegment("meta.function", "Function"),
            TshScopeSegment("entity.name", "Function name")
        ))
        val match = TshMatch(
            id = TshMatchId("m1"),
            documentId = documentId,
            documentVersion = 3L,
            source = TshMatchSource.TREE_SITTER_ATS,
            pipelineRevision = "tree-3",
            ruleId = TshRuleId("function-name"),
            captureName = "name",
            range = range,
            scopeAssignments = listOf(TshScopeAssignment(range, path, "name")),
            provenance = TshMatchProvenance(
                TshMatchSource.TREE_SITTER_ATS, "tree-3",
                grammarFingerprint = "grammar-1", queryId = "query-1"
            )
        )
        val matchSet = (TshMatchMerger.merge(
            documentId, 3L, "tree-3", null, "config-4", listOf(match)
        ) as TshResult.Success).value
        val revisions = TshRenderRevisions(
            documentId, 3L, "config-4", "theme-2", "viewport-1", "renderer-1",
            treeSitterRevision = "tree-3"
        )
        val rules = listOf(
            TshStyleRule(
                TshRuleId("base"),
                TshScopeSelector(
                    listOf(
                        TshScopeSelectorSegment.Exact("source.kotlin"),
                        TshScopeSelectorSegment.Exact("meta.function"),
                        TshScopeSelectorSegment.Exact("entity.name")
                    ),
                    TshScopeSelectorMode.EXACT_PATH
                ),
                TshResolvedStyle(backgroundArgb = 0xFF202020u, italic = true, decorations = setOf("italic")),
                priority = 1
            ),
            TshStyleRule(
                TshRuleId("foreground"),
                TshScopeSelector(listOf(
                    TshScopeSelectorSegment.Exact("meta.function"),
                    TshScopeSelectorSegment.Exact("entity.name")
                )),
                TshResolvedStyle(foregroundArgb = 0xFFFF0000u, italic = false),
                priority = 2
            )
        )
        val plan = (TshStyleResolver.resolve(
            matchSet, revisions, TshRenderPlanId("render-1"), rules
        ) as TshResult.Success).value

        assertEquals(1, plan.instructions.size)
        assertEquals(0xFFFF0000u, plan.instructions.single().style.foregroundArgb)
        assertEquals(0xFF202020u, plan.instructions.single().style.backgroundArgb)
        assertEquals(false, plan.instructions.single().style.italic)
        assertFalse("italic" in plan.instructions.single().style.decorations)
        assertEquals(setOf(TshMatchId("m1")), plan.instructions.single().sourceMatchIds)
    }

    @Test
    fun rejectsMatchSetFromStalePipelineOrConfigRevision() {
        val documentId = TshDocumentId("doc")
        val matchSet = (TshMatchMerger.merge(
            documentId, 1L, "tree-old", null, "config-old", emptyList()
        ) as TshResult.Success).value
        val revisions = TshRenderRevisions(
            documentId, 1L, "config-new", "theme", "viewport", "renderer",
            treeSitterRevision = "tree-new"
        )
        val result = TshStyleResolver.resolve(
            matchSet, revisions, TshRenderPlanId("stale"), emptyList()
        )
        assertTrue(result is TshResult.Failure)
        assertTrue(result.diagnostics().any { it.code == "TSH-STYLE-001" })
    }
}
