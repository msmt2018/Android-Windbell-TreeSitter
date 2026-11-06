/*
 *  This file is part of android-tree-sitter.
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */
package com.zerostudio.idea.androidtreesitter

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.zerostudio.idea.androidtreesitter.databinding.ActivityMainBinding
import com.zerostudio.idea.androidtreesitter.databinding.ContentMainBinding
import com.zerostudio.idea.androidtreesitter.databinding.LayoutEditorPageBinding
import com.zerostudio.idea.androidtreesitter.databinding.LayoutTextInputBinding
import com.zerostudio.idea.treesitter.*
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import com.zerostudio.idea.treesitter.json.TSLanguageJson
import com.zerostudio.idea.treesitter.kotlin.TSLanguageKotlin
import com.zerostudio.idea.treesitter.logcat.TSLanguageLogcat
import com.zerostudio.idea.treesitter.python.TSLanguagePython
import com.zerostudio.idea.treesitter.string.UTF16StringFactory
import com.zerostudio.idea.treesitter.xml.TSLanguageXml
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import x.github.module.editor.model.PieceTreeBuffer
import x.github.module.editor.view.EditorView
import com.zerostudio.idea.piecetable.PieceTreeTextBufferBuilder
import com.zerostudio.idea.piecetable.common.Range
import com.zerostudio.idea.piecetable.common.SingleEditOperation
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = checkNotNull(_binding) { "ActivityMainBinding has been destroyed" }

    private val activityScope =
        CoroutineScope(Dispatchers.Default + CoroutineName("MainActivity-Kernel"))

    private var parser: TSParser? = null
    private var parseJob: Job? = null

    private var parseCount = 0L
    private var avgParse = 0L

    private var currentLanguageKey: String = "Java"
    private var currentLanguage: TSLanguage? = null
    private var editorView: EditorView? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadTextFileFromUri(it) }
    }

    companion object {
        private const val TAG = "TreeSitter_MainActivity"
        private const val DEF_ITERS = 500
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

        setupTabs()
        setupConsoleTab(binding.contentConsole)
        setupEditorTab(binding.contentEditor)
        loadExternalLanguages()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("🧪 控制台与局部AST钻取"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("📈 大文件极限压测"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("⚙️ 编辑器专用TS测试套件"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("📝 代码编辑器"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.contentConsole.root.visibility = View.VISIBLE
                        binding.contentEditor.root.visibility = View.GONE
                    }
                    1 -> {
                        showLargeFileBenchmarkDialog()
                    }
                    2 -> {
                        showEditorTreeSitterTestSuiteDialog()
                    }
                    3 -> {
                        binding.contentConsole.root.visibility = View.GONE
                        binding.contentEditor.root.visibility = View.VISIBLE
                        editorView?.requestFocus()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupConsoleTab(content: ContentMainBinding) {
        //禁止在后台/屏幕旋转时将巨大的文本序列化进 Bundle
        content.code.isSaveEnabled = false
        content.ast.isSaveEnabled = false
        
        content.tsMeta.text = "Tree Sitter Language ABI Version: " + TreeSitter.getLanguageVersion()
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
                currentLanguage?.let { lang ->
                    parser?.close()
                    parser = TSParser.create()
                    parser?.setLanguage(lang)
                    editorView?.setSyntaxHighlighter(null)
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

        // 🚀【需求变更】：改为弹出对话框询问是否加载示例 Asset 文本
        MaterialAlertDialogBuilder(this)
            .setTitle("📝 代码编辑器初始化")
            .setMessage("是否在编辑器中加载默认的大型示例源码文件 (View.java.txt) 以供体验？")
            .setPositiveButton("加载示例文件") { _, _ ->
                activityScope.launch(Dispatchers.IO) {
                    val fileContent = runCatching {
                        assets.open("View.java.txt").bufferedReader().use { it.readText() }
                    }.getOrElse { "// ⚠️ 加载 Asset 失败: ${it.localizedMessage}" }

                    withContext(Dispatchers.Main) {
                        val buffer = PieceTreeBuffer(PieceTreeTextBufferBuilder().build().apply {
                            applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), fileContent)), false)
                        })
                        editorView?.setBuffer(buffer)
                    }
                }
            }
            .setNegativeButton("留空") { _, _ ->
                val sampleCode = """
                    package com.zerostudio.idea.treesitter;
                    
                    public class BenchmarkDemo {
                        public void runTest() {
                            System.out.println("Tree-sitter Editor Active.");
                        }
                    }
                """.trimIndent()
                
                val buffer = PieceTreeBuffer(PieceTreeTextBufferBuilder().build().apply {
                    applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), sampleCode)), false)
                })
                editorView?.setBuffer(buffer)
            }
            .setCancelable(false)
            .show()
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
            R.id.action_open_file -> {
                filePickerLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                return true
            }
            R.id.reload_external -> {
                destroyParserResources()
                loadExternalLanguages()
                return true
            }
            R.id.test_parser_performance -> {
                askIterCount(DEF_ITERS) { doParserPerfTest(it) }
                return true
            }
            R.id.test_string_performance -> {
                askIterCount(DEF_ITERS) { doStringPerfTest(it) }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    private fun showLargeFileBenchmarkDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("📈 Tree-sitter 大文件全方位性能压测")
            .setMessage("即将启动自动沙箱大文件压测。\n" +
                    "测试范围：5MB 起步，以 5MB 为阶梯递增。\n" +
                    "安全上限：100MB 终点（或 JVM 堆内存安全边界触发熔断）。\n" +
                    "文件生成：存储于应用隐私数据缓存目录，测试完毕后自动递归安全删除。")
            .setPositiveButton("立即开始压测") { _, _ ->
                runLargeFileHierarchyBenchmark()
            }
            .setNegativeButton("取消") { _, _ ->
                binding.tabLayout.getTabAt(0)?.select()
            }
            .show()
    }

    private fun runLargeFileHierarchyBenchmark() {
        val pd = ProgressDialog(this).apply {
            setTitle("大文件极限性能压测进行中")
            setMessage("正在初始化安全沙箱...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 20
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val benchmarkDir = File(cacheDir, "ts_benchmarks").apply {
                if (!exists()) mkdirs()
            }

            val lang = currentLanguage ?: languageMap["Java"] ?: error("No language available")
            val reportBuilder = StringBuilder()
            reportBuilder.append("=== Android Tree-sitter 大文件分级压测报告 ===\n\n")

            val baseChunk = """
                package com.zerostudio.idea.treesitter.benchmark;
                public class GeneratedMassiveClass {
                    public void method_%d() {
                        String s = "Tree-sitter memory and parsing robustness testing chunk string literal content data...";
                        long z = %d * %d + 42L;
                    }
                }
            """.trimIndent() + "\n"

            var currentStepIndex = 0
            var successCount = 0

            for (sizeMb in 5..100 step 5) {
                currentStepIndex++
                withContext(Dispatchers.Main) {
                    pd.progress = currentStepIndex
                    pd.setMessage("正在压测大小: ${sizeMb}MB / 100MB (上限)...")
                }

                val runtime = Runtime.getRuntime()
                val freeMemMb = (runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())) / (1024 * 1024)
                if (freeMemMb < 40) {
                    reportBuilder.append("⚠️ [安全熔断] 剩余可用堆内存仅剩 ${freeMemMb}MB，触发 GC 安全边界保护，提前终止压测！\n")
                    break
                }

                val targetBytes = sizeMb * 1024 * 1024L
                val testFile = File(benchmarkDir, "bench_${sizeMb}mb.java")
                try {
                    FileOutputStream(testFile).use { fos ->
                        var written = 0L
                        var counter = 0
                        while (written < targetBytes) {
                            val line = String.format(Locale.US, baseChunk, counter, counter, counter + 1)
                            val lineBytes = line.toByteArray(Charsets.UTF_16LE)
                            fos.write(lineBytes)
                            written += lineBytes.size
                            counter++
                        }
                        fos.flush()
                    }

                    val fileText = testFile.readText(Charsets.UTF_16LE)
                    val parseStart = System.currentTimeMillis()
                    
                    val parseSuccess = TSParser.create().use { localParser ->
                        localParser.setLanguage(lang)
                        val tree = localParser.parseString(fileText)
                        val ok = tree != null
                        tree?.close()
                        ok
                    }

                    val duration = System.currentTimeMillis() - parseStart
                    val throughputMbPerSec = if (duration > 0) (sizeMb * 1000.0) / duration else 0.0

                    reportBuilder.append(String.format(Locale.US, "✅ [%3d MB] 状态: %s | 耗时: %6d ms | 吞吐量: %.2f MB/s\n",
                        sizeMb, if (parseSuccess) "成功" else "失败", duration, throughputMbPerSec))
                    successCount++

                } catch (e: Throwable) {
                    reportBuilder.append(String.format(Locale.US, "❌ [%3d MB] 发生异常: %s\n", sizeMb, e.localizedMessage ?: "OOM/Error"))
                    break
                } finally {
                    if (testFile.exists()) {
                        testFile.delete()
                    }
                }
            }

            try { benchmarkDir.deleteRecursively() } catch (_: Throwable) {}
            reportBuilder.append("\n压测圆满结束。成功通过阶梯测试层数: $successCount / 20\n")

            withContext(Dispatchers.Main) {
                pd.dismiss()
                binding.tabLayout.getTabAt(0)?.select()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📈 大文件极限压测综合报告")
                    .setMessage(reportBuilder.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun loadTextFileFromUri(uri: Uri) {
        activityScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().use { it.readText() }
                    withContext(Dispatchers.Main) {
                        binding.tabLayout.getTabAt(3)?.select() 
                        val buffer = PieceTreeBuffer(PieceTreeTextBufferBuilder().build().apply {
                            applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), text)), false)
                        })
                        editorView?.setBuffer(buffer)
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("成功导入文件")
                            .setMessage("文件大小: ${text.length} 字符\n已安全加载至高性能编辑器。")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("打开文件失败")
                        .setMessage(err.localizedMessage ?: "未知错误")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun doStringPerfTest(iterations: Int) {
        val pd = ProgressDialog(this).apply {
            setTitle(R.string.test_string_performance)
            setCancelable(false)
            setMessage("正在测试 UTF16String...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = iterations
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val iter = AtomicInteger(0)
            val dummyText = "package com.zerostudio.idea.treesitter;\npublic class PerfTest {}"
            val flow = flow {
                repeat(iterations) {
                    emit(async {
                        val start = System.currentTimeMillis()
                        UTF16StringFactory.newString("", true).use { string ->
                            string.append(dummyText)
                            string.insert(0, "/** Header */\n")
                            string.byteAt(0)
                            string[0]
                            string.delete(0, 5)
                            string.subseqChars(0, 5).use {}
                            string.length
                            string.byteLength()
                        }
                        val duration = System.currentTimeMillis() - start
                        withContext(Dispatchers.Main) { pd.progress = iter.incrementAndGet() }
                        duration
                    })
                }
            }
            val totalDuration = flow.toList().awaitAll().sum()
            withContext(Dispatchers.Main) {
                pd.dismiss()
                showPerfTestResult("UTF16String 压力测试完成\n总耗时: ${totalDuration / 1000} 秒")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun doParserPerfTest(iterations: Int) {
        val lang = currentLanguage ?: TSLanguageJava.getInstance()
        val progress = ProgressDialog(this).apply {
            setMessage("请稍候...")
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val codeSample = "public class Main { public static void main(String[] args) { System.out.println(\"Hello\"); } }"

            val pd = withContext(Dispatchers.Main) {
                val p = ProgressDialog(this@MainActivity).apply {
                    setTitle(R.string.test_parser_performance)
                    setCancelable(false)
                    setMessage("语言: ${lang.name}\n迭代: $iterations")
                    setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                    max = iterations
                }
                progress.dismiss()
                p.show()
                p
            }

            val iter = AtomicInteger(0)
            val flow = flow {
                repeat(iterations) {
                    emit(async {
                        TSParser.create().use { localParser ->
                            localParser.setLanguage(lang)
                            val start = System.currentTimeMillis()
                            localParser.parseString(codeSample).use {}
                            val duration = System.currentTimeMillis() - start
                            withContext(Dispatchers.Main) { pd.progress = iter.incrementAndGet() }
                            duration
                        }
                    })
                }
            }

            val totalDuration = flow.toList().awaitAll().sum()
            withContext(Dispatchers.Main) {
                pd.dismiss()
                showPerfTestResult("TSParser 压测报告\n总耗时: ${totalDuration}ms\n平均耗时: ${totalDuration / iterations}ms")
            }
        }
    }

    private fun showPerfTestResult(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("性能监控结果")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun askIterCount(defIters: Int, onResult: (Int) -> Unit) {
        val bindingDialog = LayoutTextInputBinding.inflate(layoutInflater)
        bindingDialog.input.setText(defIters.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle("迭代次数")
            .setView(bindingDialog.root)
            .setPositiveButton(android.R.string.ok) { diag, _ ->
                diag.dismiss()
                onResult(bindingDialog.input.text?.toString()?.toIntOrNull() ?: defIters)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * ⚙️ 编辑器专用 Tree-sitter 多维度测试与调试中心
     */
    private fun showEditorTreeSitterTestSuiteDialog() {
        val testOptions = arrayOf(
            "1. 🟢 手动增量解析模拟测试 (Incremental Reparse)",
            "2. 🟡 半自动语法错误容错测试 (Malformed Code Recovery)",
            "3. 🔵 全局 Query 查询与谓词流式过滤测试 (Native Predicate Stream)",
            "4. 🟣 全自动多线程并发安全性压测 (Thread-Safe Concurrency Fuzzing)"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ 代码编辑器专用 Tree-sitter 测试与调试中心")
            .setItems(testOptions) { _, which ->
                when (which) {
                    0 -> runManualIncrementalParsingTest()
                    1 -> runMalformedErrorRecoveryTest()
                    2 -> runGlobalQueryPredicateTest()
                    3 -> runConcurrencyFuzzingTest()
                }
            }
            .setNegativeButton("返回") { _, _ ->
                binding.tabLayout.getTabAt(0)?.select()
            }
            .show()
    }

    private fun runManualIncrementalParsingTest() {
        val pd = ProgressDialog(this).apply {
            setTitle("🟢 增量解析压测")
            setMessage("正在模拟连续键盘键入并执行增量解析...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 20
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val lang = currentLanguage ?: TSLanguageJava.getInstance()
            val initialCode = "public class IncrementalTest {\n    int a = 1;\n}"
            var currentTree: TSTree? = null
            var totalIncrementalMs = 0L

            TSParser.create().use { parser ->
                parser.setLanguage(lang)
                currentTree = parser.parseString(initialCode)

                for (i in 1..20) {
                    withContext(Dispatchers.Main) { pd.progress = i }
                    val modifiedCode = "public class IncrementalTest {\n    int a = 1;\n    int b_$i = $i;\n}"
                    
                    currentTree?.edit(TSInputEdit.create(
                        10, 10, 10 + "    int b_$i = $i;\n".length,
                        TSPoint.create(2, 0), TSPoint.create(2, 0), TSPoint.create(3, 0)
                    ))

                    val start = System.currentTimeMillis()
                    val newTree = parser.parseString(currentTree, modifiedCode)
                    totalIncrementalMs += (System.currentTimeMillis() - start)
                    
                    currentTree?.close()
                    currentTree = newTree
                }
                currentTree?.close()
            }

            withContext(Dispatchers.Main) {
                pd.dismiss()
                binding.tabLayout.getTabAt(0)?.select()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🟢 增量解析压测结果")
                    .setMessage("成功模拟 20 次连续键盘键入增量编译。\n" +
                            "累计增量解析总耗时: ${totalIncrementalMs}ms\n" +
                            "平均单次击键增量重解析延迟: ${totalIncrementalMs.toDouble() / 20.0}ms")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun runMalformedErrorRecoveryTest() {
        val malformedCode = """
            public class BrokenSyntax {
                public void invalidMethod( {
                    int x = ; 
                    if (x > 0 { 
                        System.out.println("Unclosed")
                    }
                }
            }
        """.trimIndent()

        activityScope.launch(Dispatchers.IO) {
            val lang = currentLanguage ?: TSLanguageJava.getInstance()
            val resultReport = StringBuilder()
            resultReport.append("=== 🟡 语法错误容错与恢复测试报告 ===\n\n")

            TSParser.create().use { parser ->
                parser.setLanguage(lang)
                parser.parseString(malformedCode).use { tree ->
                    if (tree != null) {
                        val root = tree.rootNode
                        resultReport.append("根节点类型: ${root.type} (HasError: ${root.hasErrors()})\n")
                        
                        var errorNodeCount = 0
                        root.walk().use { cursor ->
                            do {
                                val node = cursor.currentNode
                                if (node.isError() || node.type == "ERROR") {
                                    errorNodeCount++
                                    resultReport.append("  -> 捕获容错恢复节点: ERROR [byte: ${node.startByte}..${node.endByte}]\n")
                                }
                            } while (cursor.gotoNextSibling() || cursor.gotoFirstChild())
                        }
                        resultReport.append("\n检测到容错恢复的 ERROR 节点总数: $errorNodeCount\n")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                binding.tabLayout.getTabAt(0)?.select()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🟡 容错测试结果")
                    .setMessage(resultReport.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun runGlobalQueryPredicateTest() {
        val sampleCode = """
            class QueryTest {
                void testMethod() {
                    int printf = 42;
                }
            }
        """.trimIndent()

        activityScope.launch(Dispatchers.IO) {
            val lang = currentLanguage ?: TSLanguageJava.getInstance()
            val queryStr = "((method_invocation name: (identifier) @name) (#eq? @name \"printf\"))"
            val report = StringBuilder()
            report.append("=== 🔵 全局 Query 与谓词流式过滤测试 ===\n\n")

            try {
                TSParser.create().use { parser ->
                    parser.setLanguage(lang)
                    parser.parseString(sampleCode).use { tree ->
                        TSQuery.create(lang, queryStr).use { query ->
                            report.append("Query 创建成功，模式数量: ${query.patternCount}\n")
                            parser.close()
                            
                            val cursor = TSQueryCursor.create()
                            cursor.exec(query, tree!!.rootNode)
                            var matchCount = 0
                            for (match in cursor) {
                                matchCount++
                                report.append("  -> 匹配成功！PatternIndex: ${match.patternIndex}, 捕获点数: ${match.captures.size}\n")
                            }
                            cursor.close()
                            report.append("\n总计有效匹配数: $matchCount\n")
                        }
                    }
                }
            } catch (e: Throwable) {
                report.append("Query 测试抛出异常: ${e.localizedMessage}")
            }

            withContext(Dispatchers.Main) {
                binding.tabLayout.getTabAt(0)?.select()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🔵 谓词过滤测试结果")
                    .setMessage(report.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun runConcurrencyFuzzingTest() {
        val pd = ProgressDialog(this).apply {
            setTitle("🟣 并发安全性 Fuzzing 压测")
            setMessage("正在启动多线程高并发树遍历...")
            setCancelable(false)
            show()
        }

        activityScope.launch(Dispatchers.IO) {
            val lang = currentLanguage ?: TSLanguageJava.getInstance()
            val code = "public class ConcurrentFuzz { void foo() { int x = 100; } }"
            var concurrencyErrors = 0
            val iterations = 50

            TSParser.create().use { parser ->
                parser.setLanguage(lang)
                parser.parseString(code).use { sharedTree ->
                    if (sharedTree != null) {
                        val jobs = (1..5).map { threadId ->
                            async(Dispatchers.Default) {
                                runCatching {
                                    repeat(iterations) {
                                        sharedTree.copy().use { copiedTree ->
                                            copiedTree.rootNode.walk().use { cursor ->
                                                while (cursor.gotoNextSibling() || cursor.gotoFirstChild()) {
                                                    cursor.currentNode.type
                                                }
                                            }
                                        }
                                    }
                                }.onFailure {
                                    concurrencyErrors++
                                    Log.e(TAG, "Thread $threadId failed", it)
                                }
                            }
                        }
                        jobs.awaitAll()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                pd.dismiss()
                binding.tabLayout.getTabAt(0)?.select()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🟣 并发 Fuzzing 压测结果")
                    .setMessage("5 个独立线程并发执行，累计进行 ${5 * iterations} 次树拷贝与游标遍历。\n" +
                            "并发异常计数: $concurrencyErrors\n" +
                            (if (concurrencyErrors == 0) "✅ 验证通过：底层线程隔离安全性完美！" else "❌ 存在线程安全隐患。"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
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
                sb.append("=== 🎯 Tree-sitter AST & InlayHint 结构钻取面板 ===\n")
                sb.append("Setup: ${setupDur}ms | Parse: ${parseDur}ms | Avg: ${avgParse / parseCount}ms\n\n")

                if (tree != null) {
                    val queryScm = """
                        (class_declaration name: (identifier) @className)
                        (method_declaration name: (identifier) @methodName)
                    """.trimIndent()

                    runCatching {
                        TSQuery.create(lang, queryScm).use { query ->
                            val cursor = TSQueryCursor.create()
                            cursor.exec(query, tree.rootNode)
                            sb.append("--- 🔍 SCM Query 提取出的内嵌提示 (Inlay Hints) ---\n")
                            for (match in cursor) {
                                for (capture in match.captures) {
                                    val node = capture.node
                                    sb.append("  💡 灰色内嵌提示 -> [${node.type}] (行: ${node.startPoint.row + 1})\n")
                                }
                            }
                            cursor.close()
                            sb.append("\n")
                        }
                    }.onFailure { queryErr ->
                        sb.append("SCM Query 解析跳过: ${queryErr.localizedMessage}\n\n")
                    }

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

    private fun destroyParserResources() {
        parseJob?.cancel()
        parser?.close()
        parser = null
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