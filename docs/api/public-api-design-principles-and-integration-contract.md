# TSH Public API 设计总规范与宿主接入契约

> 本文是 TSH 公共 API 的最高层规范。领域文档可以细化本规范，但不得与本规范冲突。规范定义不代表源码实现、构建或设备验证已经完成。

## 1. 唯一产品目标

让第三方开发者把 TSH 接入自己已有的 Editor、Document、TextBuffer、Piece Tree/Rope、SyntaxTree、Layout/Viewport、Theme/Style 和 Renderer 时，不需要替换对象、不需要继承 TSH 类型、不需要重构编辑器架构，也不需要理解 JNI/Native 内部实现。

“无障碍接入”不是承诺自动兼容未知私有控件，而是每类能力均有稳定 Provider/Adapter 契约、能力协商、缺失诊断、最小实现路径和可运行示例。

## 2. 不可违反的设计原则

1. **Host Ownership First**：宿主创建、拥有、销毁自己的对象。TSH 不接管 Editor/Document/Buffer/Layout/Renderer 生命周期。
2. **No Framework Lock-in**：核心 API 不要求继承 TSH 基类，不依赖 Android View、Canvas、Compose、Spannable 或特定编辑器 SDK。
3. **Capability-based Integration**：按能力接入；只实现宿主缺失的能力。能力可检测、版本化、协商、诊断和降级。
4. **Zero-copy Where Possible**：Piece Tree/Rope/Gap Buffer 通过范围读取、分块读取和 Snapshot Lease 接入；不得强制全文 flatten。需要连续 Native 内存时必须显式 Buffer Lease、编码、长度和释放时机。
5. **Explicit Coordinates**：每个 Offset/Range 明确坐标单位、编码、文档版本和区间语义。禁止裸 Int/Long 在 UTF-8 byte 与 UTF-16 code unit 间隐式转换。
6. **Version-safe Execution**：所有异步任务和结果绑定 document/config/grammar/query/theme/viewport revision；提交前校验版本，过期结果拒绝并产生可观测状态。
7. **Two Pipelines Only**：语法查询只有 Tree-sitter + ATS 管线；Regex/Oniguruma 是独立执行管线。两者只在统一 Match 层汇合，不引入 Monarch/TextMate 第三执行管线。
8. **Backend-independent Styling**：Match、Scope、Attribute、ResolvedStyle 不依赖 UI 框架，不直接携带 Canvas/Span 绘制对象。
9. **Renderer-owned Drawing**：TSH 生成 RenderPlan/RenderInstruction；宿主 Renderer 执行实际绘制并返回 Receipt/Ack。
10. **Observable Results**：Attach、Provider 选择、降级、匹配、样式解析、Render Submit、实际 Draw 都能查询状态和诊断。
11. **No Silent Degradation**：未知属性、缺少必需能力、坐标不合法、Provider 异常、过期结果、渲染失败不得静默忽略。
12. **Stable Public Contract**：公共契约与 Native/JNI/cache/thread-pool 内部实现隔离；实现可以替换，调用方依赖不变。

## 3. 宿主对象与所有权

| 对象 | 所有者 | TSH 允许做什么 | TSH 不允许做什么 |
|---|---|---|---|
| Editor / EditorView | 宿主 | 注册身份、读取能力、建立 Binding | 强制继承、替换实例、控制编辑命令 |
| Document / TextBuffer | 宿主 | 获取不可变 Snapshot、读取范围、订阅变更 | 接管编辑事务、强制转换存储结构 |
| Piece Tree / Rope / Gap Buffer | 宿主 | 通过 TextAccess Provider 零拷贝/分块读取 | 要求全文转 String |
| SyntaxTree / Grammar | 明确的 Native Resource Owner | 通过 Lease 安全使用和退役 | 绕过 Lease 直接 close 宿主资源 |
| Layout / Viewport | 宿主 | 请求坐标映射、读取可见范围 | 假定所有宿主具有相同布局模型 |
| Theme / Style | 宿主或 TSH Config Repository，必须声明 | 解析/快照/版本绑定 | 隐式覆盖宿主主题 |
| Renderer / Canvas / Span / Compose | 宿主 | 提交 RenderPlan、接收 Receipt | 清理宿主全部 Span 或越过生命周期持有 UI 对象 |

