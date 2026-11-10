package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshRegexMatchAdapterTest {
    private val documentId = TshDocumentId("regex-doc")

    private fun range(start: Long, end: Long, version: Long? = 7L,
                      coordinates: TshCoordinateSystem = TshCoordinateSystem.UTF16_CODE_UNIT) =
        TshSourceRange(
            TshSourcePosition(start, coordinateSystem = coordinates),
            TshSourcePosition(end, coordinateSystem = coordinates),
            version
        )

    private fun result(
        matches: List<TshRegexMatchRecord>,
        completeness: TshMatchCompleteness = TshMatchCompleteness.COMPLETE,
        diagnostics: List<TshDiagnostic> = emptyList()
    ) = TshRegexResult(
        documentId = documentId,
        documentVersion = 7L,
        documentLengthUtf16 = 4L, // "a𠮷z"
        regexId = "identifier-regex",
        regexRevision = "regex-r2",
        pipelineRevision = "regex-pipeline-r5",
        matches = matches,
        completeness = completeness,
        diagnostics = diagnostics
    )

    @Test
    fun adaptsAbsoluteUtf16RangesAndNamedCaptureGroups() {
        val source = result(listOf(
            TshRegexMatchRecord(
                range(1, 3),
                listOf(
                    TshRegexCaptureGroup(0, "whole", range(1, 3)),
                    TshRegexCaptureGroup(1, "identifier", range(1, 3)),
                    TshRegexCaptureGroup(2, "optional", null)
                )
            )
        ))

        val adapted = TshRegexMatchAdapter.adapt(source) as TshResult.Success
        val match = adapted.value.matches.single()
        assertEquals(TshMatchSource.REGEX, match.source)
        assertEquals("identifier-regex", match.ruleId.value)
        assertEquals("regex-r2", match.provenance.regexRevision)
        assertEquals("regex-pipeline-r5", adapted.value.regexRevision)
        assertEquals(1L, match.range.start.offset)
        assertEquals(3L, match.range.end.offset)
        assertEquals(TshCoordinateSystem.UTF16_CODE_UNIT, match.range.start.coordinateSystem)
        assertNull(match.rawRange)
        assertEquals(listOf("whole", "identifier"), match.captures.map { it.name })
    }

    @Test
    fun rejectsNonUtf16AndOutOfDocumentRanges() {
        val byteRange = range(0, 1, coordinates = TshCoordinateSystem.UTF8_BYTE)
        assertTrue(TshRegexMatchAdapter.adapt(
            result(listOf(TshRegexMatchRecord(byteRange)))
        ) is TshResult.Failure)

        assertTrue(TshRegexMatchAdapter.adapt(
            result(listOf(TshRegexMatchRecord(range(3, 5))))
        ) is TshResult.Failure)
    }

    @Test
    fun rejectsCaptureOutsideFullMatchAndDuplicateGroupIndexes() {
        val outside = TshRegexMatchRecord(
            range(1, 2),
            listOf(TshRegexCaptureGroup(1, "bad", range(0, 2)))
        )
        assertTrue(TshRegexMatchAdapter.adapt(result(listOf(outside))) is TshResult.Failure)

        val duplicate = TshRegexMatchRecord(
            range(1, 2),
            listOf(
                TshRegexCaptureGroup(1, "first", range(1, 2)),
                TshRegexCaptureGroup(1, "second", range(1, 2))
            )
        )
        assertTrue(TshRegexMatchAdapter.adapt(result(listOf(duplicate))) is TshResult.Failure)
    }

    @Test
    fun preservesTruncationAndDiagnosticsAndUsesStableUniqueIds() {
        val warning = TshDiagnostic(
            code = "TSH-REGEX-TEST-WARN",
            severity = TshDiagnosticSeverity.WARNING,
            stage = TshDiagnosticStage.REGEX,
            message = "Test warning"
        )
        val source = result(
            listOf(
                TshRegexMatchRecord(range(2, 3)),
                TshRegexMatchRecord(range(0, 1)),
                TshRegexMatchRecord(range(0, 1))
            ),
            TshMatchCompleteness.TRUNCATED,
            listOf(warning)
        )

        val first = TshRegexMatchAdapter.adapt(source) as TshResult.Success
        val second = TshRegexMatchAdapter.adapt(source) as TshResult.Success
        assertEquals(TshMatchCompleteness.TRUNCATED, first.value.completeness)
        assertTrue(first.value.diagnostics.any { it.code == warning.code })
        assertEquals(listOf(0L, 0L, 2L), first.value.matches.map { it.range.start.offset })
        assertEquals(first.value.matches.map { it.id }, second.value.matches.map { it.id })
        assertEquals(first.value.matches.size, first.value.matches.map { it.id }.distinct().size)
    }

    @Test
    fun rejectsRangesFromAnotherDocumentVersion() {
        assertTrue(TshRegexMatchAdapter.adapt(
            result(listOf(TshRegexMatchRecord(range(0, 1, version = 6L))))
        ) is TshResult.Failure)
    }
}
