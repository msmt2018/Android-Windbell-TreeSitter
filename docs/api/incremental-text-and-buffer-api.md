# TextBuffer、增量编辑与快照 API 规范

## 目标

让 TSH 可接入 String、Spannable、GapBuffer、Piece Tree、Rope 和流式文本源，不要求宿主把整份文档复制成 String。文本访问、编辑事件、快照和坐标索引必须分离。

## 目标 API

| API | 职责 |
|---|---|
| TshTextBuffer | 文本存储抽象 |
| TshTextSnapshot | 指定版本的只读快照 |
| TshTextReader | 按范围读取或分块读取 |
| TshTextAccessProvider | 将宿主 Buffer 映射为统一接口 |
| TshEditTransaction | 原子编辑批次 |
| TshTextChangeSet | 一次版本变更的编辑集合 |
| TshLineIndex | 行起始位置和换行策略 |
| TshOffsetIndex | UTF-8/UTF-16/CodePoint 分块映射 |
| TshSnapshotLease | 快照资源租约和释放 |

## 快照语义

快照必须不可变，或保证读取期间观察到一致版本。snapshot.version 是所有解析、匹配和渲染结果的基准。若底层 Piece Tree 使用持久化节点，可通过根节点快照避免复制全文；若底层不可提供快照，则 Provider 必须实现锁定/复制策略并报告成本。

## 增量编辑模型

一次 ChangeSet 包含 beforeVersion、afterVersion、多个按旧文档坐标排序的 edit，以及坐标系统。多个编辑必须明确是基于同一旧快照，还是顺序应用的中间快照；不允许混用两种语义。重叠编辑应拒绝或由明确的规范化算法合并。

## 分块读取

TextReader 提供 read(range)、readChunk(offset, maxUnits)、iterateChunks(range)。每块返回绝对起始 offset、单位、版本和边界信息。跨块扫描必须能请求前后文，不能将 chunk-local offset 当成 document offset。

## Piece Tree/Rope 专项

- 通过节点累计长度索引 UTF-16 和 UTF-8，不得每次从文本开头扫描。
- 允许缓存每个叶子块的 byte/UTF-16/codepoint 长度。
- 编辑后仅更新受影响路径上的累计值。
- Grapheme 边界可能跨叶子块，需要 Provider 提供相邻上下文或边界索引。
- 对 Tree-sitter 输入可实现 Input callback，避免全量 flatten。
- 对 Oniguruma 连续内存需求使用专门窗口快照，不能传递不连续指针。

## 验收

- 大文件随机 offset 转换不退化为每次 O(n) 全文扫描。
- 同一 Snapshot 多线程读取结果一致。
- 编辑批次版本断裂或范围重叠会返回诊断。
- Piece Tree/Rope 增量解析不需要全量 String。
- UTF-8 多字节字符、UTF-16 surrogate 和 CRLF 边界均有测试。

## 已实现的 Tree-sitter 分块输入契约（SOURCE_PARTIAL）

当前 Java/JNI 入口为 `TSParser.parseInput(TSInput)`，输入编码固定为 UTF-16LE。TSInput.read 接收 Tree-sitter byte offset，并将宿主 Snapshot 的 UTF-16 code unit 分块编码到 Native 提供的有界 byte array；该路径不构造全文 String 或连续全文 Native buffer。

`TshSnapshotTreeSitterInput` 不仅信任 Provider 的默认实现：即使宿主覆盖 `readChunk`，也会再次校验 documentId/version/start/end、最大长度、非空进展、UTF-16 well-formedness，以及块首尾是否切开 surrogate pair。Provider 违反契约时解析失败并报告输入异常，不会将损坏字节交给 Tree-sitter。

Snapshot Lease 必须覆盖同步 parseInput 调用。ParserOwner 的 Snapshot parse overload 会在 Native 调用前 retain 独立任务 Lease，并在 parse 完成后释放；调用方可独立关闭原句柄。异步调度层若在任务尚未开始时就需要独立持有快照，仍必须在提交任务前 retain，并将该任务句柄的所有权交给任务。

