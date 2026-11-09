# TSH 公共 API 总目录

## 最高层公共 API 规范

- [TSH Public API 设计总规范与宿主接入契约](public-api-design-principles-and-integration-contract.md)：全领域最高层规范；领域文档不得与之冲突。

## 已实现基础 API

- [公共 API 基础层](core-foundation.md)：Engine 生命周期、对象注册、Capability/Provider Resolution、OwnerScope/Attachment、诊断与 Long 坐标基础类型。
- [对象与文本模型](object-and-text-model.md)：TextDocument、Snapshot Lease、分块读取、TextEdit、Long 坐标与宿主缓冲区抽象。

> 当前 API Foundation、Attach/Detach、Provider Resolution、Text Snapshot/Chunk 和 Schema 修复均为 SOURCE_PARTIAL，尚未完成本轮 Gradle 编译/测试和设备端验证。其余领域文档定义目标契约，不代表对应实现已经存在。

## 规范权威来源（Normative Source Map）

每个 API 领域只有一个权威契约。其他文档可以提供概览、示例和迁移说明，但不得重复定义或覆盖权威文档中的字段、状态、错误语义和生命周期。

| 领域 | 唯一权威文档 |
|---|---|
| 公共原则、宿主接入与所有权 | [public-api-design-principles-and-integration-contract.md](public-api-design-principles-and-integration-contract.md) |
| Engine、对象身份与生命周期 | [core-foundation.md](core-foundation.md) |
| TextDocument、Snapshot、Edit 与坐标 | [object-and-text-model.md](object-and-text-model.md) |
| DSL 类型与 Schema 校验 | [dsl-type-system-and-schema-api.md](dsl-type-system-and-schema-api.md) |
| DSL 语法、AST 与语义 | [tsh-dsl-syntax-and-semantic-contract.md](tsh-dsl-syntax-and-semantic-contract.md) |
| ATS/Tree-sitter Query | [ats-tree-sitter-query-api.md](ats-tree-sitter-query-api.md) |
| Regex 编译、窗口与跨窗口语义 | [regex-window-execution-api.md](regex-window-execution-api.md) |
| Match/MatchSet/Scope 数据模型 | [unified-match-scope-api.md](unified-match-scope-api.md) |
| Attribute 与语义高亮 | [attribute-semantic-highlight-api.md](attribute-semantic-highlight-api.md) |
| Theme、Font、Color 与 Style 属性 | [theme-style-font-color-api.md](theme-style-font-color-api.md) |
| RenderPlan、RenderProvider 与 Receipt | [theme-style-render-contract-api.md](theme-style-render-contract-api.md) |
| 后端绘制能力、确认与 Conformance | [render-backend-conformance-api.md](render-backend-conformance-api.md) |
| JNI/C++ ABI、Native handles 与生命周期 | [native-jni-cpp-completeness-api.md](native-jni-cpp-completeness-api.md) |
| Binding、事件、Provider 与扩展 | [binding-events-and-extension.md](binding-events-and-extension.md) |

## API 领域规范

| 文档 | 领域 | 主要内容 |
|---|---|---|
| [Runtime 与 Session](runtime-and-session.md) | Engine、Session、任务与资源 | 多会话隔离、状态机、线程和关闭 |
| [配置与语言工具](config-and-language-tooling.md) | .tsh、Lexer、Parser、AST、Schema | 编译阶段、诊断、补全、格式化 |
| [Highlight 与 Render](highlight-and-render-api.md) | 属性、主题、高亮、渲染 | 样式级联、RenderPlan、真实绘制 |
| [Binding、事件与扩展](binding-events-and-extension.md) | Binding、EventBus、Provider | 对象关系、事件线程、扩展卸载 |
| [ATS、Regex 与 Match](ats-regex-and-match-api.md) | 双管道匹配 | 统一 Match、优先级、增量和窗口 |
| [诊断与可观测性](diagnostics-and-observability.md) | Diagnostics、Trace、Metrics | 错误码、集成报告、渲染状态 |
| [快速接入](quick-start.md) | 接入示例 | 当前 API 使用方式与边界 |
| [API 审查清单](public-api-review-checklist.md) | 发布治理 | 新增 public API 的发布门槛 |
| [Grammar 与 Query](grammar-and-query-api.md) | Grammar、Parser、Tree、ATS Query | 语法资源生命周期、解析与查询 |
| [增量文本与 Buffer](incremental-text-and-buffer-api.md) | TextBuffer、Snapshot、Piece Tree/Rope | 分块读取、编辑事务、索引化坐标 |
| [JNI 与 Native Bridge](jni-native-bridge-api.md) | Kotlin/JNI/C++ 边界 | 句柄所有权、编码、错误和 ABI |
| [缓存与性能](cache-and-performance-api.md) | Scheduler、Cache、Backpressure | 任务优先级、缓存失效、性能预算 |
| [安全与 Regex 防护](security-and-regex-safety.md) | Regex、配置、Provider 安全 | 超时、资源配额、隔离和 SafeMode |
| [测试与兼容性](testing-and-compatibility-api.md) | API/Provider/Native 版本治理 | 测试分层、兼容策略和发布门槛 |
| [平台与线程契约](platform-and-threading-api.md) | 模块边界、Dispatcher、回调 | 并发、重入和模块依赖 |
| [.tsh 文件与配置对象管理](tsh-file-config-management-api.md) | 配置源、Artifact、Catalog、Transaction | load/reload/install/replace/unload，Theme/Font/Color 等 get/list 管理 |
| [TSH DSL 语法与语义契约](tsh-dsl-syntax-and-semantic-contract.md) | Lexer、Parser、AST、类型系统、Schema | 顶层声明、值类型、表达式、引用、语义约束与版本 |
| [Theme、Style、Font、Color 与 Decoration](theme-style-font-color-api.md) | 主题与样式模型 | Theme get/list/activate、Style resolve/explain、字体与颜色契约 |
| [TSH 核心能力覆盖矩阵](tsh-core-api-coverage-matrix.md) | DSL 到 Runtime 映射 | 每种语法对应的底层 API、产物和验收要求 |
| [DSL 完整类型系统与 Schema](dsl-type-system-and-schema-api.md) | AST、Type System、Schema、Validator | 属性类型、单位、默认值、继承、表达式和语义约束 |
| [ATS 与 Tree-sitter 查询](ats-tree-sitter-query-api.md) | Grammar、ATS Compiler、Query Executor | 查询语法、捕获、谓词、增量查询和生命周期 |
| [Regex 窗口执行](regex-window-execution-api.md) | Oniguruma、WindowLease、Incremental | 连续缓冲区、跨窗口正确性、增量与安全预算 |
| [统一 Match 与 Scope](unified-match-scope-api.md) | Match、Capture、Scope、Selector | 双管线结果归一、范围、冲突、作用域语义 |
| [Attribute、Semantic 与 Highlight](attribute-semantic-highlight-api.md) | Attribute Registry、Semantic Resolver、Highlight | DSL 属性真正进入运行时计算与高亮结果 |
| [Theme、Style 与 Render 完整契约](theme-style-render-contract-api.md) | Theme、Font、Color、Decoration、Backend | 主题管理、完整字体属性、真实绘制回执 |
| [Kotlin/JNI/C++ 完整性](native-jni-cpp-completeness-api.md) | Native operation catalog、JNI、C ABI | 接口签名、注解生成、句柄、所有权与生命周期 |

