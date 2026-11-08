# TSH 引擎开发计划地图

> 目标：把 .tsh 声明稳定地编译成可执行的双管线规则、统一语义结果和可验证的应用层绘制。TSH 是语法/高亮/样式/渲染引擎，不是通用编辑器框架。
>
> 状态口径：**契约**=文档已定义；**源码**=代码已提交；**构建**=目标模块成功编译；**测试**=自动化测试通过；**设备**=真实 Android 控件绘制验证。状态逐项记录，不以文档存在推断实现完成。

## 架构不变量

1. 只有两条执行管线：Tree-sitter + ATS；独立 Regex（Oniguruma）。
2. ATS 是 Tree-sitter Query/SCM 的受控简化混合表达，不是第三个执行引擎。
3. 两条管线在统一 Match 模型汇合；不混淆各自的执行、范围与错误语义。
4. 所有 .tsh 属性必须有类型/Schema、编译语义、Runtime consumer 和可验证输出。
5. Render 必须形成 Plan → Backend Submit → Receipt/Ack → 实际绘制验证闭环。
6. 旧 WindbellTshEngine/JNI 入口保持兼容，采用 Facade/Adapter 渐进迁移。
7. 不实现通用 Editor 功能：折叠、Outline、导航、光标/选区、多光标、Undo/Redo、命令、Gutter、Inlay、CodeLens 等由宿主负责。

## 当前基线

| 子系统 | 当前可确认状态 | 尚未证明 |
|---|---|---|
| 公共 API 基础 | TshEngine、TshResult、诊断/坐标/对象/Provider 注册、TextSnapshot 等 Kotlin 源码已存在 | 全模块构建、ABI 稳定性、与 Native 执行入口接通 |
| DSL | Parser-neutral typed AST、Schema Registry、Config Repository Kotlin 源码已提交 | .tsh Lexer/Parser、C++ Compiler Schema 接入、Gradle 构建与测试 |
| ATS/Tree-sitter | Query 数据契约、Registry、基于既有 TSQuery/TSQueryCursor 的 Compiler/Executor Adapter、Query/Tree/Language Lease 与协调式 Resource Registry 源码已提交 | ATS Lexer/Parser、宿主全面迁移、增量查询、真实 Native 测试与构建验证仍待完成 |
| 旧 TSH 执行入口 | WindbellTshEngine、TshCompiler、WindbellEngine、JNI 入口存在 | 与新 Facade 的完整桥接、错误结构化、所有权治理 |
| 双管线 | C++ core 有 Regex 与 Tree-sitter/Query 混合处理 | ATS 完整语法、统一 Match/Scope、Regex 窗口正确性 |
| 样式/绘制 | 旧 Palette 与 Spannable 前景色/粗体路径存在 | 完整 Font/Color/Decoration、通用 RenderPlan、后端回执及设备覆盖 |
| 测试 | 部分 Kotlin/C++ 测试源码存在 | 本轮未运行 Gradle/Native 测试，不宣称通过 |

## 开发阶段地图

### A. 公共 API 基础与类型模型
- [x] TshResult、Diagnostic、Coordinate、ObjectId、Capability、ObjectRegistry、ProviderRegistry 的首批 Kotlin 源码。
- [x] TextSnapshot/TextAccessProvider 的最小宿主文本访问契约。
- [ ] 完成公共 API 构建、二进制兼容和并发/生命周期测试。
- [ ] 让 TshEngine 通过可替换 Adapter 调用旧 WindbellTshEngine，而不是复制 Native 执行逻辑。

### B. DSL 类型系统、Schema 与配置仓库
- [x] 完整类型系统/Schema 的目标契约文档。
- [x] DSL Value/Type/Declaration/Document Kotlin 模型源码（Parser-neutral typed AST 基础）。
- [x] Schema Registry：类型、单位、默认值、范围、枚举、版本、能力和未知属性策略校验。
- [x] Config Artifact/Repository/Catalog：get/list/install/乐观并发 replace/unload。
- [x] Schema 与 Config Repository 首批 JUnit 测试源码。
- [ ] Lexer/Parser AST 与既有 C++ TshCompiler 的语法兼容审计。
- [ ] C++ Parser/Compiler 逐步补齐 Schema/语义校验，并提供结构化诊断。
- [ ] DSL v1 fixtures、错误恢复、迁移和兼容性测试。

