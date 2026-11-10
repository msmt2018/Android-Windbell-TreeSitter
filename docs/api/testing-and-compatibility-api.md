# 测试、兼容性与 API 版本治理规范

## 目标

TSH API 不仅要能运行，还必须能持续验证兼容性。公共 API、JNI ABI、.tsh 语法、Grammar ABI 和 Provider 协议分别版本化，不能用一个版本号覆盖所有兼容维度。

## 版本维度

| 版本 | 约束 |
|---|---|
| TshApiVersion | Kotlin/Java 公共 API |
| TshProviderApiVersion | Provider 接口和能力协议 |
| TshConfigLanguageVersion | .tsh 语法与 Schema |
| TshNativeAbiVersion | JNI/C ABI 约定 |
| TshGrammarAbiVersion | Tree-sitter Grammar ABI |
| TshThemeSchemaVersion | Theme/Attribute 结构 |

## 目标 API

- TshCompatibilityReport：宿主/API/Provider/Native/Grammar 兼容性报告。
- TshMigrationDescriptor：旧版本到新版本迁移规则。
- TshTestHarness：构建可重复的 Engine/Session/Document 环境。
- TshGoldenTestCase：输入、配置、预期 AST/Match/Style/Render 指令。
- TshConformanceSuite：Provider 能力协议一致性测试。
- TshApiSnapshot：公共 API 签名快照与兼容性比较。

## 测试分层

1. 纯 Kotlin 单元测试：注册表、坐标、状态机、诊断、级联。
2. Native 单元测试：Parser、Query、Regex、JNI handle 和错误。
3. Provider 契约测试：能力、线程、取消、生命周期和异常。
4. Android/Robolectric：View/Span/Canvas 生命周期及 UI 线程。
5. Instrumentation：真实设备编辑器绘制和滚动。
6. Golden tests：固定 .tsh、输入文本、预期 Match/Style/RenderPlan。
7. 性能回归：大文档、快速编辑、多 Session、缓存和 Native 内存。

## API 兼容性规则

- 不在同一 minor 版本删除或改变 public 方法语义。
- 新增必需 Provider 能力属于破坏性变更，必须提高 Provider API 主版本或提供兼容默认行为。
- .tsh 语法变更需声明语言版本和迁移工具。
- JNI ABI 与 TshApiVersion 独立升级。
- Deprecated API 至少保留明确迁移窗口。
- 所有兼容检查在 CI 中执行，不能只依赖人工 review。

## 完成门槛

API 功能只有在签名兼容检查、单元测试、Native 测试、Provider conformance、Android 集成测试和必要的设备验证通过后，才能标记为完成。