## 领域规范唯一权威来源

- 公共原则、宿主所有权、接入流程：public-api-design-principles-and-integration-contract.md。
- Match 数据模型/生命周期：unified-match-scope-api.md；Regex 窗口执行：regex-window-execution-api.md。
- RenderPlan/Renderer Provider：theme-style-render-contract-api.md；后端提交/回执/真实绘制验收：render-backend-conformance-api.md。
- Native/JNI ABI、operation、handle：native-jni-cpp-completeness-api.md。
- DSL 语法/Schema 语义：tsh-dsl-syntax-and-semantic-contract.md 与 dsl-type-system-and-schema-api.md（前者语法规范，后者类型/校验规范）。
- 其余文档只描述各自领域的流程或实现状态；出现冲突时以上权威文档和最高层公共规范优先。

## 公共 API 设计要求

每个公共 API 必须定义目标对象、输入/输出、能力前置条件、线程约束、生命周期、错误语义、诊断信息、扩展点、兼容策略和测试要求。

公共 API 不应暴露 Native 指针、JNI 内部数组布局或内部单例作为应用集成的必要条件。

## API 稳定性

- public：面向应用开发者的稳定契约。
- experimental：允许在次版本调整，必须显式标注。
- internal：引擎内部实现，不保证兼容。
- deprecated：保留迁移窗口，提供替代 API 和移除版本。

禁止将 Native 实现细节或临时适配类直接升级为稳定 API。

## 错误处理

优先返回结构化结果或抛出有文档约束的异常。错误至少包含稳定 code、stage、message、source range（如适用）、object id（如适用）和 cause。不得静默吞掉配置错误、Provider 缺失或渲染失败。

## Kotlin/Java 互操作

公共接口应能被 Java 和 Kotlin 直接使用；避免仅 Kotlin 调用方便但 Java 难以消费的 API。必要时提供 Java 友好的 Builder、显式 getter、非 suspend 同步入口及异步回调/CompletionStage 适配。

## 兼容迁移

现有 WindbellTshEngine 保留为兼容执行入口。新 API 通过 Facade/Adapter 逐步委托旧实现，避免一次性破坏 JNI、ATS/Regex 或现有调用方。每个迁移项必须有旧 API -> 新 API 映射、弃用周期和回归测试。


## 宿主集成参考（非 TSH 核心 API）

以下文档仅用于说明宿主 Editor 可如何消费 TSH，不构成 TSH 引擎公共 API，也不得据此扩展编辑器框架职责：
- symbol-outline-navigation-api.md
- selection-cursor-multicursor-api.md
- language-intelligence-api.md
- edit-transaction-undo-redo-command-api.md
- decorations-gutter-inlay-codelens-api.md（其中 Gutter/Inlay/CodeLens 属宿主；TSH Decoration 以 theme-style-font-color-api.md 为准）
- editor-adapters.md、workspace-project-document-lifecycle-api.md、accessibility-bidi-virtual-layout-api.md、injection-and-language-layer-api.md（仅作宿主集成参考；最小文本/坐标契约以核心 API 定义为准）

嵌入语言语法注入属于 TSH 核心语法能力，见 language-injection-api.md；不得将通用 Editor Language Layer 当作引擎职责。


## 已提交源码（尚待构建/测试验证）

- `TshDslModel.kt`：DSL typed value、declaration/document 和版本/单位模型。
- `TshSchemaRegistry.kt`：Schema 注册与类型/约束校验。
- `TshConfigRepository.kt`：配置 Artifact Catalog 与原子版本替换/卸载。
- `TshDslSchemaRepositoryTest.kt`：上述基础模型的测试源码。

这些源码暂时不负责解析 .tsh 文本，也没有调用 C++ TshCompiler；Parser/Compiler Adapter 将在语法兼容审计后实现。
