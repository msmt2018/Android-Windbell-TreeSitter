# Phase A：源码盘点、绑定基线确认与迁移依赖图

> 分支：`migration/tsh-0.28-from-backup-no-largefile`  
> 备份祖先：`back_main_0e9051c`（`cf455e544f0661e4d43f4f98dc243efa90c9c147`）  
> 本阶段基线提交：`451274228264f2dcaf63c0e408783639cbcd5553`  
> Tree-sitter 子模块：`62816e1c0d0a4ca9553174f3cbe5b9631047f7f4`（上游 Cargo.toml = 0.28.0）

本文件记录 Phase A 的实际源码核查结论，作为后续迁移的文件级依据。它不是实现完成报告。

---

## 1. 核查结论：最终绑定基线已经明确

备份仓库中存在两个不同的 Tree-sitter JNI 代码目录，不能将它们误认为同一个模块：

### 1.1 活跃绑定：`android-tree-sitter/`

备份分支 `settings.gradle` 明确 include `:android-tree-sitter`；`app/build.gradle.kts` 依赖 `projects.androidTreeSitter`。演示 Activity 使用：

- `com.zerostudio.idea.treesitter.TreeSitter`
- `TSParser`
- `TSTree`
- `TSLanguage`
- 各语言 Grammar 模块

其 JNI 实现由 `android-tree-sitter/src/main/cpp/CMakeLists.txt` 编译，Java API 位于 `android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/`。

备份版本的关键文件：

| 文件 | 备份版本 Blob SHA | 作用 |
|---|---|---|
| `android-tree-sitter/src/main/cpp/CMakeLists.txt` | `50db76f382e871afb5f6996b09069b8a9b63bb05` | 原生 JNI 构建清单 |
| `android-tree-sitter/src/main/cpp/ts_parser.cc` | `3333abe7b0929bdb7a5a8b6e866b3e9947d306ab` | 连续 UTF-16LE 输入、Parser 生命周期、取消/超时 |
| `android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSParser.java` | `043881a6a58d8b7a13a23f093e4ddffe2d53045b` | 常规 String/UTF16String/byte[] Parser API |
| `android-tree-sitter/src/main/cpp/main.cc` | `17b82544a27c86b1639ac8bde9365093076d55b7` | JNI 注册和引擎版本标记 |

备份 `TSParser.java` 使用完整 `UTF16String`，没有 `parseWindowed`、`parseBuffers` 或窗口阈值分流。备份 `ts_parser.cc` 把完整 UTF-16LE 源数据交给一次 Tree-sitter Parse 输入，不依赖 `largefile::TextSource`。

**决定：本任务以备份分支的 `android-tree-sitter/` Java + JNI 绑定作为唯一绑定迁移基线。** 迁移 TSH 时不得用当前 main 的窗口化 Parser 实现覆盖它。

### 1.2 非活跃并行代码：`treesitter/`

备份仓库另有 `treesitter/` 目录，包含 Kotlin Parser / Query / Tree API 以及另一套 JNI C++ 文件。但备份 `settings.gradle` 没有 include `:treesitter`，备份 app 也没有依赖该模块。它与活跃 `android-tree-sitter` 使用相同的 Java/Kotlin package namespace 和类名体系，不能直接一起加入构建，否则会形成重复 API / 类冲突风险。

**决定：本轮不将这个未纳入 Gradle 工程的平行 `treesitter/` 目录作为第二套绑定迁移。** 仅保留其作为历史源码参考；若后续发现其中有活跃绑定缺失且无法从 `android-tree-sitter` 等价实现的能力，必须单独做 API 对照后移植，不整目录合并。

---

## 2. Tree-sitter 0.28.0 核验

备份分支的子模块指针为 `9632b561db2f647e9c0be9e61cf91f10671a6491`，其上游 `Cargo.toml` 声明 `0.28.0`。当前 main 使用 `62816e1c0d0a4ca9553174f3cbe5b9631047f7f4`，上游 `Cargo.toml` 同样声明 `0.28.0`。

迁移分支已经把 `tree-sitter-lib` 固定到后一个提交。它是完整上游仓库子模块版本指针，不是只替换 C API 头文件。

