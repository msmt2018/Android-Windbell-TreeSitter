# Regex 独立执行与窗口 API 契约

> 状态：目标契约。Regex 是独立管线；不混入 Tree-sitter/ATS。重点解决 Oniguruma 连续指针与 Piece Tree/Rope/滑动窗口非连续文本的冲突。

## 1. 核心原则

Oniguruma 调用只接收在调用期间稳定、连续、有效的字符/字节缓冲区。绝不把 Piece Tree chunk 指针、临时拼接片段或窗口外地址伪装成连续输入。每次执行使用 WindowLease：拥有连续 scratch buffer，固定编码、base offset、有效长度和生命周期；调用结束即释放或归还池。

## 2. 窗口模型

~~~kotlin
data class TshRegexWindow(
  val baseUtf16: Long,
  val text: CharSequence,
  val leftContext: CharSequence,
  val rightContext: CharSequence,
  val isDocumentStart: Boolean,
  val isDocumentEnd: Boolean,
  val revision: Long
)
interface TshRegexWindowProvider {
  fun acquire(range: TshTextRange, context: TshRegexContext, budget: TshExecutionBudget): TshResult<TshRegexWindowLease>
}
interface TshRegexExecutor {
  fun execute(pattern: TshCompiledRegex, window: TshRegexWindowLease, options: TshRegexExecutionOptions): TshRegexResult
}
~~~

窗口契约必须定义：目标区间、前后文长度、锚点语义、编码、UTF-16/UTF-8 映射、边界是否为真实文档边界、窗口是否包含完整 grapheme。缓冲区不得在 Native 调用期间移动或被修改。

## 3. 跨窗口正确性

仅增加 overlap 并不能对任意正则保证正确。必须区分：
- 有界上下文规则：编译期可证明最大前后文跨度，使用 overlap + 去重。
- 无界/不可证明规则：必须退化为整段连续快照执行、支持状态续扫的后端，或返回 REGEX_WINDOW_UNBOUNDED；不得静默漏匹配。
- 锚点 ^/$：仅在真实文档边界或显式 multiline 行边界成立，不能把窗口边界误当文本边界。
- lookbehind/lookahead：窗口上下文必须满足编译期计算的需求；无法满足则失败/回退。
- greedy/lazy、捕获组和反向扫描必须定义窗口拼接语义；无法证明等价则不启用窗口模式。

## 4. Oniguruma Adapter

Adapter 负责将窗口转成稳定连续编码 buffer，创建 region/match data，调用编译/搜索，提取捕获范围并立即转换成全局偏移。必须处理 encoding、invalid sequence、region boundary、match data 生命周期和异常码。所有 Native pointer 仅在 lease 生命周期内有效。

## 5. 增量执行

文本变更映射为 dirty ranges。Regex 规则声明依赖特征：最大匹配跨度、lookbehind/lookahead、行锚点、多行模式、全局状态。有限跨度规则扩展 dirty range 后局部重算；无界规则按策略重算完整逻辑区域。缓存键包含 pattern revision、document revision、encoding、window policy 和 rule options。

## 6. 安全

每条规则有 timeout、步数/回溯预算、最大窗口长度、最大捕获数和取消检查。Oniguruma 若无法强制中断某个调用，则必须在隔离工作线程/可终止执行单元中运行，不能声称软超时可以终止 native call。连续超限可隔离规则。

## 7. 验收

Piece Tree 跨 chunk、emoji/组合字符、超长行、窗口边缘匹配、lookbehind、lookahead、^/$、多行、零宽匹配、灾难性回溯、取消和增量 dirty range 均需与整段连续基准结果比对。无法证明等价的模式必须明确回退或拒绝。


## 当前源码落地状态（2026-11-10）

已实现的资源层基础：
- `TshRegexWindowConstraints`、`TshRegexWindowLease`：有限 max match/lookaround、连续 UTF-16 窗口、Unicode 边界、core start 唯一归属、EOF 零宽匹配、可选 REGEX_WINDOW reservation。
- `TshRegexWindowSequence`：独立 Snapshot Lease、自动连续分区、surrogate-safe core boundary、失败不推进、空文档单窗口。

尚未完成的执行层接线：
- 现有 `WindbellTshEngine.computeHighlights(tree, text, extension)` 仍将完整 String 传入 JNI `nativeHighlight`；C++ `WindbellEngine::highlightHybrid` 仍接收整份连续 UTF-16 文本。
- 现有 DSL Regex MATCH 规则尚未声明可证明的最大匹配/上下文跨度；BLOCK 规则需要跨窗口状态机。
- WindowLease/Sequence 尚未驱动 Oniguruma，也未把窗口命中统一映射为 `TshMatch`。
- 因此大文本 Regex/Oniguruma 尚未完成窗口化迁移；不得将资源层 API 的完成等同于 Regex 大文本执行完成。
