# TSH 引擎整体完成度、性能整改与开发待办地图

> 文档状态：项目级权威路线图（2026-11-19）
>
> 仓库：msmt2018/Android-Windbell-TreeSitter
>
> 模块：android-tree-sitter（Java/Kotlin API、JNI、C/C++ Native、Tree-sitter、TSH、ATS、Regex/Oniguruma）
>
> 本文记录已存在的源码能力、尚未完成的工程接线、真实构建/测试证据、性能目标和验收条件。源码存在不等于功能完成；测试源码存在不等于测试通过；单项测试通过不等于端到端设备体验通过。

相关文档：
- [既有 API 与宿主接入路线图](../docs/roadmap.md)
- [Native 大文本 Token 与分块输入架构](../docs/TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.md)

## 0. 本文件的权威性与设计决策

本文件是 TSH 完成度、性能和后续开发顺序的权威地图。它细化并更新仓库既有 docs/roadmap.md 与 docs/TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.md 中尚未验证的条目。

### 0.1 本次明确的架构决策

1. **不以人为设置很低的固定内存预算作为大文件优化方案。** 不能通过降低 Capture 数量、缩小缓存到无法命中、提前停止查询、减少语法树内容或牺牲高亮完整性来制造“内存安全”。
2. **优先优化数据结构和算法本身。** 重点是持久化 Piece Tree / 多路树、分块缓冲、节点聚合摘要、局部路径复制、节点池化、索引复用、批处理和增量更新。
3. **内存观测与异常保护仍然必要，但定位是护栏，不是主引擎。** 应观测真实 Native Heap、JVM Heap、PSS、映射页、缓存驻留、分配速率和峰值；系统压力时回收可重建缓存、降低预取并发、暂停非可见后台工作。不得将很低的常量预算当作正常运行上限。
4. **完整语义优先。** 任何性能优化都必须保持完整文档坐标、跨块语法上下文、Query Capture、Regex 语义、视口颜色、编辑、撤销/重做和增量更新正确。
5. **Native 引擎必须独立具备大文本能力。** 应用层 Piece Tree 可以作为 TextSource，但不能替代 JNI/Native 自身对分块输入、缓冲区生命周期、编码、节点、查询结果和大规模对象的处理能力。
6. **保留两条查询管线。** ATS 与 Tree-sitter Query 是两个前端，最终进入同一 Tree-sitter Query 执行体系；Regex/Oniguruma 保持独立，不引入第三条通用执行管线。

### 0.2 对旧内存预算描述的修订

旧设计中出现的“常规 100 MiB 目标 / 512 MiB 硬上限”是此前阶段的资源预算设计，不再作为性能设计目标，也不能据此把正常缓存、Query 结果或 Native 数据结构压到很低的固定上限。后续实现必须区分：

- **真实数据结构占用**：文本、语法树、节点、Capture、Match、RenderPlan 等实际存活对象；
- **可回收缓存占用**：窗口、索引、重复查询结果、Regex 派生状态；
- **瞬态工作集**：解析、编辑、查询、渲染批次的临时对象；
- **系统资源压力**：Android 进程可用内存、Native Heap、JVM Heap、PSS、mmap 页和文件系统空间。

内存管理要解决对象冗余、复制、碎片、生命周期和缓存淘汰，而不是靠人为小预算让功能无法完成。异常保护阈值应根据真实设备进程约束和测量结果设置，并提供明确诊断。

## 1. 总体完成度结论

TSH 已经具备相当数量的 Native 大文件、Piece Tree、增量解析、Query/ATS、Regex、统一 Kotlin API、RenderPlan 和 Android Span 接入基础；但目前仍是**基础架构已建立、多个执行路径仍处于分阶段接线、性能和设备闭环尚未达标**的状态。

不能给出一个可信的“整体完成百分比”：当前不同子系统的源码完成、构建完成、测试完成和设备完成程度不一致。下面按可核查状态逐项标记。

### 1.1 状态定义

| 状态 | 含义 |
|---|---|
| SPEC | 有设计/契约，未实现 |
| SOURCE | 已有部分源码，未证明完整 |
| WIRED | 已接入真实调用路径，但验证范围有限 |
| BUILD | 指定模块/变体构建通过 |
| TEST | 指定自动化测试通过 |
| DEVICE | 指定 Android API、ABI 和真实宿主控件端到端通过 |
| PERF | 有可重复的性能数据并达到目标 |

任何条目只有在记录 commit、命令、设备/API、测试结果后才能提升状态。不能把一个 API 26 通过结果外推为 API 30/35 通过，也不能把 Host Native 测试外推为 Android JNI 通过。

### 1.2 子系统状态总览

| 子系统 | 当前判断 | 已有基础 | 主要未完成项 |
|---|---|---|---|
| Native 大文本输入 | SOURCE/WIRED | TextSource、FileWindowSource、MappedFileSource、TSInput callback、PieceTreeSource | 输入建立耗时、回调次数、复制次数、真实吞吐优化和完整压力验证 |
| Native Piece Tree | SOURCE | 持久化 treap、路径复制、64 KiB append chunks、Snapshot clone | 多路节点、聚合坐标指标、叶块合并、碎片压力、批量编辑与高频读取性能 |
| Tree-sitter Parser | WIRED | 原生 Parser、UTF-16LE TSInput、旧 Tree edit、增量 parse 基础 | 密集 Token/节点分配、解析栈与 Subtree 性能观测、增量编辑尾延迟 |
| Token / Subtree | SOURCE（上游能力） | Tree-sitter 原生 Subtree、SubtreePool、ReusableNode、局部 TokenCache | 本项目尚无完整自有 Token Page/多级 Token 缓存实现；需要先 profiling 再决定扩展 |
| ATS / AST Selector | SOURCE/WIRED（部分） | AST Selector、supertype 索引、节点/兄弟约束执行基础 | ATS 语言前端/语义编译完整度、索引成本、密集节点扫描性能、完整回归 |
| Tree-sitter Query API | SOURCE/WIRED（适配层） | Compiler/Executor、Cursor、Capture、Query/Tree/Language Lease | 新公共 API 与生产高亮链路全面收敛、取消/预算能力和密集 Capture 吞吐 |
| Regex / Oniguruma | SOURCE/WIRED（部分） | 独立 Regex 管线、有限宽度窗口、连续输入优化、mmap Snapshot | 编辑后快照全量重建、跨窗口上下文语义、Regex State 增量和吞吐 |
| 统一 Match / Scope / Style | SOURCE | Match/MatchSet/Scope/Selector/StyleResolver 模型 | Tree-sitter/ATS/Regex 生产结果全面接入、冲突策略和 revision 事务 |
| JNI / Kotlin API | SOURCE/WIRED（部分） | Native Handle、批量数组输出、UTF-16LE 输入、统一 API 基础 | JNI ABI 全量审计、对象复制与编码路径收敛、旧 API 迁移 |
| 高亮 Renderer | WIRED（部分） | Native spans、Viewport 查询、Android Spannable Binding、Receipt | 真实 Canvas/Compose/custom View 完整链路、API 30/35 设备失败修复、闪烁关闭 |
| 增量高亮 | SOURCE/WIRED（部分） | Edit queue、Tree edit、Dirty ranges、缓存版本、部分 Cache Rebase | 依赖范围准确性、旧结果保留、原子提交、快速编辑完整体验 |
| DSL / Schema | SOURCE | Schema、诊断、Runtime Consumer 基础 | 完整 Lexer/Parser/AST/SemanticAnalyzer/Lowering/Runtime Catalog |
| 性能基准体系 | SOURCE | 100 MiB 与 32,768 声明压力 fixture | 阶段计时、CPU/内存/分配/JNI/渲染分项指标、重复运行统计 |
| CI / 设备矩阵 | 不完整 | API 26/30/35 工作流、Host/Release 任务 | 多个 Draft PR 最新提交尚无新 CI；API 30/35 存在失败 |

## 2. 性能验收目标（最高优先级）

### 2.1 已报告的基准

以下是当前项目讨论和压力测试所报告的基线，不应误解为已经完成的性能验收：

| 场景 | 输入与实际工作 | 当前耗时 | 主要覆盖 |
|---|---|---:|---|
| 100 MiB 低语法密度文本 | 100 MiB UTF-16LE 文件，超大 Java block comment 前缀，Native 文档创建/全量解析，再查询文件尾部视口 | 约 16.8 秒 | 文件输入、Native backing source、Tree-sitter 扫描、尾部坐标/分区定位、视口 Query |
| 高密度 Java | 32,768 个合法局部变量声明；每个声明位于独立 block，生成真实 Java 语法节点；全文件 Query 并取回真实变量 Capture，再查询尾部视口 | 约 7.6 秒 | Parser 节点构建、AST/Query 扫描、Capture 产生、JNI 结果输出、Kotlin 结果处理 |

这两个测试不是同一种压力：

- 100 MiB 场景主要是**大输入量、低 AST 节点密度**；它不是 100 MiB 的 32,768 节点密集语法测试。
- 32,768 场景主要是**较小文本体积、高语法节点与 Capture 密度**；它验证真实节点和 Query 结果，而不是稀疏文件读取。
- 高密度测试中，全文件 Capture 结果会跨 JNI 返回给 Kotlin，并统计 Capture 数量；不能只优化到尾部 viewport 快而让全文件 Query 失去完整结果。

### 2.2 必须达到的目标

| 场景 | 基线 | 第一验收目标 | 目标耗时下降 | 进一步目标 |
|---|---:|---:|---:|---:|
| 100 MiB 低语法密度 | 16.8 s | ≤ 5.0 s | ≥ 70.2% | 5–6 秒区间作为首轮门槛 |
| 32,768 高密度 Java + 全量 Capture | 7.6 s | ≤ 2.0 s | ≥ 73.7% | 1–2 秒区间；目标向 1 秒靠近 |

目标是同等输入、同等完整语义、同等设备和同等结果要求下的端到端耗时下降。不得通过下列方式“达标”：

- 把全文件解析改成只解析尾部；
- 把真实 Query 改成文本正则或合成结果；
- 丢弃 Capture、减少 AST 节点或缩小输出范围；
- 关闭 TSH 规则、谓词、局部变量解析或错误恢复；
- 让视口外区域永久不更新，或把未完成结果冒充完整；
- 只计时 Native 的某个局部阶段，忽略文件准备、JNI、结果转换和应用层渲染；
- 降低视觉效果、破坏滚动、选择、输入、撤销/重做或增量高亮。

