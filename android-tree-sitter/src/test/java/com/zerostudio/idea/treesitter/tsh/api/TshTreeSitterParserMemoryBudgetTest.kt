package com.zerostudio.idea.treesitter.tsh.api

import com.zerostudio.idea.treesitter.TSLanguage
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
}
