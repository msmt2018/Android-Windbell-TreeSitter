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
