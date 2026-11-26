package com.zerostudio.idea.treesitter.tsh

import android.content.Context
import android.os.Build
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TreeSitter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class TshLanguageRequest(
    val fileName: String,
    val mimeType: String? = null,
    val shebang: String? = null,
    val contentPrefix: String = ""
)

data class TshLanguageResolution(
    val languageId: String,
    val extension: String,
    val matchedBy: Match
) {
    enum class Match { COMPOUND_EXTENSION, EXTENSION, MIME, SHEBANG, FIRST_LINE, CONTENT }
}

/** Resolves compound suffixes before simple suffixes, then MIME/shebang/content evidence. */
class TshLanguageResolver(private val mimeAliases: Map<String, String> = mapOf(
    "application/json" to "json", "application/xml" to "xml", "text/html" to "html",
    "text/x-python" to "python", "text/x-java-source" to "java",
    "text/javascript" to "javascript", "application/javascript" to "javascript",
    "text/x-shellscript" to "bash", "application/x-yaml" to "yaml",
    "text/yaml" to "yaml"
)) {
    private fun normalizeExtension(value: String): String =
        value.trim().trimStart('.').lowercase(Locale.ROOT)

    private fun matchesLanguage(entry: TshGrammarDescriptor, id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return entry.id.equals(id, true) || entry.aliases.any { it.equals(id, true) }
    }

    fun resolve(request: TshLanguageRequest, registry: TshGrammarRegistry): TshLanguageResolution? {
        val name = request.fileName.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        val entries = registry.snapshot()
        var longestCompound = 0
        val compoundMatches = ArrayList<Pair<TshGrammarDescriptor, String>>()
        for (entry in entries) for (raw in entry.extensions) {
            val ext = normalizeExtension(raw)
            if (ext.contains('.') && name.endsWith(".$ext")) {
                if (ext.length > longestCompound) {
                    longestCompound = ext.length
                    compoundMatches.clear()
                }
                if (ext.length == longestCompound) compoundMatches += entry to ext
            }
        }
        if (compoundMatches.isNotEmpty()) {
            val unique = compoundMatches.distinctBy { it.first.id.lowercase(Locale.ROOT) }
            if (unique.size == 1) return TshLanguageResolution(
                unique[0].first.id, unique[0].second,
                TshLanguageResolution.Match.COMPOUND_EXTENSION
            )
        }

        val extensionMatches = ArrayList<Pair<TshGrammarDescriptor, String>>()
        for (entry in entries) for (raw in entry.extensions) {
            val ext = normalizeExtension(raw)
            if (ext.isNotEmpty() && !ext.contains('.') && name.endsWith(".$ext")) {
                extensionMatches += entry to ext
            }
        }
        if (extensionMatches.isNotEmpty()) {
            val unique = extensionMatches.distinctBy { it.first.id.lowercase(Locale.ROOT) }
            if (unique.size == 1) return TshLanguageResolution(
                unique[0].first.id, unique[0].second,
                TshLanguageResolution.Match.EXTENSION
            )
        }

        val mime = request.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val mimeId = mime?.let { mimeAliases[it] }
        val mimeMatches = entries.filter { entry ->
            matchesLanguage(entry, mimeId) ||
                entry.mimeTypes.any { it.substringBefore(';').trim().equals(mime, true) }
        }
        if (mimeMatches.size == 1) {
            val entry = mimeMatches[0]
            return TshLanguageResolution(entry.id,
                normalizeExtension(entry.extensions.firstOrNull().orEmpty()),
                TshLanguageResolution.Match.MIME)
        }

        val firstLine = (request.shebang ?: request.contentPrefix.lineSequence().firstOrNull().orEmpty())
            .trim().lowercase(Locale.ROOT)
        val executableId = if (firstLine.startsWith("#!")) when {
            firstLine.contains("python") -> "python"
            firstLine.contains("node") || firstLine.contains("deno") -> "javascript"
            firstLine.contains("bash") -> "bash"
            firstLine.contains("zsh") || firstLine.contains("/sh") -> "bash"
            firstLine.contains("ruby") -> "ruby"
            firstLine.contains("perl") -> "perl"
            firstLine.contains("php") -> "php"
            else -> null
        } else null
        val executableMatches = entries.filter { matchesLanguage(it, executableId) }
        if (executableMatches.size == 1) {
            val entry = executableMatches[0]
            return TshLanguageResolution(entry.id,
                normalizeExtension(entry.extensions.firstOrNull().orEmpty()),
                TshLanguageResolution.Match.SHEBANG)
        }

        val sampleFirstLine = (request.shebang
            ?: request.contentPrefix.removePrefix("\uFEFF").lineSequence().firstOrNull().orEmpty())
            .trimEnd()
        val firstLineMatches = entries.filter { entry ->
            val pattern = entry.firstLinePattern
            !pattern.isNullOrBlank() && runCatching {
                Regex(pattern).containsMatchIn(sampleFirstLine)
            }.getOrDefault(false)
        }
        if (firstLineMatches.size == 1) {
            val entry = firstLineMatches[0]
            return TshLanguageResolution(entry.id,
                normalizeExtension(entry.extensions.firstOrNull().orEmpty()),
                TshLanguageResolution.Match.FIRST_LINE)
        }

        val prefix = request.contentPrefix.removePrefix("\uFEFF").trimStart()
        val contentId = when {
            prefix.startsWith("{") || prefix.startsWith("[") -> "json"
            prefix.startsWith("<?xml", true) -> "xml"
            prefix.startsWith("<html", true) || prefix.startsWith("<!doctype html", true) -> "html"
            prefix.startsWith("---") || Regex("(?m)^[A-Za-z0-9_-]+\\s*:").containsMatchIn(prefix) -> "yaml"
            Regex("(?m)^\\s*(package\\s+[A-Za-z_][\\w.]*(?:;)?|import\\s+java\\.)").containsMatchIn(prefix) &&
                prefix.contains(';') -> "java"
            Regex("(?m)^\\s*(package\\s+[A-Za-z_][\\w.]*|import\\s+kotlin\\.|(suspend\\s+)?fun\\s+|data\\s+class\\s+)").containsMatchIn(prefix) -> "kotlin"
            Regex("(?m)^\\s*(def\\s+[A-Za-z_]|from\\s+\\w+\\s+import|import\\s+\\w+)").containsMatchIn(prefix) -> "python"
            Regex("(?m)^\\s*(export\\s+|import\\s+.*from\\s+|const\\s+|let\\s+|function\\s+)").containsMatchIn(prefix) -> "javascript"
            prefix.contains("#include") && (prefix.contains("std::") || prefix.contains("namespace ")) -> "cpp"
            prefix.contains("#include") || prefix.contains("int main(") -> "c"
            Regex("(?m)^\\s*(pub\\s+)?fn\\s+main\\s*\\(|^\\s*use\\s+std::").containsMatchIn(prefix) -> "rust"
            Regex("(?m)^package\\s+main\\s*$|^func\\s+main\\s*\\(").containsMatchIn(prefix) -> "go"
            Regex("(?is)^\\s*(select|with|create\\s+table|insert\\s+into)\\b").containsMatchIn(prefix) -> "sql"
            else -> null
        }
        val contentMatches = entries.filter { matchesLanguage(it, contentId) }
        if (contentMatches.size == 1) {
            val entry = contentMatches[0]
            return TshLanguageResolution(entry.id,
                normalizeExtension(entry.extensions.firstOrNull().orEmpty()),
                TshLanguageResolution.Match.CONTENT)
        }
        return null
    }
}

