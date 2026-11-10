package com.zerostudio.idea.treesitter.tsh.api

import org.junit.Assert.*
import org.junit.Test

class TshQueryApiTest {
    private fun grammar(fingerprint: String = "sha256:grammar-a") =
        TshGrammarDescriptor("kotlin", "1.0", 14, fingerprint)

    private fun query(grammar: TshGrammarDescriptor = grammar()) =
        TshCompiledQuery(
            queryId = "highlights",
            revision = "r1",
            grammar = grammar,
            source = TshAtsSource("(identifier) @variable"),
            sourceHash = "sha256:query",
            compilerVersion = "ats-1"
        )

    @Test fun unsupportedBudgetsAreNotEnabledByDefault() {
        assertFalse(TshQueryCompileOptions().strictNodeValidation)
        assertNull(TshQueryExecutionBudget().maxSteps)
        assertEquals(100_000, TshQueryExecutionBudget().maxMatches)
        assertEquals(1_000_000, TshQueryExecutionBudget().maxCaptures)
        assertEquals(32L * 1024L * 1024L, TshQueryExecutionBudget().maxResultBytes)
    }

    @Test fun queryResultBudgetsRequirePositiveCaptureAndByteLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            TshQueryExecutionBudget(maxCaptures = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TshQueryExecutionBudget(maxResultBytes = 0)
        }
    }

    @Test fun byteRangeUsesHalfOpenIntervals() {
        val left = TshByteRange(2, 5)
        assertTrue(left.intersects(TshByteRange(4, 8)))
        assertFalse(left.intersects(TshByteRange(5, 8)))
        assertTrue(TshByteRange(3, 3).isEmpty)
    }

    @Test fun publicOffsetsUseLongWhilePreservingIntConstructionCompatibility() {
        val position = TshSourcePosition(
            offset = 3_000_000_000L,
            coordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT
        )
        assertEquals(3_000_000_000L, position.offset)
        val range = TshByteRange(2_500_000_000L, 2_500_000_010L)
        assertEquals(2_500_000_000L, range.startByte)
        assertEquals(5L, TshByteRange(2, 7).endByte - TshByteRange(2, 7).startByte)
    }

    @Test fun compiledQueryRequiresExactGrammarFingerprint() {
        val compiled = query()
        assertTrue(compiled.isCompatibleWith(grammar()))
        assertFalse(compiled.isCompatibleWith(grammar("sha256:grammar-b")))
    }

    @Test fun registryRejectsDuplicateAndIncompatibleGrammar() {
        val registry = TshCompiledQueryRegistry()
        val compiled = query()
        assertTrue(registry.register(compiled) is TshResult.Success)
        assertTrue(registry.register(compiled) is TshResult.Failure)
        assertTrue(registry.findCompatible("highlights", grammar()) is TshResult.Success)
        assertTrue(registry.findCompatible("highlights", grammar("sha256:other")) is TshResult.Failure)
    }

    @Test fun resultIsCurrentOnlyForExactDocumentAndTreeRevision() {
        val tree = TshSyntaxTreeSnapshot(
            documentId = "doc-1",
            documentVersion = 7,
            treeRevision = 12,
            grammar = grammar(),
            rootByteRange = TshByteRange(0, 100),
            nativeObjectId = TshObjectId(9)
        )
        val result = TshQueryResult(
            queryId = "highlights",
            queryRevision = "r1",
            documentId = "doc-1",
            documentVersion = 7,
            treeRevision = 12,
            grammarFingerprint = grammar().fingerprint,
            captures = emptyList(),
            stepsUsed = 20
        )
        assertTrue(result.isCurrentFor(tree))
        assertFalse(result.isCurrentFor(tree.copy(documentVersion = 8)))
        assertFalse(result.isCurrentFor(tree.copy(treeRevision = 13)))
    }
}