Tree-sitter 分块输入已经具备首版源码契约。项目现有 PieceTreeTextBuffer 的宿主适配器已加入 editor 模块：通过宿主提供的 snapshotFactory 创建 PieceTreeSnapshot，并按范围读取，不调用 PieceTreeTextBuffer.toString()。PieceTreeSnapshot 保留原始文本块引用、复制仍在变化的输入缓冲区内容，且可在后续编辑期间独立读取。此前缺少真实 EditorDocument 接线；本轮新增 `TshPieceTreeEditorBinding`，通过 `EditorView.addEditCallback` 在批量编辑前后分别固定快照，并将 `AppliedChange` 转成一次版本化 Change。该回调运行在 EditorView 编辑线程，快照在编辑前/后边界创建，不依赖 TSH 自行 synchronized 猜测宿主写锁。坐标索引 Provider、文件身份切换与宿主永久关闭的自动生命周期接线、Native 构建和设备级大文件验证仍未完成。 当前 `TshSparseCoordinateIndex.build(lease)` 会先 retain 独立 Snapshot Lease，调用方可在构建成功后关闭原始 Lease；索引自身实现 `AutoCloseable`，必须在不再使用时调用 `close()`。该索引仍需扫描整个 Snapshot 建立 checkpoint（O(n)），只是将读取与转换扫描限制在有界 chunk/stride 内，不应描述为 Piece Tree/Rope 原生索引或编辑增量索引。



### Piece Tree Snapshot 原生坐标查询（SOURCE_PARTIAL）

`PieceTreeSnapshot` 现在固定每个 Piece 的文档起始偏移、源缓冲区偏移、源缓冲区 lineStarts 引用快照、Piece 起始行列，以及 Piece 之前的 line-feed prefix。对坐标请求先二分定位 Piece，再在其 backing buffer 的 lineStarts 中二分定位行；不建立与整个文档行数成比例的新 line-start 数组，也不扫描 Piece 前面的文档内容。对于活动 change buffer，Snapshot 复制当前有效文本；其 lineStarts 通过 `IntList.snapshotView()` 以 O(1) copy-on-write 方式固定，后续编辑第一次修改该 IntList 时才分离底层数组，旧 Snapshot 继续读取原索引。查询复杂度为 O(log(piece count) + log(lines in backing buffer))，新增坐标元数据空间为 O(piece count + backing buffers)；原始与 change-buffer lineStarts 都通过每个 backing buffer 的不可变视图共享，不在每次 Snapshot 创建时复制整份行索引。

`TshTextSnapshot.supportsLineColumnLookup` 与 `lineColumnAtUtf16(offset)` 共同构成可选宿主能力：能力为 true 时，返回零基 Tree-sitter row 和 UTF-16 column；返回 null 表示该端点非法，编译器直接报告坐标边界错误，不会再扫描全文。能力为 false 时表示宿主未提供兼容索引或当前换行语义不兼容，编译器仅在 Snapshot ≤1 Mi UTF-16 时退回有界分块全文扫描；超过该上限直接返回 `TSH-EDIT-COMPILE-009`，要求宿主提供原生索引，避免把分块读取误当作总工作量有界。`TshPieceTreeTextAccessProvider` 将其映射到 `PieceTreeSnapshot.getTreeSitterPositionAt`。PieceTreeSnapshot 的快速路径仅在宿主确认换行归一化且没有 unusual line terminators 时启用；CR-only/异常换行继续走正确性优先的扫描路径。跨 surrogate pair 中间的端点返回不可用，由扫描路径给出既有非法边界诊断。

该实现是利用 Piece Tree 现有节点/源缓冲区元数据的 Snapshot 坐标查询，不是独立 Rope Provider，也不表示编辑后可跨版本复用旧 Snapshot 坐标数据。每个新版本 Snapshot 仍需按当前 Piece 数量固定元数据；进一步减少快照创建成本及构建可编辑增量索引仍是后续工作。

## 可独立续租的 Snapshot Lease（SOURCE_PARTIAL）