data class TshGrammarDescriptor(
    val id: String,
    val languageSymbol: String,
    val extensions: Set<String>,
    val aliases: Set<String>,
    val mimeTypes: Set<String>,
    val library: File,
    val tshSource: File,
    val source: Source,
    val firstLinePattern: String? = null
) {
    enum class Source { APK, EXTERNAL }
}

/** Unified descriptor registry; collisions are explicit and APK entries take precedence. */
class TshGrammarRegistry {
    data class Entry(val descriptor: TshGrammarDescriptor, val state: State, val diagnostic: String? = null) {
        enum class State { AVAILABLE, CONFLICT, ABI_INCOMPATIBLE, LOAD_FAILED, READY }
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    fun register(value: TshGrammarDescriptor): Entry = entries.compute(value.id.lowercase()) { _, old ->
        when {
            old == null -> Entry(value, Entry.State.AVAILABLE)
            old.descriptor == value -> old
            old.descriptor.library.canonicalPath == value.library.canonicalPath ->
                Entry(value, Entry.State.AVAILABLE)
            old.descriptor.source == TshGrammarDescriptor.Source.APK -> old
            value.source == TshGrammarDescriptor.Source.APK -> Entry(value, Entry.State.AVAILABLE)
            else -> Entry(value, Entry.State.CONFLICT, "Conflicting Grammar providers for " + value.id)
        }
    }!!
    fun update(id: String, state: Entry.State, diagnostic: String? = null) {
        entries.computeIfPresent(id.lowercase()) { _, old -> old.copy(state = state, diagnostic = diagnostic) }
    }
    fun find(id: String): Entry? = entries[id.lowercase()]
    fun snapshot(): List<TshGrammarDescriptor> = entries.values
        .filter { it.state == Entry.State.AVAILABLE || it.state == Entry.State.READY }
        .map { it.descriptor }
}

/** Discovers APK/external .so files, validates Tree-sitter ABI, and compiles matching .tsh files. */
class TshGrammarProvider(
    private val context: Context,
    private val registry: TshGrammarRegistry,
    private val engine: WindbellTshEngine = WindbellTshEngine.instance
) {
    @Volatile var lastResolutionDiagnostic: String? = null
        private set

    private data class TshMetadata(
        val id: String?,
        val extensions: Set<String>,
        val aliases: Set<String>,
        val mimeTypes: Set<String>,
        val firstLinePattern: String?
    )

    private fun parseMetadata(source: String): TshMetadata {
        val languageBlock = Regex("(?s)language\\s*\\{(.*?)\\}").find(source)?.groupValues?.get(1).orEmpty()
        fun scalar(key: String): String? =
            Regex("""(?m)\b$key\s*:\s*["']([^"']+)["']""")
                .find(languageBlock)?.groupValues?.get(1)
        fun array(key: String): Set<String> {
            val body = Regex("(?s)\\b$key\\s*:\\s*\\[(.*?)\\]").find(languageBlock)
                ?.groupValues?.get(1).orEmpty()
            return Regex("""["']([^"']+)["']""").findAll(body)
                .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toSet()
        }
        val firstLinePattern = Regex("""(?m)\bfirstLine\s*:\s*/((?:\\.|[^/])*)/""")
            .find(languageBlock)?.groupValues?.get(1)
        return TshMetadata(
            scalar("id"), array("extensions"), array("aliases"),
            array("mimetypes") + array("mimeTypes"), firstLinePattern
        )
    }

    private fun materializeAssetGrammars(): Map<String, File> {
        val names = context.assets.list("tsh").orEmpty()
        if (names.isEmpty()) return emptyMap()
        val directory = File(context.cacheDir, "tsh-grammar-runtime")
        if (!directory.exists() && !directory.mkdirs()) return emptyMap()
        val result = LinkedHashMap<String, File>()
        for (name in names.sorted()) {
            if (!name.endsWith(".tsh", true)) continue
            val target = File(directory, name.substringAfterLast('/'))
            try {
                // cacheDir survives APK upgrades. Refresh the cached DSL when the
                // packaged asset changes instead of silently compiling a stale file.
                val assetBytes = context.assets.open("tsh/$name").use { it.readBytes() }
                val unchanged = target.isFile && target.length() == assetBytes.size.toLong() &&
                    target.readBytes().contentEquals(assetBytes)
                if (!unchanged) {
                    val temporary = File.createTempFile(target.name + ".", ".tmp", directory)
                    try {
                        FileOutputStream(temporary).use { it.write(assetBytes) }
                        // Rename within cacheDir so readers never observe a partially
                        // written grammar when discovery and loading overlap.
                        android.system.Os.rename(temporary.absolutePath, target.absolutePath)
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                }
                result[name.substringAfterLast('/').removeSuffix(".tsh").lowercase(Locale.ROOT)] = target
            } catch (_: Exception) {
                // A broken optional asset is recorded by omission; other grammars remain discoverable.
            }
        }
        return result
    }

    fun discover(externalDirectories: List<File> = emptyList()) {
        val assets = materializeAssetGrammars()
        val apkRoot = File(context.applicationInfo.nativeLibraryDir)
        val roots = (listOf(apkRoot) +
            externalDirectories.flatMap { root ->
                val abiRoot = Build.SUPPORTED_ABIS
                    .map { File(root, it) }
                    .firstOrNull { it.isDirectory }
                listOfNotNull(abiRoot, root)
            }).filter { it.isDirectory }.distinctBy {
                runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
            }

        for (root in roots) {
            val libraries = root.listFiles { file ->
                file.isFile && file.name.lowercase(Locale.ROOT).startsWith("libtree-sitter-") &&
                    file.name.lowercase(Locale.ROOT).endsWith(".so")
            }.orEmpty()
            for (library in libraries) {
                val normalizedLibraryName = library.name.lowercase(Locale.ROOT)
                val libraryId = normalizedLibraryName.removePrefix("libtree-sitter-")
                    .removeSuffix(".so")
                val candidates = listOfNotNull(
                    File(library.parentFile, "$libraryId.tsh").takeIf { it.isFile },
                    File(root, "tsh/$libraryId.tsh").takeIf { it.isFile },
                    assets[libraryId]
                ).distinctBy { it.absolutePath }
                val tsh = candidates.firstOrNull() ?: continue
                val source = runCatching { tsh.readText() }.getOrNull() ?: continue
                val metadata = parseMetadata(source)
                val id = metadata.id?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: libraryId
                val extensions = metadata.extensions.ifEmpty { setOf(libraryId) }
                registry.register(TshGrammarDescriptor(
                    id = id,
                    languageSymbol = "tree_sitter_" + libraryId.replace(Regex("[^A-Za-z0-9_]"), "_"),
                    extensions = extensions,
                    aliases = metadata.aliases + libraryId,
                    mimeTypes = metadata.mimeTypes,
                    library = library,
                    tshSource = tsh,
                    source = if (root.absolutePath == apkRoot.absolutePath) TshGrammarDescriptor.Source.APK
                        else TshGrammarDescriptor.Source.EXTERNAL,
                    firstLinePattern = metadata.firstLinePattern
                ))
            }
        }
    }

    private fun verifyAndroidAbi(library: File) {
        val header = ByteArray(64)
        val count = FileInputStream(library).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) break
                offset += read
            }
            offset
        }
        require(count >= 20 && header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()) {
            "Grammar library is not a valid ELF shared object: " + library.name
        }
        val elfClass = header[4].toInt() and 0xff
        val elfData = header[5].toInt() and 0xff
        val elfType = (header[16].toInt() and 0xff) or
            ((header[17].toInt() and 0xff) shl 8)
        val machine = ByteBuffer.wrap(header, 18, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(elfData == 1 && elfType == 3) {
            "Grammar ELF must be a little-endian ET_DYN shared object: " + library.name
        }
        val supported = Build.SUPPORTED_ABIS.any { abi ->
            when (abi) {
                "arm64-v8a" -> elfClass == 2 && machine == 183
                "armeabi-v7a", "armeabi" -> elfClass == 1 && machine == 40
                "x86" -> elfClass == 1 && machine == 3
                "x86_64" -> elfClass == 2 && machine == 62
                "riscv64" -> elfClass == 2 && machine == 243
                else -> false
            }
        }
        require(supported) {
            "Grammar ELF ABI does not match this device: class=" + elfClass +
                " machine=" + machine + " supported=" + Build.SUPPORTED_ABIS.joinToString()
        }
    }