### 2.3 交互体验不回退

性能优化的最终对象是编辑器体验，而不是单次压测数字：

- 视口内可见行优先得到正确高亮，不能因为后台全文件工作阻塞 UI。
- 连续输入、删除、粘贴、撤销、重做时，不出现先清空旧颜色再等待新颜色的空白帧。
- 滚动、水平滚动、长行、折行、选区、光标、括号匹配、代码折叠和点击定位使用同一份准确坐标。
- 旧版本任务不得覆盖新版本结果；过期结果必须被拒绝。
- 对 60 Hz 屏幕，渲染主线程必须控制在约 16.7 ms 帧预算内；高刷新率设备按实际刷新周期验证。
- 后台完整解析可渐进完成，但可见视口和用户编辑操作不能被全量 Capture 物化拖慢。

## 3. 两个慢基准的真实执行链路与待查瓶颈

### 3.1 100 MiB 输入链路

当前 Native JNI 文档创建实现可见以下步骤：

1. Kotlin 提供 NativeTextWindowReader，通过 DirectByteBuffer 按窗口读取 UTF-16LE。
2. JNI 取得 Reader 的 read(JI, ByteBuffer): Int 方法。
3. Native 调用 createAnonymousBackingFile；当前实现只允许不超过 64 MiB 的输入使用 memfd，超过该阈值会直接创建应用缓存目录中的临时文件。
4. Native 循环调用 Java Reader，把完整源内容按 64 KiB 窗口传入 DirectByteBuffer，再写入 backing file。当前 100 MiB 基准超过 64 MiB memfd 阈值，因此走应用缓存文件路径；这不是原始文件的直接 mmap，而是先复制到 Native backing file。
5. Native 将 backing file 映射为 MappedFileSource，建立 PieceTreeSource。
6. 使用一个连续的 Tree-sitter Parser 状态机和 TSInputEncodingUTF16LE 对整个文档解析。
7. 对尾部视口执行 TSH 查询并输出 Capture。

因此 16.8 秒必须拆分计量，不能简单归因于 Tree-sitter Parser。至少要分别记录：

- backing file 创建耗时；
- Java Reader 回调次数、每次读取字节数和累计字节数；
- JNI 调用次数与累计传输字节数；
- memfd/file 写入耗时；
- mmap 创建耗时和首次触页耗时；
- PieceTreeSource 建立耗时；
- Tree-sitter 全文 parse 耗时；
- TextPartitionIndex 构建/查询耗时；
- TSH Query 耗时；
- Capture 结果写入 JNI 数组耗时。

**优先验证的性能假设（待测，不视为已确认根因）：**

- 64 KiB 传输窗口可能导致过多 Reader/JNI 调用和系统调用；
- 100 MiB 输入因当前 64 MiB memfd 阈值而使用应用缓存临时文件；Java Reader 通过约 1,600 次 64 KiB 窗口调用将数据复制到该文件，再 mmap。该复制/写入成本必须单独计时；优先评估直接 FD/原始文件映射或可持久化 Native TextSource，不能仅通过盲目提高 memfd 阈值来换取速度；
- 首次解析期间存在文件准备与解析串行阶段；
- 单一大 block comment 让 Lexer 扫描大量字符，但 AST 节点很少；应检查 Lexer 每字符成本、UTF-16 解码、注释扫描和输入回调开销；
- TextPartitionIndex 当前以每 256 行 checkpoint 为基础，查询时还需扫描局部窗口；需测量 checkpoint 密度和每次查询扫描字节数。

### 3.2 高密度 Java 链路

当前高密度压力 fixture 的核心操作是：

1. 生成 32,768 个合法 Java 局部变量声明，每个声明处于独立 block，避免重复变量名非法。
2. 将 UTF-16LE 文件分块写入。
3. 创建 Native 文档并完成完整 Tree-sitter parse。
4. 对整个文档调用 highlightNativeDocument(startByte=0, endByte=documentBytes)。
5. 将所有真实 Capture 输出到 Kotlin 数组；当前 JNI 结果行使用六个整数表示一条 span。
6. Kotlin 扫描数组，统计变量 style Capture 数量。
7. 再对尾部视口执行一次 Query，并确认尾部类型 Capture。

必须分别记录：

- Native 文档初始化/输入阶段；
- Parser parse；
- AST Selector 索引构建；
- ATS/Tree-sitter Query 执行；
- Predicate/Regex 处理；
- Match/Capture 收集；
- Native span 合并/排序；
- JNI 数组分配和复制；
- Kotlin 数组遍历与样式 ID 统计；
- 尾部 viewport Query；
- 总端到端耗时。

**优先验证的性能假设（待测）：**

- ATS 对每个节点逐条遍历 Selector/Rule 的成本是否仍接近 O(nodes × selectors)；
- Symbol/NodeType/Supertype 查找是否在热循环中重复字符串转换或 Hash 查找；
- AST 子节点、兄弟节点、祖先查询是否重复遍历同一树区域；
- Capture 是否先生成多份中间 vector，再排序、合并、转成 JNI 六整数数组；
- Capture 结果是否逐条创建 JVM 对象，或发生不必要的装箱/字符串复制；
- Query 是否重复执行相同 pattern，或没有共享预编译 predicate/selector dispatch；
- Kotlin 端是否对完整 Capture 数组进行多次扫描；
- 大量节点/捕获是否造成 allocator 高频分配和缓存局部性差。

在分项数据出来前，不得只凭架构推测认定某一个函数是唯一根因。

## 4. Native 大文本与 Piece Tree 当前实现评估

### 4.1 已存在的实现

当前 Native 侧已存在：

- TextSource 抽象及分段/连续/映射/文件窗口数据源；
- MappedFileSource：文件映射和按需页访问；
- FileWindowSource：固定窗口 pread() 读取；
- PieceTreeSource：UTF-16LE 持久化 Piece Tree；
- ChunkedTextStore：分块追加存储；
- TextPartitionIndex：稀疏行/字节 checkpoint；
- JNI TSInput read callback；
- Tree-sitter Parser 对 UTF-16LE 输入的连续状态解析；
- Tree edit 与增量 parse 的基础；
- 原始 buffer / append chunks / Snapshot clone 的共享所有权。

### 4.2 当前 PieceTreeSource 的实际结构

当前 PieceTreeSource 是持久化随机优先级 treap（二叉树），Node 记录：

- buffer 来源（Original / Added）；
- source offset；
- piece length；
- subtree bytes；
- subtree piece count；
- subtree height；
- priority；
- left/right shared_ptr。

编辑通过 split/merge 和 shallow path copy 复用未变化分支；追加内容以 64 KiB chunk 存储；clone 共享根节点和原始数据源。单次读取沿树按累计字节查找对应 Piece。

这已经比全文 String 拼接和数组搬移更合理，但它还不能被称为完整的极速多缓冲 B-tree/Rope 引擎。

### 4.3 必须继续改进的结构性问题

1. **二叉 treap 的节点访问成本。** 随机优先级树的平均高度通常较好，但仍需在大量编辑、碎片化和极端序列下测量高度与尾延迟；应比较高扇出 B+Tree/Rope。
2. **节点聚合指标不完整。** 当前 subtree 主要聚合 byte length、piece count、height；尚未形成统一的 UTF-16 units、UTF-8 bytes、line feeds 等多维摘要索引。
3. **Piece 合并和碎片控制。** 连续引用同一 backing buffer 且物理区间相邻的 Piece 应合并；需要避免编辑后 Piece 数持续增长。
4. **节点分配方式。** 每个 Node 使用 shared_ptr 控制块会增加分配、引用计数和指针追踪成本；应评估 slab/page allocator、intrusive refcount、节点内联和批量回收。
5. **树的扇出与缓存局部性。** 需要评估多路节点、连续 child/piece descriptor 数组、前缀累计长度数组，降低一次 offset 定位所需的指针跳转。
6. **多段连续读取。** 当前 TextSource read 返回当前 Piece 可提供的连续段；跨多个 Piece 的顺序读取可能产生更多回调和重复树定位。应提供稳定的批量读/窗口拼接接口，且只拼接当前有界窗口。
7. **批量编辑。** 多 Edit 应在一次事务中合并路径复制、减少重复 split/merge 和树遍历，保证旧快照仍然稳定。
8. **Snapshot 版本共享。** 快照应只复制被修改的路径和追加块，未修改节点/缓冲区继续共享；需要验证多快照长时间驻留时的引用计数和回收成本。
9. **读写并发。** 不可变 Snapshot 支持稳定并发读；可变 root 的编辑发布必须原子化，不能让 Native Parser 看到半完成版本。
10. **真实碎片基准。** 必须增加 1、100、1,000、10,000、100,000 Piece 的随机读、顺序读、编辑、clone 和多快照基准。

### 4.4 目标数据结构方向

评估并逐步演进为高扇出持久化 B+Tree / Rope：

- Leaf：引用 Original/Append/Mapped Buffer 的连续片段；避免按每行或每 Token 创建叶节点。
- Internal Node：连续存储多个 child，维护每个子树的累计 UTF-16 长度、UTF-8 字节数、UTF-16LE 字节数、LF 数量和 Piece 数。
- Search：使用前缀累计值 + 二分查找或小扇出线性扫描；降低随机指针追踪。
- Edit：路径复制，只复制受影响的叶和祖先；批量 Edit 合并路径。
- Coalesce：合并同 buffer 的相邻 Piece；设置叶容量和分裂/合并阈值。
- Allocate：Node/Leaf 使用 slab 或 page，减少 shared_ptr 控制块和小对象分配；公共快照使用明确引用生命周期。
- Read：顺序读取时沿 Leaf iterator 前进，不要每个窗口从根重新定位；随机读仍可 O(log N) 定位。
- Snapshot：持久化根版本共享；版本发布与 Native Tree revision 对齐。

这是一条需要基准证明的演进方向，不要求为了替换结构而重写所有代码。应保留现有 treap 作为对照实现，用相同测试比较吞吐、编辑延迟、内存、碎片和尾延迟后再决定迁移。

