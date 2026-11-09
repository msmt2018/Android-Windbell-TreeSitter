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

Snapshot Lease 必须在整个同步 parseInput 调用期间保持打开。调用方不得在解析期间并发 close；若发生关闭，后续回调会失败而不会继续读取。当前 Lease 接口尚未提供跨调用 retain/fork，因此异步调度层必须由宿主负责把 Lease 生命周期覆盖到任务结束。

此实现只代表分块输入源码具备首版契约；Piece Tree/Rope 的真实节点索引 Provider、并发编辑压力测试、Native 构建和设备级大文件验证仍未完成。

## 可独立续租的 Snapshot Lease（SOURCE_PARTIAL）

`TshTextSnapshotLease.retain()` 返回独立关闭的 Lease 句柄，不得返回原句柄本身。默认实现返回明确的 unsupported 诊断，避免旧自定义 Lease 在多个任务之间共享关闭状态。

`TshSharedTextSnapshotLease` 提供引用计数实现：每个句柄只释放自己的引用；最后一个句柄关闭后才执行一次 `onFinalRelease`。该回调只能释放被固定的快照根/宿主快照资源，不得关闭宿主 Document。回调失败保留在 `finalReleaseDiagnostics` 中。

默认 Provider 的 Snapshot 契约要求快照本身不可变，因此默认 `acquireSnapshot` 使用可续租的共享 Lease，并校验 documentId/version/length；Provider 获取异常和非法元数据以结构化诊断返回。可变 Piece Tree/Rope Provider 必须在创建共享 Lease 时 pin 住持久化根或版本资源，并在最终释放回调中 unpin；不得只包装一个仍指向可变当前根的对象。

`TshTreeSitterParserOwner.parse(snapshotLease, textProvider, treeRevision, oldTree, chunkSizeBytes)` 会先 retain 一份独立 Lease，再执行 `TSParser.parseInput`，并可传入已按同版本编辑过的旧语法树执行增量解析，并在 Native parse 返回后释放自己的句柄。调用方可以独立关闭原始句柄；但若任务尚未开始，调度层必须先 retain 并把该任务句柄交给任务，不能把已关闭句柄排队。
