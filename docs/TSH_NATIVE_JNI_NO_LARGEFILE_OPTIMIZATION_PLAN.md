# TSH + 原生 Tree-sitter JNI 绑定：无大文本专用技术的完整优化与缺陷修复任务规范

> 项目分支：`migration/tsh-0.28-from-backup-no-largefile`  
> 迁移基线：`back_main_0e9051c`  
> Tree-sitter 上游版本：`0.28.0`  
> 文档性质：本分支实施总纲、架构边界、缺陷清单、迁移约束、测试与验收规范  
> 核心目标：只保留原生 Tree-sitter 引擎、常规 JNI 绑定、TSH 引擎及 Android 应用渲染链路；彻底排除此前专门为大文本引入的处理技术及其变种。

---

## 0. 任务定义

本任务不是继续开发一套大文本处理框架，也不是将大文本处理代码换名、隐藏或改造成另一套窗口/分区/Token 管线。

本任务要建立一个干净、可审计的对照实现：

1. 以原生 Tree-sitter 0.28.0 完整源码为语法解析引擎。
2. 使用常规、直接、连续的文本输入方式，通过 JNI 将 Android Java/Kotlin API 与原生 Tree-sitter 连接。
3. 在该 JNI 绑定基础上迁移和完善 TSH 引擎。
4. 修复 JNI、Tree-sitter 调用封装、TSH 编译/执行、结果转换、Android 渲染链路中的正确性、稳定性、内存和性能问题。
5. 不依赖任何此前的大文本专用处理实现，尤其不依赖 `cpp/largefile/`、Piece Tree Source、File Window、Mapped File Snapshot、TextSource/Chunk、TextPartition、分区 Token、滑动窗口、分块续算、窗口 Regex、checkpoint/convergence 等技术。
6. 最终必须证明：普通文档、较大文档均通过同一套常规 JNI + Tree-sitter + TSH 语义执行链路；`.tsh` 配置的颜色最终确实显示在 Android 编辑器的文字绘制层。

本任务允许针对常规 JNI 和引擎代码做算法优化、减少无效分配、减少 JNI 往返、改善缓存与生命周期管理，但不允许借优化之名重新引入大文本专用分段/窗口/Token 管线。

## 1. 基线、版本与分支纪律

### 1.1 分支来源

- 本分支必须以 `back_main_0e9051c` 为迁移祖先。
- 备份分支是旧版 Tree-sitter JNI 绑定源码基线，不包含当前 TSH 引擎。
- TSH 与相关应用代码从当前仓库经过审查的实现中迁移，不得将整个 main 分支覆盖到本分支。
- 所有开发、提交、重构和测试仅在本分支执行。
- 不得擅自修改 `main`、备份分支或此前其他实验分支。

### 1.2 Tree-sitter 版本

- 必须完整升级到上游 Tree-sitter `0.28.0` 源码。
- 当前分支的 `tree-sitter-lib` 子模块应固定在已核实的上游提交 `62816e1c0d0a4ca9553174f3cbe5b9631047f7f4`。
- 不允许仅替换头文件、仅复制某几个 C/C++ 文件，或在旧版本核心上伪装成 0.28.0。
- 需要同步审查 C API、头文件、查询游标 API、Parser API、ABI、编译选项和 JNI 包装层。
- 不得因为升级而删除 0.28.0 的新 API 或回退到旧版本兼容行为。
- 每次升级相关修改都要明确区分：上游源码原样内容、本仓库平台构建适配、本仓库 JNI/TSH 自有实现。

### 1.3 版本核验

必须提供以下证据：
- 子模块 commit SHA。
- 上游版本声明。
- 实际参与 Native 编译的源码路径。
- 编译日志中的版本/构建信息。
- 绑定层对 0.28.0 新旧 API 的兼容性测试。

## 2. 绝对架构边界：常规连续文本 JNI

### 2.1 允许保留的基本模型

本分支采用常规的完整文档输入模型：

```
Android Editor / Activity
        |
        v
Java / Kotlin Tree-sitter API
        |
        v
JNI boundary
        |
        v
Native Tree-sitter 0.28.0 Parser
        |
        v
Complete TSTree
        |
        +------> Tree-sitter Query / ATS
        |
        +------> TSH result mapping / Style resolution
        |
        v
Android render result / Span
        |
        v
Editor text drawing
```

输入必须具有单一、明确的文档语义和完整的字节序列。可根据 API 契约采用 UTF-8 字节缓冲区或 JNI 字符串转换，但同一解析任务必须保证传给 Parser 的内容完整、连续、长度准确、生命周期有效。

