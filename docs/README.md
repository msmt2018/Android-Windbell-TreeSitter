# TSH 文档中心

[简体中文](README.md) | [English](README_EN.md)

本目录包含 Windbell Tree-sitter Highlighting（TSH）的架构、实现、语言配置、引擎行为与集成说明。

## 文档导航

| 文档 | 内容 |
|---|---|
| [总体架构](architecture/overview.md) / [English](architecture/overview.en.md) | 模块边界、分层、数据流和架构原则 |
| [源码 API 参考](api/README.md) | 根据当前 Java/Kotlin 源码编写的 API 参考（英文） |
| [编辑器集成](guides/editor-integration.md) / [English](guides/editor-integration.en.md) | 宿主编辑器如何接入 TSH |
| [高亮与渲染](guides/highlight-render-pipeline.md) / [English](guides/highlight-render-pipeline.en.md) | 配置、匹配、样式与 Android 渲染链路 |
| [TSH DSL v2 规范](TSH_DSL_V2_SPEC.md) / [English](TSH_DSL_V2_SPEC.en.md) | 文件结构、架构边界、两条管线与实现约束 |
| [TSH DSL v2 语法参考](TSH_DSL_V2_SYNTAX.md) / [English](TSH_DSL_V2_SYNTAX.en.md) | 根字段、ATS、Query、Regex、predicates、样式与主题 |
| [SCM → TSH 迁移指南](TSH_SCM_TO_TSH_MIGRATION.md) / [English](TSH_SCM_TO_TSH_MIGRATION.en.md) | highlights.scm 的 Query、capture 与 predicate 迁移 |
| [TSH 语言与配置工具链](language/tsh-language.md) / [English](language/tsh-language.en.md) | Lexer/Parser/Schema/Validator/Compiler 工具链职责与边界 |
| [ATS 与 Regex](engine/ats-regex.md) / [English](engine/ats-regex.en.md) | Tree-sitter/ATS 与独立 Regex 匹配管线 |
| [诊断规范](diagnostics/diagnostics.md) / [English](diagnostics/diagnostics.en.md) | 诊断模型与错误定位 |
| [开发路线图](roadmap.md) / [English](roadmap.en.md) | 分阶段工作与验收状态 |
| [当前实现审计基线](audit/current-baseline.md) / [English](audit/current-baseline.en.md) | 静态源码审计及待验证风险 |
| [高亮准确性与真实渲染规范](TSH_HIGHLIGHT_ACCURACY_AND_RENDERING_INTEGRITY.md) / [English](TSH_HIGHLIGHT_ACCURACY_AND_RENDERING_INTEGRITY.en.md) | 匹配完整性、坐标、颜色和最终绘制验收 |
| [超大文本 Native Token 架构](TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.md) / [English](TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.en.md) | Native 分块输入、Token、Query、缓存与内存策略 |
| [TSH 语言规范与核心架构](%2A.tsh%20语言规范与体系架构.md) / [English](TSH_LANGUAGE_ARCHITECTURE.en.md) | TSH 单一配置入口、无 SCM 中间产物、ATS/原生 Query 双路径 |
| [Native 架构图](Architecture.md) / [English](Architecture.en.md) | Kotlin、JNI 与 C/C++ 核心边界 |

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
