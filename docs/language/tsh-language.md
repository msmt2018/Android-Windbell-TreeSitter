# TSH 语言与 .tsh 配置工具链

## 1. 目标与边界

.tsh 是 TSH 引擎的声明式配置语言。核心工具链负责加载、词法分析、语法分析、AST、Schema/类型/语义校验、编译、诊断、格式化与版本迁移。Completion/Hover 等语言编辑体验可由宿主工具消费，但不是 TSH 执行引擎 API 的职责。

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


## 6. 配置对象与管理 API

应用必须能通过 ConfigRepository/Catalog 查询和管理 .tsh 编译产物：配置源 load/reload/validate/compile/install/replace/unload；Theme/Style/Font/Color/Scope/Selector/Attribute/Grammar/Query/Regex/Injection 对象 get/list/resolve；Theme preview/activate/reset；Style resolve/explain。运行时对象不可变，变更经 ConfigTransaction 或新的 .tsh 源重新编译并原子替换。详细契约见 api/tsh-file-config-management-api.md。

## 7. DSL 属性和底层语义契约

每个可写属性必须在 Schema 中声明名称、类型、默认值、单位、继承与合并策略、能力要求和版本。字体必须覆盖 family、weight、style、size、stretch、lineHeight、letterSpacing、baselineShift、OpenType features、variable axes、fallback 和 synthesis；颜色必须定义格式、alpha 与颜色空间；Decoration 必须定义类型、范围、层级和合成。Parser 接受语法不代表 Runtime 已执行，必须由底层 Resolver/Render Backend 消费并完成真实绘制。见 api/tsh-dsl-syntax-and-semantic-contract.md 与 api/theme-style-font-color-api.md。