### 2.2 明确禁止的技术和依赖

禁止将以下技术或其等价变体迁入本分支：

- `cpp/largefile/` 目录及其所有实现。
- `TextSource`、`TextChunkView`、`TextSegment`、`SegmentedTextSource`、`CallbackTextSource` 等分段抽象。
- Piece Tree Native Source、PieceTreeSource、Piece Tree Token Source。
- Mapped File Source、Mapped File Snapshot、File Window Source。
- Text Partition Index、分区版本、分区快照、分区索引。
- 滑动窗口、固定窗口、窗口覆盖、窗口拼接。
- 分块 Parser 输入、窗口 Parser 输入、独立窗口语法树及 AST 拼接。
- 分区 Token 化、批量 Token 变种、Token checkpoint、分区续算。
- 窗口化 Tree-sitter Query、窗口化 Regex、Regex overlap、窗口边界补偿。
- Regex state checkpoint、窗口收敛、跨窗口状态恢复。
- 为大文本专门设计的 source-version/partition-version 双重身份系统。
- 以 viewport 或当前屏幕可见文本代替完整文档作为 Tree-sitter 语法上下文。
- 任何仅更换命名、移动目录、包装接口后仍然执行上述行为的实现。

**判定原则：** 不以目录名称判断是否违规，而以运行时行为、调用链、数据结构和执行语义判断。代码即使不在 `largefile` 目录，只要仍然通过分块/窗口/分区方式处理文档，就属于本任务明确排除的技术。

### 2.3 不应误删的常规能力

以下内容不属于大文本专用技术，不能因清理而误删：

- Tree-sitter 原生 Parser、Tree、Node、TreeCursor。
- Tree-sitter Query、QueryCursor、Match、Capture。
- Tree-sitter 增量解析本身的标准 API（Parser 对完整文档重新解析或基于完整输入执行标准 edit/reparse）。
- 标准 JNI 参数批量转换或减少 JNI 往返的常规接口，只要它不实现分块/分区 Token 执行。
- 普通对象池、短生命周期缓存、编译后 Query 缓存（必须有明确容量和生命周期）。
- 标准文档 revision / generation，用于拒绝过期异步结果。
- Android Span、样式解析、颜色映射、渲染调度。
- Oniguruma Regex 独立执行管线。
- 正确的取消、线程同步、异常映射、内存压力处理。

## 3. Tree-sitter 与 TSH 的两条执行管线

本分支只能保留两条语义管线，不得引入第三条。

### 3.1 ATS / Tree-sitter Query 管线

- 使用 Tree-sitter 0.28.0 原生 Parser 生成完整 TSTree。
- 使用标准 TSQuery / TSQueryCursor 对正确的完整语法树执行查询。
- 保留 Query Pattern、Predicate、Capture、Match、Capture ID、节点类型、字段关系和语法上下文。
- 查询范围优化只能使用 Tree-sitter 正式 API 的范围能力，不能把完整树查询替换成独立 viewport 文本解析。
- 必须验证非局部模式、父子节点、兄弟关系、祖先关系、字段约束和 Predicate 的语义。
- 不得静默截断匹配结果；任何上限必须在 API 中显式表示，并能区分完整结果与被限制结果。

### 3.2 独立 Regex / Oniguruma 管线

- Regex 是独立执行管线，不得伪装成 Tree-sitter Query。
- 保留 Oniguruma 正则语义，包括捕获组、命名组、边界、锚点、lookaround、反向引用及错误处理（以当前引擎实际支持范围为准）。
- 不得依赖固定 overlap 来声称任意正则都能跨窗口正确执行。
- 本分支输入为完整连续文本，Regex 执行应直接面对完整文档的明确输入范围。
- Regex 编译结果与执行状态必须有明确所有权、线程安全和释放规则。
- 正则错误、超时/取消、无匹配和内部执行失败必须区分，不能统一伪装成空结果。

### 3.3 TSH 编译与调度

- TSH DSL 编译器负责语法、Schema、类型、属性约束和执行计划。
- TSH Core 负责将编译结果调度到上述两条管线。
- Style Resolver 负责 Capture/Regex 结果到样式规则的确定性映射。
- Render ABI 负责向 Android 层传递稳定、可验证的渲染结果。
- 不得在 TSH Core 中添加第三种扫描/Tokenizer/窗口执行路径。
- 同一规则的优先级、覆盖关系、Capture 映射和颜色解析必须确定且可测试。

## 4. JNI 绑定层完整审查与修复范围

### 4.1 JNI 边界正确性

逐个检查所有 Native 方法：

