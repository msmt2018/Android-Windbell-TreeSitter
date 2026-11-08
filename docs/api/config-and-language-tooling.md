# TSH 配置与语言工具 API 规范

## 目标

将 .tsh 从“传入字符串、返回 Boolean”升级为完整语言服务：加载、词法分析、语法分析、AST、Schema 校验、语义校验、编译、诊断、格式化、补全和跳转。配置作者与应用开发者都应获得同一套结构化结果。

## 目标服务与数据类型

| API | 职责 |
|---|---|
| TshConfigSource | 文件、字符串、Asset、URI 或内存来源 |
| TshConfigLoader | 加载并保留来源 URI、版本和编码 |
| TshLexer | Token 流与词法诊断 |
| TshParser | 语法树、错误恢复和语法诊断 |
| TshAstNode | 节点类型、范围、父子关系和属性 |
| TshSchemaRegistry | 字段、类型、必需项、枚举、默认值和版本规则 |
| TshSemanticAnalyzer | 引用解析、重复定义、冲突和跨字段约束 |
| TshConfigCompiler | 生成不可变的运行时配置快照 |
| TshLanguageService | diagnostics、completion、hover、definition、references、format |
| TshConfigSnapshot | 编译成功后的不可变、可并发读取配置 |

上述名称是目标契约，不代表当前源码已全部实现。

## 编译阶段

SOURCE -> DECODE -> LEX -> PARSE -> AST -> SCHEMA -> SEMANTIC -> COMPILE -> CONFIG_SNAPSHOT。

每个阶段独立返回诊断；后续阶段仅在输入满足前置条件时运行。支持错误恢复的阶段应尽可能继续收集多个问题，但不得把不完整 AST 当作有效配置发布。

## .tsh 语言服务最低要求

- Token 类型和词法范围稳定可查询。
- Parser 支持错误恢复，单个错误不应导致整份文件剩余内容全部失去诊断。
- AST 节点范围必须使用明确坐标系，并绑定 source URI 与 document version。
- Schema 支持字段类型、必需/可选、默认值、枚举、范围、废弃字段和版本约束。
- 语义校验检查未定义引用、重复 ID、循环引用、无效属性名、管道冲突和不兼容规则。
- Compiler 输出不可变快照；运行中的 Session 只在新快照完整成功后原子切换。
- 格式化不得改变语义；支持范围格式化和保留注释。
- 补全结果应说明插入文本、替换范围、排序和适用上下文。

## 诊断范围与修复

诊断必须包含 source、start/end、severity、stage、code、message。可修复问题应提供 CodeAction：标题、编辑列表、适用版本和是否需要确认。应用编辑前验证 documentVersion，版本不一致则拒绝并重新计算。

## 兼容策略

旧 loadTshSource(String): Boolean 仅作为兼容包装；新 API 返回 TshCompilationResult。Boolean 包装不得吞掉诊断，必须能通过诊断监听器或 last result 读取具体原因。新增语法特性需更新语法文档、Schema、格式化、补全、测试和版本迁移说明。

## 验收

- 每一种非法 token、缺失分隔符、未知字段、错误类型、重复 ID、未知引用都有确定错误码和范围。
- 一个文件包含多个独立错误时能返回多条诊断。
- 编译失败时运行中的旧配置继续可用。
- 新配置成功后原子替换，不能出现半更新状态。
- Language Service 与 Compiler 使用同一 Lexer/Parser/Schema 定义。
