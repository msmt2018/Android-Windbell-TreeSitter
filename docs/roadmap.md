# TSH 引擎开发计划地图

> 产品目标：让第三方开发者将 TSH 接入已有 Editor、Document、Piece Tree/Rope、Layout、Theme 和 Renderer 时，不需要替换宿主对象或重构编辑器。TSH 只负责语法/查询/Regex/Match/Scope/Style/RenderPlan，不成为通用编辑器框架。
>
> 最高层规范：[TSH Public API 设计总规范与宿主接入契约](api/public-api-design-principles-and-integration-contract.md)。

## 状态口径

- SPEC_ONLY：只有规范。
- SOURCE_PARTIAL：有源码切片，未覆盖完整契约。
- SOURCE_COMPLETE：源码覆盖规范范围，仍不代表可构建。
- BUILD_VERIFIED：指定模块/变体构建通过，记录命令与 commit。
- TEST_VERIFIED：指定测试集通过，记录命令与结果。
- DEVICE_VERIFIED：指定设备/API/真实宿主控件端到端验证通过。

本分支本轮新增与修复内容目前均最多为 SOURCE_PARTIAL。未运行 Gradle/JUnit/CMake/NDK/JNI/Android 设备验证，不得标为 BUILD_VERIFIED、TEST_VERIFIED 或 DEVICE_VERIFIED。

## 本轮已落地的源码切片

### 公共 API 与宿主接入

- [x] 新增公共 API 最高层设计规范：Host Ownership First、No Framework Lock-in、Capability-based Integration、Zero-copy Where Possible、Explicit Coordinates、Version-safe Execution、Two Pipelines Only、Renderer-owned Drawing、No Silent Degradation。
- [x] SourcePosition Offset 与 Tree-sitter ByteRange 改为 Long，保留 Int 构造兼容入口。
- [x] Native TSQueryCursor Int byte-range 边界显式检查，超过 Int.MAX_VALUE 拒绝而不是截断。
- [x] Provider API version range、Capability 最低版本、优先级、稳定排序、冲突/缺失诊断与 supports 异常隔离。
- [x] TshEngineOptions.strictProviderValidation 与 enableDiagnostics 接入 Provider resolution。
- [x] 新增 OwnerScope、AttachmentRequest/Handle/Report、AttachmentManager，以及 Engine attach/inspect/refresh/detach。
- [x] OwnerScope 自动 detach；detach 只注销 TSH 自己的身份登记，不关闭宿主对象；重入 close 不会造成 cleanup 死锁。
- [x] Native resource/query raw object getter 收敛为 internal，阻止宿主绕过 Lease 直接 close。
- [x] Engine lifecycle lock 线性化 initialize/attach/provider resolution/adapter creation/parser open 与 close；公开 Object/Provider/CompiledQuery registries 在 Engine close 后永久拒绝新注册。
- [x] strictProviderValidation 统一覆盖 attach/refresh/resolveProviders；严格 attach 缺失必需能力时 detach 回滚，lenient 模式保留 INCOMPLETE report。
- [x] 同一宿主对象支持多个 OwnerScope/Attachment 并以引用计数管理注册；宿主外部拥有的 ObjectRegistry 注册不会被 TSH detach 注销。
- [x] Attachment detach notifications 驱动 Android Binding 关闭 RenderCoordinator、清理本 Renderer 拥有的 Span；Engine close/OwnerScope close 后拒绝新 RenderPlan 和迟到回执；后台清理由宿主 postToRenderThread 派发。
- [x] Native close/dependency close 错误进入结构化诊断；Engine 提供 shutdownDiagnostics；OwnerScope 提供 closeWithReport。

### Text Access 与坐标