`TshTextSnapshotLease.retain()` 返回独立关闭的 Lease 句柄，不得返回原句柄本身。默认实现返回明确的 unsupported 诊断，避免旧自定义 Lease 在多个任务之间共享关闭状态。

`TshSharedTextSnapshotLease` 提供引用计数实现：每个句柄只释放自己的引用；最后一个句柄关闭后才执行一次 `onFinalRelease`。该回调只能释放被固定的快照根/宿主快照资源，不得关闭宿主 Document。回调失败保留在 `finalReleaseDiagnostics` 中。

默认 Provider 的 Snapshot 契约要求快照本身不可变，因此默认 `acquireSnapshot` 使用可续租的共享 Lease，并校验 documentId/version/length；Provider 获取异常和非法元数据以结构化诊断返回。项目 PieceTree 适配器不直接 pin 可变树根，而是使用 PieceTreeTextBuffer.createSnapshot() 产生独立不可变 PieceTreeSnapshot；其对象持有原始 chunk 引用并复制活动 change buffer 的当前有效文本，因此 Lease 生命周期由不可变 Snapshot 对象引用保证。snapshotFactory 必须由宿主在现有编辑互斥/读锁下调用，不能仅靠 TSH 在 buffer 上 synchronized 来假设与编辑器写锁互斥。其他可变 Piece Tree/Rope 实现仍必须 pin 持久化根或版本资源，并在最终 Lease 释放时 unpin。

`TshTreeSitterParserOwner.parse(snapshotLease, textProvider, treeRevision, oldTree, chunkSizeBytes)` 会先 retain 一份独立 Lease，再执行 `TSParser.parseInput`，并可传入已按同版本编辑过的旧语法树执行增量解析，并在 Native parse 返回后释放自己的句柄。调用方可以独立关闭原始句柄；但若任务尚未开始，调度层必须先 retain 并把该任务句柄交给任务，不能把已关闭句柄排队。

## 项目 PieceTreeTextBuffer 适配器（SOURCE_PARTIAL）

editor 模块新增 `TshPieceTreeDocument` 与 `TshPieceTreeTextAccessProvider`，并依赖 `android-tree-sitter` 的公共 TSH API。宿主提供 `snapshotFactory`，在其现有读锁/编辑串行化机制内调用 `PieceTreeTextBuffer.createSnapshot()`。适配器把 `PieceTreeSnapshot.versionId` 映射为 TSH document version，`length` 映射为 UTF-16 长度，范围读取使用 `PieceTreeSnapshot.substring(start, end)`；不会调用 `PieceTreeTextBuffer.toString()` 或构造全文 String。

PieceTreeSnapshot 的创建成本为 O(piece count) 并复制当前仍在 change buffer 的有效文本；原始不可变 chunks 通过引用保留。快照创建后可跨线程读取，并在 live buffer 后续编辑、compact 或 EOL 变化时保持原版本内容。其当前底层长度和偏移 API 仍为 Int，因此适配器显式拒绝超过 Int offset 能力的范围；不能将该限制描述为已经支持超过 2 Gi UTF-16 code units 的单个 PieceTreeSnapshot。


## Tree-sitter multi-edit compiler (SOURCE_PARTIAL)

新增 `TshTreeSitterEditCompiler`，将一个 `TshTextChange` 编译为现有 Tree-sitter `TSInputEdit` 序列：

- 强制校验 documentId 与 beforeVersion 必须匹配提供的不可变 Snapshot；不允许把其他文档/版本的编辑应用到旧树。
- 所有偏移均以 UTF-16 code unit 表示，转换为 Tree-sitter UTF-16LE byte offset 时使用 64-bit 中间计算，并在 Native Int 边界显式拒绝溢出。
- 若 `TshTextEdit` 提供 before-version 行列坐标，直接使用该坐标；否则优先调用 `TshTextSnapshot.lineColumnAtUtf16` 宿主索引（Piece Tree Snapshot 为 Piece 定位 + backing-buffer lineStarts 二分查找）；仅无索引或索引不支持当前换行语义时，且 Snapshot 不超过 1 Mi UTF-16，才通过有界 32K UTF-16 分块单次扫描；更大的 Snapshot 明确失败并要求宿主提供原生坐标索引。全文扫描不创建全文 String 或全局行表，并检查 Snapshot 的 UTF-16 surrogate 完整性。
- 编辑范围不得越界或切开 surrogate pair；起点/旧终点/新终点分别转换为 Tree-sitter row/byte-column。
- 输入编辑按原始文档坐标升序描述，编译结果反向输出为降序；整个批次一次性传给 `TshTreeSitterParserOwner.editTreeBatch`，在一个独占 Native Lease 内依次应用，较高偏移的编辑不会改变较低偏移编辑的坐标。
- CRLF 中的 CR 计入当前 Tree-sitter byte column，LF 才推进 row；替换文本中的换行也按同一规则计算。