    fun resolve(request: TshLanguageRequest, resolver: TshLanguageResolver = TshLanguageResolver()): TshGrammarBinding? {
        lastResolutionDiagnostic = null
        val resolved = resolver.resolve(request, registry) ?: return null
        val entry = registry.find(resolved.languageId) ?: return null
        if (entry.state != TshGrammarRegistry.Entry.State.AVAILABLE &&
            entry.state != TshGrammarRegistry.Entry.State.READY) return null
        try {
            return load(entry.descriptor.id, resolved.extension)
        } catch (failure: Throwable) {
            lastResolutionDiagnostic = failure.message ?: failure.javaClass.simpleName
        }

        // If the suffix's provider is missing/incompatible, retry only from
        // independent evidence (MIME, shebang, content), never the same suffix.
        val fallback = resolver.resolve(request.copy(fileName = ""), registry) ?: return null
        if (fallback.languageId.equals(resolved.languageId, true)) return null
        return try {
            load(fallback.languageId, fallback.extension)
        } catch (failure: Throwable) {
            lastResolutionDiagnostic = failure.message ?: failure.javaClass.simpleName
            null
        }
    }

    fun load(id: String, requestedExtension: String? = null): TshGrammarBinding {
        val entry = checkNotNull(registry.find(id)) { "No Grammar registered for " + id }
        check(entry.state != TshGrammarRegistry.Entry.State.CONFLICT) {
            entry.diagnostic ?: "Grammar conflict"
        }
        val descriptor = entry.descriptor
        if (!descriptor.library.isFile || !descriptor.tshSource.isFile) {
            val diagnostic = "Grammar .so or .tsh source missing for " + id
            registry.update(id, TshGrammarRegistry.Entry.State.LOAD_FAILED, diagnostic)
            error(diagnostic)
        }
        try {
            verifyAndroidAbi(descriptor.library)
        } catch (failure: Throwable) {
            registry.update(id, TshGrammarRegistry.Entry.State.ABI_INCOMPATIBLE,
                failure.message ?: "Grammar ELF ABI validation failed")
            throw failure
        }
        val language = try {
            checkNotNull(TSLanguage.loadLanguage(
                descriptor.library.canonicalPath,
                descriptor.languageSymbol.removePrefix("tree_sitter_")
            )) { "Unable to load Grammar shared object " + descriptor.library }
        } catch (failure: Throwable) {
            registry.update(id, TshGrammarRegistry.Entry.State.LOAD_FAILED,
                failure.message ?: "Unable to load Grammar shared object")
            throw failure
        }

        try {
            val abi = language.languageVersion
            val min = TreeSitter.getMinimumCompatibleLanguageVersion()
            val max = TreeSitter.getLanguageVersion()
            require(abi in min..max) {
                registry.update(id, TshGrammarRegistry.Entry.State.ABI_INCOMPATIBLE,
                    "Grammar ABI $abi outside $min..$max")
                "Incompatible Tree-sitter Grammar ABI for $id"
            }

            val source = descriptor.tshSource.readText()
            val declared = descriptor.extensions.map {
                it.trim().trimStart('.').lowercase(Locale.ROOT)
            }.filter { it.isNotEmpty() }.distinct()
            // Compile the DSL once. Native registry lookup resolves every
            // additional extension declared by this immutable Grammar snapshot.
            val bindingExtension = requestedExtension?.trimStart('.')?.lowercase(Locale.ROOT)
                ?.takeIf { declared.isEmpty() || it in declared }
                ?: declared.firstOrNull()
                ?: id.lowercase(Locale.ROOT)
            if (!engine.loadTshSource(bindingExtension, source, language)) {
                val diagnostic = engine.lastCompileDiagnostics.joinToString {
                    it.code + ": " + it.message
                }
                registry.update(id, TshGrammarRegistry.Entry.State.LOAD_FAILED, diagnostic)
                error("Unable to compile .tsh source for .$bindingExtension: $diagnostic")
            }
            val binding = TshGrammarBinding.bind(language, bindingExtension, engine, descriptor)
            registry.update(id, TshGrammarRegistry.Entry.State.READY)
            return binding
        } catch (failure: Throwable) {
            if (registry.find(id)?.state != TshGrammarRegistry.Entry.State.ABI_INCOMPATIBLE) {
                registry.update(id, TshGrammarRegistry.Entry.State.LOAD_FAILED,
                    failure.message ?: failure.javaClass.simpleName)
            }
            language.close()
            throw failure
        }
    }
}

