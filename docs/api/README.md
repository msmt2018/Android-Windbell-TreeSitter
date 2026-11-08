# TSH 公共 API 总目录

## 已实现基础 API

- [公共 API 基础层](core-foundation.md)：Engine 生命周期、对象注册、Capability、Provider Registry、诊断与坐标基础类型。
- [对象与文本模型](object-and-text-model.md)：TextDocument、TextSnapshot、TextEdit、坐标模型与宿主缓冲区抽象。

> 当前 API 文件已经提交，但尚未完成 Gradle 编译和设备端验证。以下领域文档定义目标契约，不代表对应实现已经存在。

## API 领域规范

| 文档 | 领域 | 主要内容 |
|---|---|---|
| [Runtime 与 Session](runtime-and-session.md) | Engine、Session、任务与资源 | 多会话隔离、状态机、线程和关闭 |
| [配置与语言工具](config-and-language-tooling.md) | .tsh、Lexer、Parser、AST、Schema | 编译阶段、诊断、补全、格式化 |
| [Editor 与对象适配](editor-adapters.md) | Editor、View、Layout、Viewport | 实例注册、Provider 协商、适配等级 |
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
| [平台与线程契约](platform-and-threading-api.md) | 模块边界、Dispatcher、回调 | UI 线程、并发、重入和模块依赖 |
| [Symbol、Outline、Folding 与 Navigation](symbol-outline-navigation-api.md) | 符号、折叠与导航 | 符号树、折叠区间、定义目标和版本校验 |
| [Selection、Cursor 与 Multi-Cursor](selection-cursor-multicursor-api.md) | 光标与选择 | 主/次光标、选区、矩形选择和事件订阅 |
| [Language Intelligence](language-intelligence-api.md) | 语言智能 | Completion、Hover、Definition、References、Rename |
| [Injection 与 Language Layer](injection-and-language-layer-api.md) | 嵌入语言 | 语言层级、范围映射、增量失效与资源限制 |
| [Decoration、Gutter、Inlay 与 CodeLens](decorations-gutter-inlay-codelens-api.md) | 装饰与边栏 | 所有权、样式合成、渲染能力和交互命令 |
| [EditTransaction、Undo/Redo 与 Command](edit-transaction-undo-redo-command-api.md) | 编辑事务 | 版本校验、原子应用、撤销栈所有权和命令安全 |

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