- [x] TextSnapshot Lease、bounded chunk read、TextChange subscription 扩展点、Document disposed subscription 扩展点。
- [x] TshTextSnapshotLease.retain() 与 TshSharedTextSnapshotLease：独立句柄引用计数、最后引用释放回调、旧自定义 Lease 的显式 unsupported 诊断；默认不可变快照 Lease 可续租；默认 Snapshot acquisition 校验元数据并结构化报告 Provider 异常（SOURCE_PARTIAL）。
- [x] Text Provider 声明线程策略与 concurrent-read 能力；UNKNOWN 默认不作线程安全假设。
- [x] TextEdit replacement 拒绝不成对 surrogate；TextChange Edit 列表要求 before-version 坐标、升序且不重叠。
- [x] Chunk read 不允许切开 UTF-16 surrogate pair，读取关闭的 Snapshot Lease 会失败。
- [x] 坐标转换拒绝不成对 UTF-16 surrogate；UTF-8 byte 累积使用 Long；非 String CharSequence 明确拒绝隐式 flatten/toString 复制。
- [x] 项目 editor 模块新增真实 PieceTreeTextBuffer → PieceTreeSnapshot → TSH Snapshot Provider；不可变 PieceTreeSnapshot 保留原始 chunk 引用并复制活动 change buffer 有效文本，Lease 可独立持有旧版本（SOURCE_PARTIAL）。
- [x] EditorView Binding 在编辑线程的 before/after transaction 边界创建 PieceTreeSnapshot，并通过 EditorDocument 替换事件在同一 documentId 下自动触发 Session.resync（SOURCE_PARTIAL；尚未完成并发/设备验证）。
- [ ] 其他可变 Piece Tree/Rope 仍需由宿主提供读写互斥或 pin persistent root/unpin。
- [x] 新增 `TshSparseCoordinateIndex`：绑定 documentId/version/length，独立 retain Snapshot Lease；调用方关闭原始 Lease 不影响索引，索引 `close()` 后释放自己的 Lease；构建按有界 chunk 读取，转换按实际 checkpoint stride 分块扫描，支持 UTF-8/UTF-16/Code Point 并拒绝字符编码中间偏移（SOURCE_PARTIAL，新增大 stride 与 Lease 生命周期测试，待 CI）。
- [x] `PieceTreeSnapshot` 新增基于 Piece 起始偏移、原始源缓冲区坐标、lineStarts copy-on-write snapshot view 和 piece line-feed prefix 的 Tree-sitter 坐标查询；`TshTextSnapshot.supportsLineColumnLookup/lineColumnAtUtf16` 将宿主原生坐标索引暴露给 EditCompiler，非法端点不会触发全文扫描（SOURCE_PARTIAL，测试源码已补，待 CI）。
- [ ] 该坐标索引是 Snapshot 固定的 Piece 元数据查找，不维护编辑间可复用的独立增量索引对象；Rope 通用 Provider、CR-only/异常换行快速索引、完整 Grapheme（UAX #29）转换仍未实现。
- [ ] Grapheme 目前使用运行时 BreakIterator，不宣称完整 UAX #29。
- [x] 项目 PieceTree Provider 的 change/disposal subscription 已由 EditorDocument edit callback、Binding.publishChange 与 Binding.close 接线；Buffer replacement 现通过独立生命周期事件通知 EditorView observers（SOURCE_PARTIAL）。
- [ ] 文档永久关闭自动 dispose、文件身份切换自动关闭旧 Session/重新 attach，以及真实线程竞态验证尚未完成。

### DSL / Schema / Runtime Consumer

- [x] 修复 nullable 与 OptionalType 混淆、默认值校验、Measure range unit、Map key type、Capability version 和非数值 range。
- [x] Schema 数值范围比较保留 Long 整数精度，避免超过 2^53 后转 Double 丢失精度；新增边界回归测试源码。
- [x] 未知属性不再静默丢弃；进入 unappliedProperties 并产生诊断。
- [x] 新增 RuntimeConsumerRegistry/Dispatcher；Schema 通过不再等于属性已生效。
- [x] Runtime Dispatcher 报告每个属性 applied/unapplied，检查 Capability 版本并隔离 Consumer 异常。
- [ ] 内建 Schema Catalog、ReferenceResolver、完整 SemanticAnalyzer、ATS Lowering 和内建 Runtime Consumer Catalog 尚未完成。
- [ ] .tsh Lexer/Parser 尚未实现；typed AST 仍为 parser-neutral 模型。
- [ ] 既有 C++ TshCompiler 尚未接入统一 Schema/Runtime Consumer 契约。
- [ ] Runtime Consumer 目前逐属性 best-effort，不提供跨 Consumer 原子事务。

