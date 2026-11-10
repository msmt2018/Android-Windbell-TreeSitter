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


## 当前源码落地状态（2026-11-10，SOURCE_PARTIAL）

### 已实现的资源层与独立 Native 执行切片

- `TshRegexWindowConstraints`、`TshRegexWindowLease`：有限 max-match/lookaround 声明、连续 UTF-16 窗口、surrogate-safe 边界、core start 唯一归属、EOF 零宽匹配、可选 REGEX_WINDOW reservation。窗口左侧同时保留 maxMatchLength-1、lookbehind 与行锚点前导字符，右侧保留 maxMatchLength-1、lookahead 与行锚点后导字符；容量校验计入两侧上下文、surrogate 边界扩展，并按每 UTF-16 code unit 6 bytes 估算 String/JNI/sentinel 暂存。
- `TshRegexWindowSequence`：独立 Snapshot Lease、自动连续分区、失败不推进、空文档单窗口。
- `TshRegexNativeRegistry` / `TshOnigurumaRegex`：新增独立 Oniguruma Native Handle Registry、compile/release/find-all JNI 和 Kotlin Executor；opaque handle 由注册表管理，执行期间以 shared ownership 保活，单个 handle 的执行串行化。
- `executeWindow` 只向 Native 传入一个有界窗口 String，不传整份 Piece Tree/Document；Native 使用 UTF-16LE、region RAII、合成非换行边界 sentinel，防止窗口边界伪装成文档边界并保持 `^`/`$`/`\A`/`\z` 锚点语义、core start 归属、匹配数/捕获组上限。Kotlin 校验返回范围和 Unicode 边界并转换为绝对 UTF-16 范围。当前捕获组用 `group_N` 数字名；Native 对单个 pattern 和单个窗口分别设置 64 Ki UTF-16 code-unit pattern 上限、512 个活跃 Native Regex Handle 上限与 2 MiB 单窗口结果载荷上限。

### 仍未完成 / 不能宣称已验证

- 旧 `WindbellTshEngine.computeHighlights(tree, text, extension)` 仍将完整 String 传入 JNI `nativeHighlight`；新独立 Executor 尚未迁移现有 DSL Regex MATCH/BLOCK 规则，也未替换旧混合高亮入口。
- `TshRegexWindowConstraints` 是调用方声明，不是 Regex 静态分析器证明。当前明确拒绝 `\\G` 与 `\\K`；其他全局状态及任意 greedy/lazy/lookaround 模式的跨窗口等价尚未验证。
- 捕获组目前以 `group_N` 数字名暴露，Oniguruma named-capture 名称枚举尚未接入。
- BLOCK 规则跨窗口状态机、timeout/强制取消、灾难性回溯隔离、增量 dirty range、Native/NDK/JUnit 验证尚未完成。
- 因此这只是独立 Regex Native Executor 的首个源码切片，不是生产级完整 Regex 管线，也不代表大文本高亮已完成窗口化迁移。