- Java/Kotlin 声明与 JNI 导出符号一致。
- 包名、类名、方法名、静态/实例方法签名一致。
- 参数顺序、宽度、有符号性、可空性和返回类型一致。
- UTF-8 byte offset 与 UTF-16 code-unit offset 不得混淆。
- 数组长度、ByteBuffer capacity、字符串长度与实际字节长度不得混淆。
- 所有 JNI 局部引用在适当边界释放。
- 长循环或大量对象转换必须控制 Local Reference Table 增长。
- Global Reference / Weak Global Reference 必须有明确所有者和销毁路径。
- 禁止跨线程使用不合法的 JNIEnv 指针。
- 需要跨线程访问 Java 对象时，必须使用正确的线程附加和引用管理机制。
- JNI 异常不得被静默吞掉；Native 侧必须检查 pending exception，并按契约退出当前调用。
- 禁止在异常未清理的情况下继续调用不允许执行的 JNI API。
- 禁止将临时 UTF 字符串指针、数组元素指针保存到 JNI 调用之外。

### 4.2 字符串与字节转换

- 统一文档内部编码契约，明确 Tree-sitter 输入为 UTF-8。
- 明确 Java String 的 UTF-16 表示到 UTF-8 的转换策略。
- 不允许按 Java String.length() 作为 UTF-8 字节长度。
- 不允许在 UTF-8 多字节字符中间切分编辑区间。
- 正确处理补充平面字符、组合字符、CRLF、LF、孤立 surrogate 的输入策略。
- 任何转换失败必须有可观察错误，不得静默替换后继续生成错误坐标。
- 尽可能减少重复编码/解码，但不得通过不安全的指针借用换取性能。
- 明确每个 Native 输入缓冲区的分配者、使用者、释放者和有效期。

### 4.3 Native Handle 与生命周期

对 Parser、Tree、Query、QueryCursor、Language、TSH Program/Plan、Regex 编译对象等 Native 资源：

- 定义统一的 handle 所有权规则。
- 禁止 Java/Kotlin 层持有已释放或已复用的裸指针。
- 处理重复 close、close 后调用、析构竞争、异步任务仍引用资源等情况。
- 释放操作必须幂等或明确拒绝重复释放。
- 需要使用引用计数/共享所有权时，必须证明不会形成泄漏环。
- 不允许在对象 finalizer/不确定时机中承担唯一释放责任。
- 提供明确 close/dispose API，并在 Kotlin 层提供安全包装。
- 所有错误路径（编译失败、解析失败、取消、JNI 异常）都必须释放已创建的临时资源。

### 4.4 线程安全与并发

- 明确每个 Parser、Tree、QueryCursor 和 TSH 执行对象是否可并发使用。
- 不允许多个线程无同步地修改同一个 Parser 或 QueryCursor。
- 允许只读共享的资源必须由实际 Tree-sitter/TSH 契约支持。
- 文档修改、解析、Query、Regex、样式计算和 UI 渲染之间必须定义线程边界。
- 过期异步任务不得覆盖较新文档版本的渲染结果。
- 取消任务后必须阻止结果提交，并确保 Native 临时资源释放。
- 禁止持有 JNI 临界区或 Native 全局锁执行耗时 UI 回调。
- 对锁顺序、重入和死锁风险提供审查记录。

## 5. Native Tree-sitter 0.28.0 绑定优化

### 5.1 Parser 与 Tree 管理

- Parser 创建/删除配对完整。
- Language 设置和 ABI 检查正确。
- Parser timeout、取消和解析错误状态有明确映射。
- Parser 输入长度与 Tree-sitter API 的整数宽度边界经过检查。
- Tree 的 copy/edit/delete 生命周期正确。
- 增量解析只使用标准 Tree-sitter edit/reparse 契约，不得引入窗口续算。
- 文档内容变化后，旧 Tree、旧 Match 和旧 Capture 不得被误认为当前版本结果。
- 对完整文档重复解析的性能成本必须测量，不得通过丢失语义的局部树替代。

### 5.2 Query 与 QueryCursor

- Query 编译错误位置、错误类型和源文本偏移映射准确。
- Query / Cursor 创建和释放完整。
- Match/Capture 读取必须检查边界和对象有效性。
- Capture name / ID 映射必须与编译 Query 对应。
- Pattern disable、Capture disable、Match limit、Byte range、Point range 等能力应按 0.28.0 实际 API 正确绑定。
- QueryCursor 复用必须保证每次执行的状态清理完整。
- 不得把 QueryCursor 的结果限制当成完整结果。
- Match limit、超时、取消和结果数量上限必须能向上层准确表达。
- 对大量匹配结果的常规 JNI 传递可优化批次/对象创建，但不能引入分区 Token 机制，也不能丢弃结果。

