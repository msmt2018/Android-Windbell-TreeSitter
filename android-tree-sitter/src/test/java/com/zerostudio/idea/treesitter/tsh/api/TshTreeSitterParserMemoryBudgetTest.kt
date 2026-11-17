package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSInputEdit
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TSPoint
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import org.junit.Assert.*
import org.junit.Test

class TshTreeSitterParserMemoryBudgetTest {
    private fun grammar() = TshGrammarDescriptor(
        languageId = "java",
        grammarVersion = "test",
        abiVersion = TSLanguageJava.getInstance().languageVersion,
        fingerprint = "java-memory-admission-test"
    )

    private fun engine(targetBytes: Long = 1024L * 1024L) = TshEngine.create(
        TshEngineOptions(
            memoryBudgetOptions = TshMemoryBudgetOptions(
                targetBytes = targetBytes,
                hardLimitBytes = maxOf(targetBytes, 2L * 1024L * 1024L)
            )
        )
    )

    private fun openOwner(engine: TshEngine, estimator: TshNativeTreeMemoryEstimator): TshTreeSitterParserOwner {
        TreeSitter.loadLibrary()
        val borrowedLanguage = TSLanguageJava.getInstance()
        // Give the Engine a distinct wrapper. TSLanguage.close() invalidates only this wrapper;
        // the generated grammar's static singleton remains usable by other tests.
        val language = TSLanguage.create("java", borrowedLanguage.nativeObject)
        val languageRegistration = engine.nativeResources.register(language, "grammar-language")
        val opened = engine.openTreeSitterParser(languageRegistration.handle, grammar(), estimator)
        assertTrue(opened.toString(), opened is TshResult.Success<*>)
        return (opened as TshResult.Success<TshTreeSitterParserOwner>).value
    }

    @Test
    fun stringParseReservesInputTemporarilyAndTreeEstimateUntilTreeRetirement() {
        val engine = engine()
        engine.initialize()
        val expectedTreeBytes = 512L
        val owner = openOwner(engine, TshNativeTreeMemoryEstimator { request ->
            assertEquals("doc-memory", request.documentId)
            assertEquals(1L, request.documentVersion)
            assertEquals("java", request.grammarLanguageId)
            assertFalse(request.incremental)
            expectedTreeBytes
        })
        val source = "class MemoryBudgetProbe {}"

        val parsed = owner.parse("doc-memory", 1L, 1L, source)
        assertTrue(parsed.toString(), parsed is TshResult.Success<*>)
        val tree = (parsed as TshResult.Success<TshOwnedSyntaxTree>).value
        val snapshot = engine.memoryBudget.snapshot()
        assertEquals(0L, snapshot.bytesByCategory[TshMemoryCategory.PARSER_INPUT])
        assertEquals(expectedTreeBytes, snapshot.bytesByCategory[TshMemoryCategory.SYNTAX_TREE])

        assertTrue(owner.retireTree(tree))
        assertEquals(0L, engine.memoryBudget.snapshot().reservedBytes)
        owner.close()
        engine.close()
    }

    @Test
    fun treeAdmissionFailurePreventsParseAndReleasesInputReservation() {
        val engine = engine(targetBytes = 300L)
        engine.initialize()
        val owner = openOwner(engine, TshNativeTreeMemoryEstimator { 256L })

        val parsed = owner.parse("doc-rejected", 2L, 1L, "class Rejected {}")

        assertTrue(parsed is TshResult.Failure)
        assertTrue((parsed as TshResult.Failure).diagnostics.any { it.code == "TSH-MEMORY-004" })
        assertEquals(0L, engine.memoryBudget.snapshot().reservedBytes)
        owner.close()
        engine.close()
    }
    private class StreamingSnapshot(
        override val documentId: String,
        override val version: Long,
        private val source: String
    ) : TshTextSnapshot {
        override val lengthUtf16: Long get() = source.length.toLong()

        override fun readUtf16(start: Long, end: Long): CharSequence =
            source.substring(start.toInt(), end.toInt())
    }

    private class StreamingProvider(
        private val value: StreamingSnapshot,
        private val onRead: () -> Unit = {}
    ) : TshTextAccessProvider {
        override val id = "memory-budget-piece-tree-provider"
        override val threading = TshTextAccessThreading.ANY_THREAD
        override val supportsConcurrentReads = true

        var readCalls: Int = 0
            private set

        override fun supports(document: Any) = true

        override fun snapshot(document: Any): TshResult<TshTextSnapshot> =
            TshResult.Success(value)

        override fun readChunk(
            lease: TshTextSnapshotLease,
            startUtf16: Long,
            maxUtf16Length: Int
        ): TshResult<TshTextChunk> {
            readCalls++
            onRead()
            val snapshot = lease.snapshot
            val endUtf16 = minOf(
                snapshot.lengthUtf16,
                startUtf16 + maxUtf16Length.toLong()
            )
            return TshResult.Success(TshTextChunk(
                documentId = snapshot.documentId,
                documentVersion = snapshot.version,
                startUtf16 = startUtf16,
                endUtf16 = endUtf16,
                text = snapshot.readUtf16(startUtf16, endUtf16)
            ))
        }
    }