当前新增单元测试源码覆盖多 Edit 降序、版本错配、surrogate 边界与 CRLF/UTF-16LE byte column。EditorView Binding 已将真实 AppliedChange 接入 Change 编译入口；Gradle/JUnit、EditorView 集成和 Native 验证尚未运行，因此仍为 SOURCE_PARTIAL。


## Snapshot-to-incremental-parse orchestrator (SOURCE_PARTIAL)

新增 `TshTreeSitterIncrementalUpdate.apply`，要求调用方提供同一文档的 before/after Snapshot Lease、版本化 `TshTextChange`、旧 OwnedSyntaxTree 和目标 treeRevision。入口依次校验两端快照身份/版本、编译 Edit、原子应用 Native Edit、以 after Snapshot 执行增量 parse；新树成功注册后才 retire 旧树。调用方仍拥有两份 Snapshot Lease，必须保持其在同步调用期间有效。

编辑成功但增量 parse 失败时，旧树保留在 afterVersion 的 EDITED_PENDING_PARSE 状态，可供重试 parse，但不可执行 Query。ParserOwner 对单 Edit 与批量 Edit 都写入该状态；QueryExecutor 在获取 Native Query/Tree Lease 前预检该状态，并在获取 Tree Lease 后再次检查以封闭并发竞态。新 Snapshot parse 注册的替代树标记为 READY。空文本 Edit 允许只推进版本元数据，不触发 Native edit；这覆盖内容未变化但宿主版本推进的情况。

该编排层现由 `TshPieceTreeEditorBinding` 与 `TshPieceTreeIncrementalSession` 接入真实 EditorView：编辑前后固定 Snapshot，完整批次转成 Change，并在单线程 worker 串行执行增量更新。源码路径已连接，但端到端 Native/JUnit 和设备验证尚未完成。


## EditorView / EditorDocument transaction binding (SOURCE_PARTIAL)

editor 模块新增 `TshPieceTreeEditorBinding.attach(view, documentId, languageId, ...)`：

- 在 `beforeTextChanged` 回调中获取 before Snapshot Lease；在 `afterTextChanged` 中、整个批次已应用后获取 after Snapshot Lease。
- `EditorDocument.AppliedChange` 本身已携带旧范围 UTF-16 offset/行列、删除长度和插入长度。PieceTree EOL 已归一化时，绑定层直接把旧范围行列作为 TSH endpoint metadata，避免每次编辑扫描整个 before Snapshot；未归一化时回退到 Snapshot 分块坐标扫描，以正确处理 CR-only 等非 Tree-sitter 换行。绑定层同时扫描 `TextSlice.forEachChunk` 计算替换 LF/tail 指标，不调用 `TextSlice.toText()`，大型 undo/redo 不会为了构造事件而复制大块插入文本。
- `TshTextEdit` 新增可选 `startLine/startColumnUtf16/oldEndLine/oldEndColumnUtf16`（before-version、零基 row、UTF-16 code-unit column），以及 `replacementLengthUtf16`、`replacementLineFeedCount` 与 `replacementTrailingUtf16Length`，描述未物化替换内容的长度/行尾指标，不把 Tree-sitter Native Point 泄漏进通用文本 API；普通调用方仍可使用 String replacement。PieceTable 的 `TextSlice.forEachChunk` 可遍历文本切片的只读块视图，不构造完整 String。
- 多编辑从 EditorDocument 的 bottom-up applied 顺序恢复为 TSH before-version 升序；Tree-sitter compiler 再反向生成 Native 降序 Edit。相同 offset 的多个纯插入保留稳定顺序；重叠替换仍拒绝。
- `TshPieceTreeDocument` / Provider 增加 change subscription 与 disposal notification；Provider capability 显式声明 `text.change-subscription` 和 `text.disposal-notification`。
- `onTransaction(change, beforeLease, afterLease)` 是后台调度边界。消费者接管两个 Lease 后必须在 parse/retry 结束时关闭；不得在 EditorView 回调中同步执行大文件 Native parse。
- `EditorDocument.replaceBuffer()` 现在发出独立的 `onBufferReplaced(previous, current)` 生命周期事件；EditorView 将事件转发给已注册 observer，`TshPieceTreeEditorBinding` 在主线程更新版本并通知 Session 自动从新 PieceTree Snapshot 执行 full resync。该事件不是 TextEdit，不会伪造增量编辑或把旧树迁移到新 Buffer。
- `TshPieceTreeEditorBinding.close()` 仍须由宿主在文档永久关闭时、EditorView 主线程调用。切换 documentId 时仍须结束旧 Session 并重新 attach。

