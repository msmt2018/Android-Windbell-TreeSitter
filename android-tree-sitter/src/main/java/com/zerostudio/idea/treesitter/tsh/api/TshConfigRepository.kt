package com.zerostudio.idea.treesitter.tsh.api

data class TshConfigArtifact(
    val configId: String,
    val revision: String,
    val sourceId: String,
    val sourceText: String,
    val document: TshDslDocument,
    val compiledAtEpochMillis: Long = System.currentTimeMillis()
) {
    init {
        require(configId.isNotBlank()) { "Config id must not be blank" }
        require(revision.isNotBlank()) { "Config revision must not be blank" }
        require(sourceId.isNotBlank()) { "Source id must not be blank" }
        require(compiledAtEpochMillis >= 0L)
    }
}

data class TshConfigDescriptor(
    val configId: String,
    val revision: String,
    val sourceId: String,
    val languageIds: Set<String>,
    val declarationCount: Int,
    val compiledAtEpochMillis: Long
)

/**
 * In-memory catalog for validated/compiled immutable artifacts.
 * This repository does not parse .tsh source; callers supply an artifact from
 * the compiler pipeline. Native installation is provided by a later adapter.
 */
class TshConfigRepository {
    private val lock = Any()
    private val artifacts = LinkedHashMap<String, TshConfigArtifact>()

    fun install(artifact: TshConfigArtifact): TshResult<TshConfigDescriptor> = synchronized(lock) {
        if (artifacts.containsKey(artifact.configId)) {
            return@synchronized failure("TSH-CONFIG-001",
                "Config '\${artifact.configId}' is already installed; use replace")
        }
        artifacts[artifact.configId] = artifact
        TshResult.Success(descriptor(artifact))
    }

    fun replace(artifact: TshConfigArtifact, expectedRevision: String): TshResult<TshConfigDescriptor> =
        synchronized(lock) {
            val current = artifacts[artifact.configId]
                ?: return@synchronized failure("TSH-CONFIG-002", "Config '\${artifact.configId}' is not installed")
            if (current.revision != expectedRevision) {
                return@synchronized failure("TSH-CONFIG-003",
                    "Config revision changed; expected '\$expectedRevision' but found '\${current.revision}'")
            }
            artifacts[artifact.configId] = artifact
            TshResult.Success(descriptor(artifact))
        }

    fun get(configId: String): TshConfigArtifact? = synchronized(lock) { artifacts[configId] }
    fun list(): List<TshConfigDescriptor> = synchronized(lock) { artifacts.values.map(::descriptor) }

    fun unload(configId: String, expectedRevision: String? = null): TshResult<Boolean> =
        synchronized(lock) {
            val current = artifacts[configId]
                ?: return@synchronized failure("TSH-CONFIG-004", "Config '\$configId' is not installed")
            if (expectedRevision != null && current.revision != expectedRevision) {
                return@synchronized failure("TSH-CONFIG-003",
                    "Config revision changed; expected '\$expectedRevision' but found '\${current.revision}'")
            }
            artifacts.remove(configId)
            TshResult.Success(true)
        }

    fun clear() = synchronized(lock) { artifacts.clear() }

    private fun descriptor(artifact: TshConfigArtifact) = TshConfigDescriptor(
        configId = artifact.configId,
        revision = artifact.revision,
        sourceId = artifact.sourceId,
        languageIds = artifact.document.declarations
            .filter { it.kind == TshDslDeclarationKind.LANGUAGE }
            .mapNotNull { declaration ->
                (declaration.properties["id"] as? TshDslValue.StringValue)?.value ?: declaration.id
            }.toSet(),
        declarationCount = artifact.document.declarations.size,
        compiledAtEpochMillis = artifact.compiledAtEpochMillis
    )

    private fun failure(code: String, message: String) =
        TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.CONFIG,
            message = message
        )))
}
