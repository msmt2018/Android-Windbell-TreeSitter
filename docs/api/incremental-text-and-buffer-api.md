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

Tree-sitter 分块输入已经具备首版源码契约。项目现有 PieceTreeTextBuffer 的宿主适配器已加入 editor 模块：通过宿主提供的 snapshotFactory 创建 PieceTreeSnapshot，并按范围读取，不调用 PieceTreeTextBuffer.toString()。PieceTreeSnapshot 保留原始文本块引用、复制仍在变化的输入缓冲区内容，且可在后续编辑期间独立读取。当前仍缺少真实 EditorDocument 锁/调度接线、变更与销毁事件订阅、坐标索引 Provider、Native 构建和设备级大文件验证。

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
- 通过有界 32K UTF-16 分块构建行起点索引，不创建全文 String；索引同时检查 Snapshot 的 UTF-16 surrogate 完整性。
- 编辑范围不得越界或切开 surrogate pair；起点/旧终点/新终点分别转换为 Tree-sitter row/byte-column。
- 输入编辑按原始文档坐标升序描述，编译结果反向输出为降序。按此顺序逐个调用现有 `TshTreeSitterParserOwner.editTree`，较高偏移的编辑不会改变较低偏移编辑的坐标。
- CRLF 中的 CR 计入当前 Tree-sitter byte column，LF 才推进 row；替换文本中的换行也按同一规则计算。

当前新增单元测试源码覆盖多 Edit 降序、版本错配、surrogate 边界与 CRLF/UTF-16LE byte column。尚未运行 Gradle/JUnit，也尚未把真实 EditorDocument 的 change/dispose 事件接入该编译器；因此仍为 SOURCE_PARTIAL。