注意：备份 `android-tree-sitter/src/main/cpp/main.cc` 中的 `TS_VERSION_NUM` 是 `0.28.0`，但 `TS_GIT_HASH` 仍为旧短标记 `ad067fd`。后续版本标记必须与迁移分支实际子模块 SHA 同步，不能继续保留过时标记。

---

## 3. 当前 main 中 TSH 的迁移来源

当前 main 的 TSH 实现位于原生绑定模块内，不是独立可直接复制的目录。

### 3.1 Native TSH

| 文件 | 当前 main Blob SHA | 主要职责 | 迁移处理 |
|---|---|---|---|
| `android-tree-sitter/src/main/cpp/tsh/WindbellTshCompiler.cpp` | `<see main ref>` | DSL 编译、规则和 Query 编译 | 先审查与 TextSource 的直接依赖，再迁移 |
| `android-tree-sitter/src/main/cpp/tsh/WindbellTshCompiler.h` | `<see main ref>` | Compiler API | 迁移 |
| `android-tree-sitter/src/main/cpp/tsh/WindbellTshCore.cpp` | `598c83554d4f7ba5d671d5da7480513bb09aef27` | ATS/Regex 执行、Capture、Style 结果 | 必须改成连续输入核心 |
| `android-tree-sitter/src/main/cpp/tsh/WindbellTshCore.h` | `45ae238d62a5d1db347798ab1a1c891a5f43dbfc` | TSH Native 类型和执行契约 | 移除 TextSource / Partition / Checkpoint 参数 |
| `android-tree-sitter/src/main/cpp/tsh/TshRegexNative.cpp` | `<see main ref>` | 独立 Oniguruma API | 审查后迁移 |
| `android-tree-sitter/src/main/cpp/tsh/TshRegexNative.h` | `<see main ref>` | Regex Native API | 审查后迁移 |
| `android-tree-sitter/src/main/cpp/ts_windbell_jni.cpp` | `6f8fd1c316460201b982bcdc03650eef68ea7870` | TSH JNI、配置、Palette、Highlight、Document Session | 保留普通 Highlight 与配置 API；删除 Native Document Session / 分区接口 |

当前 `WindbellTshCore::highlightHybrid` 已存在完整连续文本入口：调用方传入完整 `input_chars`，且 `text_source == nullptr` 时走连续文本分支。后续重构应把这条路径提升为唯一正式执行路径，并从签名、辅助函数和状态结构中移除 TextSource 依赖，而不是另造一条 TSH 执行管线。

### 3.2 已确认的 TSH JNI 双路径

当前 `ts_windbell_jni.cpp` 中：

- `nativeHighlight`（约 866 行）接受完整 Java String、现有完整 TSTree 和字节范围，直接 pin UTF-16 源数据并调用 `highlightHybrid`。这是本分支应保留并强化的常规 JNI 入口。
- `nativeCreateDocument`、`nativeApplyDocumentEdit`、`nativeHighlightDocument` 及其 Native Document Registry 是 PieceTree / MappedFile / FileWindow / TextPartition 执行路径，必须整体排除。
- TSH 配置、语言元数据、Compile Diagnostics、Scheme、Style Catalog、Theme Tokens、Palette 等非大文本 API 应逐项保留。

不能只删除这三个 JNI 方法；还必须同步清理 Kotlin Native 声明、Document handle、编辑队列、分区 Capture 缓存、Regex checkpoint 和调用者。

### 3.3 TSH Core 直接依赖情况

当前 `WindbellTshCore.h` 的 `highlightHybrid` 参数包含：

- `windbell::largefile::TextSource*`
- `RegexStateCheckpoint*`
- `source_version`
- `partition_version`

当前 `WindbellTshCore.cpp` 中还存在：

- TextSource / TextChunkView 读取与 `copyTextRange`。
- 有界窗口 Regex 执行。
- Regex state checkpoint 和恢复。
- source/partition version 一致性路径。
- TextSource 文本谓词和 AST Capture 文本提取辅助函数。

这些内容必须从运行时执行模型中清除。完整连续文本模式下，普通 Oniguruma Regex 可以直接面对完整 UTF-16 文本；Tree-sitter Query 继续面对同一份完整 TSTree。

---

## 4. 当前 main 的 Parser / JNI 差异

