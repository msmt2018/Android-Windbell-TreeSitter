# Attribute、Semantic 与 Highlight API 契约

> 状态：目标契约。确保 .tsh 声明的属性真正参与运行时计算，而非停留在 AST 或配置 Map 中。

## 1. Attribute Registry

~~~kotlin
interface TshAttributeRegistry {
  fun register(schema: TshAttributeSchema): TshResult<TshAttributeKey<*>>
  fun get(name: String): TshResult<TshAttributeSchema>
  fun list(namespace: String? = null): List<TshAttributeSchema>
}
~~~

AttributeSchema 包含 name、type、default、inheritance、mergePolicy、resolver、consumer capability、serialization codec 和 version。属性名稳定，类型不可在兼容版本中改变。扩展属性必须有注册消费方。

## 2. 语义解析

SemanticResolver 将 Match + Capture + Scope + Selector Context 转换为 TypedAttributeSet。输入携带 document/config revision；输出记录每个属性的来源规则、优先级、默认/继承路径和覆盖原因。语义计算不得直接修改 AST 或全局注册表。

## 3. 属性继承与合并

继承来源包括引擎默认、语言默认、父 Scope、当前 Scope、Selector、Theme/Style 和显式覆盖。每个属性单独声明 replace、fallback、append、set-union 或 numeric-compose；未声明策略时默认 replace。循环继承和类型不匹配是编译错误。

## 4. Highlight API

~~~kotlin
interface TshHighlightService {
  fun highlight(input: TshHighlightInput): TshResult<TshHighlightResult>
}
data class TshHighlightResult(
  val matches: TshMatchSet,
  val scopes: List<TshScopeAssignment>,
  val attributes: List<TshResolvedAttributeSet>,
  val styles: List<TshResolvedStyle>,
  val diagnostics: List<TshDiagnostic>,
  val revision: TshPipelineRevision
)
~~~

处理顺序：两条匹配管线 → Match normalization → Scope assignment → Selector matching → Attribute resolution → Theme/Style cascade → RenderPlan。每阶段均可独立诊断和 trace。禁止在匹配阶段直接硬编码颜色。

## 5. 语义属性的运行时消费

每个属性 Schema 必须声明 consumer：Match engine、Scope resolver、Style resolver、Font resolver、Color resolver 或 Render backend。没有 consumer 的属性不得报告“已生效”。属性计算结果进入 RenderInstruction，RenderReceipt 必须报告实际提交/降级/拒绝。

## 6. 缓存与增量

缓存键包含 match identity、scope revision、attribute schema revision、theme revision 和 config revision。属性定义或 selector 改变时只失效依赖节点；输出必须绑定输入版本，禁止旧结果覆盖新结果。

## 7. 验收

为每个内建属性建立从 DSL fixture 到 resolved attribute 再到 render instruction 的端到端测试；断言最终属性值、来源解释和后端消费结果。