## 5. 多缓冲区与多树杈节点机制

必须区分以下对象，不得统一成一个模糊的 ChunkNode：

| 结构 | 所属层 | 作用 |
|---|---|---|
| Piece Tree / Rope 节点 | 文本存储 | 文本片段、累计坐标、编辑与快照共享 |
| Buffer Page / Window | 输入与存储 | 承载连续字节、双/三缓冲和预取 |
| Tree-sitter Subtree | 语法树 | 语法节点、子树共享、增量复用 |
| Parser StackVersion | 解析器 | GLR 冲突/错误恢复的状态分支 |
| TokenCache / Token Page | 词法与解析 | Token 临时缓存和批量分配 |
| Query Match/Capture Page | 查询结果 | 分页输出、批量 JNI 传输 |
| Regex Window State | Regex | 连续缓冲区、边界上下文和扫描状态 |

### 5.1 Buffer 双缓冲/三缓冲

- ACTIVE：当前被 Parser/Regex 读取；
- FILLING：下一窗口填充；
- READY：数据完成、等待消费者；
- FREE：可重新租借。

所有窗口必须携带 owner、document revision、base offset、encoding、valid length、EOF/error 状态和 Lease。ACTIVE 窗口不得被覆盖。预取线程只能准备数据，不能推进同一个 Parser 的语法状态。

窗口大小应做 64/128/256/512 KiB 等级的基准，不固定认定 64 KiB 最优。对顺序扫描增加 readahead；对随机视口读保留小窗口路径。

### 5.2 Token 与 Subtree

Tree-sitter 自身已有 Subtree、SubtreePool、inline subtree、引用计数和 ReusableNode 等机制。本项目不应再建立一棵与 Tree-sitter 脱节的重复语法树。

当前项目尚未证明已经实现完整的自有 Token Page/多级 Token 缓存。先通过 profiling 观察 Token 分配、Subtree 分配、栈分支数量、复用率和缓存命中率，再决定扩展：

- Token 热字段紧凑化；
- 临时 Token/Match/Capture 使用 arena/slab；
- 小对象 inline；
- 批量构造/释放；
- 复用必须检查 parser state、lex mode、external scanner state 和位置；
- 结果逃逸到公共 API 前必须转换为稳定生命周期的对象或 Lease。

### 5.3 不得把文本块当作独立语法文档

同一个 Tree-sitter Parser 的语法状态必须跨输入窗口连续推进。不得把每个文本块独立解析后简单拼接 AST。多 Parser 并行仅适用于能够证明语法边界和状态传递语义的特定语言/任务；不能作为通用默认方案。

## 6. UTF 编码、文本对象与 ATS 坐标体系（必须彻底治理）

编码和坐标不应依赖运行时反复扫描全文或临时创建 String/ByteArray。当前 Native 大文本输入以 UTF-16LE 为主，Tree-sitter 的 byte offset 与公共 Kotlin 编辑器坐标不是同一个单位。

### 6.1 必须明确的坐标域

| 坐标域 | 单位 | 主要使用者 |
|---|---|---|
| UTF-16 code unit offset | 16-bit code unit | Android/Kotlin EditorDocument、String/CharSequence |
| UTF-16LE byte offset | bytes | 当前 Native PieceTreeSource、Tree-sitter UTF16LE TSInput |
| UTF-8 byte offset | bytes | UTF-8 文件、Query/Regex pattern/source、部分 grammar/外部接口 |
| Unicode code point offset | scalar values | Unicode 语义处理 |
| Grapheme cluster offset | 用户感知字符 | 光标移动、选择、删除等编辑器交互 |
| Tree-sitter Point column | 当前 TSInput encoding 的字节列 | Tree-sitter parser/query 范围 |
| Line offset | line index + line-local offset | 行布局、视口和渲染 |

不能将 UTF-16 code unit、UTF-8 byte、UTF-16LE byte、Code Point 和 Grapheme offset 互相直接相加或比较。

### 6.2 统一转换规则

- 每个 TextSnapshot 必须声明 encoding、revision 和坐标能力。
- 公共编辑器 API 以 UTF-16 code unit 为宿主坐标；Native Tree-sitter 范围必须携带输入编码身份。
- UTF-16LE byte offset 与 UTF-16 code unit offset 的转换必须检查偶数边界和代理对边界。
- UTF-8 byte offset 转 UTF-16 offset 不能通过简单乘除实现；必须依赖每个 Leaf 的转换摘要/checkpoint。
- 跨 Leaf 的 UTF-8 多字节序列和 UTF-16 surrogate pair 必须按逻辑文本连续处理，不能在窗口边界截断。
- CRLF 必须作为两个 code unit/byte 序列处理，同时在行索引里保持统一行语义。
- 非法 UTF-8/UTF-16 输入要有明确拒绝或替换策略；不得静默产生错位。
- 32-bit Tree-sitter byte offset 边界必须在 JNI 入口显式检查；上层 Long 坐标不能被静默截断。
- Query source 的 UTF-8 byte offset 与被查询文档的 UTF-16LE byte offset 是不同坐标域，诊断和 Capture 不得混用。

### 6.3 需要实现的编码索引

每个 Text Leaf 应维护可组合摘要：

- UTF-16 code unit count；
- UTF-16LE byte count；
- UTF-8 byte count；
- LF count；
- 可选 Code Point count；
- 首尾编码状态/边界信息，用于跨 Leaf 校验；
- UTF-8/UTF-16 转换 checkpoint（按字节或字符间隔）；
- 行首位置摘要。

Grapheme cluster 具有跨 Leaf 上下文状态，不能简单把各叶计数相加；应使用边界状态合并或在光标附近做有界扫描。

### 6.4 ATS 与文本对象性能

ATS/AST Selector 的热点不得在每个节点反复：

- 将 node type 转成 Kotlin/Native String；
- 重复查找相同 Symbol 名；
- 重新解析 Selector 字符串；
- 从文档开头扫描 UTF-8/UTF-16 偏移；
- 为每个 Capture 构造临时字符串/对象。

应在 Grammar/Query 编译阶段预解析 Symbol、Field、Supertype、Predicate、Selector flags，生成稳定的整数 dispatch 表；节点执行阶段只使用整数 ID、位标志和连续数组。公开结果边界再按需解析名称。

## 7. ATS / Tree-sitter Query 性能与统一执行

### 7.1 当前基础

- Tree-sitter Query Compiler/Executor Adapter 已有源码；
- Query Cursor、Match、Capture、Predicate Handler 和 ByteRange 适配已有基础；
- Native Query / Tree / Language Lease 有基础；
- ATS AST Selector 已有节点类型、字段、祖先、兄弟和 supertype 相关逻辑；
- supertype symbols 已有预索引与 Native 元数据暴露；
- ATS 与 Tree-sitter Query 作为两个输入前端，目标是复用同一 Tree-sitter Query 执行体系；
- Regex 保持独立。

### 7.2 仍未完成

- ATS Lexer、Parser、AST、完整 Compiler/Lowering 和 Schema 驱动语义尚未形成完整生产闭环；
- strict Node Schema Provider 尚未完成；
- 新公共 Kotlin Query API 与现有生产 TSH 高亮执行路径仍处于迁移阶段；
- Query 结果与统一 MatchSet 的完整接线尚未完成；
- Native step budget 不能因 Kotlin 字段存在就声称已执行；
- 旧版 Tree-sitter 不支持 0.28 progress callback 时，只能在 Native 查询返回后检查取消，不能声称可中断正在运行的查询。

### 7.3 性能改造

1. 编译期将 Selector 按 node symbol、supertype、field、named/anonymous、flags 分桶。
2. 对相同 node symbol 的规则生成紧凑 dispatch table，避免每个节点扫描全部规则。
3. 预计算祖先/兄弟约束的必要元数据；共享同一节点访问结果，避免重复遍历。
4. Query Pattern/Predicate 在编译阶段完成解析和可缓存的预计算。
5. Query Cursor 执行时直接使用 Native node/symbol ID，不在热循环中创建字符串。
6. Capture 结果采用紧凑连续结构和分页/批量接口，减少多份 vector 和 JNI 数组复制。
7. 对可见 viewport 执行范围查询；完整文档 Query 仍须保留完整结果语义。
8. 为 ATS 建立节点访问次数、selector 候选数、实际匹配数、supertype 查找次数、capture 数和索引内存基准。

## 8. Regex / Oniguruma 独立执行管线

### 8.1 已有基础

- Regex 与 Tree-sitter Query 分离；
- Oniguruma 使用连续输入指针；
- 有限宽度 Regex 存在窗口执行和 overlap 处理；
- 对上下文依赖/无界规则，当前实现会使用完整连续视图；
- MappedFileSource 可为完整文件提供 mmap 视图；
- PieceTreeSource 在原始单 Piece 情况下可直接暴露 backing view；
- 编辑后的多 Piece 文档可建立 mmap Snapshot，避免 Native Heap 中再复制一个完整大 String。

### 8.2 关键未完成问题

- 编辑后 mmap Snapshot 可能需要全量重建，单次编辑成本与文件大小相关；
- Regex WindowLease 和边界上下文状态尚未形成完整统一生命周期；
- 任意长度匹配、回溯、锚点、前后文和 BLOCK 状态不能简单靠固定 overlap 解决；
- Regex State Checkpoint 的增量更新和 dirty range 传播尚未完成；
- Regex 的结果需要进入统一 MatchSet，但不能因此引入第三条执行管线。

### 8.3 目标

- 有限最大宽度模式采用可证明语义的窗口 + overlap；
- 无界/上下文模式采用可证明的完整视图或明确状态机，不得错误截断；
- 编辑时只重建受影响映射块/索引，探索分块临时文件、持久化 Snapshot 和按块重映射；
- 为每个 Regex Pattern 记录宽度、上下文、锚点、回溯风险和窗口能力；
- 记录每个窗口读取字节、匹配候选、最终 Match、回溯耗时和峰值连续工作区。

## 9. JNI / JVM / Kotlin 边界

### 9.1 已有基础