### C. Grammar 与 Tree-sitter + ATS 查询
- [x] Grammar Descriptor、带输入编码契约的 Native ByteRange、Query Source/Options、CompiledQuery、SyntaxTreeSnapshot、Capture、ExecutionBudget、Result 等 Kotlin API 模型。
- [x] TshAtsCompiler/TshQueryExecutor 公共接口与 Grammar fingerprint 兼容检查。
- [x] CompiledQuery Registry：重复注册保护、替换、查询、Grammar 兼容查找和注销。
- [x] 查询 API 首批单元测试源码（范围、Grammar fingerprint、Registry、stale result）。
- [ ] Grammar/Language Definition Registry、版本和依赖模型。
- [ ] ATS Lexer/Parser/AST/Compiler，映射为 Tree-sitter Query plan。
- [x] 基于现有 TSQuery/TSQueryCursor 的 Kotlin Compiler/Executor Adapter：Query 编译、Cursor 执行、Capture 转换、Grammar 检查、结构化错误和 Cursor finally-close。
- [x] Native Query lease/refcount：编译器与执行器共享 Lease Store；release/替换先退役，活动执行结束后延迟 close。
- [x] Tree/Language Native lease 首版源码接入：Tree Executor 持有 Tree Lease；Language Compiler 将 Language Lease 转交为 Query 依赖 Lease，覆盖 Query 整个存活期；共享 Store 负责 retire/retireAll 后延迟 close。
- [x] Native Resource Lease Store 支持资源依赖 Lease，Native resource close 后逆序释放依赖；新增 `TshTreeSitterParserOwner`，Parser 生命周期保留 Grammar Language Lease，每棵 Tree 独立保留 Language Lease，Tree 可安全晚于 Parser/Grammar Provider 释放。
- [x] `TshSyntaxTreeSnapshot` 显式携带 UTF8/UTF16_LE/UTF16_BE 输入编码；`TshByteRange` 与 Capture range 定义为 Native Tree-sitter byte offset，不再错误假设所有 Parser 输入都是 UTF-8。当前 Java `TSParser.parseString` 路径标记 UTF16_LE。
- [x] 新增 `TshNativeResourceRegistry` 协调 ObjectRegistry 与 Native Resource Lease Store 的注册、类型/实例校验获取、retire/作用域化 retireAll；`TshObjectRegistry.registerUnique` 原子拒绝重复实例发布；补充生命周期测试源码。
- [x] Resource Registry 先在协调锁内撤销 ID 的获取资格并移除对象索引，再在锁外执行 Native retire/close；增加永久 close 状态，避免 Native close 回调在协调锁内运行。
- [x] Query Lease Store 增加 `registerDeferred` / `retireDeferred`：高层 Query Registry 事务锁内只切换 revision/登记退役，旧 Query 与依赖 Language Lease 的 close action 在事务锁外执行；增加 deferred close 测试源码。
- [x] `TshEngine` 持有自己的 Native Resource Lease Store/Registry；Engine.close 先关闭该 Registry 并退役其拥有的 Native 资源，再清理 Provider/Object 索引。
- [x] 新增 Engine Native 生命周期测试源码：验证 idle resource 在 Engine.close 时释放、活动 Lease 延迟释放，以及 Engine 关闭后拒绝新资源注册。
- [x] Engine 拥有 `TshCompiledQueryRegistry` 和 `TshNativeQueryLeaseStore`；提供 `createTreeSitterQueryCompiler/Executor` 与 `openTreeSitterParser` 工厂，强制使用同一个 Engine ObjectRegistry、Query catalog、Query Lease Store 与 Native Resource Registry；补充 Engine 关闭后拒绝打开 Parser 的测试源码。
- [x] Engine.close 按 Query → Parser/Tree → Language 顺序退役；Native Query Lease Store 增加永久 close，关闭后拒绝 Query 发布，活动 Query Lease 释放后再关闭 Query 和 Language 依赖。
- [x] Native Resource Lease 支持从仍然持有的 Lease 派生子 Lease；Grammar 已退役时不允许从 Store 重新 acquire，但 Parser 可从其存活的 Language dependency lease 派生 Tree dependency lease；新增退役后 child retain 与并发 retain/release 测试源码。
- [x] 修复 Query Compiler Native Query 的失败清理路径：统一由外层 finally 释放未转移所有权的 Query，避免错误分支提前 close 后 finally 再次 close；修复 cleanup 变量作用域。
- [x] 修复 `TSQuery.create(String)` JNI 编码契约：将 Java UTF-16 显式编码为标准 UTF-8，并把真实 UTF-8 字节长度传给 `ts_query_new`；不再混用 `GetStringUTFChars` 的 Modified UTF-8 与 `GetStringLength` 的 UTF-16 长度。标准 UTF-8 转换已收敛到 `utils/jni_string`，并接入 Query JNI、TSH DSL 源码加载和动态 Grammar 路径/符号名转换；Native 返回的 Capture Name/Query String Value 按标准 UTF-8 解码为 Java UTF-16；增加含 `é` 与补充平面 emoji 的 Query 编译/字符串回读测试源码。
- [x] Tree-sitter Compiler/Executor Adapter 增加可选 `TshNativeResourceRegistry` 注入：启用时 Language/Tree Lease 获取经统一协调入口完成；旧底层 Store 注入保留为兼容迁移路径。
- [ ] 旧宿主所有 Tree/Language/Parser owner 迁移到协调 Registry 的 register/acquire/retire，清除绕过 Store 的直接 close；新 API 应通过 Engine 工厂创建 Compiler/Executor；增加并发/真实 Native 测试。ParserOwner 目前是新 API 的可用源码，不代表旧宿主调用点已迁移。
- [ ] 审计 Engine.close 与并发 register/retire/compile/execute 的线性化和等待语义；Store 已具备关闭门闩及活动 Lease 延迟释放，但尚未完成全场景并发验证。
- [x] Predicate Handler 结果语义：OK 接受当前表达式、ERR 拒绝当前 match、UNHANDLED 委派下一 Handler；按 Done 分隔表达式并逐个执行，同一 pattern 的多个表达式全部通过才保留 match；按注册顺序确定 Handler 调用顺序。
- [ ] Tree-sitter incremental edit/changed-range 查询。
- [ ] 与现有 dynamic_ts_query 及旧 native 方法建立兼容 Adapter。
- [ ] 实现可中断 Native 执行/真实 step budget；当前 Cursor 不暴露 VM steps，取消只能在两次 nextMatch 调用之间检查。
- [ ] ATS 与原生 Tree-sitter Query 一致性测试。