- [x] 新增 `TshTreeSitterEditCompiler`：优先使用 Edit 自带 before-version 坐标，其次使用 Snapshot 原生坐标索引；无索引时仅允许 ≤1 Mi UTF-16 的兼容性分块扫描，超限明确返回 `TSH-EDIT-COMPILE-009`，避免大文件小编辑触发 O(n) 全文扫描。将多 Edit 编译为 UTF-16LE TSInputEdit 并按降序输出（SOURCE_PARTIAL，新增超限拒绝测试，待 CI）。
- [x] 新增 `TshTreeSitterIncrementalUpdate`：接通 before/after Snapshot → Edit Compiler → 原子 Native Batch Edit → Incremental Parse → 新树发布/旧树回收；编辑后旧树标记 `EDITED_PENDING_PARSE`，QueryExecutor 在 Native Lease 前后拒绝访问，替代树标记 READY（SOURCE_PARTIAL，未运行 Native/JUnit）。

### Tree-sitter / ATS Query

- [x] Tree-sitter Native Query Compiler/Executor Adapter、Query Registry、Query/Tree/Language Lease 有源码。
- [x] TshQueryCompiler 与 ATS 专用 TshAtsCompiler 契约分离；Native S-expression Adapter 不再冒充 ATS Compiler。
- [x] ATS 输入当前明确返回 unsupported；文档状态统一为 ATS Lexer/Parser/AST/Compiler/Lowering 尚未实现。
- [x] strictNodeValidation 默认关闭；显式启用会返回 unsupported，而不是静默忽略。
- [x] maxSteps 默认 null；显式请求会在进入 Native 前返回 unsupported；stepsUsed 为 null 表示 Native API 不提供计数。
- [x] maxMatches 同时配置 Cursor pending-match limit 和 drain 阶段总返回 Match 上限。
- [x] Long ByteRange 在进入 Int-limited Native Cursor 前显式检查。
- [ ] ATS Lexer/Parser/AST/Compiler/Lowering 尚未实现，ATS 最终必须编译到现有 Tree-sitter Query 管线。
- [ ] Grammar Node Schema Provider / strictNodeValidation 尚未实现。
- [ ] Tree-sitter 真实 step budget、Native 可中断取消、增量 Edit/changed-range 尚未实现。
- [x] TSParser.parseInput(TSInput) / JNI callback / TshSnapshotTreeSitterInput / ParserOwner Snapshot overload 分块解析源码；该入口会独立 retain 快照 Lease，并在 Native parse 返回后释放（SOURCE_PARTIAL）。
- [x] TshTextAccessProvider 纳入 Engine Provider Registry；ProviderRegistry.acquireScoped 引用计数共享 Provider 生命周期；新增 TshTextAccessBinding 管理 Document Attachment、Snapshot Lease 获取、Change/Disposal subscriptions 与生命周期。text.snapshot/text.chunk 与可选 change/disposal notification 通过 capability negotiation 暴露。
- [x] OwnedSyntaxTree exclusive edit lease、snapshot/registry metadata revision 同步和 QueryExecutor stale snapshot rejection 源码。
- [x] 项目 PieceTreeTextBuffer 的 Snapshot/Chunk Provider 源码与测试源码（SOURCE_PARTIAL）。
- [x] 新增 `TshPieceTreeEditorBinding`：在 EditorView 批量编辑前后固定 PieceTree Snapshot Lease，将 AppliedChange 转成不物化替换文本的元数据型 TshTextChange；EOL normalized 时复用编辑器行列坐标避免每次全文件扫描，非 normalized 时回退分块扫描；Provider 增加 Change/Disposal subscription capability 与事件；源码及转换测试已提交（SOURCE_PARTIAL）。
- [x] 新增 `TshPieceTreeIncrementalSession`：每文档单线程串行化初始 Parse、增量 Edit/Parse、pending parse retry、full-parse fallback 与旧树回收；pending 工作 coalesce 到最多一个批次/快照，避免快照队列无界增长；Session 接管并 finally 释放快照 Lease（SOURCE_PARTIAL，未运行测试）。
- [x] 同一 EditorView 的 backing Buffer replacement 通过 EditorDocument.onBufferReplaced → EditorView observer → TshPieceTreeEditorBinding → Session.resync 自动重新解析（SOURCE_PARTIAL）。
- [ ] 文件身份变化自动重建 Binding、宿主永久关闭自动 dispose，以及端到端 Native parse integration test。
- [ ] 旧宿主所有 Tree/Language/Parser owner 尚未全部迁移到统一 Resource Registry。

