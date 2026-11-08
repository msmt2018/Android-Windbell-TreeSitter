package com.zerostudio.idea.treesitter.tsh.api

/**
 * Stable identity and compatibility metadata for a loaded Tree-sitter grammar.
 * fingerprint must change whenever the generated language ABI or node schema changes.
 */
data class TshGrammarDescriptor(
    val languageId: String,
    val grammarVersion: String,
    val abiVersion: Int,
    val fingerprint: String,
    val capabilities: Set<String> = emptySet()
) {
    init {
        require(languageId.isNotBlank())
        require(grammarVersion.isNotBlank())
        require(abiVersion >= 0)
        require(fingerprint.isNotBlank())
    }
}

/** Native Tree-sitter byte range. End is exclusive; its encoding is carried by the tree snapshot. */
data class TshByteRange(val startByte: Int, val endByte: Int) {
    init {
        require(startByte >= 0)
        require(endByte >= startByte)
    }
    val isEmpty: Boolean get() = startByte == endByte
    fun intersects(other: TshByteRange): Boolean =
        startByte < other.endByte && other.startByte < endByte
}

/** Source wrapper keeps ATS and native Tree-sitter query inputs distinguishable. */
enum class TshQuerySourceLanguage { ATS, TREE_SITTER }

data class TshAtsSource(
    val text: String,
    val language: TshQuerySourceLanguage = TshQuerySourceLanguage.ATS,
    val sourceId: String? = null
) {
    init { require(text.isNotBlank()) { "Query source must not be blank" } }
}

data class TshQueryCompileOptions(
    val enablePredicates: Boolean = true,
    val strictNodeValidation: Boolean = true,
    val maxPatternCount: Int = 10_000
) {
    init { require(maxPatternCount > 0) }
}

/** A query is compiled against one exact grammar fingerprint. */
data class TshCompiledQuery(
    val queryId: String,
    val revision: String,
    val grammar: TshGrammarDescriptor,
    val source: TshAtsSource,
    val sourceHash: String,
    val compilerVersion: String,
    val options: TshQueryCompileOptions,
    val nativeObjectId: TshObjectId? = null
) {
    init {
        require(queryId.isNotBlank())
        require(revision.isNotBlank())
        require(sourceHash.isNotBlank())
        require(compilerVersion.isNotBlank())
    }

    fun isCompatibleWith(candidate: TshGrammarDescriptor): Boolean =
        grammar.languageId == candidate.languageId &&
            grammar.grammarVersion == candidate.grammarVersion &&
            grammar.abiVersion == candidate.abiVersion &&
            grammar.fingerprint == candidate.fingerprint
}

/** Encoding used by the source buffer passed to the native Tree-sitter parser. */
enum class TshTreeSitterInputEncoding { UTF8, UTF16_LE, UTF16_BE }

/** Minimal immutable syntax-tree identity; native tree ownership remains with its provider. */
data class TshSyntaxTreeSnapshot(
    val documentId: String,
    val documentVersion: Long,
    val treeRevision: Long,
    val grammar: TshGrammarDescriptor,
    val rootByteRange: TshByteRange,
    val nativeObjectId: TshObjectId,
    val inputEncoding: TshTreeSitterInputEncoding = TshTreeSitterInputEncoding.UTF8
) {
    init {
        require(documentId.isNotBlank())
        require(documentVersion >= 0)
        require(treeRevision >= 0)
    }
}

data class TshQueryExecutionBudget(
    val maxSteps: Long = 5_000_000,
    val maxMatches: Int = 100_000
) {
    init {
        require(maxSteps > 0)
        require(maxMatches > 0)
    }
}

/** Cooperative cancellation only; native calls must provide their own interruption boundary. */
fun interface TshQueryCancellation {
    fun isCancelled(): Boolean
    companion object {
        val NEVER = TshQueryCancellation { false }
    }
}

data class TshCapture(
    val name: String,
    val nodeType: String,
    val byteRange: TshByteRange,
    val nodeId: Long,
    val patternIndex: Int
) {
    init {
        require(name.isNotBlank())
        require(nodeType.isNotBlank())
        require(nodeId >= 0)
        require(patternIndex >= 0)
    }
}

data class TshQueryResult(
    val queryId: String,
    val queryRevision: String,
    val documentId: String,
    val documentVersion: Long,
    val treeRevision: Long,
    val grammarFingerprint: String,
    val captures: List<TshCapture>,
    val stepsUsed: Long,
    val isComplete: Boolean = true,
    /** Byte ranges in captures use this tree source encoding. */
    val inputEncoding: TshTreeSitterInputEncoding = TshTreeSitterInputEncoding.UTF8
) {
    init {
        require(queryId.isNotBlank())
        require(queryRevision.isNotBlank())
        require(documentId.isNotBlank())
        require(documentVersion >= 0)
        require(treeRevision >= 0)
        require(stepsUsed >= 0)
    }

    fun isCurrentFor(tree: TshSyntaxTreeSnapshot): Boolean =
        documentId == tree.documentId &&
            documentVersion == tree.documentVersion &&
            treeRevision == tree.treeRevision &&
            grammarFingerprint == tree.grammar.fingerprint
}

/** ATS compiles to the Tree-sitter query execution representation; it is not a parser engine. */
interface TshAtsCompiler {
    fun compile(
        source: TshAtsSource,
        grammar: TshGrammarDescriptor,
        options: TshQueryCompileOptions = TshQueryCompileOptions()
    ): TshResult<TshCompiledQuery>
}

/** Implementations must retain tree/query native resources for the duration of execute(). */
interface TshQueryExecutor {
    fun execute(
        query: TshCompiledQuery,
        tree: TshSyntaxTreeSnapshot,
        range: TshByteRange? = null,
        budget: TshQueryExecutionBudget = TshQueryExecutionBudget(),
        cancellation: TshQueryCancellation = TshQueryCancellation.NEVER
    ): TshResult<TshQueryResult>
}
