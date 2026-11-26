# TSH 语言与 .tsh 配置工具链

具体文件语法以以下规范为准：

- [TSH DSL v2 规范](../TSH_DSL_V2_SPEC.md)
- [TSH DSL v2 语法参考](../TSH_DSL_V2_SYNTAX.md)
- [SCM → TSH 迁移指南](../TSH_SCM_TO_TSH_MIGRATION.md)

## 1. 工具链职责

目标工具链可包含 Source Manager、Lexer、Parser、不可变 AST、Schema/类型校验、语义校验、Compiler、诊断、格式化与 Language Service。当前 Native WindbellTshCompiler 是从 .tsh 文本编译为运行时 TshGrammar 的入口；不能把规划中的 IDE 语言服务能力描述为已完成。

## 2. 当前 Native 配置入口

当前根入口包括 language、兼容 name/ignoreCase、matching、editor、styles、schemes、defaultScheme、isDark、defines、repository、treeSitter.queries、ast 与 contains。

当前只有 ATS 与 Oniguruma Regex 两条匹配管线。Tree-sitter Query 是 ATS/Tree-sitter 侧的原生 Query 执行方式，不是第三条管线。Native compiler 不接受 Monarch，也不接受尚未实现的统一 rules { ats/regex } façade。

## 3. 编译阶段

1. Lexer：读取标识符、字符串、反引号原始字符串、Regex、颜色、注释与结构标点。
2. Parser：识别根区块、规则对象、Query source 与嵌套 Regex 规则。
3. Grammar binding：将 ATS 节点/field 与 Query 绑定到传入的 TSLanguage。
4. Regex/Query compilation：编译 Oniguruma 表达式与原生 Tree-sitter Query。
5. Semantic binding：解析 style 名、capture group、Query capture、repository/defines 引用和 scheme inheritance。
6. Runtime materialization：生成可执行 grammar、selector index、query program 与 regex rules。
7. Capture arbitration/rendering：将两条管线结果统一为 span，交给主题与 Android renderer。

## 4. DSL Model/Schema API 与文件语法的区别

TshDslModel.kt、TshSchemaRegistry.kt 与 TshDslRuntimeDispatcher.kt 定义 parser-neutral 声明模型、Schema 校验与属性消费者分发机制。它们是 DSL/API foundation 的类型系统，不意味着所有 TshDslDeclarationKind、单位、颜色空间或通用值类型都已映射为 Native .tsh 文件语法。

必须区分：
- 文件 Parser 接受：Native WindbellTshCompiler 实际识别的字段与结构。
- Schema model 可表示：Kotlin DSL model 能表达的通用声明/值。
- Runtime consumer 已注册：某属性有实际 consumer 可执行。
- 宿主已消费并绘制：Android/editor adapter 已将结果用于最终输出。

这四种状态不能互相推断。

## 5. 诊断与验证

Native compiler 的 compileDetailed 返回本次编译结果及 offset/line/column 诊断。TshDslRuntimeDispatcher 对未应用属性、缺失 consumer、能力不满足与 consumer 异常保留诊断；Schema 验证通过并不等于属性已应用。

正式语言服务仍需持续补齐完整 AST/source range、字段 Schema、语义引用解析、formatting、completion、hover、definition/references 与迁移测试。不要通过文档声称尚未落地的能力已实现。
