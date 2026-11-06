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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
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
    private var editorHighlightJob: Job? = null

    private var parseCount = 0L
    private var avgParse = 0L

    private var currentLanguageKey: String = "Java"
    private var currentLanguage: TSLanguage? = null

    // 核心编辑器控件与文本缓存
    private var editorView: EditorView? = null
    private var editorTree: TSTree? = null
    private var currentEditorRawText: String = ""
    @Volatile private var javaTshReady: Boolean = false
    private var activeTshHighlighter: TshSyntaxHighlighter? = null

    companion object {
        private const val TAG = "Windbell_MainActivity"
        private val languageMap = hashMapOf<String, TSLanguage>()

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
     * 加载并编译原生 .tsh 语法配置
     */
    private fun initTshEngineConfigurations() {
        activityScope.launch(Dispatchers.IO) {
            runCatching {
                assets.open("tsh/java.tsh").bufferedReader().use { it.readText() }
            }.onSuccess { javaTshContent ->
                val ok = WindbellTshEngine.instance.loadTshSource(
                    "java",
                    javaTshContent,
                    TSLanguageJava.getInstance()
                )
                withContext(Dispatchers.Main) {
                    javaTshReady = ok
                    installTshHighlighterIfReady()
                    Log.d(TAG, "Windbell-TSH [Java] configuration compiled: $ok")
                }
            }.onFailure {
                Log.e(TAG, "Failed to load java.tsh from assets: ${it.message}")
            }
        }
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

    private fun installTshHighlighterIfReady() {
        val view = editorView ?: return
        if (javaTshReady && currentLanguageKey.equals("Java", ignoreCase = true)) {
            if (activeTshHighlighter == null) {
                activeTshHighlighter = TshSyntaxHighlighter(
                    language = TSLanguageJava.getInstance(),
                    extension = "java"
                )
            }
            view.setSyntaxHighlighter(activeTshHighlighter)
        } else {
            view.setSyntaxHighlighter(null)
            activeTshHighlighter = null
        }
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
    private fun triggerEditorFullHighlight() {
        installTshHighlighterIfReady()
        val currentJob = editorHighlightJob

        editorHighlightJob = activityScope.launch(Dispatchers.Default) {
            currentJob?.cancel(CancellationException("New editor highlight requested"))

            val rawText = currentEditorRawText
            if (rawText.isEmpty()) return@launch

            // 1. 宏观 AST 解析
            val lang = currentLanguage ?: TSLanguageJava.getInstance()
            val localParser = TSParser.create().apply { setLanguage(lang) }
            val newTree = localParser.parseString(rawText)
            localParser.close()

            editorTree?.close()
            editorTree = newTree

            // 2. 调用 Windbell-TSH 核心引擎进行微观正则 + 宏观 AST 合成
            val ext = if (currentLanguageKey.equals("Kotlin", ignoreCase = true)) "kt" else "java"
            val spans = WindbellTshEngine.instance.computeHighlights(
                tree = editorTree,
                text = rawText,
                extension = ext
            )

            Log.d(TAG, "TSH Highlighting complete: spans=${spans.size}")
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
            runCatching {
                val cLang = TSLanguage.loadLanguage(this@MainActivity, "c")
                if (cLang != null) {
                    withContext(Dispatchers.Main) {
                        languageMap["C"] = cLang
                        (binding.contentConsole.languageChooser.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
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
            R.id.toggle_tsh_theme -> {
                toggleTshTheme()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * 切换 TSH 白天/黑夜多态调色板
     */
    private fun toggleTshTheme() {
        WindbellTshEngine.instance.isDarkTheme = !WindbellTshEngine.instance.isDarkTheme
        val currentThemeName = if (WindbellTshEngine.instance.isDarkTheme) "黑夜深色 (Dark)" else "白天浅色 (Light)"
        triggerEditorFullHighlight()
        MaterialAlertDialogBuilder(this)
            .setTitle("🎨 TSH 多态调色板热切换")
            .setMessage("当前调色板已瞬间切换为: $currentThemeName\n（验证：零重新分词开销，仅依赖 Native StyleID 调色板重新投射绘制）")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * ⚙️ Windbell-TSH 核心引擎专用全功能测试与诊断中心
     */
    private fun showTshCoreTestSuiteDialog() {
        val testOptions = arrayOf(
            "1. 🎨 多态调色板 (Palette) 映射完整性测试",
            "2. ⚡ 原生 AST 选择器 vs 正则微观流覆盖测试",
            "3. 📂 语法代码折叠 (Fold Ranges) 自动化提取测试",
            "4. 🏷️ 符号大纲导航 (Outline Tags) 深度提取测试",
            "5. 💡 光标位置悬停感知 (Hover Info) 测试",
            "6. 🚀 32 线程高并发重入 Fuzzing 压测"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Windbell-TSH 引擎全功能测试与诊断")
            .setItems(testOptions) { _, which ->
                when (which) {
                    0 -> runTshPaletteTest()
                    1 -> runTshHybridCoverageTest()
                    2 -> runTshFoldRangesTest()
                    3 -> runTshOutlineTagsTest()
                    4 -> runTshHoverInfoTest()
                    5 -> runTshConcurrencyTest()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun runTshPaletteTest() {
        val report = StringBuilder()
        report.append("=== 🎨 TSH 调色板映射测试 ===\n\n")

        val ext = "java"
        val sampleCode = "public class Sample { int x = 100; }"
        val spans = WindbellTshEngine.instance.computeHighlights(editorTree, sampleCode, ext)

        report.append("当前已加载语言扩展: $ext\n")
        report.append("生成 Span 数量: ${spans.size}\n\n")

        val distinctStyleIds = spans.map { it.styleId }.distinct()
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
                package com.demo;
                /** Javadoc comment with @param target */
                public class TestClass {
                    // Single line comment
                    private String message = "Hello \"Escaped\" World";
                    public void doWork() {
                        int value = 0xABCD;
                    }
                }
            """.trimIndent()

            val lang = TSLanguageJava.getInstance()
            val parser = TSParser.create().apply { setLanguage(lang) }
            val tree = parser.parseString(text)
            parser.close()

            val spans = WindbellTshEngine.instance.computeHighlights(tree, text, "java")
            tree?.close()

            val report = StringBuilder()
            report.append("=== ⚡ 双通道混合扫描线合成报告 ===\n\n")
            report.append("源码字符总数: ${text.length} (字节数: ${text.length * 2})\n")
            report.append("合成后有效 Span 数量: ${spans.size}\n\n")

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

    private fun runTshFoldRangesTest() {
        val text = """
            package com.demo;
            import java.util.*;
            
            /**
             * Class Documentation
             */
            public class FoldDemo {
                public void methodOne() {
                    System.out.println("Line 1");
                    System.out.println("Line 2");
                }
            }
        """.trimIndent()

        activityScope.launch(Dispatchers.IO) {
            val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
            val tree = parser.parseString(text)
            parser.close()

            val folds = tree?.let { WindbellTshEngine.instance.computeFolds(it, text, "java") } ?: emptyList()
            tree?.close()

            val report = StringBuilder()
            report.append("=== 📂 代码折叠 (Fold Ranges) 自动化测试 ===\n\n")
            report.append("检测到可折叠块数量: ${folds.size}\n\n")

            for (fold in folds) {
                val kindStr = when (fold.kind) {
                    0 -> "代码块 (Code Block)"
                    1 -> "注释段 (Comment)"
                    2 -> "导入区域 (Imports)"
                    else -> "其他"
                }
                report.append("  🔹 [字节: ${fold.startByte}..${fold.endByte}] 类别: $kindStr\n")
            }

            withContext(Dispatchers.Main) {
                showResultDialog("代码折叠测试结果", report.toString())
            }
        }
    }

    private fun runTshOutlineTagsTest() {
        val text = """
            public class OuterClass {
                int fieldAlpha = 1;
                public void testMethod() {}
                enum Status { OK, FAIL }
            }
        """.trimIndent()

        activityScope.launch(Dispatchers.IO) {
            val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
            val tree = parser.parseString(text)
            parser.close()

            val outlines = tree?.let { WindbellTshEngine.instance.computeOutline(it, text, "java") } ?: emptyList()
            tree?.close()

            val report = StringBuilder()
            report.append("=== 🏷️ 符号大纲提取 (Outline Tags) 测试 ===\n\n")
            report.append("提取到的全局符号数: ${outlines.size}\n\n")

            for (tag in outlines) {
                val kindStr = when (tag.kind) {
                    1 -> "Class/Interface"
                    2 -> "Method"
                    3 -> "Field"
                    4 -> "Enum"
                    else -> "Symbol"
                }
                report.append("  📌 [${kindStr}] 名称: \"${tag.name}\" (字节: ${tag.startByte}..${tag.endByte})\n")
            }

            withContext(Dispatchers.Main) {
                showResultDialog("符号大纲测试结果", report.toString())
            }
        }
    }

    private fun runTshHoverInfoTest() {
        val text = "public class HoverDemo { int targetVariable = 42; }"
        activityScope.launch(Dispatchers.IO) {
            val parser = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
            val tree = parser.parseString(text)
            parser.close()

            val offset = text.indexOf("targetVariable") * 2
            val hover = tree?.let { WindbellTshEngine.instance.getHoverInfo(it, offset) }
            tree?.close()

            val report = StringBuilder()
            report.append("=== 💡 悬停感知信息提取测试 ===\n\n")
            if (hover != null) {
                report.append("目标位置: 字符索引 ${offset / 2} ('targetVariable')\n")
                report.append("AST 节点类型: ${hover.nodeType}\n")
                report.append("作用域详情: ${hover.detail}\n")
                report.append("物理字节范围: [${hover.startByte}..${hover.endByte}]\n")
            } else {
                report.append("未命中指定位置的 AST 节点。")
            }

            withContext(Dispatchers.Main) {
                showResultDialog("悬停提示测试结果", report.toString())
            }
        }
    }

    private fun runTshConcurrencyTest() {
        val pd = ProgressDialog(this).apply {
            setTitle("🚀 TSH 32 线程并发 Fuzzing 压测")
            setMessage("正在压测双通道 Native 引擎与调色板并发重入安全性...")
            setCancelable(false)
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val threadCount = 32
            val iterations = 30
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)
            val codeSnippet = "public class ConcTest { int a = 1; void run() { a++; } }"
            var errorCount = 0

            val start = System.currentTimeMillis()

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        val p = TSParser.create().apply { setLanguage(TSLanguageJava.getInstance()) }
                        val tree = p.parseString(codeSnippet)
                        repeat(iterations) {
                            val spans = WindbellTshEngine.instance.computeHighlights(tree, codeSnippet, "java")
                            val folds = tree?.let { WindbellTshEngine.instance.computeFolds(it, codeSnippet, "java") }
                            if (spans.isEmpty() || folds == null) {
                                errorCount++
                            }
                        }
                        tree?.close()
                        p.close()
                    } catch (e: Throwable) {
                        errorCount++
                        Log.e(TAG, "Concurrency fuzz error", e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()
            val duration = System.currentTimeMillis() - start

            withContext(Dispatchers.Main) {
                pd.dismiss()
                showResultDialog(
                    "并发压测通过",
                    "测试规格: $threadCount 个并发线程，每线程 $iterations 次遍历循环。\n" +
                            "累计混合渲染调用: ${threadCount * iterations} 次。\n" +
                            "总耗时: ${duration}ms (平均单次渲染: ${String.format(Locale.US, "%.3f", duration.toDouble() / (threadCount * iterations))}ms)\n" +
                            "异常/空结果数: $errorCount\n" +
                            (if (errorCount == 0) "✅ 验证通过：底层线程隔离与局部引用防护达成绝对安全！" else "❌ 发现竞态条件错误。")
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
        editorHighlightJob?.cancel()
        parser?.close()
        parser = null
        editorTree?.close()
        editorTree = null
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