- JNI 大文本 Native Document Handle；
- DirectByteBuffer 读取窗口；
- UTF-16LE Native PieceTreeSource；
- ScopedStringChars 用于避免额外 Native UTF-16 全文 vector copy；
- Capture/Span 通过批量数组输出；
- Native 错误转换为 Java 异常；
- Query/Tree/Language Lease 与 Registry 已有部分统一实现；
- Kotlin TSH SyntaxHighlighter 使用 viewport range、Capture range cache、编辑队列和 Native 增量 edit。

### 9.2 必须审计与优化

- 每个 JNI 方法的 Java/Kotlin 声明、C++ 符号、参数、返回值、异常和 Handle 所有权；
- 每次 JNI 调用的次数、复制字节数、分配次数和耗时；
- String/CharSequence 到 UTF-16LE/UTF-8 的编码路径是否重复；
- GetStringChars 是否被误称为绝对 zero-copy（JVM 仍可选择复制）；
- 大结果是否经过 Native vector → Java primitive array → Kotlin collection 多次物化；
- Native Handle 是否含 generation/type/owner/closed 状态；
- Close 与并发 Parse/Query/Edit 是否存在 use-after-free；
- Native close 是否在 Registry 锁内执行；
- API 21–35 与各 ABI 的偏移宽度、对齐和编码行为。

目标是批量、稳定、少复制，不是将所有内容一次性物化到 JVM。

## 10. 高亮渲染、增量更新与闪烁

### 10.1 已有基础

- Native Capture/Span 生成；
- Viewport range 查询；
- Kotlin CaptureRangeIndex 和缓存；
- Spannable Renderer Provider；
- TSH-owned Span 精确清理；
- RenderPlan Revision、Receipt、Generation staging/rollback；
- Edit queue、Native Tree edit、Dirty range 和缓存版本基础；
- Canvas 绘制与 Span 应用已区分，不再把 Span applied 等同于真正 drawn。

### 10.2 仍未完成

- API 30/35 真实 EditorView 测试仍有失败；
- 100 MiB 真实 EditorView Canvas 颜色可见性尚未完成设备验收；
- PR #12 的依赖范围和 Cache Rebase 尚未形成完整 CI 证据；
- 旧高亮与新高亮之间的无闪烁原子切换仍需真实连续编辑验证；
- Canvas/Compose/custom View Renderer 的完整生产 Provider 尚未完成。

### 10.3 无闪烁事务要求

1. 每次编辑产生单调递增 Document Revision。
2. 保留旧版本有效高亮，直到新版本相应区域结果准备完成。
3. 对 Query、ATS、Regex、AST Selector 依赖范围进行失效分析。
4. 只将新结果写入 staging generation。
5. 仅当 Document/Tree/Query/Theme/Viewport Revision 全部匹配时原子提交。
6. 过期结果丢弃；新任务失败时保留旧结果并报告状态。
7. 局部编辑不得无理由清空整份 Capture Cache。
8. 任何被保留的旧 Capture 必须经过依赖正确性证明；无法证明时保守重算，不能显示错误颜色。

## 11. 内存策略：从“低预算限流”转为“数据结构效率与真实压力治理”

### 11.1 当前值得审计的固定容量

当前源码中可见多个固定容量或默认限制，例如：

- Native Highlight Cache 每文档 4 MiB；
- 进程共享 Native Highlight Cache 预算 32 MiB；
- Kotlin Capture Cache 32 MiB；
- QueryExecutionBudget 的默认结果字节上限 32 MiB；
- PieceTreeSource 的默认 append allocation limit 64 MiB（该值限制 Added chunks 的追加分配，不等于原始 mmap 文档大小或整个 Native 文档内存上限；仍需评估其是否错误限制正常编辑）；
- TshMemoryBudget 的默认 target/hard limit 配置。

这些参数不应被直接视为合理的最终生产限制。必须逐项说明它们限制的是“可回收缓存”“新增编辑块”“结果输出”还是“真实 Native 对象”，并检查是否导致缓存持续失效、结果被拒绝或高密度文档吞吐下降。

### 11.2 新的内存原则

- 不为正常全量语法树、合法 Capture 或正常编辑数据设置不合理的小额度。
- 缓存容量根据真实设备可用内存、命中率、文档数量和对象大小自适应；内存压力时优先淘汰可重建数据。
- 大型不可变文本和 Snapshot 优先共享 backing store，不复制。
- 使用 slab/arena/page、inline、SoA、持久化节点共享，降低 allocator 和碎片成本。
- 对 Native Heap、JVM Heap、mmap resident pages 和临时文件分别观测，不将 mmap 虚拟大小误算为全部常驻内存。
- 发生系统压力时先停止预取、淘汰 LRU 派生缓存、减少并发后台任务；不能静默截断完整 Query 结果。
- 必须保留 OOM/分配失败处理，但失败应明确报告 PARTIAL/FAILED，不能伪装成完整成功。

## 12. 性能改造路线（按依赖顺序）

### P0 — 建立可解释的性能基线

- [ ] 为 100 MiB 稀疏低 AST 密度测试增加分阶段计时和 Native 计数。
- [ ] 为 32,768 Java 声明测试增加分阶段计时和 Native 计数。
- [ ] 统计 Reader callback、JNI 次数、读取字节、文件写入、mmap、Parse、Query、Capture、数组输出、Kotlin 遍历时间。
- [ ] 记录节点数、Token 数（可从 Parser instrumentation 统计）、Capture 数、ATS Selector 候选数、缓存命中率和分配次数。
- [ ] 记录 CPU 时间、wall time、Native Heap、JVM Heap、PSS、mmap RSS、GC 次数和线程阻塞。
- [ ] 每个场景至少重复 10 次，报告 median、p90/p95、min/max；区分冷启动与热缓存。
- [ ] 所有性能测试固定设备、API、ABI、编译类型、温控/电量状态和测试输入 hash。

**退出条件：** 两个慢基准的耗时能够分解到具体阶段，能用数据确定主要成本，不再依赖猜测。

### P1 — Native 输入与 JNI 传输提速

- [ ] 为真实文件输入增加 Native FD/Descriptor-backed Source 路径，避免 Java Reader → Native → backing file 的重复拷贝；路径权限和 FD 生命周期由明确 Lease 管理。
- [ ] 对 Piece Tree 宿主输入提供批量/快照窗口读取接口，避免每 64 KiB 一次 JNI 往返。
- [ ] 比较 64/128/256/512 KiB 窗口、顺序预取、批量读取和 mmap 路径。
- [ ] 对 DirectByteBuffer 使用固定容量池和稳定 Lease，减少反复分配。
- [ ] 在同一解析语义下比较 FileWindowSource、MappedFileSource、Native FD source。
- [ ] 确认创建文档中的 Reader/写入、mmap/PieceTree setup、Parser parse 时间拆分；已在性能分支加入 Native debug timing 和 Kotlin stage timing，待 API 26/30/35 实测。

**退出条件：** 100 MiB 输入与 parse 分项数据显著下降，且内存、正确性和设备稳定性没有回退。

### P2 — Piece Tree / 多缓冲 / 多路节点

- [ ] 对当前 treap 做 piece 数、树高、节点分配、路径复制、读路径深度和碎片基准。
- [ ] 实现/评估高扇出持久化 B+Tree/Rope 对照版本。
- [ ] 增加 UTF-16、UTF-8、LF、byte length 等 subtree metrics。
- [ ] 实现顺序 Leaf Iterator 和跨 Piece 有界批量读取。
- [ ] 实现 Piece coalescing、批量 Edit 和 Snapshot root sharing。
- [ ] 评估 Node slab/page allocator、inline Piece descriptors、intrusive reference counting。
- [ ] 进行 1–100,000 Piece 的随机/顺序读、编辑、clone、并发快照基准。
- [ ] 对比 treap 与 B+Tree 的 p50/p95/p99，不以理论复杂度替代真实设备数据。

**退出条件：** 大文件和高度碎片化文档的 offset 查找、顺序扫描和编辑尾延迟稳定；快照不复制全文。

### P3 — UTF 编码和坐标索引统一

- [ ] 实现每个 Leaf 的 UTF-8/UTF-16/byte/LF 聚合指标。
- [ ] 实现 UTF-8 ↔ UTF-16 的 checkpoint 转换，不允许每次从文档起点扫描。
- [ ] 统一 Tree-sitter ByteRange 与 Editor UTF-16 Range 的显式转换 API。
- [ ] 完成 surrogate、跨 Leaf 多字节 UTF-8、CRLF、Tab、超长行、非法编码测试。
- [ ] ATS Symbol/Field/NodeType 在编译阶段解析为整数 ID，热循环不创建字符串。
- [ ] 对所有 Kotlin/Java/C++ 坐标接口进行 ABI 和溢出检查。

**退出条件：** 编码转换复杂度由局部叶索引决定，而不是全文长度；所有跨块边界测试通过。

### P4 — Tree-sitter Parser / Token / Subtree

- [ ] 为 Lexer、TokenCache、ReusableNode、SubtreePool、StackVersion 加入低开销计数。
- [ ] 测量 32,768 声明、更多节点、高嵌套、超长行和语法错误恢复。
- [ ] 对上游 Subtree inline/pool/reuse 热点做定向优化；优先贡献或维护最小补丁。
- [ ] 仅在 profiling 证明必要时实现 Token Page/Slab，避免重复存储每个 Token。
- [ ] 对单字符插入、删除、粘贴、撤销/重做验证旧树复用和解析尾延迟。
- [ ] 保证一个逻辑 Parser 跨窗口连续解析，不按块重置语法状态。

**退出条件：** 高密度真实 Java 解析阶段达到目标吞吐，语法树与节点范围完全一致。

### P5 — ATS / Query / Capture

- [ ] 将 ATS Selector 执行改为 Symbol/Field/Supertype dispatch，减少全规则扫描。
- [ ] 复用 AST 节点、祖先、兄弟和字段访问结果，避免重复遍历。
- [ ] Query Cursor 输出紧凑 Capture Page，降低 vector 和 JNI 数组中间拷贝。
- [ ] 对大结果提供分页/流式消费；页之间有 document/query/tree revision 和 completeness。
- [ ] 对 Kotlin 全量 Capture 处理减少重复遍历和对象装箱。
- [ ] 保留全文件 Query 的完整语义；viewport 查询单独优化。
- [ ] 补齐 ATS predicate、缺失 capture、OR pattern、兄弟节点、supertype、深层嵌套回归。
- [ ] 统一公共 Kotlin API 与生产高亮执行器，不绕过 Native Lease。

