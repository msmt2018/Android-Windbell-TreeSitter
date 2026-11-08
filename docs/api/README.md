# TSH 公共 API 总目录

## 1. 公共 API 设计要求

每个公共 API 必须定义：目标对象、输入/输出、能力前置条件、线程约束、生命周期、错误语义、诊断信息、扩展点、兼容策略和测试要求。

公共 API 不应暴露 Native 指针、JNI 内部数组布局或内部单例作为应用集成的必要条件。

## 2. API 领域目录

| 领域 | 核心契约（规划名称） | 责任 |
|---|---|---|
| Engine | TshEngine / TshContext | 初始化、服务访问、关闭 |
| Runtime | TshSession / TshLifecycle | 会话、状态、事务、资源 |
| Config | TshConfig / TshConfigLoader | 加载、解析、验证、热更新 |
| Language Tooling | TshLanguageService | 补全、诊断、格式化、定义、引用、重命名 |
| Object | TshObject / TshObjectRegistry | 外部实例登记与解析 |
| Capability | TshCapability / TshCapabilitySet | 能力发现与协商 |
| Text | TshTextDocument / TshTextBuffer | 文档、文本访问、版本 |
| Coordinate | TshPosition / TshRange / TshCoordinateConverter | UTF-8/UTF-16/字节/行列转换 |
| Editor | TshEditorAdapter / TshEditorSession | 编辑器对象接入 |
| Syntax | TshSyntaxService / TshQueryResult | Tree-sitter 与 ATS |
| Regex | TshRegexService / TshRegexResult | Regex 独立匹配管道 |
| Highlight | TshHighlightService / TshResolvedStyle | 匹配、作用域、样式 |
| Attribute | TshAttributeRegistry / TshTheme | 属性类型、继承、主题 |
| Render | TshRenderService / TshRenderProvider | 指令生成、提交、刷新 |
| Binding | TshBindingManager | 对象、文档、属性和生命周期绑定 |
| Events | TshEventBus | 事件订阅与取消 |
| Diagnostics | TshDiagnostics / TshDiagnostic | 诊断、追踪和报告 |
| Extension | TshProviderRegistry | Provider 注册、版本与卸载 |
| Native | Native bridge contracts | JNI 所有权、错误和编码边界 |

上述名称是统一命名目标；在代码实际落地前不代表类已存在。

## 3. API 稳定性

- `public`：面向应用开发者的稳定契约。
- `experimental`：允许在次版本调整，必须显式标注。
- `internal`：引擎内部实现，不保证兼容。
- `deprecated`：保留迁移窗口，提供替代 API 和移除版本。

禁止将 Native 实现细节或临时适配类直接升级为稳定 API。

## 4. 错误处理

优先返回结构化结果或抛出有文档约束的异常。错误至少包含稳定 code、stage、message、source range（如适用）、object id（如适用）和 cause。不得静默吞掉配置错误、Provider 缺失或渲染失败。

## 5. Kotlin/Java 互操作

公共接口应能被 Java 和 Kotlin 直接使用；避免仅 Kotlin 调用方便但 Java 难以消费的 API。必要时提供 Java 友好的 Builder、显式 getter、非 suspend 同步入口及异步回调/CompletionStage 适配。

## 6. 当前实现审计基线

当前已观察到 `WindbellTshEngine` 是 Kotlin 单例，提供 `loadTshSource`、`computeHighlights`、`computeFolds`、`computeOutline`、`getHoverInfo` 和直接写入 `SpannableStringBuilder` 的方法；Native 侧通过 JNI 扁平数组传输数据。它尚不是完整编辑器对象/Provider/诊断/渲染抽象。迁移时需保留现有入口并逐步委托到新服务。
