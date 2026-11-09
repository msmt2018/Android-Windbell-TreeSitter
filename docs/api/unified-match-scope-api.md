# 统一 Match 与 Scope API 契约

> 状态：SOURCE_PARTIAL。新增 TshMatch/TshMatchSet/TshScopePath/TshMatchMerger 首版纯 Kotlin 模型，支持双来源 provenance、Long/UTF-16 canonical range、revision 校验、去重、稳定排序和 stale Match 排除；Scope/Selector 解析、冲突策略、Tree-sitter/Regex 真实执行结果适配和端到端验证尚未完成。

## 1. TshMatch

~~~kotlin
enum class TshMatchSource { TREE_SITTER_ATS, REGEX }
data class TshMatch(
  val id: TshMatchId,
  val documentId: TshDocumentId,
  val documentVersion: Long,
  val source: TshMatchSource,
  val pipelineRevision: String,
  val ruleId: TshRuleId,
  val patternIndex: Int?,
  val captureName: String?,
  val range: TshSourceRange,
  val priority: Int,
  val captures: List<TshCaptureValue>,
  val scopeAssignments: List<TshScopeAssignment>,
  val provenance: TshMatchProvenance
)
~~~

所有 range 统一半开区间，并声明坐标系；内部 canonical offset 建议使用 UTF-16 或明确的 byte offset + converter，不允许在一个字段里混用单位。Match 保留原始 byte range（ATS）或字符 range（Regex），并提供 canonical mapped range。

## 2. 统一查询结果

TshMatchSet 携带 document/config/grammar/query/regex revisions、排序规则、diagnostics、completeness（complete/partial/truncated）和统计。部分结果不得伪装成完整结果。匹配 ID 稳定性只保证在同 revision 同输入快照内。

## 3. Scope 模型

Scope 是语义路径，不是颜色，也不是管线。TshScopePath 由有序 segment 构成，例如 source.lang > meta.function > entity.name.function。ScopeAssignment 可绑定整个 Match 或命名 Capture；支持显式 parent scope、scope stack 和局部覆盖。Scope ID 与展示名称分离。

## 4. Selector

Selector 语法必须支持精确 scope、通配、祖先/后代关系、组合和受限否定。编译成 SelectorPlan，静态计算 specificity；匹配器有复杂度预算。selector 对 scope path 进行匹配，不执行额外文本解析。

## 5. 冲突与合并

匹配重叠不等于冲突。Resolver 分别处理：完全相同范围、包含关系、交叉重叠、相同规则多 capture。规则策略包括 retain-all、highest-priority、narrowest、outermost、merge-scopes；每种策略在语言配置中声明，默认规则固定。ATS 与 Regex 同优先级时必须使用确定性 tie-breaker，并保留双方 provenance。不得因优先级丢弃结构性 Match，除非规则显式要求。

## 6. 去重与排序

去重 key 至少包括 source、ruleId、capture identity、range 和 revision。输出排序先 start，再 end/嵌套策略，再 priority，再稳定 ruleId；同范围顺序稳定。跨 revision 结果绝不合并。

## 7. 验收

两条管线产生相同字段完整度；验证 Unicode 映射、capture scope、重叠/包含/交叉冲突、稳定排序、partial result、stale revision 和 provenance 可追溯。