### 5.3 TreeCursor 与 Node

- Node 的 Tree 所属关系和生命周期必须明确。
- TreeCursor 不得在底层 Tree 释放后继续访问。
- Node start/end byte、Point、child index、field name、named/error/missing 状态必须正确绑定。
- 递归遍历要避免不必要的 JNI 往返和重复节点对象分配。
- 深层语法树需要验证栈安全、遍历完整性和取消响应。
- 不得以自定义 Token 化结果替代 Tree-sitter 节点语义。

### 5.4 Native 内存与性能

- 识别并修复重复分配、无效复制、重复 UTF 转换、临时容器膨胀和不必要的 JNI 对象创建。
- 评估 Query 编译缓存、Language 缓存和安全复用策略。
- 缓存必须有明确上限、失效条件和释放时机。
- 对大对象分配失败、Native OOM、JNI OOM 和标准库异常进行安全处理。
- 不允许通过无限增长缓存或静态全局对象隐藏泄漏。
- 任何池化必须有容量上限，且不得改变 Tree-sitter 对象所有权。
- 不得以固定内存预算或特殊窗口框架作为本任务的核心架构。
- 记录 Native heap、Java heap、JNI 调用次数、解析耗时、Query 耗时和渲染耗时。

## 6. TSH DSL / Compiler / Runtime 缺陷修复

### 6.1 DSL 解析与 Schema

- 每个 DSL 属性必须有明确类型、默认值、合法值域和语义。
- 非法字段、未知字段、类型不匹配、缺失必需字段必须产生定位明确的诊断。
- 字符串转义、Unicode、注释、空白和多行输入行为必须确定。
- 数值、颜色、布尔、枚举、列表、Capture 引用和 Regex 字段必须验证。
- Schema 校验不能依赖渲染阶段补救。
- 编译失败不能生成半有效 Program 并继续执行。

### 6.2 编译器

- Compiler 输入、输出和错误模型明确。
- 编译结果不可变或有清晰的并发访问约束。
- ATS Query 编译与 Regex 编译分别报告错误。
- Query 错误 offset 必须映射回 TSH 源文件位置。
- Regex 错误 offset 必须映射回对应规则。
- 重复编译相同配置可使用受控缓存，但缓存键必须包含所有语义相关配置。
- 不得把编译错误降级成无匹配结果或默认颜色。

### 6.3 Runtime

- Runtime 只调度 ATS 与 Regex 两条管线。
- 文档输入、Tree、Query、Regex、Capture、Style、RenderPlan 之间必须有明确的文档 revision。
- 不允许混合不同 revision 的结果。
- 同一输入和配置必须得到确定性结果。
- 执行失败、部分结果、取消、超时和完整成功必须区分。
- 结果排序、重叠 Capture、样式优先级和冲突解决必须稳定。
- 清理所有对 largefile、TextSource、partition、window、checkpoint 的依赖。

### 6.4 Style Resolver

- Capture 名称、Capture ID、规则 ID 与样式定义映射正确。
- 规则优先级、覆盖、继承和默认样式语义明确。
- 同一位置多条规则冲突时，采用文档定义的确定性策略。
- 未知 Capture、缺失颜色、非法颜色和无效样式必须有诊断。
- 颜色值必须保持 Alpha、RGB 和目标 Android Color 的转换精度。
- 不得在 Native 层与 Android 层重复应用不同的颜色解释规则。

## 7. Android Java/Kotlin API 与应用层迁移

### 7.1 API 迁移

- 迁移常规 Tree-sitter Java/Kotlin 绑定。
- 迁移 Parser、Tree、Node、TreeCursor、Query、QueryCursor、Match、Capture 等必要 API。
- 迁移 TSH DSL、Schema、Compiler、Program、Runtime、Style、Render ABI 等公开 API。
- 删除或不迁入所有 windowed/segmented/partition/PieceTree/TextSource 专用 API。
- 清理旧 API 的调用点、测试、样例和文档，不能只删除声明而保留调用。
- API 命名、包路径、可见性、异常和 nullability 保持一致。
- Java 与 Kotlin 包装层必须有对应的 JNI 契约测试。

### 7.2 Activity 与生命周期

