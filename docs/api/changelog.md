# API 变更记录

## Unreleased

### Added
- 建立 TSH 统一 API 架构文档、对象/文本模型、编辑器集成、高亮渲染、TSH 语言、ATS/Regex、诊断和路线图规范。
- 新增 TshEngine 生命周期门面、结构化结果/诊断、坐标与范围基础类型。
- 新增实例身份对象注册表、能力描述符和 Provider 注册/发现契约。
- 新增 TextDocument/TextSnapshot/TextEdit/TextChange 与 TextAccessProvider 契约。
- 新增内存文本 UTF-8/UTF-16/Unicode Code Point/Grapheme 坐标转换器及测试源码。
- 新增 Runtime/Session、Config/Language Service、Editor Adapter、Highlight/Render、Binding/Event/Extension、ATS/Regex/Match 和 Diagnostics/Observability 详细 API 规范。
- 新增 Grammar/Parser/Query、增量 TextBuffer、JNI/Native Bridge、Cache/Performance、安全/Regex 防护、测试/兼容性、平台/线程契约文档。
- 补充 .tsh 配置源、加载/编译/安装/替换/卸载、对象 Catalog 查询和配置事务契约。
- 补充 .tsh DSL v1 顶层声明、类型系统、属性 Schema、表达式、引用和语义诊断契约。
- 补充 Theme/Style/Font/Color/Decoration API，明确字体族、字重、字形、字号、字距、行高、OpenType 特性、可变字体轴与 fallback。
- 补充 DSL 能力到 Parser/Resolver/Runtime/Render Backend 的覆盖矩阵，防止只定义语法而没有执行实现。
- 从核心 API 索引移除通用编辑器功能文档；这些内容仅作为宿主集成边界参考。嵌入语言语法注入保留为 TSH 核心能力。
- 新增 DSL typed value/declaration/document Kotlin 模型、Schema Registry、Config Artifact/Repository 和首批测试源码。
- 新增 ATS/Tree-sitter Query API 首批 Kotlin 源码：Grammar descriptor、UTF-8 byte range、query source/options、compiled query、syntax tree snapshot、capture、execution budget、cancellation、versioned result 及 Compiler/Executor contracts。
- 新增 CompiledQuery Registry，提供注册/替换/查询/Grammar fingerprint 兼容检查/注销。
- 新增 ATS Query API 测试源码，覆盖半开 byte range、Grammar fingerprint、Registry 冲突与 stale result 识别。\n- 新增 `TshTreeSitterQueryCompiler`：复用 `TSQuery.create(TSLanguage, source)` 编译原生 Tree-sitter S-expression Query，绑定 Grammar fingerprint，映射 Native 编译错误和 byte offset，执行 pattern count 限制；ATS 输入明确拒绝，等待专用 ATS Parser/Compiler。\n- 新增 `TshTreeSitterQueryExecutor`：复用 `TSQueryCursor` 执行，支持 byte range、Capture name/type/range/node identity 转换、总 match 数预算、协作式取消检查、过期 Grammar/Query 检查，并在 `finally` 中关闭 Cursor。\n- 新增编译查询显式 `release(queryId)`，负责从对象注册表注销并关闭编译器拥有的 `TSQuery`；替换同 ID 查询时释放旧 Native Query。\n- 新增 Adapter 合约测试源码，覆盖 ATS 不误解析、Grammar 缺失、Grammar 不匹配、Native handle 缺失和 query revision 过期。

### Planned
- Piece Tree/Rope 索引化坐标映射与 Layout-aware 坐标 Provider。
- Engine Session、任务调度、资源 Scope 和兼容迁移接线。
- .tsh Lexer/Parser/AST/Schema/Language Service 实现及 C++ Compiler 兼容。
- ATS Lexer/Parser/AST/Compiler 到现有 Tree-sitter Query 执行器的适配。
- 基于既有 TSQuery/TSQueryCursor 的 Native lease/refcount、增量查询与可中断取消/真实 step budget。
- Regex 独立 WindowLease、Oniguruma 连续缓冲区和跨窗口等价策略。
- Unified Match/Scope/Attribute/Highlight 与 Theme/RenderPlan 运行时实现。
- Binding/EventBus/Provider 版本协商和诊断收集器。
- 本地 Gradle 编译、自动化测试和真实编辑器渲染验证。

### Status
- 本轮新增的 Kotlin API 和测试仅代表源码已提交；没有运行 Gradle、JUnit、C++/JNI 构建或 Android 设备验证。Tree-sitter Adapter 调用了既有 Native API，但未做真实 Native 运行验证。
- 现有仓库已检查到 `TSQuery`、`TSQueryCursor`、`TshCompiler`、`TshGrammar.dynamic_ts_query`；后续实现应复用这些既有能力并保持旧 JNI 入口兼容。
