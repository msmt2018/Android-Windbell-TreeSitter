package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshMatchApiTest {
    private val documentId = TshDocumentId("doc")

    private fun range(start: Long, end: Long, version: Long = 4L) = TshSourceRange(
        TshSourcePosition(start, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
        TshSourcePosition(end, coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT),
        documentVersion = version
    )

    private fun treeMatch(id: String, start: Long, end: Long, pipeline: String = "tree-r1") = TshMatch(
        id = TshMatchId(id),
        documentId = documentId,
        documentVersion = 4,
        source = TshMatchSource.TREE_SITTER_ATS,
        pipelineRevision = pipeline,
        ruleId = TshRuleId("identifier"),
        captureName = "variable",
        range = range(start, end),
        provenance = TshMatchProvenance(
            source = TshMatchSource.TREE_SITTER_ATS,
            pipelineRevision = pipeline,
            grammarFingerprint = "grammar-sha",
            queryId = "highlight-query",
            queryRevision = "q1"
        )
    )

    private fun regexMatch(id: String, start: Long, end: Long) = TshMatch(
        id = TshMatchId(id),
        documentId = documentId,
        documentVersion = 4,
        source = TshMatchSource.REGEX,
        pipelineRevision = "regex-r1",
        ruleId = TshRuleId("comment"),
        range = range(start, end),
        provenance = TshMatchProvenance(
            source = TshMatchSource.REGEX,
            pipelineRevision = "regex-r1",
            regexId = "comment-rule",
            regexRevision = "r1"
        )
    )

    @Test
    fun mergesBothPipelinesWithStableOrderingAndDeduplication() {
        val matches = listOf(
            treeMatch("tree-2", 4, 8, priority = 0),
            treeMatch("tree-duplicate", 4, 8),
            regexMatch("regex-1", 0, 3)
        )
        val merged = (TshMatchMerger.merge(
            documentId, 4, "tree-r1", "regex-r1", "config-r2", matches
        ) as TshResult.Success).value
        assertEquals(listOf("regex-1", "tree-2"), merged.matches.map { it.id.value })
        assertEquals(TshMatchCompleteness.COMPLETE, merged.completeness)
        assertTrue(merged.isCurrentFor(documentId, 4, "tree-r1", "regex-r1", "config-r2"))
        assertFalse(merged.isCurrentFor(documentId, 5, "tree-r1", "regex-r1", "config-r2"))
    }

    @Test
    fun excludesStalePipelineResultsAndMarksSetPartial() {
        val merged = (TshMatchMerger.merge(
            documentId, 4, "tree-r1", "regex-r1", "config-r2",
            listOf(treeMatch("fresh", 0, 2), treeMatch("stale", 3, 5, "tree-old"))
        ) as TshResult.Success).value
        assertEquals(listOf("fresh"), merged.matches.map { it.id.value })
        assertEquals(TshMatchCompleteness.PARTIAL, merged.completeness)
        assertTrue(merged.diagnostics.any { it.code == "TSH-MATCH-002" })
    }

    @Test
    fun rejectsNonCanonicalMatchRangesAndMismatchedProvenance() {
        assertThrows(IllegalArgumentException::class.java) {
            TshMatch(
                id = TshMatchId("bad"),
                documentId = documentId,
                documentVersion = 4,
                source = TshMatchSource.REGEX,
                pipelineRevision = "regex-r1",
                ruleId = TshRuleId("rule"),
                range = TshSourceRange(
                    TshSourcePosition(0, coordinateSystem = TshCoordinateSystem.UTF8_BYTE),
                    TshSourcePosition(1, coordinateSystem = TshCoordinateSystem.UTF8_BYTE)
                ),
                provenance = TshMatchProvenance(
                    TshMatchSource.REGEX, "regex-r1", regexId = "rule"
                )
            )
        }
    }
}