- 迁移任务所需的 Activity、编辑器宿主、配置加载入口和生命周期管理。
- Activity 只负责 UI 生命周期和调度，不应持有不受控的 Native 裸句柄。
- onCreate/onStart/onStop/onDestroy 等生命周期转换中，解析任务和 Native 资源必须安全。
- Activity 重建、旋转、后台恢复、快速切换文件和关闭文档时不得泄漏 Parser/Tree/Query。
- 后台任务不得在 Activity 销毁后向旧 View 提交结果。
- 不允许把解析和 Regex 执行放在主线程。

### 7.3 文档输入与编辑更新

- 明确 Editor 文本快照到 JNI 输入的转换入口。
- 每次解析输入必须对应唯一文档 revision。
- 解析完成后校验 revision，再提交结果。
- 编辑更新必须正确维护 UTF-8 byte offset 与 UTF-16 offset 的映射。
- CRLF、LF、Unicode 补充字符和多行编辑必须有测试。
- 本任务不实现 Piece Tree 到 Native 的分块流式桥接。
- 如果应用编辑器内部仍使用 Piece Tree，其内容在本任务中只能通过明确的常规连续快照输入契约进入 JNI；该快照是本实验分支的输入适配，不得在 Native 层继续引入 PieceTreeSource/窗口分区体系。

## 8. 高亮结果到真实绘制的端到端链路

这是本任务的关键验收目标。只证明 Native 返回了 Match、Capture 或 RenderPlan，不代表高亮修复完成。

必须逐层验证：

1. Activity 打开文件并加载正确的 `.tsh` 配置。
2. TSH Schema/Compiler 成功生成可执行 Program。
3. Tree-sitter Parser 对完整文档生成有效 Tree。
4. ATS Query 和 Regex 两条管线产生结果。
5. Capture / Match 被正确映射到 Style。
6. Style Resolver 生成有效范围、颜色、字体属性等渲染数据。
7. JNI 返回值、坐标和颜色无损。
8. Kotlin Renderer 将结果转换为 Android Span 或编辑器原生绘制指令。
9. Span/绘制指令被正确应用到当前文本快照。
10. 编辑器实际执行文字绘制，用户能看到 `.tsh` 配置颜色。

必须验证：
- 普通短文本和较大文本。
- 首次打开、滚动、编辑、撤销、重做、切换文件。
- 同一行多种 Capture 重叠。
- 多行 Capture、嵌套节点、空节点、错误节点。
- 主题切换和颜色透明度。
- 快速连续编辑时旧结果不会覆盖新结果。
- 关闭 Activity/文档时不再提交旧 Span。
- 关闭高亮或切换语言后清理旧样式。

必须保存关键阶段日志或测试观测数据，能区分“没有解析结果”“没有 Style”“Span 未应用”“绘制层未刷新”等故障。

## 9. 大文档测试的边界

本分支不是大文本处理技术分支，也不以支持任意规模文件作为架构承诺。

但用户最初关注的问题是大文本打开后高亮颜色不能正确加载、渲染或绘制，因此仍需要用不同尺寸文档验证常规 JNI 路径的稳定性和性能：

- 小文档：验证功能基线。
- 中型文档：验证常规调用与增量更新。
- 较大文档：暴露 JNI 复制、内存、Query、Regex、结果转换及 Span 应用中的真实瓶颈。
- 超出设备承受能力的文档：必须安全失败或可取消，不得崩溃、死锁、无限分配或显示伪造的完整结果。

测试大文档时不得为了通过测试重新加入：
- 窗口化 Parser。
- 分块 Token 化。
- 分区 Query。
- Window Regex。
- TextSource/Chunk。
- Piece Tree Native Source。
- Token checkpoint / partition convergence。

如果连续完整文本路径出现性能瓶颈，应优化常规 JNI、Tree-sitter API 调用、内存复制、查询算法、缓存和 Android Span 应用本身，并如实记录其能力边界。

## 10. 必须清理的源码与依赖

清理范围包括但不限于：

### Native C/C++

- `android-tree-sitter/src/main/cpp/largefile/`。
- 引用 `largefile::` 命名空间的 JNI 文件。
- 引用 `TextSource` / `TextChunkView` / `TextPartitionIndex` 的 TSH Core。
- 依赖窗口化输入的 Parser 适配。
- 依赖分区/窗口的 Regex、Query、Token、Checkpoint 代码。
- CMakeLists 中对应源文件、include 路径和编译宏。

### Java/Kotlin

- `largefile` 包。
- PieceTree/Window/Partition/Segmented Parse API。
- `parseWindowed`、`parseBuffers` 等仅服务于被排除技术的接口及 JNI 方法。
- 大文本专用 Token 分区、窗口调度、checkpoint 状态类。
- 对上述 API 的调用点、测试和文档。

### 构建和工程配置