    @Test
    fun snapshotParseReservesStreamingBridgeDuringReadsAndTreeUntilRetirement() {
        val engine = engine()
        engine.initialize()
        val expectedTreeBytes = 512L
        val owner = openOwner(engine, TshNativeTreeMemoryEstimator { request ->
            assertEquals("doc-stream-memory", request.documentId)
            assertEquals(7L, request.documentVersion)
            assertEquals("java", request.grammarLanguageId)
            assertFalse(request.incremental)
            expectedTreeBytes
        })
        val snapshot = StreamingSnapshot(
            documentId = "doc-stream-memory",
            version = 7L,
            source = "class StreamedMemoryProbe { int value = 7; }"
        )
        var observedInputReservationDuringRead = false
        val provider = StreamingProvider(snapshot) {
            observedInputReservationDuringRead =
                engine.memoryBudget.snapshot().bytesByCategory[TshMemoryCategory.PARSER_INPUT]!! > 0L
        }
        val snapshotLease = TshSharedTextSnapshotLease.acquire(snapshot)

        val parsed = owner.parse(
            snapshotLease = snapshotLease,
            textProvider = provider,
            treeRevision = 3L,
            chunkSizeBytes = 8
        )

        assertTrue(parsed.toString(), parsed is TshResult.Success<*>)
        assertTrue("The provider must read the Snapshot through the bounded callback", provider.readCalls > 0)
        assertTrue("PARSER_INPUT must be reserved while the provider is reading", observedInputReservationDuringRead)
        val tree = (parsed as TshResult.Success<TshOwnedSyntaxTree>).value
        val budgetAfterParse = engine.memoryBudget.snapshot()
        assertEquals(0L, budgetAfterParse.bytesByCategory[TshMemoryCategory.PARSER_INPUT])
        assertEquals(expectedTreeBytes, budgetAfterParse.bytesByCategory[TshMemoryCategory.SYNTAX_TREE])
        assertEquals(expectedTreeBytes, budgetAfterParse.reservedBytes)

        assertTrue(owner.retireTree(tree))
        assertEquals(0L, engine.memoryBudget.snapshot().reservedBytes)
        snapshotLease.close()
        owner.close()
        engine.close()
    }

    @Test
    fun snapshotTreeAdmissionFailureReleasesStreamingBridgeWithoutReadingProvider() {
        val engine = engine(targetBytes = 300L)
        engine.initialize()
        val owner = openOwner(engine, TshNativeTreeMemoryEstimator { 256L })
        val snapshot = StreamingSnapshot(
            documentId = "doc-stream-rejected",
            version = 4L,
            source = "class StreamedRejected {}"
        )
        val provider = StreamingProvider(snapshot)
        val snapshotLease = TshSharedTextSnapshotLease.acquire(snapshot)

        val parsed = owner.parse(
            snapshotLease = snapshotLease,
            textProvider = provider,
            treeRevision = 1L,
            chunkSizeBytes = 8
        )

        assertTrue(parsed is TshResult.Failure)
        assertTrue((parsed as TshResult.Failure).diagnostics.any { it.code == "TSH-MEMORY-004" })
        assertEquals("Tree admission must fail before the Native input callback runs", 0, provider.readCalls)
        assertEquals(0L, engine.memoryBudget.snapshot().reservedBytes)
        assertEquals(0, engine.memoryBudget.snapshot().reservations)

        snapshotLease.close()
        owner.close()
        engine.close()
    }