本轮增加的转换测试覆盖大型 TextSlice 的长度/端点元数据路径和 Provider change/disposal subscription。已增加 EditorDocument Buffer replacement 生命周期事件测试源码；仍需运行 editor/android-tree-sitter 的 Gradle/JUnit 测试，并补齐 EditorView 真实批量编辑、撤销/重做、自动 replacement/resync 和关闭竞态集成测试。


## Per-document incremental parse session (SOURCE_PARTIAL)

editor 模块新增 `TshPieceTreeIncrementalSession.attach(view, documentId, languageId, parserOwner, ...)`：

- 初次 attach 后获取初始 PieceTree Snapshot，并在单线程 worker 上执行首次 parse。
- 后续 EditorView 完整编辑批次由单一 drain worker 串行处理；pending 队列最多保留一个批次。worker 落后时，中间 pending 批次的 Lease 会立即释放，只保留最新 after Snapshot 并强制 full parse，避免大文件快照在无界任务队列中累积。
- 若当前树版本与 change.beforeVersion 匹配且该批次未被 coalesce，执行 `TshTreeSitterIncrementalUpdate.apply`；若版本不匹配、pending 批次被 coalesce 或 Native Edit 尚未应用，则以 after Snapshot 做 full parse。
- 若 Native Edit 已完成但 parse 失败，树保留 `EDITED_PENDING_PARSE`，session 重试 parse，不会重复应用同一批 Edit；重试失败时仍保留待解析状态。
- 新树成功发布后回收旧树。Session 关闭时先阻止新任务，再在 worker 队列尾部回收当前树；当前同步 Native parse 不会被 Thread.interrupt 强行中断。树更新与失败回调统一投递到 Android 主线程，若主线程收到回调前已有更新树发布或 Session 已关闭，则丢弃过期回调。Session 不拥有 ParserOwner，宿主必须保证 ParserOwner 至少存活到所有使用它的 Session 都关闭。
- `onTreeUpdated` / `onFailure` 在 worker 或编辑回调线程执行，宿主需要自行切回 UI 线程更新界面。所有传入的 Snapshot Lease 由 Session 接管并在 finally 释放。
- Binding 无法表示的编辑批次会触发 invalidation，Session 在 EditorView 主线程从当前 Snapshot 自动 resync；同一 EditorView 的 Buffer replacement 现由独立生命周期事件自动触发 resync；切换到不同 documentId 时仍须在主线程关闭旧 Session 并重新 attach。

该 Session 将真实 EditorView edit callback 接到 Snapshot → Edit Compiler → Native Batch Edit → Incremental Parse 的串行任务链。当前尚未运行 Gradle/JUnit/Native 测试；还需补充快速连续编辑、初次 parse 与编辑竞态、失败注入、close 与 Native parse 并发、buffer replacement/resync 集成测试。