- CMake source list。
- Gradle source set / externalNativeBuild 配置。
- 依赖声明和 native include 路径。
- 测试 fixture、样例、benchmark 和 CI workflow。
- 不允许保留未使用的大文本模块作为“备用实现”混入产物。

清理完成后必须使用全仓库搜索审计以下关键词及其同义符号：`largefile`、`TextSource`、`TextChunk`、`TextSegment`、`PartitionIndex`、`PieceTreeSource`、`MappedFileSource`、`FileWindowSource`、`parseWindowed`、`checkpoint`、`convergence`、`windowed`、`segmented`。  
搜索命中必须逐项判定：真实执行依赖必须清除；历史文档或迁移说明可以保留，但必须标明非运行时代码。

## 11. 性能优化方法与指标

性能优化必须基于可复现测量，不能凭主观判断。

### 11.1 需要采集的指标

- 文档 UTF-8 编码耗时和字节数。
- JNI 输入转换耗时。
- Parser 初始化/复用成本。
- 完整解析耗时。
- Tree 节点数量、错误节点数量和 Tree 内存估算。
- Query 编译耗时。
- Query 执行耗时、Match 数、Capture 数。
- Regex 编译耗时、执行耗时、匹配数。
- Native 到 Java/Kotlin 结果转换耗时。
- Span 创建/合并/应用耗时。
- 从打开文档到首帧真实高亮显示的总耗时。
- Java heap、Native heap、峰值内存、分配次数。
- JNI 调用次数、局部引用峰值。
- 取消响应时间和资源释放耗时。

### 11.2 优化优先级

1. 修复错误和不安全的 JNI 生命周期。
2. 消除重复 UTF 转换和重复数据复制。
3. 减少无意义的 JNI 往返与逐节点对象创建。
4. 修复 Parser / Tree / Query / Cursor 的重复创建与泄漏。
5. 优化 QueryCursor 使用和 Match/Capture 转换。
6. 优化 TSH 编译与缓存失效策略。
7. 优化 Style Resolver 和 Render ABI。
8. 优化 Android Span 合并、更新范围和绘制刷新。
9. 对完整打开路径做端到端性能回归。

不得以减少结果、截断 Capture、跳过规则或牺牲语义的方式换取性能数字。

## 12. 错误模型与可观测性

错误至少应区分：

- 输入编码失败。
- 输入长度超出 Native API 表示范围。
- Parser 创建失败。
- Language ABI 不兼容。
- Parse 取消、超时或内部失败。
- Query 编译错误。
- Query 执行错误。
- Regex 编译错误。
- Regex 执行错误。
- Native handle 无效/已释放。
- JNI 异常。
- Native 内存分配失败。
- TSH Schema 校验失败。
- TSH Compiler 失败。
- Style 映射失败。
- Render ABI 结果不合法。
- Android Span 应用失败。
- 文档 revision 不匹配。

日志必须包含任务 ID、文档 revision、语言/Grammar 标识、阶段、耗时、结果状态和错误代码；不得输出文档敏感内容或完整源代码。日志不能只打印“highlight success”，必须能定位失败层级。

## 13. 测试体系

### 13.1 Native 单元测试

- Parser 创建/销毁。
- 完整 UTF-8 文档解析。
- 空文档、非法输入、Unicode、CRLF/LF。
- Tree copy/edit/delete。
- Query compile / execute / cursor lifecycle。
- Match/Capture 完整性。
- Regex compile / execute / error。
- TSH Schema / Compiler / Runtime。
- Style conflict resolution。
- 内存分配失败和错误路径清理。

### 13.2 JNI 契约测试

- Java/Kotlin Native 方法与 C++ 签名一致。
- String / byte[] / ByteBuffer 输入长度正确。
- UTF-16 与 UTF-8 坐标转换。
- Native handle 重复 close 和 use-after-close。
- JNI 异常传播。
- 多线程调用边界。
- 大量 Match/Capture 返回时 Local Reference 不泄漏。
- Activity 销毁后 Native 回调不再访问旧对象。

### 13.3 JVM / Kotlin 测试

- API 生命周期包装。
- nullability 和错误映射。
- Parser / Tree / Query / Cursor 的关闭语义。
- revision 一致性。
- TSH 配置加载和编译诊断。
- Capture 到 Style 映射。
- Renderer 输入结果完整性。

### 13.4 Android 集成与仪器测试

- Activity 启动和退出。
- 打开不同语言文件。
- 加载 `.tsh` 配置。
- 首帧真实高亮绘制。
- 编辑后更新高亮。
- 快速切换文件。
- 旋转、后台恢复、销毁重建。
- 主题切换。
- 大量 Capture / 多行 Span。
- 设备内存压力与取消。

