package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshTreeSitterMatchAdapterTest {
    private class Snapshot(private val value: String) : TshTextSnapshot {
        override val documentId = "doc-match"
        override val version = 9L
        override val lengthUtf16 = value.length.toLong()
        override fun readUtf16(start: Long, end: Long): CharSequence =
            value.substring(start.toInt(), end.toInt())
    }

    private val grammar = TshGrammarDescriptor(
        languageId = "test",
        grammarVersion = "1",
        abiVersion = 15,
        fingerprint = "grammar-sha"
    )

    private fun query() = TshCompiledQuery(
        queryId = "syntax-query",
        revision = "query-r3",
        grammar = grammar,
        source = TshAtsSource("(identifier) @name", TshQuerySourceLanguage.TREE_SITTER),
        sourceHash = "source-sha",
        compilerVersion = "test",
        options = TshQueryCompileOptions(),
        nativeObjectId = TshObjectId(17)
    )

    private fun result(
        encoding: TshTreeSitterInputEncoding,
        captures: List<TshCapture>,
        complete: Boolean = true
    ) = TshQueryResult(
        queryId = "syntax-query",
        queryRevision = "query-r3",
        documentId = "doc-match",
        documentVersion = 9,
        treeRevision = 12,
        grammarFingerprint = grammar.fingerprint,
        captures = captures,
        stepsUsed = null,
        isComplete = complete,
        inputEncoding = encoding
    )

    private fun capture(name: String, start: Long, end: Long, nodeId: Long = 1L, pattern: Int = 0) =
        TshCapture(name, "identifier", TshByteRange(start, end), nodeId, pattern)

    @Test
    fun mapsUtf8CaptureToCanonicalUtf16AndRetainsRawByteRange() {
        val lease = TshSharedTextSnapshotLease.acquire(Snapshot("aé𠮷z"))
        val index = (TshSparseCoordinateIndex.build(
            lease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        val tree = TshSyntaxTreeSnapshot(
            "doc-match", 9, 12, grammar, TshByteRange(0, 8), TshObjectId(18),
            TshTreeSitterInputEncoding.UTF8
        )

        val batch = TshTreeSitterMatchAdapter.adapt(
            query(), tree, result(TshTreeSitterInputEncoding.UTF8, listOf(capture("name", 1, 7))),
            index, pipelineRevision = "tree-pipeline-r4"
        ) as TshResult.Success

        val match = batch.value.matches.single()
        assertEquals(1L, match.range.start.offset)
        assertEquals(4L, match.range.end.offset)
        assertEquals(TshCoordinateSystem.UTF16_CODE_UNIT, match.range.start.coordinateSystem)
        assertEquals(1L, match.rawRange?.start?.offset)
        assertEquals(7L, match.rawRange?.end?.offset)
        assertEquals(TshCoordinateSystem.UTF8_BYTE, match.rawRange?.start?.coordinateSystem)
        assertEquals("syntax-query#pattern-0", match.ruleId.value)
        assertEquals("query-r3", match.provenance.queryRevision)
        assertEquals(TshMatchCompleteness.COMPLETE, batch.value.completeness)

        index.close()
        lease.close()
    }

    @Test
    fun mapsUtf16LeByteOffsetsWithoutTreatingThemAsUtf8() {
        val lease = TshSharedTextSnapshotLease.acquire(Snapshot("aé𠮷z"))
        val index = (TshSparseCoordinateIndex.build(
            lease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        val tree = TshSyntaxTreeSnapshot(
            "doc-match", 9, 12, grammar, TshByteRange(0, 10), TshObjectId(19),
            TshTreeSitterInputEncoding.UTF16_LE
        )

        val batch = TshTreeSitterMatchAdapter.adapt(
            query(), tree, result(TshTreeSitterInputEncoding.UTF16_LE, listOf(capture("name", 2, 8))),
            index, pipelineRevision = "tree-pipeline-r4"
        ) as TshResult.Success

        val match = batch.value.matches.single()
        assertEquals(1L, match.range.start.offset)
        assertEquals(4L, match.range.end.offset)
        assertNull(match.rawRange)

        index.close()
        lease.close()
    }

    @Test
    fun rejectsOddUtf16ByteOffsetsAndSurrogateInteriorOffsets() {
        val lease = TshSharedTextSnapshotLease.acquire(Snapshot("a𠮷z"))
        val index = (TshSparseCoordinateIndex.build(
            lease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        val utf16Tree = TshSyntaxTreeSnapshot(
            "doc-match", 9, 12, grammar, TshByteRange(0, 8), TshObjectId(20),
            TshTreeSitterInputEncoding.UTF16_LE
        )
        val odd = TshTreeSitterMatchAdapter.adapt(
            query(), utf16Tree,
            result(TshTreeSitterInputEncoding.UTF16_LE, listOf(capture("bad", 1, 4))),
            index, "pipeline"
        )
        assertTrue(odd is TshResult.Failure)

        val utf8Tree = utf16Tree.copy(
            rootByteRange = TshByteRange(0, 6),
            inputEncoding = TshTreeSitterInputEncoding.UTF8
        )
        val split = TshTreeSitterMatchAdapter.adapt(
            query(), utf8Tree,
            result(TshTreeSitterInputEncoding.UTF8, listOf(capture("bad", 2, 5))),
            index, "pipeline"
        )
        assertTrue(split is TshResult.Failure)

        index.close()
        lease.close()
    }

    @Test
    fun rejectsStaleQueryTreeAndCoordinateIndexBeforePublishingMatches() {
        val lease = TshSharedTextSnapshotLease.acquire(Snapshot("abc"))
        val index = (TshSparseCoordinateIndex.build(
            lease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        val tree = TshSyntaxTreeSnapshot(
            "doc-match", 9, 12, grammar, TshByteRange(0, 3), TshObjectId(21)
        )
        val good = result(TshTreeSitterInputEncoding.UTF8, listOf(capture("name", 0, 1)))
        assertTrue(TshTreeSitterMatchAdapter.adapt(
            query().copy(revision = "old"), tree, good, index, "pipeline"
        ) is TshResult.Failure)
        assertTrue(TshTreeSitterMatchAdapter.adapt(
            query(), tree.copy(documentVersion = 10), good, index, "pipeline"
        ) is TshResult.Failure)
        assertTrue(TshTreeSitterMatchAdapter.adapt(
            query(), tree, good, index, " "
        ) is TshResult.Failure)

        index.close()
        lease.close()
    }

    @Test
    fun sortsCapturesDeterministicallyAndPreservesTruncatedCompleteness() {
        val lease = TshSharedTextSnapshotLease.acquire(Snapshot("abcdef"))
        val index = (TshSparseCoordinateIndex.build(
            lease, chunkSizeUtf16 = 256, checkpointStrideUtf16 = 256
        ) as TshResult.Success).value
        val tree = TshSyntaxTreeSnapshot(
            "doc-match", 9, 12, grammar, TshByteRange(0, 6), TshObjectId(22)
        )
        val captures = listOf(
            capture("later", 3, 5, nodeId = 3),
            capture("outer", 0, 6, nodeId = 1),
            capture("inner", 1, 2, nodeId = 2)
        )
        val batch = (TshTreeSitterMatchAdapter.adapt(
            query(), tree, result(TshTreeSitterInputEncoding.UTF8, captures, complete = false),
            index, "pipeline"
        ) as TshResult.Success).value

        assertEquals(listOf("outer", "inner", "later"), batch.matches.map { it.captureName })
        assertEquals(TshMatchCompleteness.TRUNCATED, batch.completeness)
        assertEquals(batch.matches.map { it.id }, batch.matches.map { it.id }.distinct())

        index.close()
        lease.close()
    }
}
