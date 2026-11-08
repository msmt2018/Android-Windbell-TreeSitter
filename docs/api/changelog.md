# API 变更记录

## Unreleased

### Added
- 建立 TSH 统一 API 架构文档、对象/文本模型、编辑器集成、高亮渲染、TSH 语言、ATS/Regex、诊断和路线图规范。
- 新增 TshEngine 生命周期门面、结构化结果/诊断、坐标与范围基础类型。
- 新增实例身份对象注册表、能力描述符和 Provider 注册/发现契约。
- 新增 TextDocument/TextSnapshot/TextEdit/TextChange 与 TextAccessProvider 契约。
- 新增内存文本 UTF-8/UTF-16/Unicode Code Point/Grapheme 坐标转换器及测试源码。
- 新增 Runtime/Session、Config/Language Service、Editor Adapter、Highlight/Render、Binding/Event/Extension、ATS/Regex/Match 和 Diagnostics/Observability 详细 API 规范。

### Planned
- Piece Tree/Rope 索引化坐标映射与 Layout-aware 坐标 Provider。
- Engine Session、任务调度、资源 Scope 和兼容迁移接线。
- .tsh Lexer/Parser/AST/Schema/Language Service 实现。
- Editor/Layout/Viewport/Render Provider 标准适配器。
- Highlight/Attribute/Theme/RenderPlan 运行时实现。
- Binding/EventBus/Provider 版本协商和诊断收集器。
- 本地 Gradle 编译、自动化测试和真实编辑器渲染验证。