### 13.5 对照测试

同一份文档、同一份 Grammar、同一份 Query/TSH 规则，分别验证：
- 纯 Tree-sitter 原生解析结果。
- JNI 暴露的 Tree/Node/Query 结果。
- TSH ATS 管线结果。
- TSH Regex 管线结果。
- Style Resolver 输出。
- Android 最终 Span / 实际绘制结果。

必须能够逐层比对，不能只看最终截图。

## 14. 回归与正确性不变量

以下不变量任何提交都不能破坏：

1. 一个文档解析任务对应一个完整、连续、确定的输入快照。
2. 一个任务的 Tree、Query、Capture、Style、RenderResult 属于同一个文档 revision。
3. Tree-sitter Query 在完整语法树上执行，不使用 viewport 文本替代上下文。
4. ATS 与 Regex 是两条独立管线。
5. 不允许第三条 Tokenizer/窗口扫描执行管线。
6. Capture/Match 不得静默丢弃。
7. UTF-8 byte offset 与 UTF-16 code-unit offset 不得混用。
8. 所有 Native 资源均有明确生命周期和释放路径。
9. 任何过期结果都不得覆盖新文档渲染状态。
10. `.tsh` 颜色必须经过真实 Android 绘制链路。
11. Tree-sitter 0.28.0 上游实现不得被无意回退。
12. 本分支不得重新引入被明确排除的大文本专用技术。

## 15. 迁移实施阶段与交付物

### Phase A：基线盘点与依赖图

交付：
- 备份分支旧 JNI 文件清单。
- 当前 TSH 源码迁移清单。
- 0.28.0 上游 API 变更对照。
- 每个待迁移文件的依赖图。
- 所有 largefile/window/partition 依赖的调用链清单。
- Activity / Renderer / TSH 配置资源清单。

完成标准：每个需要迁移的文件有明确来源、目标路径、依赖和处理策略。

### Phase B：0.28.0 原生核心与常规 JNI

交付：
- 完整 Tree-sitter 0.28.0 Native 构建。
- 常规连续文本 JNI 输入。
- Parser / Tree / Node / Cursor / Query / QueryCursor 绑定。
- JNI 生命周期、异常、编码和坐标测试。
- 无 largefile 的 CMake 配置。

完成标准：Native 和 JNI 独立测试通过；全路径不引用被排除的输入抽象。

### Phase C：TSH Native Compiler / Runtime

交付：
- TSH Compiler。
- TSH Core。
- ATS Query Executor。
- 独立 Regex Executor。
- Style Resolver。
- Render ABI。
- 相关 Native 测试。

完成标准：TSH 可以在完整 Tree 与完整连续文档输入上运行，两条管线语义正确。

### Phase D：Java/Kotlin API 与 Activity

交付：
- Tree-sitter Java/Kotlin API。
- TSH Java/Kotlin API。
- Native handle 安全封装。
- 文档 revision 与任务调度。
- Activity / Editor Host。
- 配置加载和错误展示。

完成标准：无大文本专用 API、无悬空 JNI 方法、无生命周期泄漏。

### Phase E：Android 实际渲染

交付：
- Renderer。
- Capture/Style 到 Span 的转换。
- Span 更新和失效逻辑。
- 实际绘制集成测试。
- 日志和性能观测。

完成标准：设备上能确认 `.tsh` 配置颜色真实显示，并在编辑后正确更新。

### Phase F：清理、性能回归与发布门禁

交付：
- 全仓库禁止技术扫描报告。
- Native/JVM/Android 测试报告。
- ABI 与 0.28.0 版本核验。
- 性能基线和优化前后对照。
- 已知限制和剩余问题清单。

完成标准：构建、测试、渲染验证全部通过；没有未解释的依赖、失败或结果缺口。

## 16. CI / 构建矩阵

至少验证：

- Android ABI：项目实际支持的所有 ABI。
- Android NDK/CMake：仓库配置的最低与当前构建环境。
- Java/Kotlin 编译。
- Native C/C++ 编译。
- Tree-sitter 0.28.0 子模块初始化和构建。
- Oniguruma 构建与链接。
- TSH Native 单元测试。
- JNI 契约测试。
- Android instrumentation / renderer 集成测试。

CI 必须在干净 checkout 环境验证子模块初始化，不能依赖开发者机器残留文件。

## 17. 代码审查要求

每个 PR/提交必须回答：

