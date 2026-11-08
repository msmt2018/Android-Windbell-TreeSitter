# API 变更记录

## Unreleased

### Added
- 建立 TSH 统一 API 架构文档、对象/文本模型、编辑器集成、高亮渲染、TSH 语言、ATS/Regex、诊断和路线图规范。
- 新增 TshEngine 生命周期门面、结构化结果/诊断、坐标与范围基础类型。
- 新增实例身份对象注册表、能力描述符和 Provider 注册/发现契约。
- 新增 API 基础层单元测试源码。\n- 新增 TextDocument/TextSnapshot/TextEdit/TextChange 与 TextAccessProvider 契约。\n- 新增内存文本 UTF-8/UTF-16/Unicode Code Point/Grapheme 坐标转换器及测试源码。

### Planned
- 本地 Gradle 编译与测试运行验证。\n- Piece Tree/Rope 索引化坐标映射与 Layout-aware 坐标 Provider。
- TextDocument/TextBuffer/CoordinateConverter 与增量变更模型。
- 兼容现有 WindbellTshEngine 的服务接线与迁移适配。
- TSH Lexer/Parser/AST/Schema/Diagnostics 公共 API。
