package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TshTreeSitterQueryNativeIntegrationTest {
    @Test
    fun compilesExecutesAndAdaptsARealJavaGrammarQuery() {
        TreeSitter.loadLibrary()

        val engine = TshEngine.create()
        assertTrue(engine.initialize() is TshResult.Success)

        var parserOwner: TshTreeSitterParserOwner? = null
        var queryCompiler: TshTreeSitterQueryCompiler? = null
        var queryId: String? = null
        var snapshotLease: TshTextSnapshotLease? = null
        var coordinateIndex: TshSparseCoordinateIndex? = null
        try {
            // Register a disposable wrapper around the generated grammar pointer. The generated
            // TSLanguageJava instance is shared by other tests and must not be closed by this Engine.
            val language = TSLanguage.create("java-query-test", TSLanguageJava.getInstance().nativeObject)
            val grammar = TshGrammarDescriptor(
                languageId = "java",
                grammarVersion = "test-java-grammar",
                abiVersion = language.languageVersion,
                fingerprint = "java-query-native-integration-v1"
            )
            val languageRegistration = engine.nativeResources.register(
                language,
                type = "grammar-language",
                metadata = mapOf(
                    "languageId" to grammar.languageId,
                    "grammarFingerprint" to grammar.fingerprint
                )
            )

            val opened = engine.openTreeSitterParser(languageRegistration.handle, grammar)
            assertTrue(opened is TshResult.Success, "Engine must open a parser for the registered Java grammar")
            parserOwner = (opened as TshResult.Success).value

            val documentId = "java-query-probe"
            val documentVersion = 4L
            val source = "class QueryProbe {}"
            val parsed = parserOwner!!.parse(
                documentId = documentId,
                documentVersion = documentVersion,
                treeRevision = 2L,
                source = source
            )
            assertTrue(parsed is TshResult.Success, "Native Java parse must produce an owned syntax tree")
            val ownedTree = (parsed as TshResult.Success).value

            queryCompiler = engine.createTreeSitterQueryCompiler(
                TshGrammarLanguageResolver { requested ->
                    if (requested.fingerprint == grammar.fingerprint) languageRegistration.handle else null
                }
            )
            val querySource = TshAtsSource(
                text = "(class_declaration) @class_decl",
                language = TshQuerySourceLanguage.TREE_SITTER,
                sourceId = "java-class-name-native-integration"
            )
            val compiled = queryCompiler!!.compile(querySource, grammar)
            assertTrue(
                compiled is TshResult.Success,
                "Native Tree-sitter S-expression query must compile: " +
                    ((compiled as? TshResult.Failure)?.diagnostics?.joinToString { "${it.code}: ${it.message}" }
                        ?: compiled.toString())
            )
            val query = (compiled as TshResult.Success).value
            queryId = query.queryId

            val executed = engine.createTreeSitterQueryExecutor().execute(query, ownedTree.snapshot)
            assertTrue(
                executed is TshResult.Success,
                "Native TSQueryCursor execution must succeed: " +
                    ((executed as? TshResult.Failure)?.diagnostics?.joinToString { "${it.code}: ${it.message}" }
                        ?: executed.toString())
            )
            val queryResult = (executed as TshResult.Success).value
            val capture = queryResult.captures.single()
            val nameStart = 0L
            val nameEnd = source.length.toLong()

            assertEquals("class_decl", capture.name)
            assertEquals("class_declaration", capture.nodeType)
            assertEquals(TshByteRange(nameStart * 2L, nameEnd * 2L), capture.byteRange)
            assertEquals(0, capture.patternIndex)
            assertTrue(queryResult.isComplete)
            assertEquals(documentId, queryResult.documentId)
            assertEquals(documentVersion, queryResult.documentVersion)

            val snapshotDocumentId = documentId
            val sourceSnapshot = object : TshTextSnapshot {
                override val documentId: String = snapshotDocumentId
                override val version = documentVersion
                override val lengthUtf16 = source.length.toLong()
                override fun readUtf16(start: Long, end: Long): CharSequence =
                    source.substring(start.toInt(), end.toInt())
            }
            snapshotLease = TshSharedTextSnapshotLease.acquire(sourceSnapshot)
            val indexResult = TshSparseCoordinateIndex.build(
                snapshotLease!!,
                chunkSizeUtf16 = 256,
                checkpointStrideUtf16 = 256
            )
            assertTrue(indexResult is TshResult.Success, "Snapshot coordinate index must build")
            coordinateIndex = (indexResult as TshResult.Success).value

            val batchResult = TshTreeSitterMatchAdapter.adapt(
                query = query,
                tree = ownedTree.snapshot,
                result = queryResult,
                coordinates = coordinateIndex!!,
                pipelineRevision = "java-query-pipeline-v1"
            )
            assertTrue(batchResult is TshResult.Success, "Native captures must adapt to canonical TSH matches")
            val batch = (batchResult as TshResult.Success).value
            assertEquals(1, batch.matches.size)
            assertEquals(nameStart, batch.matches.single().range.start.offset)
            assertEquals(nameEnd, batch.matches.single().range.end.offset)

            val matchSetResult = TshMatchMerger.merge(
                documentId = TshDocumentId(documentId),
                documentVersion = documentVersion,
                treeSitterRevision = batch.pipelineRevision,
                regexRevision = null,
                configRevision = "java-query-config-v1",
                input = batch.matches,
                completeness = batch.completeness,
                diagnostics = batch.diagnostics
            )
            assertTrue(matchSetResult is TshResult.Success, "Adapted Native matches must enter the shared MatchSet")
            val matchSet = (matchSetResult as TshResult.Success).value
            assertEquals(1, matchSet.matches.size)
            assertEquals("class_decl", matchSet.matches.single().captureName)
            assertEquals(TshMatchSource.TREE_SITTER_ATS, matchSet.matches.single().source)
        } finally {
            coordinateIndex?.close()
            snapshotLease?.close()
            queryId?.let { queryCompiler?.release(it) }
            parserOwner?.close()
            engine.close()
        }
    }
}