**退出条件：** 32,768 节点完整 Query/Capture 端到端达到 1–2 秒目标，Capture 数和范围与基线完全一致。

### P6 — Regex Window / Oniguruma

- [ ] 建立 Regex Pattern capability analysis（有限宽度/无界/锚点/前后文/回溯）。
- [ ] 完成 WindowLease、窗口上下文、跨窗口匹配和状态 checkpoint。
- [ ] 避免编辑后每次全量重建 mmap Snapshot；研究持久化映射块和增量更新。
- [ ] 对无界模式保留完整语义，不用固定 overlap 伪造正确性。
- [ ] 对 Regex 与 AST/Query 结果做统一 MatchSet 合并。

**退出条件：** Regex 大文本扫描与编辑增量均有稳定吞吐和语义等价测试。

### P7 — Renderer / 无闪烁 / 应用体验

- [ ] 完成 API 26/30/35 真实 EditorView Canvas/Spannable 绘制回归。
- [ ] 验证 100/200/300 MiB 视口颜色正确、快速滚动和水平滚动。
- [ ] 完成 Generation staging + atomic commit，旧结果不会先被清空。
- [ ] 对输入、删除、粘贴、撤销、重做、连续快速编辑做闪烁和错色检测。
- [ ] 统计 UI 主线程帧耗时、Span 数量、绘制次数和后台队列长度。
- [ ] 验证选择、光标、手柄拖动、括号匹配、折叠、字体/主题切换不受影响。

**退出条件：** 高亮颜色完整、无旧结果污染、无可见空白闪烁，编辑器交互和滚动体验不回退。

## 13. 完整验收矩阵

### 13.1 文档规模

| 文档大小 | 稀疏语法 | 密集语法 | 多 Piece 编辑后 | 全量 Query | Viewport Render |
|---:|---|---|---|---|---|
| 1 MiB | 必测 | 必测 | 必测 | 必测 | 必测 |
| 10 MiB | 必测 | 必测 | 必测 | 必测 | 必测 |
| 50 MiB | 必测 | 必测 | 必测 | 必测 | 必测 |
| 100 MiB | 必测 | 必测 | 必测 | 必测 | 必测 |
| 200 MiB | 必测 | 必测 | 必测 | 必测 | 必测 |
| 300 MiB | 必测 | 必测 | 必测 | 必测 | 必测 |

另加 500 MiB+ 可选扩展测试，结果按设备资源报告，不以单一低预算提前终止。

### 13.2 语法与编码维度

- 低 AST 密度的超长注释/字符串；
- 高密度变量、函数、类、字段和表达式；
- 深层嵌套和大量兄弟节点；
- 超长单行、空行、CRLF/LF 混合；
- ASCII、中文、Emoji、补充平面字符和组合字符；
- UTF-8、UTF-16LE、UTF-16BE（若声明支持）；
- 文件尾部随机视口、远端随机 offset、频繁跳转；
- 大量编辑造成 Piece fragmentation；
- 多文档并发、多个 Snapshot 同时存活。

### 13.3 Android 设备维度

至少验证 API 26、30、35；记录 ABI（arm64-v8a 等）、设备型号、RAM、Android build、NDK、构建类型和 Tree-sitter commit。Host Native 测试、JVM 单测、Android Instrumentation 和真实 UI 像素验证分别记录，不得互相替代。

### 13.4 质量门槛

- 解析树节点数、节点类型、范围、父子/兄弟关系与标准 Tree-sitter 结果一致；
- Query Capture 数、Capture 名称、范围、pattern index 与基准一致；
- ATS 与原生 Tree-sitter Query 在对应语义子集上结果一致；
- Regex 跨窗口结果与连续输入结果一致；
- UTF 坐标往返转换不丢字符、不跨 surrogate；
- 视口内颜色与 .tsh style catalog 对应，像素可见；
- 旧版本结果不覆盖新 revision；
- 内存压力不导致崩溃、Use-after-free、无界队列或静默丢结果；
- 单字符输入/删除、粘贴、撤销、重做不产生持续闪烁。

## 14. 当前 PR / CI 证据快照

截至 2026-11-19，本轮相关 PR 状态如下：

| PR | 分支 | 当前状态 | 最新证据/阻塞 |
|---|---|---|---|
| #12 | fix/tsh-incremental-highlight-flicker | Open / Draft | 已有缓存依赖范围和 Host Cache Rebase 代码；CTest 命令修正后最新 head 尚无新 CI |
| #15 | fix/tsh-ast-type-index-ndk24 | Open / Draft | API 26 曾通过；API 30 EditorView 手势/Canvas 断言失败，测试 fixture 加载顺序已修正，最新 head 尚无新 CI |
| #16 | feat/tsh-engine-memory-admission | Open / Draft | Query 编译集成测试失败定位到编译断言；最小 Java Grammar Query 和诊断已更新，最新 head 尚无新 CI |
| #17 | test/tsh-high-density-large-file-stress | Open / Draft | 最新已知 Run #44017966854：Release APK、Local Tests、API 26 通过；API 30/35 Instrumentation 失败 |
| #19 | fix/ci-emulator-settings-service-crash | Open / Draft | Bash 启动脚本修正已提交；最新 head 尚无新 CI |

这些 PR 都没有因为本路线图而自动合并。CI 证据只对对应 head SHA 和测试范围有效。新提交必须重新运行构建和设备矩阵。

## 15. 立即执行顺序

1. 建立 P0 性能分项计时，不再仅有 16.8 秒和 7.6 秒两个总数。
2. 先解决 100 MiB 初始化路径的文件复制、Reader 回调、mmap、Parser 时间占比。
3. 先解决 32,768 节点路径的 Parser、ATS/Query、Capture 输出、Kotlin 处理时间占比。
4. 根据 profile 优先优化最热路径；同时建立 treap 与 B+Tree 对照基准。
5. 完成 UTF-8/UTF-16/UTF-16LE 多坐标索引，避免 ATS/文本对象在热循环反复转换。
6. 接通统一 Kotlin API 与真实 Native 高亮生产路径，完成 Query/Regex/Match/Renderer 事务。
7. 修复 PR #12/#15/#16/#19 的构建与设备失败；PR #17 的 API 30/35 必须通过。
8. 完成 100/200/300 MiB 稀疏、密集、编辑后碎片化文档验收。

## 16. 结论

TSH 已有可继续演进的底层基础，但当前最突出的问题已经不是“能不能读取大文件”，而是**读取、解析、索引、查询、结果输出和渲染的全链路单位工作成本仍然过高**。

100 MiB 约 16.8 秒和 32,768 个真实声明约 7.6 秒都不满足目标。后续不应继续靠低缓存预算、少返回结果或只处理视口来掩盖成本。必须通过分阶段 profiling，改进 Native 输入、Piece Tree 多路节点、坐标聚合索引、Tree-sitter Subtree/Token 分配、ATS dispatch、Query Capture 批量输出和 Regex 增量窗口来降低真实工作量。

最终验收不是某个单测通过，而是：**在 100–300 MiB 文件上，完整语义、正确视口、准确颜色、连续编辑和稳定内存同时成立，并达到本文件的性能目标。**


## 17. Runtime memory-pressure admission update (2026-11-23)

### Implemented in the current integration branch

- `TshMemoryBudget.updateAvailableMemory(availableMemoryBytes)` allows the host to refresh the available-memory sample without recreating the Engine-owned reservation ledger.
- The active admission limit is recomputed as the minimum of the configured target, configured hard limit, and the sampled available-memory fraction. A null sample restores the configured baseline.
- Lowering the active limit does not invalidate, release, or silently truncate existing reservations. New reservations and reservation growth are rejected while retained allocations exceed the refreshed limit; existing owners can still shrink or release their reservations.
- The budget remains bounded by the configured absolute 512 MiB hard limit.
- Unit coverage now exercises pressure-driven limit reduction, over-limit retained allocations, shrink recovery, baseline restoration, invalid samples, and closed-budget behavior.

### Integration contract and remaining work

- The host is responsible for sampling memory at meaningful lifecycle points (for example, Android memory-pressure callbacks and before expensive background work) and calling the update API. The ledger does not independently poll the operating system.
- The sample is an admission signal, not a measurement of Native Heap, JVM Heap, mmap RSS, or total process PSS. Those metrics must remain separately observable.
- Runtime limit updates do not make Tree-sitter internal allocations fully observable; Native syntax-tree reservations remain host estimates.
- Workflow run `45151763603` completed successfully for head `559044e49d7de32243304df2a0a7513587728d67`: Release APK and Local tests passed; Android device tests were skipped by the current workflow configuration. This validates the runtime-pressure implementation and its tests, but does not provide API 26/30/35 device-runtime evidence.
- Exact Native Query Step Budget remains unimplemented. The Tree-sitter core is pinned as the `tree-sitter-lib` submodule at `62816e1c0d0a4ca9553174f3cbe5b9631047f7f4`; the project JNI wrapper alone cannot expose exact VM work units. A core patch must add a monotonic 64-bit work counter and a stable budget-exceeded result, then update the submodule pointer and synchronize the C API, JNI, Java, Kotlin, ABI and regression tests. Progress-callback frequency must not be used as a substitute for work units.


## 18. Follow-up validation and Regex exact-limit correction (2026-11-23)

### Verified CI evidence