### D. 独立 Regex 与窗口执行
- [ ] Regex Pattern/Options/CompiledRegex API。
- [ ] Oniguruma WindowLease：稳定连续 buffer、编码和全局 offset 映射。
- [ ] 有界跨窗口 overlap 与无界模式整段回退/明确拒绝。
- [ ] 增量 dirty range、锚点/lookaround 语义、取消/超时/预算。
- [ ] Piece Tree chunk 边界、Unicode、超长行与整段基准对比测试。

### E. 统一 Match、Scope、Selector
- [ ] TshMatch/MatchSet/Capture/Provenance/Revision 统一模型。
- [ ] Byte/UTF-16 坐标规范化和 stale-result 防护。
- [ ] Scope path/stack、Selector parser/compiler/specificity。
- [ ] 双管线 Match 冲突、嵌套、重叠、去重和稳定排序。
- [ ] 来源追踪和跨管线一致性测试。

### F. Attribute、Semantic 与 Highlight
- [ ] Attribute Registry、TypedAttribute、默认值/继承/合并策略。
- [ ] Semantic Resolver：Match + Capture + Scope + Selector → Attributes。
- [ ] Highlight Resolver：Attributes → Theme/Style。
- [ ] 每个 DSL 属性登记实际 Runtime consumer。
- [ ] 端到端证明属性从 DSL 到最终 RenderInstruction 的值与来源。

### G. Theme、Style、Font、Color、Decoration
- [ ] Theme/Style get/list/activate/preview/reset/explain。
- [ ] 字体族、字重、字形、字号/单位、stretch、行高、字距、baseline、OpenType、variable axes、fallback。
- [ ] ColorSpace/Alpha/Token/变量与颜色混合策略。
- [ ] Text Decoration 类型、层级、范围、合成与能力降级。
- [ ] Android 字体资源 Provider 和后端真实属性消费。

### H. Render 闭环
- [ ] RenderPlan/Instruction/Backend/Capabilities/Receipt API。
- [ ] Span/Canvas 等宿主绘制适配，核心模块不依赖具体 Editor。
- [ ] plan revision、局部失效、过期计划拒绝和异步 Ack。
- [ ] 属性来源 → resolved value → instruction → backend outcome 可追踪。
- [ ] Android 控件像素/字体/装饰/主题切换设备测试。

