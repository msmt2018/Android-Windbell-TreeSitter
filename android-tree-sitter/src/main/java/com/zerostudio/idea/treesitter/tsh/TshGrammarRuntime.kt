package com.zerostudio.idea.treesitter.tsh

import android.content.Context
import android.os.Build
import com.zerostudio.idea.treesitter.TSLanguage
import com.zerostudio.idea.treesitter.TreeSitter
import java.io.File
import java.io.FileInputStream
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
    enum class Match { COMPOUND_EXTENSION, EXTENSION, MIME, SHEBANG, CONTENT }
}

/** Resolves compound suffixes before simple suffixes, then MIME/shebang/content evidence. */
class TshLanguageResolver(private val mimeAliases: Map<String, String> = mapOf(
    "application/json" to "json", "application/xml" to "xml", "text/html" to "html",
    "text/x-python" to "python", "text/x-java-source" to "java",
    "text/javascript" to "javascript", "application/javascript" to "javascript"
)) {
    fun resolve(request: TshLanguageRequest, registry: TshGrammarRegistry): TshLanguageResolution? {
        val name = request.fileName.substringAfterLast('/').lowercase(Locale.ROOT)
        val entries = registry.snapshot()
        val compound = entries.flatMap { e -> e.extensions.filter { it.contains('.') }.map { it.lowercase() to e } }
            .sortedByDescending { it.first.length }
        compound.firstOrNull { name.endsWith("." + it.first) }?.let {
            return TshLanguageResolution(it.second.id, it.first, TshLanguageResolution.Match.COMPOUND_EXTENSION)
        }
        entries.forEach { e -> e.extensions.firstOrNull { !it.contains('.') && name.endsWith("." + it.lowercase()) }?.let {
            return TshLanguageResolution(e.id, it, TshLanguageResolution.Match.EXTENSION)
        } }
        val mime = request.mimeType?.substringBefore(';')?.trim()?.lowercase()
        val mimeId = mime?.let { mimeAliases[it] }
        entries.firstOrNull { it.id.equals(mimeId, true) || (mime != null && mime in it.mimeTypes) }?.let {
            return TshLanguageResolution(it.id, it.extensions.firstOrNull().orEmpty(), TshLanguageResolution.Match.MIME)
        }
        val first = (request.shebang ?: request.contentPrefix.lineSequence().firstOrNull().orEmpty()).trim()
        val shebangId = when {
            first.startsWith("#!") && first.contains("python") -> "python"
            first.startsWith("#!") && (first.contains("node") || first.contains("deno")) -> "javascript"
            first.startsWith("#!") && first.contains("bash") -> "bash"
            first.startsWith("#!") && first.contains("ruby") -> "ruby"
            else -> null
        }
        entries.firstOrNull { it.id.equals(shebangId, true) || it.aliases.any { a -> a.equals(shebangId, true) } }?.let {
            return TshLanguageResolution(it.id, it.extensions.firstOrNull().orEmpty(), TshLanguageResolution.Match.SHEBANG)
        }
        val prefix = request.contentPrefix.trimStart()
        val contentId = when {
            prefix.startsWith("{") || prefix.startsWith("[") -> "json"
            prefix.startsWith("<?xml") -> "xml"
            prefix.startsWith("<html", true) || prefix.startsWith("<!doctype html", true) -> "html"
            else -> null
        }
        return entries.firstOrNull { it.id.equals(contentId, true) }?.let {
            TshLanguageResolution(it.id, it.extensions.firstOrNull().orEmpty(), TshLanguageResolution.Match.CONTENT)
        }
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
    val source: Source
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
            old.descriptor.library.canonicalPath == value.library.canonicalPath -> Entry(value, Entry.State.AVAILABLE)
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
    fun discover(externalDirectories: List<File> = emptyList()) {
        val roots = listOf(File(context.applicationInfo.nativeLibraryDir)) + externalDirectories.map { root ->
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            File(root, abi).takeIf { it.isDirectory } ?: root
        }
        roots.forEachIndexed { index, root ->
            root.listFiles { f -> f.isFile && f.name.startsWith("libtree-sitter-") && f.name.endsWith(".so") }
                .orEmpty().forEach { library ->
                    val id = library.name.removePrefix("libtree-sitter-").removeSuffix(".so")
                    val tsh = File(library.parentFile, id + ".tsh")
                    if (tsh.isFile) registry.register(TshGrammarDescriptor(
                        id, "tree_sitter_" + id, setOf(id), emptySet(), emptySet(),
                        library, tsh, if (index == 0) TshGrammarDescriptor.Source.APK else TshGrammarDescriptor.Source.EXTERNAL
                    ))
                }
        }
    }

    private fun verifyAndroidAbi(library: File) {
        val header = ByteArray(20)
        val count = FileInputStream(library).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) break
                offset += read
            }
            offset
        }
        require(count == header.size && header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()) {
            "Grammar library is not a valid ELF shared object: " + library.name
        }
        val elfClass = header[4].toInt() and 0xff
        val machine = ByteBuffer.wrap(header, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
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

    fun load(id: String): TshGrammarBinding {
        val entry = checkNotNull(registry.find(id)) { "No Grammar registered for " + id }
        check(entry.state != TshGrammarRegistry.Entry.State.CONFLICT) { entry.diagnostic ?: "Grammar conflict" }
        val d = entry.descriptor
        check(d.library.isFile && d.tshSource.isFile) { "Grammar .so or .tsh source missing for " + id }
        verifyAndroidAbi(d.library)
        val language = checkNotNull(TSLanguage.loadLanguage(d.library.canonicalPath, d.languageSymbol.removePrefix("tree_sitter_"))) {
            "Unable to load Grammar shared object " + d.library
        }
        val abi = language.languageVersion
        val min = TreeSitter.getMinimumCompatibleLanguageVersion()
        val max = TreeSitter.getLanguageVersion()
        if (abi !in min..max) {
            language.close()
            registry.update(id, TshGrammarRegistry.Entry.State.ABI_INCOMPATIBLE, "Grammar ABI " + abi + " outside " + min + ".." + max)
            error("Incompatible Tree-sitter Grammar ABI for " + id)
        }
        if (!engine.loadTshSource(d.extensions.first(), d.tshSource.readText(), language)) {
            language.close()
            registry.update(id, TshGrammarRegistry.Entry.State.LOAD_FAILED,
                engine.lastCompileDiagnostics.joinToString { it.code + ": " + it.message })
            error("Unable to compile .tsh source for " + id)
        }
        registry.update(id, TshGrammarRegistry.Entry.State.READY)
        return TshGrammarBinding.bind(language, d.extensions.first(), engine, d)
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
    @Volatile private var closed = false
    fun applyEdit(start: Long, oldEnd: Long, inserted: ByteArray): Boolean {
        check(!closed)
        return engine.applyNativeDocumentEdit(handle, start, oldEnd, inserted)
    }
    fun highlight(start: Long, end: Long, diagnostics: Boolean = false): IntArray? {
        check(!closed)
        return engine.highlightNativeDocument(handle, start, end, diagnostics)
    }
    override fun close() {
        if (!closed) { closed = true; engine.closeNativeDocument(handle) }
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
