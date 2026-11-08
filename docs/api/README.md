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

| [快速接入](quick-start.md) | 当前 API 使用方式与边界 |\n| [API 审查清单](public-api-review-checklist.md) | 新增 public API 的发布门槛 |\n\n## 公共 API 设计要求

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