- Workflow: [45151763603](https://github.com/msmt2018/Android-Windbell-TreeSitter/actions/runs/45151763603)
- Validated head: `559044e49d7de32243304df2a0a7513587728d67`
- `Build Release APK`: success.
- `Run local tests`: success, including native large-file storage tests (ASan/UBSan), host JNI build, native TSH cache tests and Debug tests.
- Android device tests: skipped. This run is not device validation.

### Regex exact-match-limit bug fixed after that CI head

The full-window executor previously marked the result `TRUNCATED` immediately when the accepted match count reached `maxMatches` before the final core. That bypassed the existing one-match non-admitting probe on subsequent cores and could misclassify an exactly-full but complete result set.

The executor now continues to the next core when the accepted limit is reached before the final core. The next iteration probes for one additional match without admitting it; only an actual additional match or a native truncation signal establishes `TRUNCATED`. The final core can still finish normally without a false truncation status.

Regression coverage was added for the decision to probe non-final cores. The fix commits are:

- `4b8ca787b5c24043329361616eb7a085b78d2db1` — executor correction.
- `6be9bd14775d4330776e65177487621daed4d96b` — regression test.

These commits are newer than the successful CI head above; their CI result must be checked separately before treating the fix as validated.


## 19. Exact Native Query VM work-unit budget

The previous adapter rejected `TshQueryExecutionBudget.maxSteps` because Tree-sitter's 0.28 progress callback reports a byte offset, not an exact work count. This is now implemented at the Query VM boundary.

- The pinned Tree-sitter `query.c` cursor gains a monotonic 64-bit `total_work_count`, a per-execution limit, and an explicit `did_exceed_step_limit` state.
- One work unit is defined by Tree-sitter's existing traversal accounting: one base VM traversal iteration plus one unit for each active in-progress query state (`1 + states.size`). This is an exact count of the declared VM work metric, not a count of AST nodes, matches, captures, CPU instructions, or elapsed time.
- The counter is monotonic across repeated `nextMatch()` calls and is reset only by a new cursor `exec()`. It saturates at Java `Long.MAX_VALUE` for unbounded executions.
- When the next VM iteration would exceed the caller limit, the cursor halts before performing that iteration, releases unfinished states through the existing halt path, and preserves already-finished matches. The result is returned as incomplete with diagnostic `TSH-QUERY-EXEC-036`; `stepsUsed` reports completed work units.
- JNI and Java expose `setStepLimit`, `getStepsUsed`, and `didExceedStepLimit`. The TSH executor installs the limit after `exec()` and before the first match retrieval.
- When all expected Query VM source anchors are present, the patch is applied to a private CMake build overlay of the Tree-sitter amalgamation; the Git submodule working tree is not modified. CMake tracks the upstream source files as configure dependencies. Older/incompatible Tree-sitter source layouts keep the original amalgamation, report exact counting as unavailable, and reject maxSteps with the stable unsupported diagnostic rather than breaking ordinary query builds.
- This does not change the dual query pipeline, does not impose a default result ceiling, and does not reinterpret match/capture limits as VM steps.

Regression coverage exercises an actual Java grammar query with a one-work-unit budget and checks partial-result status, exact `stepsUsed`, and the stable diagnostic. CI validation is required before treating the implementation as complete.

## 2026-11-24 Source-only defect repair status

The following changes are committed to `integration/all-open-prs`. No tests, builds, CI jobs, or device workflows were executed during this repair pass.

| Defect | Source change | Status |
|---|---|---|
| Native `any-eq?` / `any-not-eq?` with a missing OR-branch capture | Retained the missing-capture polarity implementation; corrected the regression query so the OR expression and predicate belong to one top-level pattern; the compiler rejects detached predicate-only top-level patterns instead of silently running the OR without its filter. | SOURCE_PATCHED; validation deferred |
| ATS `previousSibling` + `nextSibling` together | Resolve the current node and both adjacent siblings through one parent child-index walk; preserve symbol/type matching and type-only selectors. | SOURCE_PATCHED; validation deferred |
| ATS supertype sibling selectors | Added regression source coverage for concrete `decimal_integer_literal` operands and transitive `expression` supertype operands. | TEST_SOURCE_ADDED; not executed |
| Detached predicate regression | Added a regression case requiring malformed standalone predicate patterns to be rejected during native query compilation. | TEST_SOURCE_ADDED; not executed |
| Stale Kotlin display spans after edits | Removed coordinate rebasing of presentation-only spans; invalidate the document's display cache synchronously with the edit epoch, and serialize renderer cache reads with edit admission. | SOURCE_PATCHED; validation deferred |
| Sparse line-index growth and edit-boundary checkpoints | Bound retained checkpoint payload to 2 MiB, bound transactional index rebuild capacity, and discard the old suffix boundary checkpoint unless the replacement/deletion leaves a real line boundary. | SOURCE_PATCHED; validation deferred |

### Source-audit notes

- Tree-sitter's query cursor keeps branch-local capture lists when it forks query states. The missing-capture case must therefore be evaluated against the captures of the returned match, while `pattern_index` selects the predicate program for its top-level pattern.
- The earlier failed equality fixture placed the OR expression and predicate in separate top-level query expressions. That allowed the OR pattern to run without the predicate. The corrected fixture groups them in one expression.
- Parser cancellation/reuse already has a per-parse round guard: cancellation state is cleared at begin/end, and a cancelled/null parse resets the native parser before reuse. No additional source change was justified by this audit.
- Query execution already reports incomplete results when the native cursor exceeds match/step limits or the result collector truncates; such results are not marked complete.
- Parser cancellation/reuse already clears cancellation state at parse-round boundaries and resets Tree-sitter after cancelled/null parse results.
- Kotlin display spans are presentation-only and do not carry query dependency envelopes; they are no longer coordinate-rebased across edits. Native AST selector cache dependencies remain document-wide until candidate dependency envelopes can be proven safe, so this pass prioritizes correctness over edit-cache reuse.
- API 30 `device offline` remains an environment failure, not evidence of a source defect. XML/DTD and AIDL language-selection defects are outside the current branch's packaged TSH language set and were not changed in this pass.

**Validation gate:** all test, build, CI, and device validation remains intentionally deferred until the outstanding source-level bug backlog is closed. Source presence and regression-test source do not constitute a passing result.


### 2026-11-24 Continued source-only repair — UTF-16LE TextSource contract

Additional commits after the previous status snapshot:

- `d59f3ef88bbcd087eaaec1c51719951b374d2c3e` — `ContiguousTextSource` now records validity and rejects odd byte lengths or a null backing pointer for non-empty input.
- `c71d4c8f168cf89b0a70a16ae8bcd412f8d21f59` — invalid contiguous sources cannot masquerade as successful EOF reads; reads enforce UTF-16 code-unit alignment and valid backing storage.
- `d8ff82b408a1df94f867bb117a969d03671202dc` — shared range-copy, surrogate-boundary, and Tree-sitter input-chunk helpers reject odd-sized UTF-16LE documents.
- `60fc2f68126a209f981a5fd69f216179c559f40b` — segmented-source EOF is a successful empty read independent of requested chunk capacity, matching contiguous-source semantics.
- `1c6bee6963676f925050eddab1cd13e78f2ecee3` — PieceTree and its append-arena reader use the same EOF semantics.

Static call-site review found the native document parser already separately tracks `read_failed` and rejects failed/incomplete parse candidates before publishing them. The regular string parser also checks its UTF-16LE byte length before constructing its contiguous source. No test or build was run for these changes.

| Defect | Source change | Status |
|---|---|---|
| Contiguous source accepted odd UTF-16LE byte lengths | Added explicit validity state and consistent read/contiguous-view rejection. | SOURCE_PATCHED; validation deferred |
| Null backing pointer on non-empty contiguous input could appear as EOF at document end | Invalid source state now rejects reads, including EOF access. | SOURCE_PATCHED; validation deferred |
| Shared range/input helpers did not enforce whole-document UTF-16LE alignment | Added document-size parity guards at common helper boundaries. | SOURCE_PATCHED; validation deferred |
| Segmented/PieceTree EOF behavior differed from contiguous source when maxBytes was zero or one | Normalize EOF as successful zero-byte read before checking requested capacity. | SOURCE_PATCHED; validation deferred |

## 2026-11-24 Native TextSource 有效性契约修复（源码已提交，验证按要求暂缓）

本轮静态审查发现并修复一个跨数据源生命周期缺陷：原有 `PieceTreeSource` 构造函数只检查底层 `TextSource` 指针和字节长度，没有检查底层数据源是否处于可读取状态。因而未封存的 `ChunkedTextStore`、未打开的 `FileWindowSource` / `MappedFileSource`，以及无效的 `SegmentedTextSource` 可能被 PieceTree 接纳，直到后续读取才失败。

已提交的源码调整：
- `TextSource` 增加默认兼容的 `isValid() const noexcept` 契约；已有外部实现不必立即改写，但具有生命周期状态的内建实现显式覆盖。
- `SegmentedTextSource`、`ContiguousTextSource` 沿用其真实结构有效性；`FileWindowSource` 以打开状态和窗口存储状态判定；`MappedFileSource` 以映射/空文件打开状态判定；`ChunkedTextStore` 仅在封存后可作为有效只读源。
- `PieceTreeSource` 构造时拒绝无效底层源；运行期间继续检查底层源状态，底层文件源关闭后 PieceTree 不再报告有效，也拒绝读取、编辑和克隆。
- `copyTextRange`、UTF-16 surrogate 边界检查、Tree-sitter 输入块复制和 `MappedFileSource::createSnapshot` 均在入口拒绝无效源。

边界说明：
- 该修复解决的是数据源生命周期/有效性传播，不代表 PieceTree 多快照聚合内存预算、随机 treap 高度上界或全局 100 MiB / 512 MiB 自适应预算已经完成。
- 本轮未执行任何测试、编译、构建、CI、设备验证或基准测试；相关验证保持待办，遵守“全部 Bug 修复前跳过测试”的要求。

## 2026-11-24 ChunkedTextStore 部分摄取失败隔离（源码已提交，验证暂缓）

静态审查发现：`ChunkedTextStore::append` 在处理一个跨多个 chunk 的大块追加时，可能先成功写入前缀，随后因内存预算耗尽或分配失败返回 `false`。旧实现允许调用方随后执行 `seal()`，而 `isValid()` 仅检查 sealed 状态，因此部分文档可能被误认为完整只读输入。

修复：
- 新增失败终止状态 `failed_`。
- 摄取开始后发生 chunk 索引溢出、内存预算不足、chunk 分配失败或容器异常时，将 store 标记为 failed。
- failed store 不再接受追加；即使调用 `seal()`，`isValid()` 仍返回 false。
- `read()` 通过 `isValid()` 拒绝读取失败的部分文档。
- 更新头文件契约：发生摄取中途失败的 store 不得作为部分文档被 seal 后解析。

这避免 Tree-sitter/JNI 把内存压力下只摄取到的前缀当作完整文档。没有执行测试、编译、构建、CI、设备验证或基准测试。

## 2026-11-24 PieceTree 版本身份溢出保护（源码已提交，验证暂缓）

`PieceTreeSource::replace` 原先无条件递增 64 位版本号。达到 `UINT64_MAX` 后会回绕，使新内容状态可能重新使用旧版本身份，破坏依赖版本键的快照、Regex 快照和缓存失效判断。现在在编辑事务开始前拒绝版本号耗尽的修改，避免分配或写入后才发现无法生成唯一的新版本。没有执行测试或构建验证。

## 2026-11-24 Kotlin PieceTree 快照版本身份保护（源码已提交，验证暂缓）

进一步检查编辑器实际使用的 Kotlin PieceTree 后，发现底层 `pieceTreeBase.kt` 的 `_versionId` 是 `Int`，且 setEOL、slice insert、普通 insert、delete 四条变更路径都直接执行 `++`。溢出后版本会变为负数并可能重用历史身份；TSH 快照层再转为 Long 也无法恢复已经丢失的唯一性。

已统一改为 `bumpVersionId()`：在每条变更路径开始修改树之前检查 `Int.MAX_VALUE`，耗尽时拒绝本次变更，不允许版本号回绕。保留现有 Int API，避免不必要的 ABI/API 破坏。未执行测试、编译或构建。

## 2026-11-24 PieceTree 文档订阅与销毁竞态修复（源码已提交，验证暂缓）

静态审查发现 `TshPieceTreeDocument` 的 `addChangeListener` / `addDisposedListener` 原先采用“先检查 AtomicBoolean，再写入 CopyOnWriteArrayList”的非原子序列。并发 `dispose()` 可能恰好在检查后清空列表、而订阅随后才完成写入，导致已销毁文档仍保留新监听器；该监听器永远收不到销毁通知，也无法通过正常生命周期清理。

修复：
- 引入文档级 lifecycle lock，串行化监听器注册/移除、变更发布和 dispose 状态转换。
- `dispose()` 在锁内原子标记销毁、清空变更监听器并提取销毁监听器快照，再在锁外分发销毁通知。
- 变更发布在同一生命周期锁内执行，并在每个监听器调用前检查销毁状态；回调中重入 dispose 时，不再继续向后分发变更。
- 监听器注册与 dispose 现在具有明确的先后关系：要么成功注册并被 dispose 捕获，要么因文档已销毁而拒绝。

没有执行测试、编译、构建、CI、设备验证或基准测试。

## 2026-11-24 Native Regex 匹配/捕获范围 Unicode 边界保护（源码已提交，验证暂缓）

静态审查发现 Native Regex 执行入口验证了输入 UTF-16 的合法性和 core 边界，但对 Oniguruma 返回的 match/capture 范围只检查了字节对齐和窗口范围，没有再次确认返回的起止位置不是代理对内部边界。Native 结果因此可能把一个补充平面字符拆成两个 UTF-16 code unit 范围，违反 TSH 对外的 Unicode code-point 边界契约。

修复：
- 在 Native 层检查每个 match 的起止位置。
- 对每个已匹配 capture group 同样检查起止位置。
- 任何落在 high-surrogate / low-surrogate 之间的范围均作为 Native 执行错误拒绝，不进入统一 MatchSet。
- 未匹配 optional capture 仍保留既有 `(-1, -1)` 表示，不受影响。

没有执行测试、编译、构建、CI、设备验证或基准测试。

## 2026-11-24 JNI 增量编辑 Parser 回退事务原子性修复（源码已提交，验证暂缓）

静态审查发现：增量解析失败后，全量回退解析一旦成功，旧实现会立即删除当前文档 Parser 并安装回退 Parser；但此后仍需分配和映射 changed ranges、构建待提交脏区间。这些后续步骤可能失败并使编辑返回失败，造成 Source/Tree/索引仍属于旧版本，而 Parser 已经属于候选版本的半提交状态。

修复：
- 将全量回退 Parser 保留为局部事务候选，不再在回退解析成功时提前替换 live Parser。
- 只有候选语法树、行索引及所有待提交脏区间均准备成功后，才在 Source/Tree 提交点同步替换 Parser。
- 若脏区间准备或映射失败，销毁候选 Parser，并重置曾被中断的原 Parser，确保下一次编辑不会继承失败增量解析的内部恢复状态。
- 候选 Parser 使用 RAII 所有权，覆盖提前返回路径，避免失败分支泄漏。

本次仅完成静态源码修改；没有执行测试、编译、构建、CI、设备验证或基准测试。

## 2026-11-24 TSH 颜色优先级、视口刷新与增量缓存稳定性修复（源码已提交，验证暂缓）

本轮围绕三个反馈点检查了应用层渲染、Kotlin 捕获缓存、Native TSH 执行和编辑器行缓存。确认并修复以下问题：

### A. 注解/重叠捕获颜色优先级不一致

问题：Native 捕获结果的确定性顺序为 priority 降序、declarationOrder 升序、startByte 升序、endByte 降序、source 升序、styleId 升序；Kotlin 构造 Android SPAN_PRIORITY 时却先按 source/styleId 排序，再考虑范围。相同规则发出的 whole-match 与 nested capture 如果具有不同 style，Kotlin 可能按 styleId 分配优先级，从而让不应获胜的颜色覆盖 TSH 规则指定颜色。

修复：Kotlin 的优先级键和最终 spans 排序均改为与 Native 完全一致的六字段顺序；优先级查找键同时包含 start/end byte，避免不同捕获范围误用同一个优先级条目。

源码：android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh/WindbellTshEngine.kt

### B. 视口捕获不再触发全编辑器样式/结构刷新

问题：普通视口捕获完成后，旧逻辑无条件调用全局 invalidateStyles() 和 invalidateStructure()。EditorView 的全局样式失效会提升整份文档的 styles generation，并使已经记录的行再次进入样式处理路径。滚动时新视口的捕获因此会扰动之前已着色的 ATS 符号。

修复：
- 视口捕获成功后只对本批已完成的范围做 UTF-16 offset → 行号映射。
- 使用 invalidateStyles(startLine, endLine) 局部失效，不再对整份文档执行样式失效。
- 普通高亮捕获不再触发结构刷新；只有括号/自动配对作用域查询确实等待了捕获结果时，才刷新结构。
- 失败重试唤醒同样只刷新失败范围，不再触发全局重绘。
- 只对本批真正完成的范围失效，快速滚动时未执行的过期范围不再被误标记为已完成。
- 新增 Host 多区间 invalidateStyles(ranges) 契约；EditorView 对多个不连续脏行范围只提升一次 styles generation，并仅使这些行的 RenderNode 样式失效，避免一个编辑事务的多个局部区间反复全局更新 generation。

源码：
- app/src/main/java/com/zerostudio/idea/androidtreesitter/TshSyntaxHighlighter.kt
- editor/src/main/java/x/github/module/editor/highlight/SyntaxHighlighter.kt
- editor/src/main/java/x/github/module/editor/view/EditorView.kt

### C. 移除重复的 Kotlin 捕获缓存

问题：captureCache 和 displayCache 持有同一份 CaptureIntervalIndex 数据，分别维护两套 LRU 和区间索引；每次绘制查询还要合并并去重。这不是两个独立缓存层，而是同一结果重复存储/重复查找。

修复：统一为单个 32 MiB 捕获缓存，删除重复 displayCache/displayRangeIndex；查询只遍历一次重叠区间并直接追加命中结果，避免为每行绘制分配 LinkedHashSet。

源码：app/src/main/java/com/zerostudio/idea/androidtreesitter/TshSyntaxHighlighter.kt

### D. 增量编辑不再先清空整个 Kotlin 捕获缓存

问题：旧 rebaseCaptureCaches() 先快照所有会话条目，再 invalidate 整个 session，最后逐项重新 put。即使大多数缓存项可复用，也会对整批条目执行移除/添加和区间索引回调。

修复：
- ByteBudgetLruCache 新增 transformEntries()，在缓存锁内构建变换结果，保留原 LRU 顺序和未变化的 key/value。
- 只有确实移除或改变 key 的条目才触发区间索引回调；未变化条目不再执行 remove/add。
- 保留冻结的原始 weight；替换值才重新计算权重；缓存预算累加采用防溢出方式。
- TshSyntaxHighlighter 使用该原子变换接口，只变换当前会话条目，不再先清空整个会话缓存。
- 对无法复用的旧视口范围进行坐标变换并记录；与 Native dirty byte ranges 合并后，只失效这些范围对应的行。删除/替换造成的空区间会扩展到所在行边界。
- Native 依赖分析若确实认定捕获依赖为 document-wide，相关旧视口仍会被失效；这是语义正确性要求，不会为了避免重绘而保留错误 ATS 结果。

源码：
- android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/largefile/DocumentPartitionManager.kt
- app/src/main/java/com/zerostudio/idea/androidtreesitter/TshSyntaxHighlighter.kt

### E. 增加 Native TSH 分阶段耗时诊断

Native document 创建已有 source transfer/write、backing map/PieceTree、Tree-sitter parse 的分项计时。本轮为 nativeHighlightDocument 增加 debug verbose 诊断，区分：
- Native highlight cache hit/miss；
- context-dependent Regex snapshot 创建耗时；
- highlightHybrid 核心执行耗时；
- JNI 结果编码耗时；
- 文档字节数、请求范围、捕获数量、Native cache entries/bytes。

这样后续设备日志可以明确区分“首次 Native 文档传输/完整 AST parse”与“首次 TSH 捕获执行”，不再把所有延迟笼统归因于 Token 缓存。

源码：android-tree-sitter/src/main/cpp/ts_windbell_jni.cpp

### 尚未关闭的重点问题

1. 夜间模式 @ 注解颜色：源码已修正 Native/Kotlin 捕获优先级顺序，但最终设备绘制结果尚未验证；不能在验证前宣称颜色 bug 已彻底关闭。
2. 首次打开大文件：需要结合新增的 nativeCreateDocument 与 nativeHighlightDocument 分段日志，进一步定位源读取/写入、Tree-sitter 全文 parse、Regex/Block、ATS selector、Tree-sitter Query、JNI 编码各自占比。
3. BLOCK Regex：已加入每份 Native 文档的 bounded delimiter-state checkpoint。首次遇到某条 BLOCK 规则时仍需从文档前缀恢复一次状态；后续向前滚动时从最近 checkpoint 扫描新增区间，不再每个新视口都反向扫描到文档开头。checkpoint 以规则 declarationOrder、grammar/source/partition version 和 byte offset 区分，最多保留 512 条；增量编辑保留编辑点之前的前缀 checkpoint，丢弃编辑点之后的候选状态。
   - 编译器分别分析 BLOCK start/end pattern 的有限宽度；只有两端都通过保守宽度分析时才允许 checkpoint dependency。
   - Native 捕获结果现在附带从最近 checkpoint 到当前视口的 CHECKPOINT_BASED + BOUNDARY_SENSITIVE 依赖区间，并按 delimiter 最大宽度扩展右边界。
   - 可证明有限宽度的 BLOCK delimiter 不再仅因其需要完整 Oniguruma 字符串上下文而把所有视口缓存标记为 document-wide；无法证明的 delimiter 仍保留全局依赖，优先保证正确性。
   - 首次视口、向后跳转到所有 checkpoint 之前仍可能执行一次反向前缀恢复；极端大文件的首次打开耗时仍需结合分阶段日志判断。
4. ATS 依赖元数据：修复 Native Query 缺少依赖元数据时的回退条件。旧逻辑只在整个 dependencies 列表为空时添加 document-wide Query 依赖；当 Regex 已经添加依赖时，会错误地掩盖 Query 依赖缺失。现在按 NATIVE_QUERY source 单独检查，避免 Regex 依赖让 ATS Query 结果被误判为可局部复用。
5. 已绘制 ATS 符号稳定性：本轮已移除滚动捕获触发的全局样式/结构刷新，并使编辑失效范围局部化；仍需继续检查 Renderer 行缓存、缓存淘汰和 Native 依赖 envelope 在极端大文件下是否存在额外失效路径。

严格遵守当前开发顺序：以上均为源码修改和静态检查；没有运行 Unit/Instrumentation tests、Gradle build/compile、CI、设备验证或 Benchmark。所有验证流程继续冻结，直到用户提出的源码 Bug 修复完成。

### 2026-11-24 后续静态收敛补充

- BLOCK checkpoint 仅对 start/end 两个 delimiter 均通过有限宽度分析的规则启用；无法证明有限宽度的规则继续使用原有完整上下文/全局依赖路径。
- 第一次反向恢复若发现 delimiter 本身跨越视口边界，不会把边界中间态错误保存为 checkpoint；checkpoint 记录在未完成 delimiter 起点，后续视口再完成状态迁移。
- checkpoint 状态转换遵循现有 BLOCK 执行语义：已处于 block 内时忽略嵌套 start delimiter，直到 end delimiter 关闭当前 block。
- checkpoint 容器最多 512 项；内存分配失败时清空优化缓存并回退到反向恢复，不让可选缓存分配失败中断 TSH 高亮结果。
- 增量编辑只保留编辑点之前的 checkpoint，并同步更新其 source/partition version；编辑点之后的 checkpoint 丢弃。
- Kotlin 视口刷新和编辑刷新均携带 documentRevision；若异步刷新任务落后于后续编辑，则直接丢弃，避免旧范围把新文档行缓存错误地重新失效。
- EditorView 新增多范围样式失效入口：一个编辑事务中多个不连续脏行范围只增加一次 styles generation。
- Native Query 缺失依赖元数据时，按 NATIVE_QUERY 来源单独添加 document-wide 保护依赖，不再被同一结果中的 Regex 依赖误掩盖。

以上仍仅完成源码级静态检查，没有执行任何测试或构建流程。

### 2026-11-24 后续根因修复：捕获颜色仲裁、缓存有效性与局部失效

本轮继续以消除重复计算根因、维持已绘制结果稳定为目标，完成以下源码改动：

1. **Android Span 优先级不再承担 TSH 语义仲裁。**
   - Android 的 SPAN_PRIORITY 只有 8 位，超过 255 个不同捕获层级时不可避免地产生优先级碰撞。
   - WindbellTshEngine.applyHighlightSpansToSpannable() 改为 sweep-line：先按 Native 捕获顺序建立边界事件，再分别维护 foreground、background、font 的活动优先级集合。
   - 每个可见区间只发出已确定的颜色/背景/字体样式，不再向 StyledTextPainter 提供相互竞争的重叠样式。
   - foreground/background 分别选择最高优先级且确实声明该通道的捕获，避免只声明字体属性的高优先级捕获意外抹掉下层显式颜色。
   - 活动集合使用有序集合，边界处理为 O(n log n)；相邻且解析结果相同的区间合并，减少 Android Span 数量。
   - 字体属性保持 StyledTextPainter 原有的可组合语义：对当前活动捕获的字体标志按位合并；颜色/背景则分别按 Native 优先级选择唯一胜出者。

2. **BLOCK delimiter 跨视口起始边界修复。**
   - 检查点恢复时，若 start delimiter 的起点位于视口之前、结束位置跨过视口边界，原逻辑把检查点保留在 delimiter 起点，却让当前视口的正向扫描从 viewport 起点开始，导致当前视口漏掉这个 start delimiter。
   - 现在在当前活动 block 尚未打开时，将该跨界 start delimiter 起点作为活动 block 起点，让当前视口正确执行并绘制；检查点仍保留在 delimiter 起点，后续视口可以完整消费该事件。

3. **禁用规则不再强迫完整源快照或 document-wide 依赖。**
   - Native JNI 对 Regex、Regex State、Native Query predicate Regex、ATS text Regex 的连续源要求和依赖回退，现在只统计 enabled producer。
   - 全部禁用的规则不再触发完整 mmap Regex Snapshot，也不再仅因禁用的 Query/ATS/State 规则产生 document-wide 依赖。
   - 这减少无效的首次打开工作，并避免无关规则扩大编辑后的失效范围。

4. **Native 依赖证明与 span payload LRU 分离。**
   - 旧实现通过 Native span payload LRU 的剩余键判断 Kotlin 捕获结果能否跨编辑复用；Native payload 默认仅 4 MiB，而 Kotlin 捕获缓存为 32 MiB。
   - Native payload 被 LRU 淘汰时，依赖证明也一起消失；下一次局部编辑会因此把仍在 Kotlin 缓存中的有效捕获误判为不可复用。
   - 新增独立、受限的依赖证明 ledger：最多 2 MiB / 32768 条，按共享 Native 内存预算记账；即使 span payload 被淘汰，仍保留有效 viewport 的依赖范围。
   - 编辑时 ledger 独立执行 dependency rebase；只有依赖证明仍有效的范围才返回给 Kotlin。
   - 可复用范围同时校验 grammar revision、document version、source version 和 partition version，防止跨版本误复用。
   - 无依赖、无法入账或超预算的范围采取保守失效，不以未知依赖换取错误复用。

5. **减少捕获缓存的常驻内存与编辑峰值内存。**
   - CaptureIntervalIndex 从每个捕获保留一个 Kotlin data object，改为 primitive parallel arrays；每个捕获的常驻字段压缩到约 25 bytes，缓存权重按 32 bytes/capture 计入。
   - ByteBudgetLruCache.transformEntries() 移除额外的完整 previousEntries 副本，避免一次局部编辑同时持有三份完整缓存 map。
   - Native TSH 性能日志增加 dependency ledger 实际记账字节数。

6. **修复不连续视口导致的大范围样式失效。**
   - 旧 invalidateViewportStyles() 把一个 batch 中所有范围压成 min(start)..max(end)，即使中间存在大量未变化行也全部失效。
   - 现在分别映射到行范围、合并相邻/重叠范围，再通过 invalidateStyles(ranges) 一次提交；不再清除不连续范围之间的 ATS 行缓存。

以上为源码级改动和静态阅读结论；尚未执行测试、编译、CI、设备验证或 Benchmark。

### 2026-11-24 再后续：局部捕获可见性与 Native/ Kotlin 缓存一致性

1. **局部缓存未完成时不再抹掉同一行已捕获颜色。**
   - 旧 queryCachedCaptures() 只要请求区间存在一个未命中 gap，就返回 null；即使同一行的前半段已有完整捕获，也会让整行退回 plain text。
   - 现在返回 spans + complete 状态。Renderer 可以立即绘制已命中的捕获，缺失区间继续排队；结构/括号查询仍要求完整覆盖，避免用部分结果作语义判断。
   - 查询时直接把各命中 CaptureIntervalIndex 追加到结果列表，移除中间索引列表分配。

2. **只失效真正新捕获的 byte gaps。**
   - executeNativeRangeNow() 现在返回成功写入缓存的精确缺失区间，而不是原始 viewport 请求区间。
   - 一次 viewport 请求若只有少量空洞，已经缓存的其它行/区间不会因为这次补洞而再次被 Renderer 失效。
   - invalidateViewportStyles() 同时保留 batch 中不连续的行区间，不再用 min/max 生成大范围无关失效。

3. **Native AST Query 依赖和执行共用同一次候选节点遍历。**
   - 旧逻辑先调用 collectQueryRootRanges() 为缓存依赖扫描一次 AST，再调用 collectQueryRootCandidates() 为 Query 执行扫描第二次。
   - 现在 collectQueryRootCandidates() 产出的节点及 matching symbols 同时用于生成依赖区间和执行根节点，移除重复的视口 AST 遍历。
   - 非 ROOTED_LOCAL Query 仍保持完整树执行和 document-wide 依赖，不改变查询语义。

4. **Native payload 与 dependency ledger 统一为单一依赖元数据所有者。**
   - 进一步移除 NativeHighlightCache::Value 中重复的 dependency vector；依赖证明只由受预算保护的 ledger 持有。
   - Native span payload LRU 只负责缓存 spans；payload 被淘汰不再丢失 Kotlin 捕获结果所需的依赖有效性证明。
   - payload rebase 通过 ledger 的新版本 Key 校验；若实际 span 无法安全移动，则同步撤销对应 ledger proof，避免 Kotlin 保留不安全的旧捕获。
   - dependency vector 以 move 方式进入 ledger，避免每个视口结果再次复制整份依赖数组。

以上改动仍处于源码静态审查阶段；按照当前开发顺序，没有执行测试、编译、CI、设备或 Benchmark。