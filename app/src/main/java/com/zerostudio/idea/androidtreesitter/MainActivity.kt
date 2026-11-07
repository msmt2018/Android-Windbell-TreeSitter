/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */
package com.zerostudio.idea.androidtreesitter

import android.app.ProgressDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.zerostudio.idea.androidtreesitter.databinding.ActivityMainBinding
import com.zerostudio.idea.androidtreesitter.databinding.ContentMainBinding
import com.zerostudio.idea.androidtreesitter.databinding.LayoutEditorPageBinding
import com.zerostudio.idea.piecetable.PieceTreeTextBufferBuilder
import com.zerostudio.idea.piecetable.common.Range
import com.zerostudio.idea.piecetable.common.SingleEditOperation
import com.zerostudio.idea.treesitter.*
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.json.TSLanguageJson
import com.zerostudio.idea.treesitter.kotlin.TSLanguageKotlin
import com.zerostudio.idea.treesitter.logcat.TSLanguageLogcat
import com.zerostudio.idea.treesitter.python.TSLanguagePython
import com.zerostudio.idea.treesitter.tsh.WindbellTshEngine
import com.zerostudio.idea.treesitter.xml.TSLanguageXml
import kotlinx.coroutines.*
import x.github.module.editor.model.PieceTreeBuffer
import x.github.module.editor.view.EditorView
import x.github.module.editor.input.AutoPair
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = checkNotNull(_binding) { "ActivityMainBinding has been destroyed" }

    private val activityScope =
        CoroutineScope(Dispatchers.Default + CoroutineName("MainActivity-Kernel"))

    // 解析与高亮作业句柄
    private var parser: TSParser? = null
    private var parseJob: Job? = null

    private var parseCount = 0L
    private var avgParse = 0L

    // Console parser selection is independent from the editor active grammar.
    private var currentLanguageKey: String = "Java"
    private var editorLanguageKey: String = "java"
    private var currentLanguage: TSLanguage? = null

    // 核心编辑器控件与文本缓存
    private var editorView: EditorView? = null
    private var editorTree: TSTree? = null
    private var currentEditorRawText: String = ""
    @Volatile private var javaTshReady: Boolean = false
    private var activeTshHighlighter: TshSyntaxHighlighter? = null
    private val tshConfigurationStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var restoredTshScheme: String? = null
    private var restoredTshDark: Boolean? = null

    companion object {
        private const val TAG = "Windbell_MainActivity"
        // Keep Java first and make the console language spinner deterministic.
        private val languageMap = java.util.Collections.synchronizedMap(linkedMapOf<String, TSLanguage>())

        init {
            TreeSitter.loadLibrary()
            try { System.loadLibrary("tree-sitter-java") } catch (_: Throwable) {}
            try { System.loadLibrary("tree-sitter-json") } catch (_: Throwable) {}
            try { System.loadLibrary("tree-sitter-kotlin") } catch (_: Throwable) {}
            try { System.loadLibrary("tree-sitter-logcat") } catch (_: Throwable) {}
            try { System.loadLibrary("tree-sitter-python") } catch (_: Throwable) {}
            try { System.loadLibrary("tree-sitter-xml") } catch (_: Throwable) {}

            runCatching { languageMap["Java"] = TSLanguageJava.getInstance() }
            runCatching { languageMap["JSON"] = TSLanguageJson.getInstance() }
            runCatching { languageMap["Kotlin"] = TSLanguageKotlin.getInstance() }
            runCatching { languageMap["Logcat"] = TSLanguageLogcat.getInstance() }
            runCatching { languageMap["Python"] = TSLanguagePython.getInstance() }
            runCatching { languageMap["XML"] = TSLanguageXml.getInstance() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredTshScheme = savedInstanceState?.getString("tsh.selectedScheme")
        if (savedInstanceState?.containsKey("tsh.isDark") == true) {
            restoredTshDark = savedInstanceState.getBoolean("tsh.isDark")
        }
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        currentLanguage = languageMap[currentLanguageKey]
        parser = TSParser.create()
        currentLanguage?.let { parser?.setLanguage(it) }

        // 初始化加载原生 .tsh 语法配置（完全放弃 SCM 方案）
        initTshEngineConfigurations()

        setupTabs()
        setupConsoleTab(binding.contentConsole)
        setupEditorTab(binding.contentEditor)
        loadExternalLanguages()
    }

    /**
     * Loads every packaged TSH configuration whose matching Tree-sitter language is registered.
     * Parser availability and TSH configuration availability are tracked independently.
     */
    private fun initTshEngineConfigurations() {
        // Only use parsers whose native libraries and language bindings actually
        // registered during process initialization. A missing .so must be reported,
        // not converted into an Activity crash by another getInstance() call.
        val registrations = linkedMapOf(
            "java" to "Java", "json" to "JSON", "kotlin" to "Kotlin",
            "logcat" to "Logcat", "python" to "Python", "xml" to "XML"
        )
        val languages = linkedMapOf<String, TSLanguage>()
        registrations.forEach { (extension, displayName) ->
            val language = languageMap[displayName]
            if (language != null) {
                languages[extension] = language
            } else {
                tshConfigurationStates[extension] = "PARSER_MISSING"
                Log.e(TAG, "Tree-sitter parser unavailable: .$extension ($displayName); native .so or language binding did not register")
            }
        }
        loadAvailableTshConfigurations(languages)
    }

    private fun loadAvailableTshConfigurations(languages: Map<String, TSLanguage>) {
        activityScope.launch(Dispatchers.IO) {
            val availableAssets = runCatching { assets.list("tsh").orEmpty().toSet() }
                .getOrElse {
                    Log.e(TAG, "Unable to enumerate assets/tsh", it)
                    emptySet()
                }
            languages.forEach { (extension, language) ->
                val assetName = "$extension.tsh"
                if (assetName !in availableAssets) {
                    tshConfigurationStates[extension] = "MISSING"
                    Log.i(TAG, "TSH config unavailable: assets/tsh/$assetName (Parser is registered; highlighting config is not)")
                    return@forEach
                }
                val engine = WindbellTshEngine.instance
                if (engine.isGrammarLoaded(extension)) {
                    tshConfigurationStates[extension] = "READY"
                    Log.i(TAG, "Reusing compiled native TSH grammar .$extension; skip recompilation on Activity recreation")
                    return@forEach
                }
                val result = runCatching {
                    val source = assets.open("tsh/$assetName").bufferedReader().use { it.readText() }
                    val compiled = synchronized(engine) {
                        engine.loadTshSource(extension, source, language)
                    }
                    if (!compiled) {
                        val diagnostics = WindbellTshEngine.instance.lastCompileDiagnostics
                        val message = diagnostics.joinToString { "${it.code}@${it.line}:${it.column}: ${it.message}" }
                        error(message.ifBlank { "Native TSH compilation failed without diagnostics" })
                    }
                }
                result.onSuccess {
                    tshConfigurationStates[extension] = "READY"
                    Log.i(TAG, "TSH config ready: .$extension")
                }.onFailure { error ->
                    tshConfigurationStates[extension] = "FAILED: ${error.message}"
                    Log.e(TAG, "Failed to compile $assetName", error)
                }
            }
            withContext(Dispatchers.Main) {
                if ("java" in languages) {
                    javaTshReady = tshConfigurationStates["java"] == "READY"
                    restoredTshScheme?.let { scheme ->
                        val restored = WindbellTshEngine.instance.selectScheme("java", scheme)
                        if (!restored) Log.w(TAG, "Saved TSH scheme no longer exists: $scheme")
                    }
                    restoredTshDark?.let { dark ->
                        WindbellTshEngine.instance.setAppearance("java", dark)
                    }
                    // On first launch there is no saved override. The Activity must
                    // still follow the active TSH scheme's declared default appearance.
                    val schemeDark = WindbellTshEngine.instance.isDark("java")
                    val desiredNightMode = if (schemeDark) {
                        AppCompatDelegate.MODE_NIGHT_YES
                    } else {
                        AppCompatDelegate.MODE_NIGHT_NO
                    }
                    if (AppCompatDelegate.getDefaultNightMode() != desiredNightMode) {
                        AppCompatDelegate.setDefaultNightMode(desiredNightMode)
                    }
                    restoredTshScheme = null
                    restoredTshDark = null
                    installTshHighlighterIfReady()
                    applyTshThemeTokens()
                }
                languages.keys.forEach { extension ->
                    Log.i(TAG, "TSH registration status .$extension: parser=READY config=${tshConfigurationStates[extension] ?: "UNKNOWN"}")
                }
            }
        }
    }

    private fun loadExternalTshConfiguration(extension: String, language: TSLanguage) {
        loadAvailableTshConfigurations(mapOf(extension to language))
    }

    private fun setupTabs() {
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("🧪 控制台与AST钻取"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("📝 代码编辑器"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.contentConsole.root.visibility = View.VISIBLE
                        binding.contentEditor.root.visibility = View.GONE
                    }
                    1 -> {
                        binding.contentConsole.root.visibility = View.GONE
                        binding.contentEditor.root.visibility = View.VISIBLE
                        editorView?.requestFocus()
                        triggerEditorFullHighlight()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupConsoleTab(content: ContentMainBinding) {
        content.code.isSaveEnabled = false
        content.ast.isSaveEnabled = false

        content.tsMeta.text = "Tree Sitter Language ABI: " + TreeSitter.getLanguageVersion()
        content.tsVersion.text = "Tree Sitter Core Engine: " + TreeSitter.getEngineVersion()

        content.code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                editable?.let { triggerConsoleParse(it.toString()) }
            }
        })
        loadLanguageSpinner(content)
    }

    private fun loadLanguageSpinner(content: ContentMainBinding) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, languageMap.keys.toTypedArray())
        content.languageChooser.adapter = adapter
        content.languageChooser.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                currentLanguageKey = parent.getItemAtPosition(position) as String
                currentLanguage = languageMap[currentLanguageKey]
                installTshHighlighterIfReady()
                currentLanguage?.let { lang ->
                    parser?.close()
                    parser = TSParser.create()
                    parser?.setLanguage(lang)
                }
                val text = content.code.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    triggerConsoleParse(text)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
    }

    private fun setupEditorTab(editorBinding: LayoutEditorPageBinding) {
        editorView = editorBinding.editorViewContainer
        installTshHighlighterIfReady()

        // 弹窗引导是否载入大型示例文件 (View.java.txt)
        MaterialAlertDialogBuilder(this)
            .setTitle("📝 代码编辑器初始化")
            .setMessage("是否在编辑器中加载默认的大型示例源码文件 (View.java.txt) 以供体验？")
            .setPositiveButton("加载示例文件") { _, _ ->
                activityScope.launch(Dispatchers.IO) {
                    val fileContent = runCatching {
                        assets.open("View.java.txt").bufferedReader().use { it.readText() }
                    }.getOrElse { "// ⚠️ 加载 View.java.txt 失败: ${it.localizedMessage}" }

                    withContext(Dispatchers.Main) {
                        setEditorContent(fileContent)
                    }
                }
            }
            .setNegativeButton("使用轻量示例") { _, _ ->
                val sampleCode = """
                    package com.zerostudio.idea.treesitter;

                    /**
                     * Windbell Treesitter Highlighting Core
                     * @author Akash Yadav
                     */
                    public class BenchmarkDemo {
                        public static final int BUFFER_CAPACITY = 0x4000;

                        public static void main(String[] args) {
                            var multiline = ""${'"'}
                                Multi-line Text Block
                                Unicode Surrogate: 👨‍👩‍👧‍👦
                                ""${'"'};
                            System.out.println("TSH Core Activated: " + BUFFER_CAPACITY);
                        }
                    }
                """.trimIndent()
                setEditorContent(sampleCode)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * The console language selector and the code editor are independent surfaces.
     * Changing the console parser must never detach the editor's Java TSH styler.
     * The editor currently owns a Java document, so install its grammar whenever
     * that grammar has compiled successfully, regardless of currentLanguageKey.
     */
    private fun installTshHighlighterIfReady() {
        val view = editorView ?: return
        if (javaTshReady) {
            if (activeTshHighlighter == null) {
                activeTshHighlighter = TshSyntaxHighlighter(
                    language = languageMap.getValue("Java"),
                    extension = "java"
                )
            }
            if (view.getSyntaxHighlighter() !== activeTshHighlighter) {
                view.setSyntaxHighlighter(activeTshHighlighter)
            }
            applyTshEditorMetadata()
            applyTshThemeTokens()
        } else {
            if (view.getSyntaxHighlighter() != null) view.setSyntaxHighlighter(null)
            view.setAutoPairs(emptyList())
            view.setAutoPairFilter(null)
            view.setBracketMatcher(null)
        }
    }

    /** Installs bracket and auto-pair behavior declared by the active .tsh file. */
    private fun applyTshEditorMetadata() {
        val view = editorView ?: return
        val highlighter = activeTshHighlighter ?: return
        val metadata = WindbellTshEngine.instance.getEditorMetadata("java")
        metadata.options["tabSize"]?.toIntOrNull()?.let { size ->
            if (size in 1..16) view.setTabSize(size)
        }
        metadata.options["fontSize"]?.toFloatOrNull()?.let { size ->
            if (size in 6f..48f) {
                view.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics
                ))
            }
        }
        metadata.options["insertSpaces"]?.toBooleanStrictOrNull()?.let { useSpaces ->
            val tabSize = metadata.options["tabSize"]?.toIntOrNull()?.coerceIn(1, 16) ?: 4
            view.setIndentUnit(if (useSpaces) " ".repeat(tabSize) else "\t")
        }
        metadata.options["autoIndent"]?.toBooleanStrictOrNull()?.let(view::setAutoIndent)
        metadata.options["lineNumbers"]?.toBooleanStrictOrNull()?.let(view::setLineNumbersVisible)
        val pairs = metadata.autoClosingPairs.mapNotNull { pair ->
            if (pair.open.length == 1 && pair.close.length == 1) {
                AutoPair(pair.open[0], pair.close[0])
            } else {
                null
            }
        }
        view.setAutoPairs(pairs)
        view.setAutoPairFilter(highlighter.createAutoPairFilter())
        view.setBracketMatcher(highlighter.createBracketMatcher())
    }

    /** Applies only UI colors explicitly declared by the active .tsh scheme. */
    private fun applyTshThemeTokens() {
        val engine = WindbellTshEngine.instance
        val tokens = engine.getThemeTokens("java")
        editorView?.let { view ->
            tokens["editor.background"]?.let(view::setBackgroundColor)
            tokens["editor.foreground"]?.let(view::setTextColor)
            tokens["editor.selection"]?.let(view::setSelectionColor)
            tokens["editor.lineHighlight"]?.let(view::setCurrentLineColor)
            tokens["editor.gutter"]?.let(view::setLineNumberColor)
        }

        // TSH UI tokens are the source of truth for the whole Activity, not only
        // the editor canvas. Apply them in-place; no DSL compilation or reparsing.
        tokens["activity.background"]?.let(binding.root::setBackgroundColor)
        tokens["activity.surface"]?.let(binding.appBarLayout::setBackgroundColor)
        tokens["activity.toolbar.background"]?.let(binding.toolbar::setBackgroundColor)
        tokens["activity.toolbar.foreground"]?.let { color ->
            binding.toolbar.setTitleTextColor(color)
            binding.toolbar.setSubtitleTextColor(color)
        }
        tokens["activity.tabs.background"]?.let(binding.tabLayout::setBackgroundColor)
        val normalTabColor = tokens["activity.tabs.foreground"]
        val selectedTabColor = tokens["activity.tabs.selected"]
        if (normalTabColor != null || selectedTabColor != null) {
            val fallback = normalTabColor ?: selectedTabColor!!
            binding.tabLayout.setTabTextColors(
                normalTabColor ?: fallback,
                selectedTabColor ?: fallback
            )
        }
        tokens["activity.tabs.indicator"]?.let(binding.tabLayout::setSelectedTabIndicatorColor)
        tokens["activity.statusBar"]?.let { window.statusBarColor = it }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            tokens["activity.navigationBar"]?.let { window.navigationBarColor = it }
        }

        // Match status/navigation icon contrast to the selected TSH appearance.
        val lightSystemBars = !engine.isDark("java")
        var flags = window.decorView.systemUiVisibility
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags = if (lightSystemBars) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            flags = if (lightSystemBars) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun setEditorContent(text: String) {
        currentEditorRawText = text
        val buffer = PieceTreeBuffer(PieceTreeTextBufferBuilder().build().apply {
            applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), text)), false)
        })
        editorView?.setBuffer(buffer)
        triggerEditorFullHighlight()
    }

    /**
     * 驱动 TSH 引擎执行宏微双通道高亮与语义分析
     */
    /**
     * Refreshes the single highlighting pipeline that actually feeds EditorView.
     *
     * Do not parse a second tree here: the installed TshSyntaxHighlighter owns the
     * parser, capture snapshot, line indexing and invalidation used by drawText().
     */
    private fun triggerEditorFullHighlight() {
        installTshHighlighterIfReady()
        val highlighter = activeTshHighlighter
        if (javaTshReady && highlighter != null) {
            highlighter.reparse()
        } else {
            Log.w(TAG, "Editor TSH renderer unavailable: ready=$javaTshReady highlighterInstalled=${editorView?.getSyntaxHighlighter() != null} editorLanguage=$editorLanguageKey consoleLanguage=$currentLanguageKey")
        }
    }

    private fun triggerConsoleParse(codeText: String) {
        val lang = currentLanguage ?: return
        val currentJob = this.parseJob

        this.parseJob = activityScope.launch {
            currentJob?.cancel(CancellationException("New parse requested"))

            val activeParser = parser ?: TSParser.create().also {
                parser = it
                it.setLanguage(lang)
            }
            activeParser.reset()

            val start = System.currentTimeMillis()
            val parseStart = System.currentTimeMillis()

            activeParser.parseString(codeText).use { tree ->
                val parseEnd = System.currentTimeMillis()
                val setupDur = parseStart - start
                val parseDur = parseEnd - parseStart

                avgParse += parseDur
                ++parseCount

                val sb = StringBuilder()
                sb.append("=== 🎯 Tree-sitter AST & TSH 状态面板 ===\n")
                sb.append("Setup: ${setupDur}ms | Parse: ${parseDur}ms | Avg: ${avgParse / parseCount}ms\n\n")

                if (tree != null) {
                    val ext = if (currentLanguageKey.equals("Kotlin", ignoreCase = true)) "kt" else "java"
                    val spans = WindbellTshEngine.instance.computeHighlights(tree, codeText, ext)
                    sb.append("--- ⚡ TSH 原生 AST+正则双通道高亮 Span (总数: ${spans.size}) ---\n")
                    for (i in 0 until minOf(spans.size, 15)) {
                        val sp = spans[i]
                        sb.append("  🏷️ Span [${sp.startByte}..${sp.endByte}] StyleID: ${sp.styleId} Flags: ${sp.fontFlags}\n")
                    }
                    if (spans.size > 15) sb.append("  ... 以及额外 ${spans.size - 15} 个高亮 Span\n\n")

                    sb.append("--- 🌳 全局 AST 完整节点树 ---\n")
                    tree.rootNode.walk().use { cursor ->
                        appendAstNodeInfo(sb, cursor, 0)
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.contentConsole.ast.text = sb.toString()
                }
            }
        }.also { job ->
            job.invokeOnCompletion { err ->
                if (err != null && err !is CancellationException) {
                    activityScope.launch(Dispatchers.Main) {
                        binding.contentConsole.ast.text = traceError(err)
                    }
                }
            }
        }
    }

    private fun appendAstNodeInfo(sb: StringBuilder, cursor: TSTreeCursor, indent: Int) {
        val node = cursor.currentNode
        sb.append("  ".repeat(indent))
        sb.append(node.type).append("[byte: ").append(node.startByte).append("..").append(node.endByte).append("]\n")

        val childCount = node.childCount
        if (childCount > 0) {
            for (i in 0 until childCount) {
                node.getChild(i)?.walk()?.use { childCursor ->
                    appendAstNodeInfo(sb, childCursor, indent + 1)
                }
            }
        }
    }

    private fun loadExternalLanguages() {
        activityScope.launch(Dispatchers.IO) {
            // The language registry and native TSH engine outlive Activity recreation.
            // Do not reopen the same C grammar library on every Light/Dark switch.
            if (languageMap.containsKey("C")) return@launch
            runCatching {
                val cLang = TSLanguage.loadLanguage(this@MainActivity, "c")
                if (cLang != null) {
                    withContext(Dispatchers.Main) {
                        languageMap["C"] = cLang
                        (binding.contentConsole.languageChooser.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
                        loadExternalTshConfiguration("c", cLang)
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.reload_external -> {
                destroyParserResources()
                loadExternalLanguages()
                return true
            }
            R.id.test_tsh_suite -> {
                showTshCoreTestSuiteDialog()
                return true
            }
            R.id.test_tsh_cursor_capture -> {
                runCursorCaptureDiagnostic()
                return true
            }
            R.id.show_tsh_schemes -> {
                showTshSchemesDialog()
                return true
            }
            R.id.toggle_tsh_theme -> {
                toggleTshTheme()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }


    /** Find the deepest named Java Tree-sitter node containing a UTF-16LE byte range. */
    private fun findSmallestNamedNode(root: TSNode, startByte: Int, endByte: Int): TSNode {
        var current = root
        while (true) {
            var next: TSNode? = null
            for (index in 0 until current.childCount) {
                val child = current.getChild(index)
                if (!child.isNull && child.isNamed &&
                    child.startByte <= startByte && child.endByte >= endByte
                ) {
                    next = child
                    break
                }
            }
            current = next ?: return current
        }
    }

    /** Reparse the editor's current source and report parser, captures and Android spans. */
    private fun runCursorCaptureDiagnostic() {
        val view = editorView
        if (view == null || !javaTshReady) {
            showResultDialog("TSH 捕获诊断", "编辑器或 Java TSH 尚未就绪。")
            return
        }
        val source = view.getText()
        val lineNumber = view.getCursorLine().coerceIn(1, view.getLineCount())
        val lineStart = view.getLineStartOffset(lineNumber)
        val lineText = view.getLineText(lineNumber)
        val lineEnd = lineStart + lineText.length
        val cursorOffset = (lineStart + view.getCursorColumn() - 1)
            .coerceIn(0, (source.length - 1).coerceAtLeast(0))
        val engine = WindbellTshEngine.instance
        val report = StringBuilder()
        report.appendLine("=== TSH 光标行 / 符号块全链路诊断 ===")
        report.appendLine("Editor language: " + editorLanguageKey + " · Parser registry: " + languageMap.keys)
        report.appendLine("Console language: " + currentLanguageKey)
        report.appendLine("Cursor: line=" + lineNumber + " column=" + view.getCursorColumn() + " UTF16-offset=" + cursorOffset)
        report.appendLine("Source chars=" + source.length + ", UTF-16LE bytes=" + source.length * 2)
        report.appendLine("Cursor line [" + lineStart + "," + lineEnd + "):")
        report.appendLine(lineText)

        val parserStart = System.nanoTime()
        val activeParser = TSParser.create()
        try {
            activeParser.setLanguage(languageMap.getValue("Java"))
            val tree = activeParser.parseString(source)
            val parserNanos = System.nanoTime() - parserStart
            if (tree == null) {
                report.appendLine("Tree-sitter FAILED: null tree")
            } else {
                tree.use { parsedTree ->
                    val root = parsedTree.rootNode
                    val cursorByteStart = cursorOffset * 2
                    val cursorByteEnd = (cursorOffset + 1).coerceAtMost(source.length) * 2
                    val cursorNode = if (source.isEmpty()) root else
                        findSmallestNamedNode(root, cursorByteStart, cursorByteEnd)
                    val declarationTypes = setOf(
                        "class_declaration", "interface_declaration", "enum_declaration",
                        "record_declaration", "annotation_type_declaration",
                        "method_declaration", "constructor_declaration",
                        "field_declaration", "local_variable_declaration",
                        "formal_parameter", "enum_constant"
                    )
                    var symbolNode: TSNode? = cursorNode
                    while (symbolNode != null && symbolNode.type !in declarationTypes) {
                        symbolNode = symbolNode.parent
                    }
                    if (symbolNode == null) symbolNode = cursorNode
                    val selectedNode = checkNotNull(symbolNode)
                    val symbolStart = (selectedNode.startByte / 2).coerceIn(0, source.length)
                    val symbolEnd = (selectedNode.endByte / 2).coerceIn(symbolStart, source.length)
                    val symbolText = source.substring(symbolStart, symbolEnd)

                    val queryStart = System.nanoTime()
                    val pipelineDiagnostics = engine.computePipelineDiagnostics(parsedTree, source, "java")
                    val captures = pipelineDiagnostics.captures
                    val queryNanos = System.nanoTime() - queryStart
                    val lineCaptures = captures.filter { span ->
                        (span.startByte shr 1) < lineEnd && (span.endByte shr 1) > lineStart
                    }
                    val symbolCaptures = captures.filter { span ->
                        (span.startByte shr 1) < symbolEnd && (span.endByte shr 1) > symbolStart
                    }

                    val scheme = engine.getSelectedScheme("java") ?: ""
                    val paletteStart = System.nanoTime()
                    val palette = engine.getSchemePalette("java", scheme)
                    val paletteNanos = System.nanoTime() - paletteStart
                    val styles = engine.getStyleCatalog("java").associateBy { it.styleId }

                    val linePreview = android.text.SpannableStringBuilder(lineText)
                    engine.applyHighlightSpansToSpannable(lineCaptures, lineStart, lineText, "java", linePreview)
                    val lineForeground = linePreview.getSpans(
                        0, linePreview.length, android.text.style.ForegroundColorSpan::class.java
                    )
                    val symbolPreview = android.text.SpannableStringBuilder(symbolText)
                    engine.applyHighlightSpansToSpannable(
                        symbolCaptures, symbolStart, symbolText, "java", symbolPreview
                    )
                    val symbolForeground = symbolPreview.getSpans(
                        0, symbolPreview.length, android.text.style.ForegroundColorSpan::class.java
                    )

                    val installed = activeTshHighlighter != null && view.getSyntaxHighlighter() === activeTshHighlighter
                    val editorLineSpans = activeTshHighlighter?.styleLine(
                        lineText, lineNumber, lineStart, 0, lineText.length
                    )
                    val editorForeground = editorLineSpans?.getSpans(
                        0, lineText.length, android.text.style.ForegroundColorSpan::class.java
                    ) ?: emptyArray()

                    report.appendLine()
                    report.appendLine("1) Parser: " + parsedTree.rootNode.type + " · " +
                        String.format(Locale.US, "%.3f", parserNanos / 1_000_000.0) + " ms")
                    report.appendLine("2) TSH native pipeline: " + captures.size + " raw captures · " +
                        String.format(Locale.US, "%.3f", queryNanos / 1_000_000.0) + " ms")
                    val sourceNames = mapOf(
                        0 to "Oniguruma Regex", 1 to "Monarch",
                        2 to "ATS Selector", 3 to "Tree-sitter Query"
                    )
                    for (sourceId in 0..3) {
                        val produced = captures.count { it.source == sourceId }
                        val inLine = lineCaptures.count { it.source == sourceId }
                        val inSymbol = symbolCaptures.count { it.source == sourceId }
                        report.appendLine(
                            "   " + sourceNames.getValue(sourceId) +
                                ": matches=" + (pipelineDiagnostics.matchCounts[sourceId] ?: 0) +
                                " captures=" + produced + " line=" + inLine + " symbol=" + inSymbol
                        )
                    }
                    report.appendLine()
                    report.appendLine("3) Cursor AST node: " + cursorNode.type)
                    report.appendLine("   Ancestors: " + generateSequence(cursorNode) { it.parent }
                        .take(8).joinToString(" <- ") { it.type })
                    report.appendLine("4) Selected symbol block: " + selectedNode.type +
                        " UTF16=[" + symbolStart + "," + symbolEnd + ") chars=" + symbolText.length)
                    report.appendLine(symbolText.take(12000))
                    if (symbolText.length > 12000) report.appendLine("… symbol block truncated in report")
                    report.appendLine("5) Scheme=" + scheme + " dark=" + engine.isDark("java") +
                        " styles=" + styles.size + " palette=" + palette.size +
                        " paletteLookup=" + String.format(Locale.US, "%.3f", paletteNanos / 1_000_000.0) + " ms")
                    report.appendLine("6) Android preview spans: lineForeground=" + lineForeground.size +
                        " symbolForeground=" + symbolForeground.size)
                    report.appendLine("7) Editor renderer: attached=" + installed +
                        " styleLineForeground=" + editorForeground.size +
                        " (zero may mean the installed async snapshot has not completed yet)")
                    report.appendLine("8) Palette binding audit:")
                    symbolCaptures.distinctBy { it.styleId }.forEach { span ->
                        val style = styles[span.styleId]
                        val colors = palette[span.styleId]
                        val light = if (colors?.hasLightColor == true) String.format(Locale.ROOT, "#%08X", colors.lightColor) else "MISSING"
                        val dark = if (colors?.hasDarkColor == true) String.format(Locale.ROOT, "#%08X", colors.darkColor) else "MISSING"
                        report.appendLine(
                            "   id=" + span.styleId + " name=" + (style?.name ?: "UNKNOWN") +
                                " scope=" + (style?.scope ?: "UNKNOWN") + " light=" + light + " dark=" + dark
                        )
                    }
                    report.appendLine("9) Sample captures in selected symbol:")
                    symbolCaptures.take(80).forEach { span ->
                        val from = ((span.startByte shr 1) - symbolStart).coerceIn(0, symbolText.length)
                        val to = ((span.endByte shr 1) - symbolStart).coerceIn(from, symbolText.length)
                        report.appendLine(
                            "   " + sourceNames[span.source] +
                                " [" + from + "," + to + ") " +
                                (styles[span.styleId]?.name ?: "style#" + span.styleId) +
                                " = " + symbolText.substring(from, to).replace("\n", "\\n")
                        )
                    }
                    if (symbolCaptures.isEmpty()) report.appendLine("NO SYMBOL CAPTURES: inspect TSH rule match, ATS selector, query predicate and parser grammar.")
                    report.appendLine("10) Canvas pixels are not claimed here; device instrumentation is required for final pixel verification.")
                }
            }
        } catch (error: Throwable) {
            report.appendLine("DIAGNOSTIC ERROR: " + error.stackTraceToString())
        } finally {
            activeParser.close()
        }
        showResultDialog("TSH 光标行 / 符号捕获诊断", report.toString())
    }

    private fun applyTshScheme(scheme: String, isDark: Boolean) {
        val engine = WindbellTshEngine.instance
        val selected = activeTshHighlighter?.selectScheme(scheme) ?: engine.selectScheme("java", scheme)
        if (!selected) {
            showResultDialog("TSH Scheme", "Scheme 切换失败：" + scheme)
            return
        }
        engine.setAppearance("java", isDark)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        activeTshHighlighter?.refreshTheme()
        applyTshThemeTokens()
    }

    /** Browse all compiled schemes and their light/dark semantic colors. */
    private fun showTshSchemesDialog() {
        val engine = WindbellTshEngine.instance
        val schemes = engine.listSchemes("java")
        if (schemes.isEmpty()) {
            showResultDialog("TSH Schemes", "没有已编译的 Java Scheme。")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("TSH Scheme 主题包")
            .setItems(schemes.toTypedArray()) { _, index ->
                val scheme = schemes[index]
                val palette = engine.getSchemePalette("java", scheme)
                val styles = engine.getStyleCatalog("java").associateBy { it.styleId }
                val detail = StringBuilder("Scheme: " + scheme + "\nPalette entries: " + palette.size + "\n\n")
                palette.values.sortedBy { it.styleId }.forEach { entry ->
                    val style = styles[entry.styleId]
                    val light = if (entry.hasLightColor) String.format(Locale.ROOT, "#%08X", entry.lightColor) else "未配置"
                    val dark = if (entry.hasDarkColor) String.format(Locale.ROOT, "#%08X", entry.darkColor) else "未配置"
                    detail.appendLine((style?.name ?: "style#" + entry.styleId) + " (" + (style?.scope ?: "unknown") + ")")
                    detail.appendLine("  Light: " + light)
                    detail.appendLine("  Dark:  " + dark)
                }
                val lightTokens = engine.getSchemeThemeTokens("java", scheme, false)
                val darkTokens = engine.getSchemeThemeTokens("java", scheme, true)
                detail.appendLine()
                detail.appendLine("UI Theme Tokens · Light (${lightTokens.size})")
                lightTokens.toSortedMap().forEach { (key, color) ->
                    detail.appendLine("  " + key + ": " + String.format(Locale.ROOT, "#%08X", color))
                }
                detail.appendLine("UI Theme Tokens · Dark (${darkTokens.size})")
                darkTokens.toSortedMap().forEach { (key, color) ->
                    detail.appendLine("  " + key + ": " + String.format(Locale.ROOT, "#%08X", color))
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(scheme + " · Light / Dark")
                    .setMessage(detail.toString())
                    .setPositiveButton("按当前 Activity 模式应用") { _, _ ->
                        val mode = AppCompatDelegate.getDefaultNightMode()
                        val dark = mode == AppCompatDelegate.MODE_NIGHT_YES ||
                            (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM &&
                                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES)
                        applyTshScheme(scheme, dark)
                    }
                    .setNeutralButton("应用 Light") { _, _ -> applyTshScheme(scheme, false) }
                    .setNegativeButton("应用 Dark") { _, _ -> applyTshScheme(scheme, true) }
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 切换 TSH 白天/黑夜多态调色板
     */
    private fun toggleTshTheme() {
        val engine = WindbellTshEngine.instance
        val nextDark = !engine.isDark("java")
        engine.setAppearance("java", nextDark)
        AppCompatDelegate.setDefaultNightMode(
            if (nextDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        activeTshHighlighter?.refreshTheme()
        applyTshThemeTokens()
        val currentThemeName = if (nextDark) "黑夜深色 (Dark)" else "白天浅色 (Light)"
        MaterialAlertDialogBuilder(this)
            .setTitle("🎨 TSH / Activity 主题")
            .setMessage("已请求切换为 $currentThemeName。TSH 复用已编译 palette，不重新编译规则或重新解析文档；Activity 主题由 AppCompatDelegate 同步切换。")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * ⚙️ Windbell-TSH 核心引擎专用全功能测试与诊断中心
     */
    private fun showTshCoreTestSuiteDialog() {
        val testOptions = arrayOf(
            "1. 🎨 Scheme / Style ID / UI Token 颜色映射",
            "2. ⚡ Regex / Monarch / ATS / Native Query 四管线覆盖",
            "3. 🧩 Tree-sitter Parser 与 TSH 配置注册审计",
            "4. 🧵 32 线程并发重入与资源释放压测"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Windbell-TSH 核心测试与诊断")
            .setItems(testOptions) { _, which ->
                when (which) {
                    0 -> runTshPaletteTest()
                    1 -> runTshHybridCoverageTest()
                    2 -> runTshParserRegistryTest()
                    3 -> runTshConcurrencyTest()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun runTshParserRegistryTest() {
        val report = StringBuilder("=== Tree-sitter Parser / TSH 注册审计 ===\n\n")
        val labels = linkedMapOf("Java" to "java", "JSON" to "json", "Kotlin" to "kotlin", "Logcat" to "logcat", "Python" to "python", "XML" to "xml", "C" to "c")
        labels.forEach { (name, extension) ->
            val parserState = if (languageMap.containsKey(name)) "READY" else "PARSER_MISSING"
            val configState = tshConfigurationStates[extension] ?: "NOT_CHECKED"
            val schemes = if (configState == "READY") WindbellTshEngine.instance.listSchemes(extension) else emptyList()
            val styles = if (configState == "READY") WindbellTshEngine.instance.getStyleCatalog(extension).size else 0
            val bindingClass = if (name == "C") "TSLanguage.loadLanguage" else "TSLanguage$name"
            val nativeLibrary = "libtree-sitter-$extension.so"
            report.appendLine("$name (.$extension): parser=$parserState · binding=$bindingClass · native=$nativeLibrary · tsh=$configState · schemes=${schemes.size} · styles=$styles")
            if (schemes.isNotEmpty()) report.appendLine("   " + schemes.joinToString())
        }
        report.appendLine()
        report.appendLine("Editor language=$editorLanguageKey · attached=${activeTshHighlighter != null && editorView?.getSyntaxHighlighter() === activeTshHighlighter}")
        showResultDialog("Parser / TSH 注册审计", report.toString())
    }

    private fun runTshPaletteTest() {
        val report = StringBuilder()
        report.append("=== 🎨 TSH 调色板映射测试 ===\n\n")

        val ext = "java"
        // Never reuse editorTree here: it belongs to the console editor, not this sample.
        val sampleCode = editorView?.getText()?.takeIf { it.isNotBlank() }
            ?: "public class Sample { int x = 100; }"
        val sampleParser = TSParser.create().also { it.setLanguage(languageMap.getValue("Java")) }
        val sampleTree = sampleParser.parseString(sampleCode)
        val spans = try {
            WindbellTshEngine.instance.computeHighlights(sampleTree, sampleCode, ext)
        } finally {
            sampleTree?.close()
            sampleParser.close()
        }

        report.append("当前已加载语言扩展: $ext\n")
        report.append("生成 Span 数量: ${spans.size}\n\n")

        val distinctStyleIds = spans.map { it.styleId }.distinct()
        val engine = WindbellTshEngine.instance
        val scheme = engine.getSelectedScheme(ext) ?: engine.getDefaultScheme(ext) ?: "(none)"
        val palette = engine.getSchemePalette(ext, scheme)
        val styleCatalog = engine.getStyleCatalog(ext).associateBy { it.styleId }
        val missing = distinctStyleIds.filter { id ->
            val color = palette[id]
            color == null || (!color.hasLightColor && !color.hasDarkColor)
        }
        val preview = android.text.SpannableStringBuilder(sampleCode)
        engine.applyHighlightSpansToSpannable(spans, 0, sampleCode, ext, preview)
        val foreground = preview.getSpans(0, preview.length, android.text.style.ForegroundColorSpan::class.java)
        report.append("Scheme=$scheme · palette=${palette.size} · foregroundSpans=${foreground.size} · missing=$missing\n")
        distinctStyleIds.forEach { id ->
            val style = styleCatalog[id]
            val color = palette[id]
            report.appendLine("  id=$id ${style?.name ?: "UNKNOWN"} scope=${style?.scope ?: "UNKNOWN"} light=" + (if (color?.hasLightColor == true) String.format(Locale.ROOT, "#%08X", color.lightColor) else "MISSING") + " dark=" + (if (color?.hasDarkColor == true) String.format(Locale.ROOT, "#%08X", color.darkColor) else "MISSING"))
        }
        report.append("捕获的独立 Style ID 集合: $distinctStyleIds\n")
        report.append("StyleID 映射状态: 全部在 Native 栈完成偶数对齐与非重叠校验。\n")

        showResultDialog("调色板测试结果", report.toString())
    }

    private fun runTshHybridCoverageTest() {
        val pd = ProgressDialog(this).apply {
            setMessage("正在测试双通道扫描线合并仲裁...")
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val text = """
                // UTF-16LE probe: 中文 👨‍👩‍👧‍👦 𠮷
                package com.demo;
                /** Javadoc comment with @param target */
                public class TestClass {
                    private static final int MAX_CACHE_CAPACITY = 1024;
                    // Single line comment
                    private String message = "Hello \"Escaped\" World";
                    public void doWork() {
                        int value = 0xABCD;
                        String multiline = "native query probe";
                    }
                }
            """.trimIndent()

            val lang = languageMap.getValue("Java")
            val parser = TSParser.create().apply { setLanguage(lang) }
            var tree: TSTree? = null
            val engine = WindbellTshEngine.instance
            val diagnostics: WindbellTshEngine.PipelineDiagnostics
            val spans: List<WindbellTshEngine.HighlightSpan>
            try {
                tree = parser.parseString(text)
                diagnostics = engine.computePipelineDiagnostics(tree, text, "java")
                spans = engine.computeHighlights(tree, text, "java")
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    pd.dismiss()
                    showResultDialog("四管线测试失败", error.stackTraceToString())
                }
                return@launch
            } finally {
                tree?.close()
                parser.close()
            }

            val report = StringBuilder()
            report.append("=== ⚡ 四管线混合扫描线合成报告 ===\n\n")
            report.append("源码字符总数: ${text.length} (UTF-16LE bytes: ${text.length * 2})\n")
            report.append("原始候选捕获: ${diagnostics.captures.size} · 最终渲染 Span: ${spans.size}\n")
            val pipelineNames = mapOf(0 to "Oniguruma", 1 to "Monarch", 2 to "ATS", 3 to "Native Query")
            for (id in 0..3) {
                report.appendLine("${pipelineNames.getValue(id)} matches=${diagnostics.matchCounts[id] ?: 0} captures=${diagnostics.captures.count { it.source == id }}")
            }
            report.appendLine()

            for (span in spans.take(12)) {
                val startChar = span.startByte shr 1
                val endChar = span.endByte shr 1
                val snippet = text.substring(startChar, minOf(endChar, text.length)).replace("\n", "\\n")
                report.append("  [${span.startByte}..${span.endByte}] Id:${span.styleId} -> \"$snippet\"\n")
            }

            withContext(Dispatchers.Main) {
                pd.dismiss()
                showResultDialog("双通道覆盖测试", report.toString())
            }
        }
    }

    private fun runTshConcurrencyTest() {
        val pd = ProgressDialog(this).apply {
            setTitle("TSH Native 并发重入压测")
            setMessage("32 个线程执行 Tree-sitter parse + 四管线高亮 + 释放…")
            setCancelable(false)
            show()
        }
        activityScope.launch(Dispatchers.IO) {
            val threadCount = 32
            val iterations = 30
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)
            val errors = java.util.concurrent.atomic.AtomicInteger(0)
            val emptyResults = java.util.concurrent.atomic.AtomicInteger(0)
            val code = "// UTF-16: 中文 👨‍👩‍👧‍👦 𠮷\npublic class ConcTest { int a = 1; void run() { a++; } }"
            val started = System.nanoTime()
            repeat(threadCount) {
                executor.submit {
                    var parser: TSParser? = null
                    var tree: TSTree? = null
                    try {
                        val activeParser = TSParser.create()
                        parser = activeParser
                        activeParser.setLanguage(languageMap.getValue("Java"))
                        tree = activeParser.parseString(code)
                        repeat(iterations) {
                            val result = WindbellTshEngine.instance.computeHighlights(tree, code, "java")
                            if (result.isEmpty()) emptyResults.incrementAndGet()
                        }
                    } catch (error: Throwable) {
                        errors.incrementAndGet()
                        Log.e(TAG, "TSH concurrency probe failed", error)
                    } finally {
                        tree?.close()
                        parser?.close()
                        latch.countDown()
                    }
                }
            }
            latch.await()
            executor.shutdown()
            executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            val durationMs = (System.nanoTime() - started) / 1_000_000.0
            val totalCalls = threadCount * iterations
            withContext(Dispatchers.Main) {
                pd.dismiss()
                val passed = errors.get() == 0 && emptyResults.get() == 0
                showResultDialog(
                    "TSH 并发压测结果",
                    "线程数=$threadCount · 每线程=$iterations · 总调用=$totalCalls\n" +
                        "耗时=" + String.format(Locale.US, "%.3f", durationMs) + " ms\n" +
                        "异常数=${errors.get()} · 空结果数=${emptyResults.get()}\n" +
                        if (passed) "RESULT: PASS" else "RESULT: FAIL"
                )
            }
        }
    }
    private fun showResultDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun destroyParserResources() {
        parseJob?.cancel()
        parser?.close()
        parser = null
        editorTree?.close()
        editorTree = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tsh.selectedScheme", WindbellTshEngine.instance.getSelectedScheme("java"))
        outState.putBoolean("tsh.isDark", WindbellTshEngine.instance.isDark("java"))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyParserResources()
        activityScope.cancel()
    }

    private fun traceError(err: Throwable): String {
        val sw = StringWriter()
        err.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}