### 独立 Regex / Oniguruma

- [x] 修复 UTF-8 Regex pattern 被逐字节复制为 UTF-16 的问题，改为严格 UTF-8 解码和 UTF-16LE 编码。
- [x] OnigRegion 使用 RAII 管理，避免分支异常/早退时泄漏。
- [ ] Regex WindowLease、连续 Native buffer、Piece Tree chunk provider 尚未实现。
- [ ] 有界 overlap、无界模式整段回退/拒绝语义尚未实现。
- [ ] Regex 增量 dirty range、预算、超时、取消与 Native 测试尚未实现。

### 统一 Match / Scope / Selector

- [x] 新增 TshMatch/TshMatchSet/TshScopePath/TshMatchMerger 首版纯 Kotlin 模型。
- [x] Match 具有 canonical UTF-16 range、source provenance、pipeline revision、document version。
- [x] MatchMerger 支持两来源汇合、去重、稳定排序、stale Match 排除和 partial 状态。
- [ ] Tree-sitter/ATS Capture 与 Regex 执行结果尚未接入 TshMatch。
- [x] 首版 ScopeSelector AST、exact/suffix path 匹配、StyleRule priority/specificity merge、MatchSet→RenderPlan resolver 源码与测试源码。
- [ ] Scope parser/stack/assignment pipeline、DSL Selector parser/compiler/specificity 与高级冲突策略尚未完成。
- [ ] 完整重叠/包含/交叉冲突策略与跨管线一致性测试尚未完成。

### Style / Render / 真实绘制

- [x] 新增平台无关 ResolvedStyle、RenderInstruction、RenderPlan、Renderer Provider、Receipt、Coordinator。
- [x] Coordinator 校验 document/config/theme/viewport/renderer revisions 和 Provider capabilities。
- [x] 异步 QUEUED/Ack、过期回执拒绝、PARTIAL/FAILED/REJECTED 诊断和 Provider cancel 扩展点。
- [x] Provider 必须在实际 Draw 前检查 Acknowledger.isCurrent，完成后 Ack。
- [x] Render Receipt 区分 APPLIED/PARTIALLY_APPLIED 宿主对象变更与 DRAWN/PARTIAL 实际绘制；Span renderer 不再把 invalidate 冒充真实 Draw。
- [x] Android Spannable Renderer 使用 generation staging/rollback；host adapter 回调、Span snapshot/inspection、invalidation 失败均返回诊断并尽力恢复旧 TSH Span 和宿主 Span，新增 Robolectric 回归测试源码。
- [x] QUEUED 不允许声称已有应用/绘制数量，真实 PARTIAL draw 必须报告有效部分数量；Receipt 必须完整核算 applied/deferred/dropped，viewport 外指令 deferred。
- [x] RenderCoordinator 限制 pending plan 数量，防止异步 Provider 不回执时无界积累；Provider LinkageError 转为结构化诊断并清理 pending。
- [x] 旧 Spannable 路径只清理 TSH-owned Span，不再删除宿主 CharacterStyle；非法/奇数/越界 UTF-16LE byte range 被拒绝。
- [x] ScopeSelector/StyleRule/StyleResolver 到 RenderPlan 的首版 Resolver；[ ] Attribute semantic pipeline、DSL selector parser 和完整 Resolver。
- [x] Android Spannable Renderer Provider + OwnerScope-scoped host binding，Provider Registry 可按宿主对象身份发现；Render generation 事务回滚保留旧 TSH/宿主 Span；强制 host 提供有界 viewport 后才分配 Span，缺失 viewport 直接拒绝；离屏指令记为 deferred；Receipt 对 applied/deferred/dropped 完整计数；Binding cleanup failure 可通过 closeDiagnostics 查询；Robolectric test 源码覆盖 rollback、decoration、binding、viewport。 [ ] Canvas/Compose/custom View Renderer Provider 尚未实现。
- [ ] Robolectric test 运行、真实 Draw 回执、像素/颜色可见性、字体/Decoration/主题切换设备验证尚未完成。
- [x] editor 模块新增 `TshRenderPlanSyntaxHighlighter`：将 UTF-16 RenderPlan 指令映射为 EditorView `LineStyler` 可识别的 Foreground/Background/Style/Underline/Strikethrough Span；按行区间索引查询，并使用 128 行/16,384 Span 有界 LRU 投影缓存（不缓存行文本），避免每帧扫描整份 RenderPlan；版本不匹配时拒绝旧样式；当前 StyledTextPainter 不支持的字体族/字号/删除线产生 warning（SOURCE_PARTIAL，待 CI 与设备验证）。
- [x] RenderPlan highlighter 的 plan publication 与编辑回调通过同一锁线性化；编辑事务期间拒绝新计划，提交前再次读取 PieceTree 版本，避免异步 Query/Style 结果覆盖正在进行的编辑（测试源码待 CI）。
- [ ] RenderPlan/Receipt 源码与 LineStyler 桥接均不代表所有 `.tsh` 属性都已绘制，也不代表设备像素已验证；仍需完整应用层配置加载、Theme/Scope/Attribute 映射及真实控件端到端验证。

