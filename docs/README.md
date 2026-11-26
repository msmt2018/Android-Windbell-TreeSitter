# TSH 文档中心

本目录包含 Windbell Tree-sitter Highlighting（TSH）的架构、实现、语言配置、引擎行为与集成说明。

## 文档导航

| 文档 | 内容 |
|---|---|
| [总体架构](architecture/overview.md) | 模块边界、分层、数据流和架构原则 |
| [源码 API 参考](api/README.md) | 根据 `android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter` 当前源码编写的 Java/Kotlin API 参考 |
| [编辑器集成](guides/editor-integration.md) | 宿主编辑器如何接入现有 TSH 能力 |
| [高亮与渲染](guides/highlight-render-pipeline.md) | 配置、匹配、样式与 Android 渲染链路 |
| [TSH 语言与配置](language/tsh-language.md) | `.tsh` 配置语法及其当前实现边界 |
| [ATS 与 Regex](engine/ats-regex.md) | Tree-sitter/ATS 与独立 Regex 匹配管线 |
| [诊断规范](diagnostics/diagnostics.md) | 诊断模型与错误定位 |
| [开发路线图](roadmap.md) | 分阶段工作与验收状态 |

## API 文档准则

API 参考仅陈述当前 Java/Kotlin 源码中存在的类型、签名、生命周期和可观察行为。规划中的 API、尚未实现的语法能力、宿主编辑器功能和未来架构不得作为当前公共 API 契约描述。

具体接口以对应源码为准。若文档与源码不一致，以源码为准，并应同步修正文档。

## 状态标记

- **设计中**：仅有设计或规划，不能作为可调用 API。
- **实现中**：源码存在，但实现或集成仍在开发。
- **已实现**：对应代码已落地；不等同于已通过构建或设备验证。
- **已编译验证**：对应模块构建通过。
- **已运行验证**：自动化测试或真实设备验证通过。

文档不以接口命名、类存在或设计描述替代构建、测试与运行证据。