    @Test
    fun incrementalSnapshotParseAdmitsOldAndNewTreeOverlapUntilOldTreeRetirement() {
        val engine = engine()
        engine.initialize()
        val oldTreeBytes = 512L
        val newTreeBytes = 768L
        val owner = openOwner(engine, TshNativeTreeMemoryEstimator { request ->
            assertEquals("doc-incremental-memory", request.documentId)
            assertEquals("java", request.grammarLanguageId)
            if (request.incremental) {
                assertEquals(2L, request.documentVersion)
                newTreeBytes
            } else {
                assertEquals(1L, request.documentVersion)
                oldTreeBytes
            }
        })

        val oldSource = "class IncrementalMemoryProbe { int value = 1; }"
        val oldSnapshot = StreamingSnapshot(
            documentId = "doc-incremental-memory",
            version = 1L,
            source = oldSource
        )
        val oldLease = TshSharedTextSnapshotLease.acquire(oldSnapshot)
        val initial = owner.parse(
            snapshotLease = oldLease,
            textProvider = StreamingProvider(oldSnapshot),
            treeRevision = 1L,
            chunkSizeBytes = 8
        )
        assertTrue(initial.toString(), initial is TshResult.Success<*>)
        val oldTree = (initial as TshResult.Success<TshOwnedSyntaxTree>).value
        oldLease.close()
        assertEquals(oldTreeBytes, engine.memoryBudget.snapshot().bytesByCategory[TshMemoryCategory.SYNTAX_TREE])

        val editedOffset = oldSource.indexOf("1;")
        assertTrue("The fixture must contain the literal being edited", editedOffset >= 0)
        // This fixture is ASCII, so UTF-8 byte offsets equal the String's UTF-16 indices.
        // Use the public factory methods; TSInputEdit/TSPoint constructors are protected.
        val startByte = editedOffset
        val oldEndByte = startByte + 1
        val startPoint = TSPoint.create(0, startByte)
        val endPoint = TSPoint.create(0, oldEndByte)
        val edit = TSInputEdit.create(
            startByte,
            oldEndByte,
            oldEndByte,
            startPoint,
            endPoint,
            endPoint
        )
        val editResult = owner.editTree(
            ownedTree = oldTree,
            edit = edit,
            targetDocumentVersion = 2L,
            targetTreeRevision = 2L
        )
        assertTrue(editResult.toString(), editResult is TshResult.Success<*>)

        val newSource = oldSource.replaceRange(editedOffset, editedOffset + 1, "2")
        val newSnapshot = StreamingSnapshot(
            documentId = "doc-incremental-memory",
            version = 2L,
            source = newSource
        )
        var observedOverlapDuringRead = false
        val newProvider = StreamingProvider(newSnapshot) {
            val budget = engine.memoryBudget.snapshot()
            observedOverlapDuringRead =
                budget.bytesByCategory[TshMemoryCategory.PARSER_INPUT]!! > 0L &&
                    budget.bytesByCategory[TshMemoryCategory.SYNTAX_TREE] == oldTreeBytes + newTreeBytes
        }
        val newLease = TshSharedTextSnapshotLease.acquire(newSnapshot)
        val replacement = owner.parse(
            snapshotLease = newLease,
            textProvider = newProvider,
            treeRevision = 2L,
            oldTree = oldTree,
            chunkSizeBytes = 8
        )
        assertTrue(replacement.toString(), replacement is TshResult.Success<*>)
        assertTrue("Both tree reservations must overlap while the replacement is parsed", observedOverlapDuringRead)
        val newTree = (replacement as TshResult.Success<TshOwnedSyntaxTree>).value
        newLease.close()

        val overlap = engine.memoryBudget.snapshot()
        assertEquals(oldTreeBytes + newTreeBytes, overlap.bytesByCategory[TshMemoryCategory.SYNTAX_TREE])
        assertEquals(0L, overlap.bytesByCategory[TshMemoryCategory.PARSER_INPUT])

        assertTrue("Retiring the edited old tree releases only its reservation", owner.retireTree(oldTree))
        assertEquals(newTreeBytes, engine.memoryBudget.snapshot().bytesByCategory[TshMemoryCategory.SYNTAX_TREE])
        assertTrue(owner.retireTree(newTree))
        assertEquals(0L, engine.memoryBudget.snapshot().reservedBytes)

        owner.close()
        engine.close()
    }


    @Test
    fun engineCloseKeepsTreeReservationUntilLastNativeLeaseIsReleased() {
        val engine = engine()
        engine.initialize()
        val expectedTreeBytes = 640L
        val owner = openOwner(engine, TshNativeTreeMemoryEstimator { expectedTreeBytes })
        val parsed = owner.parse(
            "doc-retained-tree-lease",
            1L,
            1L,
            "class RetainedTreeLeaseProbe {}"
        )
        assertTrue(parsed.toString(), parsed is TshResult.Success<*>)
        val tree = (parsed as TshResult.Success<TshOwnedSyntaxTree>).value
        val treeLease = engine.nativeResources.acquire(tree.registration.handle)
        assertNotNull("The published Native tree must be leasable", treeLease)

        owner.close()
        engine.close()

        val whileLeaseActive = engine.memoryBudget.snapshot()
        assertTrue("Engine close must close admission", whileLeaseActive.closed)
        assertEquals(
            "Engine shutdown must not release memory for a Native tree still held by a lease",
            expectedTreeBytes,
            whileLeaseActive.bytesByCategory[TshMemoryCategory.SYNTAX_TREE]
        )
        assertEquals(1, whileLeaseActive.reservations)

        treeLease!!.close()

        val afterLastLease = engine.memoryBudget.snapshot()
        assertEquals(0L, afterLastLease.reservedBytes)
        assertEquals(0, afterLastLease.reservations)
        assertTrue(afterLastLease.closed)
    }


}
