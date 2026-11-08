package com.zerostudio.idea.treesitter.tsh.api

import org.junit.Assert.*
import org.junit.Test

class TshTreeSitterQueryAdapterTest {
    private val grammar = TshGrammarDescriptor(
        languageId = "kotlin",
        grammarVersion = "1",
        abiVersion = 14,
        fingerprint = "grammar-fingerprint"
    )

    private fun compiledQuery(
        grammar: TshGrammarDescriptor = this.grammar,
        nativeId: TshObjectId? = null
    ) = TshCompiledQuery(
        queryId = "q",
        revision = "r1",
        grammar = grammar,
        source = TshAtsSource("(identifier) @name", TshQuerySourceLanguage.TREE_SITTER),
        sourceHash = "source-hash",
        compilerVersion = "test",
        options = TshQueryCompileOptions(),
        nativeObjectId = nativeId
    )

    private fun tree(grammar: TshGrammarDescriptor = this.grammar) =
        TshSyntaxTreeSnapshot(
            documentId = "doc",
            documentVersion = 1,
            treeRevision = 1,
            grammar = grammar,
            rootByteRange = TshByteRange(0, 20),
            nativeObjectId = TshObjectId(100)
        )

    @Test fun compilerRejectsAtsUntilDedicatedCompilerExists() {
        val compiler = TshTreeSitterQueryCompiler(
            languages = TshGrammarLanguageResolver { error("must not resolve for ATS") },
            objects = TshObjectRegistry(),
            queries = TshCompiledQueryRegistry(),
            leases = TshNativeQueryLeaseStore()
        )
        val result = compiler.compile(TshAtsSource("(identifier) @name"), grammar)
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-ATS-COMPILER-001", (result as TshResult.Failure).diagnostics.first().code)
    }

    @Test fun compilerReportsMissingGrammarInsteadOfCreatingInvalidNativeQuery() {
        val compiler = TshTreeSitterQueryCompiler(
            languages = TshGrammarLanguageResolver { null },
            objects = TshObjectRegistry(),
            queries = TshCompiledQueryRegistry()
        )
        val result = compiler.compile(
            TshAtsSource("(identifier) @name", TshQuerySourceLanguage.TREE_SITTER),
            grammar
        )
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-QUERY-GRAMMAR-002", (result as TshResult.Failure).diagnostics.first().code)
    }

    @Test fun executorRejectsGrammarMismatchBeforeNativeLookup() {
        val registry = TshCompiledQueryRegistry()
        val query = compiledQuery()
        assertTrue(registry.register(query) is TshResult.Success)
        val executor = TshTreeSitterQueryExecutor(TshObjectRegistry(), registry, TshNativeQueryLeaseStore())
        val result = executor.execute(query, tree(grammar.copy(fingerprint = "other")))
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-QUERY-EXEC-001", (result as TshResult.Failure).diagnostics.first().code)
    }

    @Test fun executorRejectsUnregisteredNativeQueryHandle() {
        val registry = TshCompiledQueryRegistry()
        val query = compiledQuery(nativeId = TshObjectId(999))
        assertTrue(registry.register(query) is TshResult.Success)
        val executor = TshTreeSitterQueryExecutor(TshObjectRegistry(), registry)
        val result = executor.execute(query, tree())
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-QUERY-EXEC-004", (result as TshResult.Failure).diagnostics.first().code)
    }

    @Test fun executorRejectsStaleQueryRevision() {
        val registry = TshCompiledQueryRegistry()
        val active = compiledQuery().copy(revision = "r2")
        assertTrue(registry.register(active) is TshResult.Success)
        val executor = TshTreeSitterQueryExecutor(TshObjectRegistry(), registry)
        val result = executor.execute(compiledQuery(), tree())
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-QUERY-EXEC-003", (result as TshResult.Failure).diagnostics.first().code)
    }
}
