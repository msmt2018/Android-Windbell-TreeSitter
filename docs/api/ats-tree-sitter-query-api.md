# ATS 与 Tree-sitter 统一查询 API 契约

> 状态：目标契约。仅 Tree-sitter + ATS 查询管线；Regex 保持独立，不引入第三管线。

## 1. 分层

GrammarProvider → TshParserSession → TshSyntaxTree → TshAtsCompiler → TshCompiledQuery → TshQueryExecutor → TshCaptureSet。ATS 是 Tree-sitter Query/SCM 的受控简化混合语法，最终编译为 Tree-sitter Query 表示或等价 Native query plan，不是独立 parser engine。

## 2. Query 语法能力

支持节点模式、匿名节点、字段约束、子节点关系、捕获名、量化、alternation、predicate 和受控 directive。每项语法必须定义 AST、编译映射、运行时语义和诊断。predicate 首批允许 eq/not-eq、match/not-match、any-of、set、is?、is-not?；执行必须有成本预算，不允许任意回调脚本。

## 3. API 草案

~~~kotlin
interface TshAtsCompiler {
  fun compile(source: TshAtsSource, grammar: TshGrammarDescriptor, options: TshQueryCompileOptions = TshQueryCompileOptions()): TshResult<TshCompiledQuery>
}
interface TshQueryExecutor {
  fun execute(query: TshCompiledQuery, tree: TshSyntaxTree, range: TshByteRange? = null, budget: TshExecutionBudget): TshQueryResult
}
data class TshCapture(
  val name: String,
  val nodeType: String,
  val byteRange: TshByteRange,
  val nodeId: TshNodeId,
  val patternIndex: Int
)
~~~

CompiledQuery 必须绑定 grammar identity/version、query source hash、ATS language version 和 compile options。不得跨不兼容 Grammar 复用。

## 4. 增量查询

输入变化通过 Tree-sitter edit 更新 Tree；查询可按 changed ranges 限定执行，但必须处理可能跨越 changed range 的祖先/后代依赖。结果带 documentVersion、treeRevision、grammarRevision、queryRevision。旧版本结果必须被丢弃或标记 stale。

## 5. 捕获语义

捕获范围以 UTF-8 byte offset 表示，半开区间 [start,end)。Capture 必须携带 pattern index、capture name、节点类型、节点身份及来源 query。重复 capture 的排序稳定；零长度 capture 策略明确。

## 6. 错误与生命周期

语法节点/字段不存在、谓词参数类型错误、grammar ABI 不兼容、query 编译失败、超时、取消和 Native 资源关闭必须有稳定错误码。CompiledQuery、Parser、Tree 使用引用计数或 lease 生命周期；query 执行期间不得释放依赖 Grammar/Tree。

## 7. 验收

同一查询在 ATS 源码和等价 Tree-sitter Query 上产生一致 capture；验证字段、匿名节点、量化、predicate、Unicode byte range、增量 changed range、取消、预算耗尽和版本失效。
