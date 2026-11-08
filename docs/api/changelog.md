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

- 新增 Symbol/Outline/Folding/Navigation、Selection/Cursor/Multi-Cursor、Language Intelligence、Injection/Language Layer、Decoration/Gutter/Inlay/CodeLens、EditTransaction/Undo/Redo/Command 六组 API 规范。
- 明确宿主拥有编辑、撤销栈与实际绘制；TSH 通过版本化事务提案、owner-scoped decorations 和 Render Provider 接入，避免覆盖宿主状态。
- 补充语言智能多 Provider 聚合、过期版本拒绝、嵌入语言范围映射及多光标语义约束。

### Planned
- Piece Tree/Rope 索引化坐标映射与 Layout-aware 坐标 Provider。
- Engine Session、任务调度、资源 Scope 和兼容迁移接线。
- .tsh Lexer/Parser/AST/Schema/Language Service 实现。
- Editor/Layout/Viewport/Render Provider 标准适配器。
- Highlight/Attribute/Theme/RenderPlan 运行时实现。
- Binding/EventBus/Provider 版本协商和诊断收集器。
- 本地 Gradle 编译、自动化测试和真实编辑器渲染验证。


### Core DSL/API scope correction
- 补充 .tsh 配置源、加载/编译/安装/替换/卸载、对象 Catalog 查询和配置事务契约。
- 补充 TSH DSL v1 顶层声明、类型系统、属性 Schema、表达式、引用和语义诊断契约。
- 补充 Theme/Style/Font/Color/Decoration API，明确字体族、字重、字形、字号、字距、行高、OpenType 特性、可变字体轴与 fallback。
- 补充 DSL 能力到 Parser/Resolver/Runtime/Render Backend 的覆盖矩阵，防止只定义语法而没有执行实现。
- 从核心 API 索引移除通用编辑器功能文档；这些内容仅作为宿主集成边界参考。嵌入语言语法注入保留为 TSH 核心能力。
- 以上新增内容均为目标契约文档；未据此宣称底层实现、Gradle 构建、自动化测试或设备绘制已经完成。