### JNI / 大文本 / Native 生命周期

- [x] JNI 高亮/折叠/Outline 路径移除额外完整 UTF-16 Native vector copy，改用作用域化 GetStringChars。
- [x] Highlight/Fold 结果按固定批次写入 Java 数组，避免额外 N*3 临时 Native 数组。
- [x] Span 初始 reserve 不再按全文长度无界预分配。
- [x] AST/Regex Span overlap 从双重全量扫描改为 prefix-max-end 索引。
- [x] JNI 大文本 Highlight/Fold/Outline Native 异常转换为 Java 异常，不让 C++ exception 越过 JNI。
- [ ] GetStringChars 仍由 JVM 决定 pin/copy，不能称为 zero-copy。
- [x] Tree-sitter 流式 Input callback / JNI callback / Snapshot chunk parser 源码已存在；输入回调会校验 Provider 返回的文档版本、范围、长度、UTF-16 合法性与 surrogate 边界（SOURCE_PARTIAL）。
- [ ] 全量 Native operation/handle/generation manifest、ASAN、泄漏和设备压力测试尚未完成。

## 仍需完成的缺陷与绑定项

1. 完成剩余宿主生命周期接线：文件身份变化时自动关闭旧 Session 并重新 attach，文档永久关闭时自动 dispose；补齐 UTF-8/UTF-16/CodePoint 索引 Provider。
2. 为 EditorView Binding/IncrementalSession 补充真实批量编辑/撤销/重做 Native parse integration test、快速连续输入、初次 parse 竞态、自动 buffer replacement/resync 和关闭竞态验证。
3. 实现 Regex WindowLease 和跨窗口正确性语义；无界模式必须有明确回退/拒绝策略。
4. 实现 ATS 前端并 Lower 到唯一 Tree-sitter Query 执行管线。
5. 将 Tree-sitter/ATS 与 Regex 输出真正适配到统一 TshMatch。
6. 实现 Scope/Selector/Attribute/Style Resolver，将 Match 生成 RenderPlan。
7. 实现 Android 宿主 Renderer Provider，严格管理 TSH-owned draw state，并用真实控件回执验证颜色可见。
8. 完成 Engine/Provider/OwnerScope/Native Lease 并发、关闭、替换和失败清理测试。
9. 执行 Gradle/JUnit/CMake/NDK/JNI 全矩阵；随后在 Android API/ABI 与真实宿主编辑器上验证。
10. 逐个领域收敛 normative source；保持旧 WindbellTshEngine/JNI 兼容迁移，不引入第三执行管线。

## 当前证据结论

- 源码：上述切片已提交到 feat/tsh-unified-api-foundation；新增 Render Receipt 状态语义、Schema Long 数值精度修复及回归测试源码。
- 构建：未验证。
- 单元测试：新增测试源码，未运行。
- Native/JNI：未构建/运行验证。
- Android 设备：未验证。
- 超大文件：已减少 JNI/Span 中的若干额外内存和 O(m*n) 扫描，但未证明 200–300 MB 文件端到端稳定高亮与真实绘制。
- PR #2：保持 Open；当前非 Draft，未合并。

### 2026-11-10 CI 回归修复记录（SOURCE_PARTIAL）