/** Immutable .tsh-to-TSLanguage identity binding. Native Document repeats pointer identity validation. */
class TshGrammarBinding private constructor(
    val language: TSLanguage,
    val extension: String,
    val metadata: WindbellTshEngine.LanguageMetadata,
    val descriptor: TshGrammarDescriptor?
) {
    val nativeLanguageIdentity: Long = language.nativeObject
    fun requireSameLanguage(other: TSLanguage) {
        check(other.nativeObject != 0L && other.nativeObject == nativeLanguageIdentity) {
            "TSH and Parser TSLanguage identity mismatch for ." + extension
        }
    }
    companion object {
        fun bind(language: TSLanguage, extension: String,
                 engine: WindbellTshEngine = WindbellTshEngine.instance,
                 descriptor: TshGrammarDescriptor? = null): TshGrammarBinding {
            require(language.nativeObject != 0L) { "TSLanguage is closed" }
            val abi = language.languageVersion
            require(abi in TreeSitter.getMinimumCompatibleLanguageVersion()..TreeSitter.getLanguageVersion()) {
                "Incompatible Grammar ABI " + abi
            }
            val normalized = extension.trimStart('.').lowercase(Locale.ROOT)
            val metadata = engine.getLanguageMetadata(normalized)
            require(metadata.id.isNotBlank()) { "No compiled .tsh grammar for ." + normalized }
            require(metadata.extensions.isEmpty() || metadata.extensions.any {
                it.trimStart('.').equals(normalized, true)
            }) { ".tsh metadata does not declare ." + normalized }
            return TshGrammarBinding(language, normalized, metadata, descriptor)
        }
    }
}

