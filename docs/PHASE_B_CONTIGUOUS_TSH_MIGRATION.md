# Phase B：连续文本 JNI 与 TSH 引擎首批迁移记录

> 分支：`migration/tsh-0.28-from-backup-no-largefile`  
> Phase B 基线提交：`d4f67762ec57145596c74493dfd2dfd89172c698`  
> Tree-sitter：`0.28.0`，子模块 SHA `62816e1c0d0a4ca9553174f3cbe5b9631047f7f4`

## 1. 本阶段已经提交的代码

当前迁移提交：

- `d4f67762ec57145596c74493dfd2dfd89172c698` — 迁移连续文本 TSH Native、JNI、Kotlin Engine、Activity 和高亮器。
- `45447141f9276d2ffd845de59929fd8bba420be6` — Parser close 与 parse 串行化、Native close 幂等。
- `701204a5b180cc1897e14303dc9f243ccdc92648` — Parser range JNI 和 UTF-16LE 快照复制修复。
- `6f01a67bd15e3b8e68e0e36880accd181cc571b4` — 空 included ranges、UTF-16 surrogate pair 与 close 幂等测试。

### 1.1 Native TSH

迁入并重构：

- `WindbellTshCompiler.cpp/.h`：取自当前仓库 TSH 已完成 Regex State 统一后的编译器快照（`d05c33b45ea65850f103deb41f4ab20ee0dfd747`）。
- `WindbellTshCore.cpp/.h`：同一快照作为功能基线；已删除 TextSource include/参数、TextSource Capture 读取、分区版本、Regex State Checkpoint 和 checkpoint 收敛状态。
- `ts_windbell_jni.cpp`：使用不含 Native Document Session 的连续文本 JNI 入口（`97ca5d3cd861cc0729331a38859b19a98ca17a9a`）。
- `CMakeLists.txt`：采用不编译 largefile 源文件的 TSH/Oniguruma 构建清单。

TSH Core 当前唯一文本输入契约是：

```cpp
highlightHybrid(
    language,
    completeTree,
    completeUtf16Text,
    completeUtf16Length,
    extension,
    ...
)
```

Tree-sitter Query 仍对完整 TSTree 执行；范围参数只限制输出/查询结果范围，不把 viewport 文本变成独立语法树。Regex 与 Regex State 都读取同一份完整连续 UTF-16LE 输入。Regex State 从文档起点扫描，保留锚点、多行和状态迁移语义，不再从 checkpoint 恢复。

### 1.2 Android JNI / Kotlin / Activity

迁入：

- TSH Kotlin Engine 连续输入 API。
- Activity 与 `.tsh` Java 示例配置。
- `TshSyntaxHighlighter` 完整文本快照高亮路径。

高亮调用路径是：

1. Activity 获取当前完整文本快照。
2. Java/Kotlin Tree-sitter Parser 对完整文本解析。
3. TSH Engine 将同一完整文本和完整 TSTree 交给 Native `nativeHighlight`。
4. Native 返回完整 Capture/Span 数组。
5. Kotlin 高亮器把结果转换成 Android ForegroundColorSpan、StyleSpan、UnderlineSpan 等。
6. 编辑器执行实际文字绘制。

编辑器内部仍有其普通文档模型；但本分支没有把 PieceTree、EditorTextSource 或窗口/分区 Provider 传入 TSH Native。TSH 的入口是 `buffer.getText()` 得到的完整 String 快照。编辑器通用的 `EditorTextSource.kt` 不是 TSH Native 数据源，也不参与该调用链。

### 1.3 绑定基线

- 保留备份分支 `android-tree-sitter/` Java API 与 JNI Parser。
- 没有把未被 Gradle include 的平行 `treesitter/` Kotlin 模块并入工程。
- `main.cc` 的版本标记已更新为 `TS_VERSION_NUM=0.28.0`、`TS_GIT_HASH=62816e1c0d0a`。
- Tree-sitter 子模块仍固定在完整 0.28.0 上游提交。

## 2. 本阶段 Native JNI 修复

### 2.1 Included Ranges

- 用 `std::vector<TSRange>` 替代由 Java 数组长度决定的 VLA 栈数组。
- 捕获 `std::bad_alloc` 与 `std::length_error`，转换为 Java 异常。
- 逐项释放 JNI LocalRef。
- 对 pending JNI exception 立即停止后续 JNI 调用。
- `getIncludedRanges` 使用真实 `uint32_t` 计数变量，不再把 `jint*` 强转成 `uint32_t*`。
- 检查 Java 数组长度上限和 Native Parser 句柄。
- 保留 UTF-16LE 两字节边界验证。

### 2.2 Parse 输入

- 检查输入字节长度为非负偶数，避免把错误长度缩窄成 Tree-sitter `uint32_t`。
- 保留完整输入快照复制，不借用可变 UTF16String 内部 vector 指针。
- 使用 `std::unique_ptr` 管理输入副本。
- 使用 ParseRoundGuard 保证 beginRound/endRound 成对。
- 捕获长度错误和内存分配失败，转换为 Java 异常。

### 2.3 UTF16String 复制性能

`UTF16String::to_cstring()` 从逐字节 `vector::at()` 改为单次 `std::memcpy`。仍然复制为独立 Native 快照，因此没有引入可变源缓冲区的并发借用风险。

### 2.4 Parser 生命周期