- [x] 修复 PieceTreeSnapshot getNearestChunk() 默认 Int.MAX_VALUE 请求导致的 Int 溢出；修复覆盖 PieceTreeBase 与 Snapshot 并发读取失败的共同根因。
- [x] 修复 CoordinateConverter expression-body return 和 Long 结果适配器泛型约束。
- [x] 对齐 TextAccessProvider disposal callback 参数。
- [x] 修复 ParserOwner 两处 Native metadata Success 结果解包。
- [x] Query predicate 检查改为扫描经 Native 编译器验证的 S-expression 源码，忽略字符串字面量与 `;` 行注释，避免依赖构建变体可能缺失的 `TSQuery.predicatesFor(UInt)` 方法；仅要求外部 handler 声明自定义 predicate，并排除内置 predicate（新增测试源码，待 CI 验证）。
- [x] 修复 RenderCoordinator close 诊断构造与 Warning severity。
- [x] CI 固定 Node.js 20；debug test job 与 release job 一样安装全部 grammar npm dependencies。
- [ ] 等待最新 feature-branch Actions 完成；需要依据最新 commit 的完整日志修复剩余编译/测试/Grammar 生成问题，不能引用旧 commit 的结果。

### 2026-11-10 follow-up：PieceTree 双路径越界与 CI 队列

- [x] 不只修复 PieceTreeSnapshot；同时修复 PieceTreeBase.getNearestChunk() 中等价的 targetOffset + Int.MAX_VALUE 溢出。
- [x] 回归测试同时覆盖 live buffer 与 immutable snapshot 的 Int.MAX_VALUE、有限 maxLength、文档末尾读取。
- [x] CI 增加 workflow concurrency group，自动取消同分支/PR 的过期运行；移除重复的单 job 取消 action。
- [x] Debug grammar npm install 与 Release 对齐，均提供 legacy peer dependency fallback。
- [ ] 最新提交完整 Release + Debug CI 通过后才能升级验证状态。

### 2026-11-10：统一内存预算与 Query 结果硬限制（SOURCE_PARTIAL）

- [x] 新增平台无关的 `TshMemoryBudget` / `TshMemoryReservation`：分类 reservation、并发安全、分类统计、高水位、幂等释放、关闭后拒绝新 reservation。
- [x] 默认目标预算 100 MiB，绝对硬上限 512 MiB；宿主可传入可用内存，默认按可用内存的 1/4 收紧有效预算，不在公共 API 内部探测 Android/系统内存。
- [x] 新增 MEMORY 诊断阶段；无效、超预算、关闭后分配均返回结构化诊断。
- [x] Query 执行预算新增 maxCaptures 与 maxResultBytes，避免只限制 match 数却让单个 match 包含大量 capture 时无界累积；超过限制返回 partial result 与 warning。
- [ ] Query capture 字节数目前是明确的保留结果估算（字符串 UTF-16 长度 + 每项固定开销），不是 JVM heap 精确测量；尚未与 QueryResult 生命周期 reservation 绑定。
- [x] Sparse CoordinateIndex 可选接入 reservation：按 checkpoint 数量预估，在分配 checkpoint 列表前申请，随 Index.close() 释放；估算不等于 JVM heap 精确值。
- [x] RenderCoordinator 可选接入 RENDER_PLAN reservation：pending 期间持有，终态回执、成功取消、提交失败、无效/过期回执和 coordinator close 时释放。
- [x] Snapshot-backed Parser 输入回调可选接入 PARSER_INPUT reservation：按请求 chunk 上限预留临时 UTF-16 数据估算，回调结束在 finally 释放；Native destination 与 Tree-sitter 内部 Native 分配不计入此账本。
- [ ] Native Tree 尚未接入可证明有效的统一 reservation；Tree-sitter 内部 Native 分配不可由此账本精确计量。
- [x] 新增 `TshRegexWindowLease`：仅获取有界连续 UTF-16 窗口，要求有限最大匹配长度/lookaround，按实际窗口估算预留 REGEX_WINDOW，随 Lease.close() 释放；拒绝无界 Regex 窗口，不把全文转成 String。
- [x] 新增 `TshRegexWindowSequence`：持有独立 Snapshot Lease，自动按 code point 边界生成连续且不重叠的 core 区间；窗口申请失败不推进游标，可在压力解除后重试；空文档仍产生一个零长度首尾窗口，保留 offset 0 的零宽匹配机会。
- [ ] 新增 WindowLease/Sequence 尚未接入现有 `WindbellTshEngine.computeHighlights(tree, text, extension)` → JNI `nativeHighlight` → C++ `WindbellEngine::highlightHybrid` 链路；该旧链路仍把完整 String/连续 UTF-16 buffer 交给 Oniguruma。
- [ ] 现有 TSH Regex MATCH 规则尚未声明 maxMatchLength/lookbehind/lookahead，BLOCK 规则仍需要跨窗口状态机；在这些契约完成前，不得声称大文本 Regex 已切换为窗口执行。
- [ ] Regex WindowLease/Sequence 是输入资源契约，不代表 Regex 执行已完成；还需统一输出为 `TshMatch` 并补齐跨窗口捕获/边界测试。
- [ ] 本轮新增 JUnit 测试源码待 CI 执行；未声称构建或设备压力测试通过。