| 层 | 备份分支 | 当前 main | 本分支目标 |
|---|---|---|---|
| Java Parser | `TSParser.java` 约 411 行；String / UTF16String / byte[] 完整输入 | `TSParser.java` 约 730 行；加入 64 KiB 窗口、ByteBuffer 分段和窗口 API | 以备份版本为基线，只补齐 0.28.0 API 所需差异 |
| Native Parser | `ts_parser.cc` 约 322 行；完整 UTF-16LE 输入 | `ts_parser.cc` 约 566 行；TextSource、parseBuffers、parseWindowed | 保留备份连续输入实现，审计生命周期/范围/错误处理 |
| CMake | 原生 Tree-sitter + JNI 绑定源 | 额外编译 largefile 和测试源 | 无 largefile 源文件、头文件、测试目标 |
| JNI 方法 | Parser/Tree/Query 等常规绑定 | 增加分段/窗口输入 Native 方法 | 不存在窗口/分段 Native API |

当前 main `CMakeLists.txt` 编译了以下 largefile Native 源文件：`TextSource.cpp`、`FileWindowSource.cpp`、`MappedFileSource.cpp`、`PieceTreeSource.cpp`、`TextPartitionIndex.cpp`，并注册 `windbell_largefile_tests`。这些都不应进入本分支最终构建。

---

## 5. Kotlin / Java API 迁移清单

### 5.1 应迁移的 API 组

- 常规 Tree-sitter Java API：Parser、Tree、Node、TreeCursor、Query、QueryCursor、Language、Range、InputEdit、Point、Match、Capture。
- TSH Engine facade 和 Native 调用声明。
- TSH DSL Model / Schema / Compiler 结果和诊断模型。
- Query / Regex API 适配层。
- Style Resolver、Palette、Theme Tokens、Editor Metadata。
- Render API / Android Spannable Renderer。
- Native 资源生命周期管理（只保留一般 Native Handle，不保留 Document Partition / Window Lease）。

### 5.2 必须排除的 API 组

当前 main 的以下类明确服务于窗口/分段文本执行，不能直接迁入：

- `TshRegexWindowLease.kt`
- `TshRegexWindowSequence.kt`
- `TshSnapshotTreeSitterInput.kt`（当前实现依赖 Snapshot / Chunk 访问，需重写为普通完整输入契约或不迁移）
- `TshTextAccessBinding.kt` 中的 Chunk/Provider 专用桥接（需拆分通用接口）
- `TshTextDocument.kt` 中的 Chunk / Snapshot Lease / TextAccess Provider 专用能力（需按本分支简化）
- `TshMemoryBudget.kt` 中只服务于大文本窗口执行的预算逻辑
- `TshSparseCoordinateIndex.kt` 中只服务于稀疏分区坐标的索引逻辑
- 所有 Native Document / Partition / Checkpoint 对应类型和 API。

不得因为类名不含 Window 就默认它可以迁移；必须沿调用图确认是否仍然依赖 Chunk / Partition / Snapshot Window 语义。

---

## 6. Activity / Editor 集成

当前 main 的实际演示入口为：

- `app/src/main/java/com/zerostudio/idea/androidtreesitter/MainActivity.kt`
- `app/src/main/java/com/zerostudio/idea/androidtreesitter/TshSyntaxHighlighter.kt`
- `app/src/main/assets/tsh/*.tsh`
- `app/src/androidTest/java/com/zerostudio/idea/androidtreesitter/TshEditorPipelineRenderingTest.kt`

MainActivity 负责加载语言 Grammar、TSH 配置、Scheme、EditorView 和高亮器。它应迁移必要的 Activity、资源、Renderer 和配置加载逻辑。

当前 `TshSyntaxHighlighter.kt` 同时包含小文档连续路径和大文档 Native Document / PieceTree 分区路径。迁移时保留并强化完整文档快照解析路径，删除 Native Document Session、PartitionWork、CapturePageCache、窗口编辑事务等大文本专用实现。

编辑器本身的普通文档模型与 TSH Native 大文本处理框架必须区分：本分支不迁移 TSH 的 Native PieceTree/Partition 桥接；Activity 可以保留编辑器 UI，但进入 Parser/TSH 的输入必须是一次完整、连续、revision 明确的文档快照。

