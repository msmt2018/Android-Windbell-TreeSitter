# TSH 语言与 .tsh 配置工具链

## 1. 目标

.tsh 不仅是运行时配置文件，也应拥有完整的语言服务：词法分析、语法分析、AST、Schema、类型/语义校验、诊断、补全、Hover、定义、引用、重命名、格式化、快速修复、迁移和调试。

## 2. 工具链阶段

1. Source Manager：文件 URI、版本、编码和来源映射。
2. Lexer：Token、注释、字符串、正则字面量和错误恢复。
3. Parser：递归下降/声明式语法解析并生成 AST。
4. AST：稳定节点类型、父子关系、精确源范围。
5. Schema：字段、类型、必填、默认值、约束和版本。
6. Semantic Validator：名称引用、规则冲突、语言符号、正则可编译性、样式引用。
7. Compiler：将合法配置转换为运行时模型；编译失败不得污染已生效配置。
8. Language Service：补全、Hover、定义、引用、重命名、格式化和 Code Action。
9. Debugger：规则命中、管线执行、样式来源与诊断追踪。

## 3. 诊断要求

每条诊断包含 code、severity、message、source URI、range、related information、可选 quick fix 和文档版本。区分词法错误、语法错误、Schema 错误、类型错误、语义错误、编译错误和运行时警告。

## 4. 语言规范治理

在实现 Parser 前先维护正式语法规范和版本号。新语法必须有兼容策略、迁移规则、正反例和测试夹具。不要把当前编译器接受的语法自动视为完整、稳定的语言规范。

## 5. 当前已知基线

现有 Native TshCompiler 提供 .tsh 源码到 TshGrammar 的编译入口，当前 Kotlin API 的 loadTshSource 返回 Boolean。该接口没有暴露完整诊断列表、AST、Schema、格式化或语言服务结果。上述功能属于待建设目标，不能标记为已完成。