### 2026-11-10：统一 MatchSet 合并内存上限（SOURCE_PARTIAL）

- [x] `TshMatchMerger` 新增 `TshMatchMergeBudget`，默认最多保留 250,000 个候选 Match，默认估算工作集上限 64 MiB。
- [x] 合并过程改为单次流式遍历，不再通过 filter/groupBy/map 多次复制全部输入集合；先校验版本/管线，再进行 ID 冲突检查和语义去重。
- [x] MatchSet 构造时的 document/version/pipeline/ID 唯一性检查也改为单次遍历和单个 ID Set，去掉多轮 filter/map/distinct 临时集合。
- [x] 估算包含 Match/Provenance、Capture 名称与文本、Scope Assignment/Path payload，以及哈希表和排序列表固定开销；达到限制返回 TRUNCATED 与 MEMORY warning。
- [x] 限制合并诊断累计最多 4,096 条，避免 stale 输入造成诊断列表无限增长。
- [ ] 上限只约束合并器新增的候选/工作集估算；输入 Collection 由上游提供且其既有内存不计入本次合并预算。该估算不是 JVM heap 精确测量，也不替代上游 Regex/Tree-sitter 执行限额。
- [ ] 新增测试源码待最新 CI 验证。


### 2026-11-10：Parser Native 取消与超时状态（SOURCE_PARTIAL）

- [x] `TSParser` 新增 `isCancellationRequested()` 公共只读状态和 `requestCancellationAndWaitIfParsing()` 布尔返回 API；保留既有 void `requestCancellationAndWait()` ABI。
- [x] `TshTreeSitterParserOwner` 新增异步取消、取消并等待、Native timeout 设置/读取 API；取消时保留 Parser Lease，等待 Native parse 退出后才释放调用侧 Lease。
- [x] 基于 Tree-sitter `TSParseOptions.progress_callback` 新增 Java `TSParser.ProgressCallback` 与 TSH `TshParseProgressCallback`；快照流式解析可观察 UTF-16 进度、hasError，并由回调返回 true 协作取消；回调异常映射为结构化诊断。
- [x] String parse 与 Snapshot streaming parse 的 null-tree 路径区分已接受的 Native cancellation、达到已配置 timeout 的“可能超时”以及其他无树失败。
- [ ] Tree-sitter Native API 对 timeout/cancel 只返回 null，没有精确 stop-reason；timeout 分类使用实际 parse 耗时与配置阈值推断，诊断明确标注“likely”，不伪称 Native 精确原因。
- [x] 底层 ParserTest 增加进度回调主动停止、回调异常从 JNI 安全回退、reset 后恢复解析测试。
- [ ] TSH Owner 的真实并发 parse/cancel、timeout、progress callback、close 竞态仍需 CI/设备验证；新增底层 ParserTest 源码尚未通过最新 CI。


### 2026-11-10：Regex 有界窗口 Lease（SOURCE_PARTIAL）