- `TSNativeObject.close()` 改为 synchronized，避免两个线程同时释放同一个 Native Handle。
- `TSParser.close()` 设置 closeRequested，先请求正在进行的解析取消，再获取与 parse 共用的锁释放 Native Parser。
- parse 在获得锁后再次验证关闭状态和 Native Handle，防止“先通过 access check、随后被 close、最后继续进入 JNI”的竞态。
- 增加 close 幂等测试。

## 3. 当前禁止技术扫描结果

迁移分支当前：

- `android-tree-sitter/src/main/cpp/largefile/`：不存在。
- `CMakeLists.txt`：不包含 largefile 源文件或 largefile 测试目标。
- TSH Core / Header / Compiler / JNI / Kotlin Engine / Activity Highlighter：未检出 `TextSource`、`TextChunk`、`PieceTreeSource`、`FileWindowSource`、`TextPartitionIndex`、`parseWindowed`、`parseBuffers`、`RegexStateCheckpoint` 或 partition-version 运行时引用。
- 全树唯一匹配 `EditorTextSource.kt` 是编辑器通用的只读行访问接口，不进入 TSH/JNI 调用链；不能与 Native 大文本 TextSource 混为一谈。

## 4. 尚未完成 / 不可宣称完成的项目

本阶段只是把无 largefile 的 TSH 连续输入路径建立起来，尚不代表 TSH 最新功能和全部渲染缺陷已完全迁移。

- [ ] 把当前 main 中后续的 TSH 正确性修复逐项审查并移植：Predicate tri-state / missing-capture semantics、QueryCursor 0.28 options 生命周期、Match/Capture 不完整检测、无静默截断、Span interval resolver、诊断计数和 Render ABI 改进。
- [ ] 完成当前最新 TSH API / Schema / Render ABI 与本阶段 Kotlin Engine 的逐项兼容对照。
- [ ] 完成当前最新 Android Spannable Renderer 的纯连续文本迁移与实际接入。
- [ ] 完成 Activity、Editor Host、Theme、配置加载和异常展示的完整迁移审计。
- [ ] 增加 Native TSH 单元测试、JNI 契约测试和 TSH 规则回归。
- [ ] 完成 Tree-sitter 0.28.0 QueryCursor 新 API 的绑定与功能验证。
- [ ] 完成全量 Native / JVM / Android 构建。
- [ ] 在设备/模拟器验证 `.tsh` 颜色真实绘制，覆盖首次打开、编辑、撤销/重做、切换语言和 Activity 销毁。
- [ ] 记录完整文本模型下的内存峰值、解析吞吐、Query/Regex 耗时、Span 创建与首帧高亮耗时。

## 5. CI 状态

迁移分支已加入 `.github/workflows/build.yml` 的 push 分支过滤器，GitHub Actions 已创建本分支构建任务。当前最新相关运行：

- Run ID：`42912687947`
- URL：<https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/runs/42912687947>
- Build Release APK 与 Run local tests 均处于运行中（本记录生成时尚未取得最终结果）。

不得把“Actions 已启动”写成“构建通过”。最终状态以运行结束后的 job conclusion 和日志为准。

---

## 6. 本阶段判定

**已完成：** 备份分支的常规 Java/JNI Parser 基线、TSH 双管线连续输入初始实现、Activity/高亮器的完整文本快照链路、首批 JNI 内存/生命周期缺陷修复，以及基础回归测试。

**尚未完成：** 最新 TSH 功能回迁、完整 API/Renderer 对齐、Native/JVM/Android 全量测试和真实设备绘制验收。

## 7. Tree-sitter 0.28 QueryCursor API 已补齐

后续补充提交：

- `c733cca08350381ff3e8fc55369718fb4fd0d931`
- `9062edeed46f6d6f3b061376873dd5bd29a3b552`
- `a2f8976e119a3d070cfd88352adce8770d6d217c`

已将当前 main 的纯 QueryCursor 0.28 绑定能力迁入：

- `TSQueryCursor.ProgressCallback` 和 `exec(query, node, callback)`。
- Native `TSQueryCursorOptions`、`ts_query_cursor_exec_with_options`。
- callback GlobalRef / options payload 的生命周期管理。
- callback 执行期间禁止同一 cursor 重入、close 或其他访问。
- 普通 exec、再次 exec 和 close 时释放此前 retained callback state。
- CMake 使用真实编译探测验证 0.28 QueryCursor options ABI；仅在签名匹配时定义 `TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS`。

TSH Native Query 执行也已启用 0.28 options API，并将 Match Limit 设置为 `UINT32_MAX`。Query 执行完成后检查 `ts_query_cursor_did_exceed_match_limit`；若发生结果上限截断，Native TSH 抛出错误并由 JNI 转换为 Java `IllegalStateException`，不返回貌似完整的部分高亮结果。

新增 `ParserTest.testQueryCursorProgressCallbackCanCancelExecution`，用大规模 Java AST 验证进度回调确实被执行并可协作取消查询。该测试尚待 CI 最终结果确认。

## 8. 最新 CI 运行

- Run ID：`42913706789`
- Commit：`a2f8976e119a3d070cfd88352adce8770d6d217c`
- URL：<https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/runs/42913706789>
- Build Release APK：运行中。
- Run local tests：排队中。

本次记录没有把运行中状态当作通过。