注册表强/弱引用策略必须显式。强引用必须绑定 OwnerScope/RegistrationLease 并支持幂等 detach；弱引用不能替代生命周期协议。detach 后必须取消或失效相关任务、订阅和绑定。

## 4. Attach / Detach 与能力协商

公共接入流程必须能以一个可诊断的 Attach 操作完成，而不是要求调用者分别猜测并组合多个 Registry。

规范流程：
1. 提交宿主对象、对象角色、OwnerScope 和期望能力。
2. 校验对象身份、API 版本、必需/可选能力和 Provider 兼容性。
3. 按确定性优先级选择 Provider；同优先级冲突必须诊断，禁止依赖偶然注册顺序。
4. 返回 Attachment Handle 与 Attachment Report。
5. inspectAttachment 可查询 Provider 选择、缺失能力、降级级别、版本、诊断和运行状态。
6. detach 幂等；停止新任务、取消任务、解绑事件、释放 TSH 自有资源，但不销毁宿主对象。

Provider Resolution 至少报告 selected/rejected providers、必需能力缺失、可选能力缺失、能力版本不兼容、API 版本不兼容、supports 异常和 degradation level。第三方 supports/回调必须在 Registry 锁外执行并隔离异常。

## 5. 文本访问与快照

核心文本契约应支持：
- 不可变且固定版本的 Snapshot；
- UTF-16/UTF-8 明确范围读取；
- readChunk/readRange，允许范围跨 Piece Tree/Rope 叶子；
- Snapshot Lease，确保异步读取期间底层版本仍可读；
- 文档变更订阅、销毁通知和版本检查；
- 明确线程安全、并发读取和取消契约。

变更事件必须携带 documentId、beforeVersion、afterVersion、坐标系和有序 Edit 列表。多个 Edit 的排序、重叠、应用方向必须定义；不满足契约时返回诊断。读取超大文件时只能按窗口/块拉取，不允许为方便 API 而生成完整 String。

## 6. 坐标和 Unicode

公共坐标必须具备可审计的单位/编码/版本信息。推荐以 Long 表示文档绝对 Offset；Native API 的 Int/UInt/size_t 边界只在 Adapter 内显式转换并检查溢出。

必须覆盖 UTF-8、UTF-16、Unicode code point、grapheme、CRLF/LF、组合字符、Emoji ZWJ、非法/不成对 surrogate。对不成对 UTF-16 surrogate 默认拒绝并诊断，不允许自定义长度计算与编码器替换行为不一致。Grapheme 算法必须声明 Unicode/UAX #29 兼容等级和运行时版本；JDK BreakIterator 不能宣称完整 UAX #29。

Piece Tree/Rope 坐标转换必须由索引化 Provider 完成，不可 flatten。Line/column 与 screen pixel 必须通过 Layout-aware Provider，并声明列单位、制表符、换行和 bidi 规则。

## 7. 执行、任务和版本

每个任务都声明输入 revision、优先级、取消语义、资源预算、线程约束和结果 revision。取消必须区分尚未开始、协作式取消、Native 调用不可中断、已完成；不得虚报即时中断。预算字段只有底层确实执行时才可宣称受限；不支持的 steps/time/memory budget 必须返回 unsupported diagnostic。

结果在消费/提交前检查 document、grammar、query、config、theme、viewport 和 renderer revision。任何 revision 不匹配都拒绝应用，不允许旧结果覆盖新内容。

## 8. 双执行管线与统一 Match