- [x] 新增 `TshRegexWindowConstraints` 与 `TshRegexWindowLease`，显式约束 core window、最大连续窗口、最大匹配长度、lookbehind/lookahead。
- [x] 窗口只读取当前 core 和必要上下文；核心区间决定 match start 的唯一归属，避免相邻窗口重复输出。
- [x] 拒绝未声明有限最大匹配长度的模式；核心边界不能拆分 surrogate pair，读取窗口边缘自动扩展一个 UTF-16 code unit 以保持完整 code point；match-start 归属、完整 match range 校验与 local→absolute 映射也拒绝落在 surrogate pair 中间的偏移，并确保 match end 不越过窗口右边界。
- [x] 通过 REGEX_WINDOW reservation 预留临时 CharSequence 与 retained String 的双份估算，Reservation 随 Lease.close() 释放；超预算在读取完整窗口前拒绝。
- [x] 暴露 document length 与窗口是否触及文档首尾，供 Regex Executor 正确处理文档锚点；Executor 仍需在非文档边界禁用窗口伪造的 BOL/EOL 语义。
- [ ] WindowLease 不执行 Regex、不实现 Oniguruma 状态跨窗延续；使用者必须按 core interval 过滤 match start，并遵守有限 match/lookaround 声明。
- [ ] 新增测试源码待 CI 验证。


- [x] Regex WindowSequence 测试覆盖跨 surrogate core 边界、连续分区、caller Lease 提前关闭、空文档、无界 pattern 拒绝与 Sequence 生命周期。


- [x] TSH Parser Owner 在 null tree、Native cancellation、progress callback cancellation、timeout 或 parse 异常后，持有 Owner parse lock 执行 `TSParser.reset()`，防止 Tree-sitter 的默认 resumable parse state 被错误用于下一文档/版本。

### 2026-11-10：Native Tree 生命周期预算接入

- [x] 新增 `TshNativeTreeMemoryEstimator` / `TshNativeTreeMemoryRequest`：由宿主根据 Grammar、输入规模及增量解析状态提供 Native Tree admission estimate，不伪装成 Native 实际内存测量。
- [x] String 与 Snapshot 两条 Parser Owner 解析路径在 Native parse 前申请 `SYNTAX_TREE` reservation；预算不足或估算器异常时不启动解析。
- [x] 成功发布的 Tree 将 reservation 与 Language lease 一起转交给 `TshNativeResourceRegistry`；只有 Tree 实际关闭（包括最后一个活动 Tree lease 释放之后）才释放预算。
- [x] `TshEngine` 拥有共享 `TshMemoryBudget`，通过 `TshEngineOptions.memoryBudgetOptions` 配置；Engine 打开 Parser 时自动共享该预算，并允许传入 Tree estimator。
- [x] 增加估算器校验、预算拒绝、reservation 生命周期及 Engine 关闭行为测试源码。
- [ ] Native Tree 估算值仍由宿主提供，Tree-sitter 内部 allocator 尚未按 Parser/Tree 做精确归属；不可声称 Native RSS 已被硬限制。
- [ ] 本轮新增 JUnit 测试待 CI 对最新 HEAD 执行验证。

### 2026-11-10：String Parser 输入内存准入

- [x] 旧版 `parse(String)` 路径在调用 `TSParser.parseString()` 前，按 UTF-16LE 长度加固定估算开销申请 `PARSER_INPUT` reservation。
- [x] 预算不足时在创建 Native 连续输入副本之前拒绝解析；解析完成、失败或取消后在 `finally` 释放临时 reservation。
- [x] 保留 Snapshot + TSInput 分块路径：其 reservation 仍按每次最大 callback chunk 申请并在 callback 返回前释放，不因文件总长度而申请全文副本预算。
- [ ] 该 String 路径无法避免调用方已经持有的 JVM String；200–300 MiB 及更大的宿主文档应优先走 Snapshot/Piece Tree streaming API。


### Parser Input Buffer Admission

- [x] Legacy String parse 在创建 Native UTF16String 前预留 sourceLengthUtf16×2 的 Native copy estimate，覆盖整个 parse 调用并在结束后释放。
- [x] Streaming parse 在 Native 创建可复用 Java byte[] 前预留 chunkSizeBytes×2+overhead，保守覆盖 byte[] 与可能的 JNI `GetByteArrayElements` copy；整个 parse 生命周期持有该 reservation。
- [x] 每次 TSInput.read 回调另行预留 provider 返回的 UTF-16 chunk payload，回调结束即释放。
- [ ] 以上均为可控输入缓冲 admission estimate，不覆盖 Tree-sitter 内部 parser stack、lookahead buffer 或其他 Native allocations。