/** Per-document parser session. Native side owns an independent shared-object lease. */
class TshParserSession internal constructor(
    val binding: TshGrammarBinding, private val engine: WindbellTshEngine, val handle: Long
) : AutoCloseable {
    private val gate = Any()
    @Volatile private var closed = false

    fun applyEdit(start: Long, oldEnd: Long, inserted: ByteArray): Boolean = synchronized(gate) {
        check(!closed) { "Parser session is closed" }
        engine.applyNativeDocumentEdit(handle, start, oldEnd, inserted)
    }

    fun highlight(start: Long, end: Long, diagnostics: Boolean = false): IntArray? = synchronized(gate) {
        check(!closed) { "Parser session is closed" }
        engine.highlightNativeDocument(handle, start, end, diagnostics)
    }

    override fun close() = synchronized(gate) {
        if (!closed) {
            closed = true
            engine.closeNativeDocument(handle)
        }
    }
}

class TshParserFactory(private val engine: WindbellTshEngine = WindbellTshEngine.instance) {
    fun create(binding: TshGrammarBinding, bytes: Long, directory: File,
               reader: NativeTextWindowReader): TshParserSession {
        require(bytes >= 0L && bytes % 2L == 0L)
        binding.requireSameLanguage(binding.language)
        val handle = engine.createNativeDocument(binding.language, binding.extension, bytes, directory, reader)
        check(handle != 0L)
        return TshParserSession(binding, engine, handle)
    }
}

/** AST/ATS/Query/Regex/Capture/RenderPlan document owner and edit revision. */
class TshDocumentRuntime(val binding: TshGrammarBinding, val parser: TshParserSession) : AutoCloseable {
    private var revision = 0L
    @Synchronized fun applyEdit(start: Long, oldEnd: Long, inserted: ByteArray): Long {
        check(parser.applyEdit(start, oldEnd, inserted))
        return ++revision
    }
    @Synchronized fun revision(): Long = revision
    override fun close() = parser.close()
}
