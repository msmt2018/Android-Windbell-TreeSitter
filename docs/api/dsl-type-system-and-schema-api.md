# TSH DSL 完整类型系统与 Schema API

> 状态：目标契约。本文定义 .tsh 每个语法节点和属性如何被解析、类型检查、编译及执行；不宣称当前 Parser 已完整实现。

## 1. 编译契约

唯一配置编译链：Source → Lexer → Parser → AST → Schema Validation → Type Checking → Reference Resolution → Semantic Validation → Lowering → Immutable ConfigArtifact。任何阶段失败都不得发布半成品。诊断必须携带 source URI、revision、AST node ID、精确 range、稳定 code 和 stage。

## 2. AST 节点体系

根节点 TshDocument(version, declarations)。声明节点：MetaDecl、LanguageDecl、GrammarDecl、QueryDecl、RegexDecl、ScopeDecl、SelectorDecl、AttributeDecl、ThemeDecl、StyleDecl、FontDecl、ColorDecl、DecorationDecl、InjectionDecl、VariableDecl、IncludeDecl。表达式节点：LiteralExpr、ArrayExpr、ObjectExpr、ReferenceExpr、CallExpr、UnaryExpr、BinaryExpr、ConditionalExpr。每个节点包含 nodeId、range、leading/trailing trivia、parentId；注释和原始字面量必须保留以支持稳定格式化。

## 3. 完整值类型

| 类型 | 语义 | 约束 |
|---|---|---|
| Bool | true/false | 不接受字符串转换 |
| Int/Float | 有符号数值 | 溢出、NaN、Infinity 拒绝 |
| String | Unicode 字符串 | 转义与编码明确 |
| Identifier | 声明名/键 | 词法规则固定 |
| Enum<T> | 有限枚举 | 未知值诊断 |
| Color | 颜色值 | 色彩空间、alpha 明确 |
| FontFamily | 字体族列表 | 顺序即 fallback 顺序 |
| FontSize/Length | 带单位尺寸 | 不丢失单位 |
| Duration/Angle | 时间/角度 | 单位归一化 |
| Regex | 正则模式 AST/源码 | 独立 Regex 管线编译 |
| Query | ATS 查询源码/AST | Tree-sitter grammar 绑定 |
| Array<T> | 同质列表 | 元素逐个校验 |
| Map<K,V> | 键值映射 | 键类型固定 |
| Reference<T> | 对象引用 | 编译期解析与类型检查 |
| Optional<T> | 可缺省值 | null 与 absent 区分 |
| Union<T...> | 有限联合 | 必须可判别 |
| Expression<T> | 有类型表达式 | 推导结果必须为 T |

尺寸单位至少包括 px、dp、sp、pt、em、rem、%；单位换算必须携带 TshMeasureContext，不允许用无上下文的隐式常数换算。颜色支持 #RGB/#RGBA/#RRGGBB/#RRGGBBAA、rgb/rgba、hsl/hsla、命名 token 和变量引用。

## 4. Schema 定义

~~~kotlin
data class TshPropertySchema(
  val name: String,
  val type: TshType,
  val required: Boolean,
  val defaultValue: TshValue?,
  val nullable: Boolean,
  val enumValues: Set<String>,
  val minimum: Double?,
  val maximum: Double?,
  val unit: TshUnit?,
  val inheritable: Boolean,
  val mergePolicy: TshMergePolicy,
  val introducedIn: TshDslVersion,
  val deprecatedIn: TshDslVersion?,
  val capability: TshCapability?
)
interface TshSchemaRegistry {
  fun schema(declarationKind: TshDeclarationKind): TshResult<TshDeclarationSchema>
  fun validate(ast: TshDocument, mode: TshValidationMode): TshValidationResult
}
~~~

Schema 必须定义字段是否允许重复、顺序是否有意义、默认值何时生效、空值语义、扩展命名空间、未知属性策略、跨字段约束和后端能力要求。

## 5. 属性语义契约

所有可声明属性必须有 canonical name、类型、默认值、单位、可继承性、合并策略、解析阶段、消费阶段和降级策略。属性不能只存在于 AST；必须映射到 TypedAttribute、ResolvedStyle 或执行规则。未知属性不得静默丢弃。扩展属性必须注册 Schema 和消费 Provider；没有消费方时编译失败或明确 warning（按 strictness）。

## 6. 表达式、引用和语义

支持字面量、数组、对象、变量引用、强类型对象引用、有限内建函数、算术/逻辑表达式和条件表达式。禁止任意脚本执行。引用图必须检测未定义、类型不符、循环和跨语言不兼容。函数 Schema 包含参数类型、返回类型、纯度、可用阶段和成本预算。

## 7. 语法版本与迁移

TshDslVersion 使用 major.minor；major 不兼容变更，minor 仅增加兼容语法。Compiler 必须拒绝未知 major。MigrationPlan 是纯 AST 转换，保留注释和源映射，输出迁移诊断；不得静默改写未知字段。

## 8. 验收矩阵

每种声明/属性都必须有 lexer fixture、parser AST golden、schema 正反例、type checker 测试、reference test、compiler artifact test 和 runtime consumer test。新增 DSL 属性的 PR 必须同时提交 Schema、Resolver/Executor 消费实现、文档和测试。