---

## 7. 已发现的首批 Native JNI 风险（待修复）

这些是源码审查发现，尚未在本阶段修改：

1. 备份 `ts_parser.cc` 的 `TSParser_setIncludedRanges` 使用 C++ 变长栈数组 `TSRange tsRanges[count]`。这是编译器扩展，不是标准 C++；范围数量由 Java 数组控制，可能导致栈空间过大。应改成受控的 `std::vector<TSRange>`，并处理分配失败。
2. 同一循环取出的 `jobject range` 未见对应的 `DeleteLocalRef`；应逐项释放并在 JNI 异常 pending 时立即停止继续 JNI 调用。
3. `TSParser_getIncludedRanges` 对 `jint count` 通过 `reinterpret_cast<uint32_t*>` 作为 Tree-sitter 输出参数，存在有符号/无符号别名与宽度表达不清的问题。应改为 `uint32_t count` 后安全转换。
4. `TSParser_parse` 把 `source->byte_length()` 转成 `uint32_t`，需要在调用 Tree-sitter 前显式验证输入长度、UTF-16LE 偶数字节边界和输入对象有效性。
5. `TSParserInternal::getParser` 失败时可能返回空指针；多个 Native 方法在取得 Parser 后仍继续调用 Tree-sitter API。需要统一校验返回值并在 pending exception 下安全返回。
6. `TSParser_parse` 当前以 `to_cstring()` 复制完整 UTF-16LE 输入。该复制可能是保护可变 `UTF16String` 并发修改的安全边界；在没有引入不可变输入/读租约前，不得仅为减少一次复制而改成裸借用内部 vector 指针。
7. `TSParserInternal` 的 Native delete 与 parse/cancel 并发语义需和 Java `close()`、Parser lock 一起审计；不能仅依赖 AtomicLong 指针就认为 Native 生命周期线程安全。

风险 1–5 可作为第一批 JNI 正确性修复；风险 6–7 需先定义输入与对象所有权契约再实现。

---

## 8. Phase A 交付状态

- [x] 核实备份分支与迁移分支的祖先关系。
- [x] 核实 Tree-sitter 0.28.0 子模块版本。
- [x] 区分活跃 `android-tree-sitter` Java JNI 与未纳入 Gradle 的 `treesitter/` Kotlin 平行实现。
- [x] 确认常规连续 Parser 的备份源码入口。
- [x] 确认当前 main TSH 的 Native、Kotlin、Activity、Renderer 迁移来源。
- [x] 确认 Native Document Session / Window / Partition 的排除范围。
- [x] 记录首批 JNI 风险和安全优化约束。
- [ ] 完成所有 TSH Kotlin API 文件的完整调用图与迁移分组。
- [ ] 完成所有 JNI 导出/动态注册方法与 Java Native 声明逐一对照。
- [ ] 完成 Native Compiler / Core 的逐函数 TextSource 依赖清单。
- [ ] 完成 Activity / Renderer 端到端调用链对照。

Phase A 尚有三个细粒度审计项未完成。Phase B 可以并行开始第一批 JNI 安全修复，但不能把 Phase A 全部审计标记为完成。

---

## 9. 下一步实施顺序

1. 在备份连续输入基线修复 `ts_parser.cc` 的范围数组、JNI LocalRef、异常检查、Parser 空句柄保护和长度边界。
2. 对照 Tree-sitter 0.28.0 的 Parser / QueryCursor 新 API，确认绑定缺失项；只补齐正式 API，不引入窗口接口。
3. 迁移 TSH Compiler 和 Core，先把 `input_chars + complete TSTree` 路径作为唯一执行契约。
4. 从 TSH Core Header / Runtime 移除 TextSource、Chunk、Partition、Checkpoint 和版本字段。
5. 迁移 TSH JNI 的普通配置与 `nativeHighlight`；删除 Native Document Session 系列方法。
6. 迁移 Kotlin TSH API 和 Renderer，清理 Window/Chunk/Partition API。
7. 迁移 Activity、Assets 和实际绘制测试。
8. 执行全量构建、单元测试、JNI 契约测试和 Android 实际绘制验收。

每一步必须单独提交，提交后重新读取分支文件确认实际内容。构建未执行或失败时必须如实标注。