### I. Native/JNI 完整性
- [ ] Native operation manifest 与 Kotlin external API 对照。
- [ ] 类型化 handle/generation/refcount/lease，不把 jlong 当裸指针 API。
- [ ] JNI/C ABI/C++ 错误码、编码、范围和线程约束。
- [ ] 注解/KSP/AP 生成、JNI registration 与 C header 同步检查。
- [ ] 异常边界、重复释放、use-after-free、并发关闭、ASAN/泄漏测试。
- [ ] 保持旧 JNI 名称/签名兼容，逐项迁移而非一次性破坏。

### J. 集成、性能与发布
- [ ] Kotlin 单元测试、C++ 单元测试、JNI 集成测试。
- [ ] Android API/ABI matrix、真实宿主控件绘制验证。
- [ ] 大文档、增量、Unicode、并发、内存、超时与性能基准。
- [ ] 更新 API changelog、迁移指南和实现状态矩阵。

## 本轮实施顺序

1. [x] 实现 B 的 Kotlin 类型系统、Schema Registry 和配置 Artifact Repository（纯 JVM 层，尚未连接 C++ Parser）。
2. [x] 为该切片补充单元测试源码。
3. [x] 实现 C 阶段第一批 Query/Grammar/Capture API 模型、Executor/Compiler 接口和编译查询 Registry。
4. [x] 为查询 API 首批契约补充单元测试源码。
5. [ ] 执行 Gradle 构建与测试并修复实际编译问题。
6. [ ] 审计既有 .tsh 语法和 C++ Compiler，制定 DSL v1 兼容表。
7. [ ] 实现 ATS Lexer/Parser/Compiler，并接入现有 TSQuery/TSQueryCursor。
8. [ ] 实现 D 的独立 Regex Window Adapter，不能让 Regex 借用 ATS 执行接口。
9. [ ] 贯通 E/F/G/H 的结果与真实绘制闭环。
10. [ ] 完成 I 的 Native/JNI 同步与全矩阵验收；期间始终保持旧入口可用。

## API 文档映射

- B：`dsl-type-system-and-schema-api.md`、`tsh-file-config-management-api.md`、`tsh-dsl-syntax-and-semantic-contract.md`
- C：`ats-tree-sitter-query-api.md`、`grammar-and-query-api.md`
- D：`regex-window-execution-api.md`、`security-and-regex-safety.md`
- E：`unified-match-scope-api.md`、`scope-selector-and-semantic-api.md`
- F：`attribute-semantic-highlight-api.md`、`highlight-resolution-and-token-api.md`
- G/H：`theme-style-render-contract-api.md`、`theme-style-font-color-api.md`、`render-plan-and-invalidation-api.md`
- I：`native-jni-cpp-completeness-api.md`、`jni-native-bridge-api.md`、`native-handle-and-memory-ownership-api.md`
- 全阶段：`tsh-core-api-coverage-matrix.md`、`public-api-review-checklist.md`、`changelog.md`

**完成标准：** 每个功能独立记录契约/源码/构建/单测/集成/设备状态。源码提交不等于构建通过；构建通过不等于运行正确；API 可调用不等于 .tsh 属性真实生效或已绘制。

## 最近一次源码提交

- DSL：新增 `TshDslModel.kt`、`TshSchemaRegistry.kt`、`TshConfigRepository.kt` 与对应测试源码。
- ATS/Tree-sitter：新增 `TshQueryApi.kt`、`TshCompiledQueryRegistry.kt`、`TshQueryApiTest.kt`；新增 `TshTreeSitterQueryAdapter.kt`，基于现有 `TSQuery.create`、`TSQueryCursor.create/exec/nextMatch`、`TSQueryMatch`、`TSQueryCapture` 实现编译/执行与 Capture 映射；新增 Adapter 合约测试源码。
- 已检查现有实现：仓库包含 `TSQuery`、`TSQueryCursor` Java 封装，以及 C++ `TshCompiler` 和 `TshGrammar.dynamic_ts_query`。下一步应在这些既有入口上做 Adapter，不创建第二套 Native Query 引擎。
- **状态：上述 Kotlin 源码和测试源码已提交；Gradle 构建、JUnit 执行、Native Query/Tree/Language lease 与协调式 Resource Registry 均尚未验证；宿主所有者迁移尚未完成；ATS 编译执行和设备运行均未验证。Tree-sitter Query Adapter 已有真实 Native 调用代码与 Lease 管理源码；Query revision 替换/release 的 close action 已移出 Query catalog 锁；ParserOwner、Engine 统一工厂、Query Store shutdown、退役后 child Lease 和 Query JNI 标准 UTF-8 修复已有源码/测试源码，但尚未通过构建/运行测试。**