- 是否新增了大文本专用抽象或等价行为？
- 是否存在未释放 Native 资源？
- 是否有 JNI 签名或坐标单位变化？
- 是否改变 Tree-sitter 0.28.0 的 API 语义？
- 是否改变 Query Predicate / Capture 完整性？
- 是否影响 Regex 语义？
- 是否可能让旧 revision 覆盖新结果？
- 是否改变 Style 优先级或颜色解释？
- 是否真实验证了 Android 绘制层？
- 是否新增了未经测量的缓存或对象池？
- 是否有对应测试和日志证据？

禁止以“本地能编译”“Native 返回了数据”或“截图看起来正常”单独作为完成依据。

## 18. 不允许的完成声明

以下情况不得宣称任务完成：

- 只迁移了文件，没有编译。
- 只编译 Native，没有验证 Java/Kotlin JNI 签名。
- 只生成 TSTree，没有验证 Query。
- 只得到 Match/Capture，没有验证 Style。
- 只得到 RenderPlan，没有验证 Android Span 和实际绘制。
- 只在短文本上通过测试，没有做较大文档回归。
- 仅删除 `cpp/largefile/` 目录，但仍有等价窗口/分区实现。
- 仍有悬空的 Java/Kotlin API、JNI 导出或 CMake 源文件引用。
- 0.28.0 子模块未固定或实际构建使用了其他版本。
- 测试有失败但没有解释和处置。
- 用静默截断或跳过规则掩盖性能问题。

## 19. 最终验收清单

- [ ] 分支祖先为 `back_main_0e9051c`。
- [ ] Tree-sitter 上游源码完整升级至 0.28.0。
- [ ] 0.28.0 子模块 SHA 与版本声明一致。
- [ ] Native 构建不包含 `cpp/largefile/`。
- [ ] 无 TextSource/Chunk/Segment/Partition/PieceTree/Window 执行依赖。
- [ ] 无窗口化 Parser、Query、Regex、Token 和 checkpoint 执行路径。
- [ ] 仅保留 ATS / Tree-sitter Query 与独立 Regex 两条 TSH 执行管线。
- [ ] JNI 输入是完整、连续、长度准确的文档快照。
- [ ] Java/Kotlin API 与 JNI 签名一致。
- [ ] Native handle 生命周期、异常和线程安全测试通过。
- [ ] Parser / Tree / Query / Cursor 测试通过。
- [ ] TSH DSL Schema / Compiler / Runtime 测试通过。
- [ ] Capture / Match 无静默截断。
- [ ] UTF-8 / UTF-16 坐标映射测试通过。
- [ ] Activity 生命周期和异步任务测试通过。
- [ ] `.tsh` 颜色映射正确。
- [ ] Android Span / 编辑器绘制层实际显示颜色。
- [ ] 修改、撤销、重做、切换文件后高亮正确。
- [ ] 小、中、较大文档的功能和性能回归通过。
- [ ] Native / JVM / Android CI 全部通过。
- [ ] 全仓库禁止技术扫描无未解释命中。
- [ ] 所有剩余限制均已记录，不存在虚假完成声明。

## 20. 当前状态与执行记录

### 已完成

- [x] 创建本迁移分支，基于 `back_main_0e9051c`。
- [x] 固定 Tree-sitter 上游 0.28.0 子模块提交。
- [x] 建立本任务文档和明确的禁止技术边界。

### 尚未完成

- [ ] 备份分支 JNI 源码与当前 0.28.0 API 的逐文件对照。
- [ ] TSH Native 源码迁移和无 largefile 重构。
- [ ] Java/Kotlin Tree-sitter API 迁移。
- [ ] TSH Java/Kotlin API 迁移。
- [ ] Activity / Renderer / 资源迁移。
- [ ] 全仓库禁止依赖扫描。
- [ ] Native / JVM / Android 构建。
- [ ] 设备真实高亮绘制验证。
- [ ] 性能基线与回归报告。

**本文件是任务规范，不代表上述未完成项目已经实现。** 每个阶段完成后必须更新本节执行记录，并附上实际 commit、构建结果、测试结果和已知问题。

---

## 附录 A：本任务最重要的判断标准

本分支的目标不是证明某种大文本框架能工作，而是回答一个更基础的问题：

> 如果只使用 Tree-sitter 原生引擎、常规 JNI 绑定和 TSH 引擎，不使用此前任何大文本专用处理技术，是否仍能把完整文档正确解析、查询、映射为样式，并让 Android 编辑器真实绘制出 `.tsh` 配置的高亮颜色？在这条最直接的链路中，究竟有哪些 JNI、Native、TSH 和渲染性能/正确性缺陷？

所有实现、优化和验收都必须围绕这个问题展开。
