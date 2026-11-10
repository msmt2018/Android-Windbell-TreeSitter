# 统一 Match 与 Scope API 契约

> 状态：SOURCE_PARTIAL。新增 TshMatch/TshMatchSet/TshScopePath/TshMatchMerger、TshScopeSelector AST、TshStyleResolver 首版纯 Kotlin 模型，以及 Tree-sitter QueryResult → TshMatch 适配器源码与测试源码。支持双来源 provenance、Long/UTF-16 canonical range、revision 校验、去重、稳定排序、stale Match 排除、selector suffix/exact path 匹配及 Style merge；Regex → TshMatch、Scope parser/assignment 生成、DSL selector parser 和端到端验证尚未完成。新增源码尚未构建或运行测试。

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


## 8. Tree-sitter QueryResult → TshMatch 适配

`TshTreeSitterMatchAdapter` 将现有 `TshQueryResult.captures` 转换为统一 Match 批次，不创建新的查询管线，也不读取/展平源文档：

- 输入必须是同一 queryId/queryRevision、documentId/documentVersion、treeRevision 和 grammar fingerprint 的结果；任一版本身份不一致均拒绝发布。
- 坐标索引必须绑定同一 documentId/version。UTF-8 输入使用稀疏索引的 UTF-8 byte → UTF-16 转换；UTF-16LE/BE 输入要求 byte offset 为偶数，再以 UTF-16 code-unit offset 检查 surrogate 边界。
- 所有 Match 的 canonical range 均为 UTF-16 code-unit 半开区间；UTF-8 输入额外保留 rawRange（UTF-8 byte），UTF-16 输入不伪造 UTF-8 rawRange。
- 每个 Native capture 映射为一个 Match，保留 capture name、pattern index、node identity、query/grammar provenance 和 pipeline revision。scopeAssignments 暂为空，必须由后续 Scope Assignment 阶段生成。
- Native Cursor 返回顺序不是公共契约；适配器按 start、outer-before-inner、pattern、capture name 和稳定 ID 排序。
- Native result 不完整时输出 completeness=TRUNCATED；该状态必须继续传递到后续 MatchSet merge，不能被提升为 COMPLETE。
- Match ID 由文档版本、查询版本、管线版本、capture 身份和重复序号确定性生成；ID 仅在相同输入快照与相同 revision 内稳定。

该适配器只覆盖 Tree-sitter 原生查询结果；ATS Compiler 尚未实现，未来 ATS lowering 后复用同一 Tree-sitter QueryResult 适配路径。Regex 仍保持独立执行，并需要自己的结果适配器。