- Tree-sitter 原生 Query 与 ATS 编译到同一 Tree-sitter 查询执行管线。
- ATS Lexer/Parser/AST/Compiler/Lowering 是语法前端，不是第三执行管线。
- Regex/Oniguruma 独立执行，窗口/连续缓冲区/跨窗口匹配语义由 Regex API 定义。
- 两条管线输出统一 Match，保留来源、文档版本、范围、优先级和诊断。
- Match 不直接决定最终颜色；Scope/Selector/Attribute/Style Resolver 负责语义映射。

## 9. 样式与真实渲染闭环

必须保留可解释链路：

Document Range → Match → Scope → Selector → Style Rule → Resolved Style → RenderPlan → Renderer Submit → Render Receipt/Ack → 实际 Draw

RenderPlan 必须绑定 document/config/theme/viewport/renderer revision，支持全量/局部/Viewport 更新、异步回执、过期拒绝和部分失败。Renderer 能力必须明确声明颜色、字体、Decoration、混合/覆盖策略。Span 后端只能移除由 TSH 标记并拥有的 Span，不得清理宿主 Span。

“RenderPlan 已生成”不等于“屏幕已绘制”。必须通过宿主 draw callback/可观察回执或真实控件测试证明颜色可见。

## 10. DSL Schema 与 Runtime Consumer

每个属性都必须定义 canonical name、值类型、nullable、default、单位、范围、允许值、版本、继承/合并策略、必需能力、运行时 Consumer、序列化和诊断。默认值必须经过与显式值相同的类型/单位/范围/能力校验。未知属性默认 ERROR；任何兼容性忽略都必须生成 WARNING 并保留原始值/诊断，禁止无声丢弃。Measure 范围必须在同一单位或显式转换到规范单位后比较。Map 必须定义 Key 与 Value 类型。

内建属性与扩展属性走同一 Schema/Consumer/Capability 路径。Schema 验证通过只代表静态合法；只有存在 Runtime Consumer 且执行成功，属性才算生效。

## 11. 错误、线程和扩展安全

错误统一包含稳定 code、stage、severity、对象/文档标识、版本、range、cause 和可恢复建议。参数错误、生命周期状态错误、Provider 错误、Native 运行错误、预算耗尽、取消和 stale result 不可混为一类。

所有公共回调声明调用线程、是否允许重入、是否持锁、异常处理和调度器。绝不在 Registry/Lease 内部锁中执行第三方回调或 Native close。扩展卸载必须使其 Provider、任务、监听器和缓存失效。

## 12. 明确不属于 TSH 的职责

Selection/Cursor/Multi-Cursor、Undo/Redo、编辑命令、Gutter、Outline、Folding、Symbol Navigation、Inlay、CodeLens、Workspace/Project 管理由宿主负责。TSH 只提供其运行所需的文本访问、坐标、语法查询、语义样式和 RenderPlan 适配契约。

## 13. 规范状态与证据等级

- SPEC_ONLY：只有规范。
- SOURCE_PARTIAL：有部分源码，未覆盖完整契约。
- SOURCE_COMPLETE：源码覆盖规范范围，仍不代表可构建。
- BUILD_VERIFIED：指定模块/变体构建通过，附命令和 commit。
- TEST_VERIFIED：指定测试集通过，附命令和结果。
- DEVICE_VERIFIED：指定设备/Android/API/真实控件端到端验证通过。

状态只能逐级提升，不能以文件存在、测试源码存在或静态审计代替运行证据。每个领域指定唯一 normative source，其余文档只概览并链接，避免冲突重复定义。

## 14. 最小宿主接入验收

一个第三方编辑器接入示例必须证明：不继承 TSH 类型；保留宿主 Document 和 Piece Tree；通过 Provider 读取固定版本 Snapshot 与分块文本；只提供宿主具备的能力；Attach Report 指出缺失能力和降级；Tree-sitter/ATS 与 Regex 双管线独立运行并汇合 Match；结果经过 Scope/Style Resolver；RenderPlan 提交宿主 Renderer；Renderer 只更新 TSH 自有绘制对象并返回 Receipt；文档/配置/主题变化和 detach 后旧任务/结果不会污染宿主 UI。
