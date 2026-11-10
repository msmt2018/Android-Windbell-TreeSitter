# TSH 高亮匹配准确性、结果完整性与真实渲染问题总规范

> 文档目的：汇总项目此前反复确认的高亮/绘制问题，将“响应不准确、捕获匹配对象不对、结果截断、颜色未显示、大文本局部失效”等现象统一为可实现、可诊断、可验收的工程规范。
>
> 适用范围：`treesitter`、`android-tree-sitter` JNI/Native、TSH C++ Runtime、Kotlin API/Adapter、宿主 Editor、Piece Tree/Rope、Span/Canvas/View Renderer。
>
> 本文是问题定义与端到端正确性规范，不代表其中所有目标已经实现或通过设备验证。实现状态必须以代码、构建、测试和真实设备证据为准。

---

## 1. 问题总述

项目要解决的不是单一的“语法高亮颜色没有显示”，而是一条完整链路上的正确性问题：

1. **响应不准确**：请求的文档、范围、版本或视口与实际执行的数据不一致；局部请求返回了错误区域、旧结果或不完整结果。
2. **匹配不准确**：Tree-sitter Query/ATS 或 Regex 没有匹配到应匹配的语法对象，或者匹配到了错误的节点、错误的规则、错误的文本区间。
3. **捕获对象不准确**：Query Match、Capture、Node、Pattern、Capture Name、Rule、Style 的对应关系丢失或错配；重复 Capture、嵌套节点、多个 Pattern 的结果被错误合并。
4. **坐标不准确**：UTF-8 byte、UTF-16 code unit、Unicode code point、行列、Piece Tree 绝对偏移、窗口局部偏移之间发生混用、漏算或错误换算。
5. **结果被截断**：Native match limit、pending-match limit、内存预算、分页、批次、窗口边界、输出数组容量或 UI viewport 限制造成结果缺失；上层却把部分结果当作完整结果。
6. **结果被错误过滤或覆盖**：谓词语义、优先级、重叠冲突、Scope/Style 解析、缓存失效或跨管线合并导致合法匹配消失或颜色错误。
7. **颜色/样式未到达绘制层**：匹配和 Style 已经生成，但 .tsh 颜色未解析、Style ID 映射失败、RenderPlan 未被消费、Renderer 不支持某属性，或实际绘制未发生。
8. **大文本性能与稳定性不足**：全文复制、连续缓冲区假设、全量 Span 分配、无界临时数组、查询结果一次性物化、长时间阻塞或内存增长使大文件响应迟缓、失败或显示不全。
9. **增量更新错误**：编辑后旧 Tree、旧 Match、旧 Capture、旧 Span 或旧 RenderPlan 未失效；只按 Capture/viewport 重叠判断缓存有效，遗漏祖先、兄弟、谓词和上下文依赖。
10. **验证口径错误**：仅验证 API 返回非空、Native 有结果或调用 invalidate，就声称颜色已经正确显示；没有验证最终 View/Canvas 像素。

以上问题相互关联。必须按端到端数据链路定位，不能只在某一层增加测试、临时补色或隐藏错误。

## 2. 不可妥协的设计原则

### 2.1 正确性优先于“看起来有响应”

- 响应快不代表响应正确。
- 返回非空不代表匹配正确。
- 返回部分结果不代表完整成功。
- RenderPlan 创建、提交、排队、Span 更新、invalidate 均不等同于屏幕已经绘制。
- 任一阶段无法证明结果完整性时，必须明确返回 PARTIAL、TRUNCATED、STALE、CANCELLED 或 FAILED 等状态，不能伪装为 COMPLETE。

### 2.2 不允许静默丢失

任何层都不得静默丢弃合法数据，包括但不限于：
- Query Match、Capture、Node 和 Pattern；
- Regex full match 与 capture group；
- 跨分区、跨窗口、跨批次结果；
- 视口内应显示的 Span/RenderInstruction；
- .tsh 中声明的颜色、字体、背景、装饰属性；
- 编辑后仍有效的缓存和局部结果。

因明确规则冲突而被覆盖的属性，也必须保留可解释的 arbitration/provenance 记录。

### 2.3 大文本能力必须在底层成立

- Tree-sitter JNI 绑定和 TSH Native 核心必须能直接处理大文本，不得把全部能力寄托在应用层 Piece Tree。
- Piece Tree/Rope 是 TextSource/文本提供者，可提供有界读取、快照、版本和增量编辑；它不是替代 Tree-sitter/TSH 核心完成解析、匹配和捕获的外部补丁。
- Native 层必须正确处理 token、match、capture、offset、结果预算、分页和生命周期。
- 不能通过“先把全文拼接成 String，再交给 JNI/TSH”作为大文本架构。
- 目标设备场景至少覆盖 200–300 MiB 常规大文件，并尽可能支持 200–500 MiB 甚至更大文件；具体能力需按设备内存和操作类型分级验证。
- 动态内存预算必须有硬上限、压力保护和降级/分块策略；项目既定约束为最高不超过 512 MiB，不能无限扩张。

### 2.4 只保留两条匹配管线

1. **ATS / Tree-sitter Query**：结构化语法树查询和 Capture。
2. **独立 Regex / Oniguruma**：连续文本模式与局部补充规则。

两条管线在统一 Match 数据模型汇合，不得引入第三条执行管线，也不得将两者的内部执行语义混为一谈。TextMate/Monarch 可以作为配置组织和语义参考，不作为第三个 Runtime。

### 2.5 Renderer 拥有最终绘制责任

TSH 负责解析、匹配、Scope/Style 解析和 RenderPlan；宿主 Renderer 负责实际显示对象更新与绘制。核心层不得假设宿主一定支持某种样式能力，也不得把 invalidate 冒充 Draw。

---

## 3. 端到端数据链路与正确性边界

必须保留每一阶段的输入身份、版本、坐标、输出数量、状态和诊断。

| 阶段 | 输入 | 输出 | 必须证明的正确性 |
|---|---|---|---|
| Text Access | Document/Snapshot/Piece Tree | 有界文本读取 | 文档身份、版本、长度、编码、范围正确 |
| Parse | TextSource + Grammar | TSTree | 输入完整、字节/点坐标一致、无错误截断 |
| Query Compile | Grammar + Query Source | Compiled Query | Pattern/Capture/Predicate 定义正确 |
| Query Execute | Tree + Cursor + Range | Match/Capture | 匹配对象、Pattern、Capture、Node 对应正确 |
| Regex Execute | TextSource + Regex + Window | Match/Capture Group | 窗口语义等价、绝对偏移正确 |
| Normalize | Native results | TshMatchSet | 坐标转换、版本绑定、来源和完整性保留 |
| Resolve | Match + Scope + Style/Theme | ResolvedStyle | 规则命中、颜色/属性和优先级正确 |
| Compose | 多来源样式 | RenderInstruction/Plan | 重叠/包含冲突确定且可解释 |
| Apply/Draw | RenderPlan + Host Renderer | Receipt | 视口内指令实际应用/绘制，视口外正确 deferred |
| Verify | Receipt + Host/Pixel | 可观察结果 | 真实控件颜色/装饰与预期一致 |

每个阶段都必须携带或可追溯：
- documentId、documentVersion；
- grammar identity/version/fingerprint；
- query/regex revision；
- tree revision；
- pipeline revision；
- 绝对范围和坐标单位；
- source provenance；
- completeness 与 diagnostics；
- 适用的 viewport/layout/style/theme/renderer generation。

任何阶段不得仅凭“当前对象仍存在”推断它属于当前文档版本。

## 4. Query Match / Capture / Node 对象错配问题

这是本项目必须重点防止的错误类别。

### 4.1 不得混淆对象层级

- **TSQueryMatch**：一次 Pattern 匹配的结果，含 match id、pattern index、capture count 和 captures。
- **TSQueryCapture**：一个捕获项，含 TSNode 与 capture index。
- **TSNode**：语法树中的节点对象，具有 start/end byte、point、symbol/type、tree identity 等。
- **Pattern**：Query 中的模式编号及其谓词/捕获定义。
- **Capture Name**：由 Query 的 capture index 映射得到的语义名称。
- **TSH Rule**：配置层规则身份，不等于 Tree-sitter Pattern index。
- **Style ID / Scope**：样式解析结果，不等于 Capture index 或 Node type id。

严禁将数组下标、Pattern index、Capture index、Node symbol、Style ID 当作同一类 ID 使用。

### 4.2 捕获对象必须保留的身份信息

每个 Capture/归一化 Match 至少保留：
- documentId 与 documentVersion；
- queryId/queryRevision；
- grammar fingerprint；
- treeRevision；
- patternIndex；
- captureIndex 与 captureName；
- node identity（在对应 Tree 生命周期内有效）；
- node type / grammar type（若可取得）；
- 原始 startByte/endByte；
- 原始坐标系统；
- canonical document range；
- 来源管线、ruleId 和 provenance。

同一个 Pattern 可包含多个 Capture；同一个节点也可能被多个 Pattern 或多个 Capture 命中。不得假定一个节点只对应一个 Capture，也不得把一个 Match 的所有 captures 错误压成单一范围。

### 4.3 重复、嵌套和顺序

- 保留重复 Capture 的真实语义，不得仅按范围去重。
- 嵌套节点必须允许同时存在外层与内层 Capture。
- 同范围的多个 Capture 必须保留不同的 capture identity。
- Native Cursor 返回顺序不应成为公共 API 契约；归一化阶段必须使用稳定排序。
- 建议排序至少考虑 start、end/嵌套策略、patternIndex、captureName、稳定身份；具体 tie-breaker 必须固定。
- 去重 key 必须包含来源、rule/query identity、capture identity、range 和 revision，不能只比较 start/end。
- 对 Tree-sitter 的 capture 范围，不得错误强制其等于完整 Match 的范围；对 Regex，lookaround 等语义也可能产生不嵌套于 full-match 的 capture group。

### 4.4 语法对象与文本对象一致性

- Capture 的 TSNode 必须来自执行该 Query 的同一 Tree。
- Query 必须与对应 Grammar 兼容。
- 不得在 Tree 被编辑/释放后继续读取旧 Node。
- 编辑前保存的 Node/Range 若要跨编辑继续使用，必须遵守 Tree-sitter edit 规则或重新查询。
- Query/Tree/Language 的 Native 生命周期必须由 lease/refcount 或明确的独占访问约束保护，禁止 use-after-free 和并发 close。

## 5. 匹配范围与坐标转换准确性

### 5.1 坐标单位必须显式

至少区分：
- Tree-sitter UTF-8 byte offset；
- UTF-16LE/BE byte offset；
- UTF-16 code-unit offset；
- Unicode code point offset；
- 行号与列号；
- Regex 窗口局部偏移；
- 文档绝对偏移；
- Piece Tree chunk/leaf 局部偏移；
- Editor viewport / line-column 坐标。

所有范围统一采用半开区间 `[start, end)`。每个 range 必须明确 coordinateSystem，禁止把不同单位放入同一字段。

### 5.2 必须覆盖的边界

- BMP 字符；
- 补充平面字符（代理对）；
- 组合字符；
- CRLF 与 LF；
- 空行、文档末尾；
- 零长度 Capture；
- 多字节 UTF-8；
- UTF-16 奇数 byte offset；
- Piece Tree 分区边界；
- Regex 窗口边界；
- 超过 32-bit Native ABI 可表示范围的文档偏移。

所有 64-bit → 32-bit 转换必须先检查边界；禁止静默窄化、溢出或 wraparound。Tree-sitter ABI 使用 32-bit offset 时，超范围输入必须明确拒绝或按受支持的分区模型处理，不能伪造一个局部 offset 冒充文档绝对 offset。

### 5.3 转换必须可逆验证

对可转换的合法边界，测试应验证：
- byte → UTF-16 → byte 不发生偏移；
- chunk-local → document-absolute 不重复加偏移或漏加偏移；
- 窗口局部 → 文档绝对转换只执行一次；
- start/end 均落在合法编码边界；
- 转换前后文本切片与预期捕获文本一致。

## 6. 结果截断、分页与完整性问题

### 6.1 截断可能发生的位置

必须逐一检查而不是只看最终列表：
- Tree-sitter Query cursor match limit；
- pending match limit；
- capture list 内存/字节预算；
- Query 执行预算、取消和超时；
- Native 临时 vector 的容量与分配失败；
- JNI 数组长度、分批传输和整数上限；
- Kotlin List/Array 的转换和聚合；
- MatchSet 的 max count / estimated bytes；
- Regex 窗口 overlap 和窗口边界；
- CaptureIntervalIndex 的构建和查询；
- viewport 限制、可见区域预取和 deferred 指令；
- Renderer 的 apply/draw 能力与部分失败。

### 6.2 不允许“静默截断后返回成功”

如果 Native cursor 已超过 match limit、捕获列表达到预算、分页中断、某个 batch 失败或取消发生：
- 不得返回伪造的完整列表；
- 不得把 `complete` 置为 true；
- 必须报告 TRUNCATED/PARTIAL/FAILED/CANCELLED 等明确状态；
- 必须报告已产生数量、预期/未知总量、限制值、停止原因和可恢复性；
- 必须保留可用的部分结果，但必须标记为部分结果；
- 上层 MatchSet、RenderPlan 和 UI 必须继续传播不完整状态；
- 需要恢复时，使用明确 continuation/cursor/page token 或重新执行策略，不能从不稳定的数组下标猜测续读位置。

### 6.3 All-or-nothing 与可分页结果

对一次 Query block，如果执行器无法证明结果完整，当前策略应保留已有的 all-or-nothing 行为：失败整个 block，不发布不完整 captures。若后续改为分页/流式发布，则必须引入显式的分页协议、稳定顺序、去重身份、版本绑定和最终完成标记；不得混合两种语义。

分页不能只限制 UI 返回条数，而必须从 Native 到 Kotlin 都有明确的页边界、内存预算和结果生命周期。避免同时保留完整 Native Span vector 与完整 Java/Kotlin 副本。

### 6.4 完整性状态建议

统一结果应能区分：
- COMPLETE：已完整遍历并验证；
- PARTIAL：有可用结果，但尚未完成；
- TRUNCATED：因明确上限而截断；
- CANCELLED：调用方/用户取消；
- TIMEOUT：执行预算耗尽；
- STALE：输入版本或配置版本已变化；
- FAILED：无法产生可信结果；
- EMPTY：完整执行成功但没有匹配。

EMPTY 不等于 FAILED；PARTIAL/TRUNCATED 不等于 EMPTY；任何非 COMPLETE 结果都不得在合并阶段被提升为 COMPLETE。

## 7. 大文本、分块和滑动窗口的正确语义

### 7.1 不得将分块等同于独立文档

分块/窗口是输入和执行的资源策略，不改变文档语义。必须保留：
- 全局绝对偏移；
- 原始文档版本；
- 跨块的语法上下文；
- Query 所需的祖先/兄弟/谓词上下文；
- Regex 跨窗口匹配状态；
- UTF-8/UTF-16 编码边界；
- 输出 Capture/Span 的绝对坐标。

不能将每个 chunk 当成一个独立完整文档解析，再简单拼接局部结果，除非语言/规则明确证明该语义等价。

### 7.2 Tree-sitter 路径

- 优先通过 TSInput read callback / TextSource 有界读取接入 Piece Tree，避免全文 String 与第二份连续 Native buffer。
- Tree-sitter Parse 仍须保持完整文档语义和正确的 Tree 生命周期；included ranges 只是解析范围机制，不自动等于独立分区 AST 的正确拼接。
- Query range 限定不能忽略查询依赖的祖先、后代、兄弟和 predicate 上下文。
- 对大文本采用分区 AST、窗口或局部查询时，必须定义边界依赖与结果等价规则。
- Match limit 和 capture-list byte budget 应在 Tree-sitter core 层可控；不能仅在 JNI 包装层修改返回数组。

### 7.3 Regex / Oniguruma 路径

- Oniguruma 对连续 buffer 的要求必须通过明确的 buffer lease/window provider 满足，不能把不连续 Piece Tree 内存伪装成连续指针。
- 每种 Regex 模式需声明有限宽度、最大回溯/窗口能力或无界依赖。
- 跨窗口匹配必须定义 overlap、锚点、lookbehind/lookahead、捕获组和状态恢复语义。
- 无法证明窗口等价的模式必须整段有界回退、拒绝或显式标记 unsupported，不能静默漏匹配。
- 不得为每个 Regex Cursor 无条件复制完整 UTF-16 文档。

### 7.4 内存与性能

- 采用有界 scratch buffer、分批传输、结果页/lease、增量索引和按视口查询。
- 不按全文长度无界 reserve Span/capture 数组。
- 不同时持有不必要的全文副本、全量 Native 输出和全量 JVM 输出。
- 内存预算要考虑解析树、查询状态、capture、span、缓存、临时编码缓冲和 Renderer 对象，而不只是单一 vector。
- 触发内存压力时应取消/降级/分页/缩小预取范围并提供诊断，不能 OOM 后才处理。

## 8. Match 合并、优先级和缓存失效

### 8.1 匹配不等于样式冲突

多个合法 Match 可以：
- 完全相同范围；
- 内外嵌套；
- 部分交叉；
- 同一节点多 Capture；
- ATS 与 Regex 同时命中。

不得默认“后一个覆盖前一个”，也不得依赖 HashMap 遍历顺序。冲突策略必须明确配置优先级、范围包含/相交规则、属性级合并、Capture 归属和稳定 tie-breaker。

### 8.2 provenance 必须保留

从 Native Capture 到最终 RenderInstruction，必须能追溯：
- 原始 Query/Pattern/Capture 或 Regex rule/group；
- ruleId、scope、style；
- 命中范围与坐标转换；
- 采用/覆盖/拒绝该属性的理由；
- 最终颜色/字体/装饰来源。

不能只输出不可解释的匿名 style id 或最终颜色。

### 8.3 缓存失效不能只看 Capture 是否与编辑区重叠

Tree-sitter Query 结果可能依赖未被 Capture 覆盖的祖先、兄弟、predicate 文本和周围结构。因此 Capture/viewport 与 dirty range 不相交，并不能证明缓存仍然有效。

在没有为每个缓存项记录足够保守的 query dependency envelope 前，编辑后必须采取安全失效策略；不能以“捕获范围没有变化”为由保留潜在 stale Match。缓存 key 至少绑定 documentVersion、grammar/query/regex revision、tree/partition revision 和必要的窗口状态。

### 8.4 旧版本结果禁止回流

异步执行期间如果文档、Query、Grammar、配置、Theme、Viewport 或 Renderer generation 已变化：
- 旧结果不得覆盖新结果；
- 旧 RenderPlan 不得执行；
- 旧回执必须拒绝；
- 必须报告 STALE，而不是把旧结果当作当前结果。

## 9. .tsh 颜色与样式未显示的完整排查链

“配置文件里有颜色但编辑器没有显示”必须沿以下链路逐项确认：

1. .tsh 文件是否成功加载、解析和编译；
2. 当前文件是否选中了正确的 language/scope；
3. Query/Regex 规则是否实际执行；
4. Capture/Match 是否正确且完整；
5. Capture Name / Rule ID 是否映射到正确的 semantic style；
6. style ID 是否来自当前配置/grammar revision，是否发生旧缓存或 palette cache 未初始化；
7. Theme/scheme 是否选择正确的 light/dark 与继承结果；
8. StyleResolver 是否解析出目标 foreground/background/font/decoration；
9. RenderInstruction 是否包含正确范围、属性和 revision；
10. Renderer 是否声明并支持对应 capability；
11. viewport 是否覆盖该范围；
12. Renderer 是否实际消费指令并提交有效 Receipt；
13. 目标 View/Canvas/Span 是否在正确 UI/render thread 更新；
14. 滚动、重绘、主题切换和异步回调后是否仍保持正确；
15. 最终屏幕像素或实际控件 Span 是否与预期一致。

不得通过默认黑/白色填补缺失颜色。缺失颜色应保持 absent，由宿主按其既定策略处理。动态数字 ID 是编译实现细节，不得硬编码“某个 ID 就代表字符串/关键字”。

## 10. Renderer 与真实绘制确认

### 10.1 状态语义必须严格区分

- QUEUED：已排队，不代表宿主对象更新或绘制；
- APPLIED：显示对象已更新，不代表 Canvas/View 已实际 Draw；
- PARTIALLY_APPLIED：部分显示对象已更新；
- DRAWN：后端确认全部指令实际绘制；
- PARTIAL：后端确认部分指令实际绘制；
- REJECTED：后端拒绝；
- FAILED：后端失败且没有有效提交。

Span mutation + invalidate 只能证明宿主对象发生变化，不能证明像素已经绘制。不得把 invalidate 返回成功当作 DRAWN。

### 10.2 视口与延迟处理

- Span Renderer 必须由宿主提供明确、有界的 UTF-16 viewport。
- 没有 viewport 时，拒绝对大文档执行无界全量 Span 分配。
- viewport 外指令应记为 deferred，不应伪装成已绘制，也不应无诊断地丢弃。
- 滚动进入新 viewport 时，按当前 document/style/layout/renderer revision 获取或重用有效结果。
- Canvas/View Renderer 必须在宿主真实绘制生命周期内消费 RenderPlan。

### 10.3 宿主对象所有权

- TSH 只能删除自己创建/拥有的 Span，不得清空宿主所有 CharacterStyle。
- detach/close 必须清理自身资源，并保留第三方 Span。
- 失败替换应支持 generation staging/rollback，不能因新一代应用失败而丢失旧的有效高亮。
- 后台 UI 对象更新和清理必须遵循宿主 render-thread dispatcher。

### 10.4 Receipt 必须可审计

回执至少包含 planId、document/style/layout/renderer generation、visible range、applied/deferred/dropped instruction count 和 diagnostics。所有数量必须可核算；部分执行必须明确报告。异步 Provider 在实际 Draw 前检查 isCurrent，完成后 Ack。

## 11. 诊断与日志规范

启动日志和每次高亮任务日志应能将一次任务串成同一个 trace/task id。

建议记录：
- documentId/version、文档长度、编码、请求范围、viewport；
- grammar id/version/ABI/fingerprint；
- query/regex revision、pattern count、capture definition count；
- parse input bytes、parse status、tree revision；
- cursor range、match limit、pending limit、执行时间；
- match count、capture count、重复/过滤/拒绝数量；
- 截断原因、预算使用、最大队列/批次、已返回页数；
- 坐标转换输入/输出单位、边界检查结果；
- MatchSet completeness 和 diagnostics；
- StyleResolver 命中/未命中、最终 style 来源；
- RenderPlan ID、指令数、viewport；
- Renderer capability、applied/deferred/dropped 数量；
- Receipt 状态、真实 Draw 确认、耗时；
- Native allocation、OOM、cancel、timeout、stale、close race；
- 缓存命中/失效原因、分区和窗口版本。

禁止只打印“highlight success”或“render success”。成功日志必须说明成功到哪一阶段；例如“Native Query 完整”“MatchSet COMPLETE”“Span APPLIED”“Canvas DRAWN”是不同状态。

建议统一诊断类别：
`INPUT_RANGE_INVALID`、`ENCODING_BOUNDARY_INVALID`、`OFFSET_OVERFLOW`、`GRAMMAR_MISMATCH`、`QUERY_INVALID`、`PREDICATE_UNDECIDED`、`MATCH_LIMIT_EXCEEDED`、`CAPTURE_BUDGET_EXCEEDED`、`RESULT_TRUNCATED`、`SOURCE_STALE`、`WINDOW_BOUNDARY_UNPROVEN`、`MEMORY_BUDGET_EXCEEDED`、`STYLE_UNRESOLVED`、`UNSUPPORTED_RENDER_CAPABILITY`、`RENDER_PARTIAL`、`DRAW_NOT_CONFIRMED`、`NATIVE_LIFECYCLE_ERROR`。

## 12. 测试与验收矩阵

测试必须覆盖正确性、完整性、版本安全、内存和真实绘制，不允许只增加大量孤立测试而不修复核心实现。

### 12.1 Query / Capture 正确性

- 一个 Pattern 多 Capture；
- 多 Pattern 命中同一节点；
- 同范围不同 Capture；
- 外层/内层嵌套 Capture；
- 重复 Capture 与稳定排序；
- 匿名节点、字段约束、量化、alternation、predicate；
- 不同 Query pattern 对相同文本的相似但不同匹配；
- Query、Tree、Grammar 不匹配时明确失败；
- 真实 capture 文本切片与预期相同。

### 12.2 截断与完整性

- match limit 恰好达到、超过 1；
- pending-match limit 超限；
- capture byte budget 超限；
- Native 分配失败；
- JNI 分批中断；
- Kotlin 聚合失败；
- 取消/超时发生在不同批次；
- 结果分页重复、漏页、乱序；
- PARTIAL/TRUNCATED 在 MatchSet、RenderPlan、Receipt 全链路传播；
- 无匹配的完整执行正确返回 EMPTY，而不是 FAILED。

### 12.3 Unicode / 坐标

- UTF-8 多字节字符、UTF-16 代理对、组合字符；
- CRLF/LF、空行、末尾换行；
- byte/code-unit 转换与 round-trip；
- chunk/window 边界落在多字节字符附近；
- 32-bit offset 边界及超范围拒绝；
- Range 半开语义和零长度范围。

### 12.4 大文本与压力

- 至少 1 MiB、几十 MiB、100 MiB、200–300 MiB；
- 200–500 MiB 作为扩展压力目标；
- Piece Tree 多分区、长行、超长行、密集 token、稀疏 token；
- 连续编辑、快速滚动、视口跳转；
- 高 capture 密度和大量嵌套节点；
- 内存预算耗尽、低内存设备、后台切换；
- 统计峰值 Native/JVM 内存、临时副本、执行时间和首屏响应时间。

### 12.5 增量与并发

- 插入/删除/替换跨行；
- 修改发生在 Capture 之外但影响祖先/谓词；
- Query/Grammar/Theme 更新；
- 旧版本任务晚到；
- Query release 与 execute 竞争；
- Tree edit 与 query read 竞争；
- close/cancel 与批次回调竞争；
- 32 线程并发压力必须在实际设备/Native 构建中执行并记录结果，源码存在不代表测试通过。

### 12.6 真实渲染

- 同一 fixture 在 Spannable、Canvas、自定义 View 等后端对比；
- .tsh foreground/background、字体、underline、strike、其他装饰；
- light/dark scheme 切换；
- 主题热切换与快速连续编辑；
- 滚动跨 Piece Tree 分区；
- viewport 内外指令正确 applied/deferred；
- 第三方 Span 共存；
- 失败回滚与旧高亮保留；
- Receipt 与最终像素/控件 Span 一致；
- 必须有 Android Instrumentation 或真实设备证据，不能仅凭 JVM 测试声称绘制正确。

## 13. 缺陷排查顺序

发生高亮不准确、错位、截断或不显示时，按以下顺序定位，避免跨层盲目修改：

1. 固定文档快照与版本，记录输入长度、编码、请求范围和 viewport。
2. 检查 TextSource 分块读取是否完整、稳定，边界是否切开编码单元。
3. 检查 Parser 输入是否完整、Tree 是否绑定正确 Grammar 和版本。
4. 独立检查 Query/Regex 编译、Pattern 和 predicate 语义。
5. 对比 Native 原始 Match/Capture 与预期节点/文本切片。
6. 检查 Capture index/name、Pattern index、Node identity 是否错配。
7. 检查坐标转换和分区/窗口偏移是否正确。
8. 检查 match/capture/byte budget、JNI batch 和 Kotlin 聚合是否发生截断。
9. 检查 MatchSet completeness、去重、排序、冲突和 stale 过滤。
10. 检查 Scope/Style/Theme 解析与 palette/style ID 映射。
11. 检查 RenderPlan 指令、版本、viewport 和 capability。
12. 检查 Renderer 应用、deferred/dropped 计数和 Receipt。
13. 最后验证真实 View/Canvas/屏幕像素。

每一步都要保存可复现 fixture 和前后数量对账，不能只观察最终屏幕猜测问题来源。

## 14. 完成定义（Definition of Done）

只有同时满足以下条件，才能宣称“高亮/绘制问题已彻底解决”：

- [ ] Native Query/Regex 对正确输入产生准确匹配，捕获对象身份、范围和文本完全对应。
- [ ] 多 Pattern、多 Capture、重复、嵌套和冲突场景无错配、漏配。
- [ ] 所有坐标单位明确，Unicode、CRLF、窗口和 Piece Tree 边界转换正确。
- [ ] Match limit、内存预算、分页、取消、超时均显式报告，不发生静默截断。
- [ ] PARTIAL/TRUNCATED/STALE 等状态从 Native 到最终 Renderer 不丢失。
- [ ] 大文本不依赖全文 flatten/额外全文复制，Native 内存有界并能在压力下安全降级。
- [ ] 编辑后缓存和异步结果不会把旧版本结果发布到当前文档。
- [ ] .tsh 颜色/字体/装饰正确解析并映射到 RenderInstruction。
- [ ] Renderer 对能力缺失、viewport 外指令、失败和回滚有明确结果。
- [ ] 真实 Android 控件/Canvas 已验证最终颜色和绘制，而不只是 API/Span mutation。
- [ ] 目标大文本规模、设备、API level、峰值内存、响应时间和测试命令均有记录。
- [ ] 构建、Native 测试、Kotlin/JUnit、Instrumentation/设备测试状态均按事实标记。

状态标记必须区分 SPEC_ONLY、SOURCE_PARTIAL、SOURCE_COMPLETE、BUILD_VERIFIED、TEST_VERIFIED、DEVICE_VERIFIED。文档、测试源码或编译成功都不能替代更高等级的验证。

## 15. 与现有规范的关系

本文汇总端到端问题和验收口径；具体 API 以以下现有规范为准，发生冲突时不得在本文另造一套 API：

- [TSH DSL v2 规范](TSH_DSL_V2_SPEC.md)
- [ATS / Tree-sitter Query API](api/ats-tree-sitter-query-api.md)
- [Regex 窗口执行 API](api/regex-window-execution-api.md)
- [统一 Match / Scope API](api/unified-match-scope-api.md)
- [Theme / Style / Render 契约](api/theme-style-render-contract-api.md)
- [Render Backend Conformance API](api/render-backend-conformance-api.md)
- [Tree-sitter Query Filter 与大文档高亮计划](architecture/TREESITTER_QUERY_FILTER_INTEGRATION_PLAN.md)
- [开发计划地图](roadmap.md)

本文特别强调：规范中列出的目标、已有源码、测试源码、已构建、已运行测试和真实设备验证是不同状态。不得把计划写成已完成，也不得因为某个单点修复就宣称端到端问题已经解决。

---

## 16. 最终目标

最终效果必须是：无论文件大小、语法复杂度、Capture 数量、Piece Tree 分区方式、视口位置、滚动速度或编辑频率如何变化，TSH 都能对当前文档版本给出**准确、完整、可追溯、可诊断、可绘制**的结果。

当资源不足或执行被取消时，系统可以明确降级，但不能给出错误的完整性承诺；当 .tsh 声明了颜色和样式且规则确实命中时，必须能够追踪它们从配置、匹配、Style 到最终 Renderer 的全过程，并通过真实控件/像素验证。


---

## 17. 首次打开文件：首屏高亮颜色响应慢、首次绘制迟缓

这是独立且必须验收的性能问题，不能被“最终能显示颜色”掩盖。用户首次打开文件时，可能经历文件已显示但长时间没有语法颜色、先显示默认文本后很久才出现高亮、部分颜色迟到、颜色分批跳变，或首屏等待整个文件解析完成。它们通常意味着首屏高亮关键路径被全文件工作、重复转换、同步 JNI 调用、过度物化或不合理调度阻塞。

### 17.1 首次打开的可观察症状

必须分别记录，不得笼统记为“打开慢”：

1. **文本先显示、高亮迟到**：Editor 已经完成文本布局/显示，但首个有效高亮结果很晚才到。
2. **首屏空白高亮**：首屏范围没有颜色，滚动或等待后才出现。
3. **整文件解析阻塞首屏**：首屏必须等待整份 Tree、全量 Query、全量 Match/Capture 或全量 Span 完成。
4. **颜色逐段跳变**：多个异步批次到达顺序不稳定，旧批次覆盖新批次，或不同阶段的颜色先后闪烁。
5. **首次明显慢、第二次明显快**：可能存在缓存冷启动、Grammar/Query 编译、Native 初始化、页面分配、JIT、文件解码或索引预热成本；必须拆分测量，不能直接归因于 Parser。
6. **文件越大首屏越慢**：说明首屏路径可能与全文长度或全量 Token/Capture 数量不必要地耦合。
7. **高亮颜色先出现后消失**：可能是后续全量结果、旧 revision、默认样式或低优先级批次覆盖先前有效结果。
8. **打开后短暂卡顿/掉帧**：可能是主线程执行解析、坐标转换、Span 全量替换、布局失效或大对象 GC。

### 17.2 首次打开的关键路径拆解

首次打开应拆成独立阶段并记录时间戳：

| 阶段 | 计时起点/终点 | 必须记录 |
|---|---|---|
| T0 文档接入 | 用户打开 → snapshot 可读 | 文档长度、编码、revision、TextSource 建立耗时 |
| T1 Native 初始化 | Native session 创建 | JNI handle、Parser、Memory Governor 初始化耗时 |
| T2 Grammar 准备 | grammar 请求 → 可用 | grammar 加载/注册/初始化耗时、缓存命中 |
| T3 Query 准备 | query 请求 → compiled | Query 编译耗时、Pattern/Capture 数量、缓存命中 |
| T4 首屏输入读取 | 首个 TextSource read → 完成 | 读取窗口数、读取字节、等待时间、复制字节 |
| T5 首次解析可用 | parse 开始 → 首个可查询 Tree/结果 | parse 字节进度、首个可用结果时间、解析状态 |
| T6 首屏 Query | query 开始 → 首屏 Capture page | viewport/range、Match/Capture 数量、页数 |
| T7 Style 解析 | MatchSet → ResolvedStyle | style 命中率、未解析数量、颜色解析耗时 |
| T8 RenderPlan | style → plan | 指令数量、viewport、revision |
| T9 Renderer Apply | plan → applied receipt | Span/对象更新数量、应用耗时 |
| T10 实际首绘 | UI/render traversal → 首次像素可见 | Draw 确认时间、帧耗时、是否发生掉帧 |

必须区分：
- **TTF（Time to First Highlight）**：从打开请求到首屏第一个正确高亮像素可见；
- **TTF-Complete（Time to First Viewport Complete）**：首屏视口内所有应有高亮均已完成；
- **TTC（Time to Complete）**：整个文档的后台解析/索引/查询工作完成时间。

不能用 TTC 代替 TTF，也不能用 RenderPlan 创建时间冒充 TTF。

### 17.3 首屏优先、全局后台的调度原则

首次打开采用“首屏优先、有限预取、后台补全”的调度模型：

1. 先确定当前 viewport 和必要的上下文范围；
2. 优先保证 Grammar/Query 可用，避免重复编译；
3. 让 Parser 按需读取并推进到能够提供首屏结构信息的位置；
4. Query 只优先生成首屏需要的 Match/Capture；
5. StyleResolver 只解析当前首屏实际使用的 Scope/Style；
6. Renderer 尽快提交首屏指令，并等待真实绘制确认；
7. 首屏完成后，再调度相邻 viewport 预取和后台全局工作；
8. 低优先级工作不得长期占用首屏需要的 Native 内存、锁、线程或缓冲区；
9. 首屏任务被滚动/编辑取代时，及时取消或降级旧任务；
10. 后台完整性状态必须与首屏局部可用状态分别表达。

**重要限制：** 首屏优先不等于只解析 viewport 文本片段并把它当作独立语法文档。Query 需要的祖先、前置状态、兄弟或 predicate 上下文必须由完整 Parser 状态/Tree 提供。首屏调度可以限制结果范围，但不能破坏语法上下文。

### 17.4 禁止的首次打开实现方式

- 等待整份大文件完成所有 Query/Capture 后才提交首屏；
- 打开时同时生成全文 String、全文 Native buffer、全量 Token List、全量 Capture List 和全量 Span；
- 在 UI 主线程执行 Parser、Query、Regex、批量坐标转换或大规模 Span 构建；
- 每次打开都重复加载/编译相同 Grammar 和 Query，却没有缓存命中指标；
- 首屏 Query 与后台全量 Query 同时争用同一 Parser/Cursor/大锁；
- 每个批次都清空整个 Editor 的 Span 再全量重建；
- 用默认颜色先覆盖真实样式，再由迟到结果反复替换造成闪烁；
- 首屏局部结果没有 revision 标识，后台旧任务可以覆盖当前结果；
- 只测平均耗时，不记录冷启动、缓存冷/热、P95/P99 和真实首绘。

### 17.5 首次打开性能指标

每个 fixture 至少记录：

- TTF、TTF-Complete、TTC；
- 首屏 Query 的 Match/Capture 数量与完整性；
- 首屏需要的 ReadWindow 次数和字节数；
- 全文总字节数、行数、Token 密度；
- Grammar/Query 冷/热缓存耗时；
- Native/JVM 分配字节、复制字节、峰值工作集；
- UI 主线程最长任务、掉帧数、首屏期间 GC 次数；
- 首屏是否被后台任务阻塞；
- 首绘时的 document/tree/query/style/renderer revision。

按文件大小、语法、Token 密度、设备内存和缓存状态分组报告 P50/P95/P99。性能目标必须由目标设备基线确定并写入项目验收表；在未测量前不得虚构固定毫秒承诺。

---

## 18. 编辑器滚动时不渲染高亮：视口驱动的持续渲染契约

“编辑器滚动时不渲染高亮颜色”不是单纯的滚动监听问题。它意味着滚动后新进入视口的范围没有获得当前版本的有效 Match/Style/RenderInstruction，或者 Renderer 没有在新布局/绘制周期应用它们。常见表现包括：初始屏幕有颜色，向下滚动后新文本全无颜色；滚动到某些 Piece Tree 分区后颜色消失；快速滚动时出现白块/默认色；回滚后颜色又出现；滚动停止很久才补色。

### 18.1 滚动时必须持续成立的契约

- 高亮结果是绑定 document revision 的语义结果；可视区域是动态变化的消费范围。
- 每次 viewport 变化都必须触发一次有界、可合并、可取消的可视范围更新。
- 新 viewport 的所有可见文本必须处于以下明确状态之一：已有有效结果、正在计算、等待 Parser 上下文、等待 Style、等待 Renderer、因预算而 deferred、失败/取消。
- 不允许把“没有 Span”解释为“没有匹配”；也不允许把“缓存没有命中”解释为“无需高亮”。
- 滚动进入新范围时，必须验证其结果 revision 与当前 document/tree/query/theme/layout/renderer generation 一致。
- 视口外结果可以延迟，但不能把视口外 deferred 计入 DRAWN。
- 滚动离开旧范围后，可以回收不再需要的视口对象，但不得误删宿主或其他模块创建的 Span。
- 滚动只改变 viewport/layout，不应无故重建整份 Tree 或重新编译 Query。

### 18.2 Viewport 变化的正确流水线

1. Editor 发布新的可见行/范围，包含绝对 UTF-16 范围和 layout generation；
2. 将范围转换为 Native/Query 所需坐标时，显式记录单位并检查边界；
3. 合并短时间内连续的 viewport 更新，取消已过时的低优先级任务；
4. 查询当前 revision 的 Match/Style 缓存；
5. 对缓存缺失范围，在完整 Tree 上执行有界 Query；若 Tree 尚未解析到可用状态，则调度必要的 Parser 进度；
6. 将 Match/Capture 归一化为绝对文档坐标；
7. 解析当前 Theme/Style，并构建只覆盖新 viewport 的 RenderPlan；
8. Renderer 在正确线程/绘制生命周期应用；
9. 收到 APPLIED 仅表示对象更新；实际 Draw 后再确认 DRAWN；
10. 检查 viewport generation，过期任务不得提交；
11. 更新 viewport coverage ledger，记录哪些区间已完成、正在处理、deferred 或失败。

### 18.3 视口覆盖账本（Viewport Coverage Ledger）

建议维护可诊断的范围状态，而不是只有一个“highlighted”布尔值：

| 状态 | 含义 | 是否可视为完成 |
|---|---|---|
| UNKNOWN | 尚未请求或状态未知 | 否 |
| QUEUED | 已排队 | 否 |
| PARSING | 等待/正在推进 Parser | 否 |
| QUERYING | 正在执行 Query/Regex | 否 |
| RESOLVING | 正在解析 Scope/Style | 否 |
| READY | RenderPlan 已准备 | 否 |
| APPLIED | Span/显示对象已更新 | 仅应用完成 |
| DRAWN | 当前 generation 的实际绘制已确认 | 是 |
| DEFERRED | 因 viewport/预算策略暂缓 | 否 |
| PARTIAL | 范围仅部分完成 | 否 |
| STALE | 对应版本已过期 | 否 |
| FAILED | 失败 | 否 |

Coverage ledger 的区间必须采用统一半开区间，并记录 document/tree/query/theme/layout/renderer generation。相邻区间合并时不能把不同 revision 或不同状态合并成一个完成区间。

### 18.4 滚动期间的预取与调度

- 预取范围按滚动方向、速度、设备内存和历史耗时动态调整；
- 快速滚动时优先当前 viewport，降低远端预取，避免大量无用 Query；
- 滚动停止后再补齐相邻范围；
- 预取必须有上限，不能因持续滚动无限积累待处理任务；
- 预取结果可以缓存，但必须绑定版本和范围；
- 多个 viewport 请求重叠时可合并计算，但必须保留每个请求的 generation/消费者；
- 新 viewport 到来后，旧任务可被取消、降级为缓存填充或继续作为低优先级后台任务，但不得提交旧 viewport 的 RenderPlan；
- 同一 Parser 的状态推进不能因多个滚动事件而并发执行。

### 18.5 常见根因分类

**A. 没有触发更新**
- Editor 的滚动/viewport 回调未接入高亮 Provider；
- 只在文本 change 时刷新，滚动时没有请求新范围；
- Recycler/virtualized line view 复用后没有重新绑定高亮；
- Piece Tree viewport 映射没有更新。

**B. 请求范围错误**
- 使用屏幕坐标、行号或局部 chunk offset 代替文档绝对范围；
- UTF-16 与 UTF-8 转换错误；
- 新 viewport 的 end exclusive 计算错误；
- viewport 跨 Piece Tree leaf 时只请求了第一段；
- 预取区间覆盖当前视口计算错误。

**C. 结果没有生成**
- Query cursor 只执行初始范围；
- Parser 尚未提供所需上下文；
- 缓存 key 不包含范围或 revision；
- match limit/pending limit 使后续范围被截断；
- 大文件调度器把新 viewport 请求排在永不完成的全量任务之后。

**D. 结果生成但未应用**
- MatchSet 到 RenderPlan 的范围裁剪错误；
- StyleResolver 对新批次没有正确解析；
- RenderPlan 使用旧 viewport/layout generation；
- Renderer 只在首次 attach 时应用 Span；
- Span 被后续全量替换或清理逻辑删除；
- 新行 View 复用后没有重新绑定对应 Span。

**E. 已应用但没有实际绘制**
- 没有触发正确的 invalidate/requestLayout；
- invalidate 调用对象不是实际显示的 Editor/View；
- 绘制发生在错误线程；
- Renderer 的绘制缓存未因 viewport/layout generation 更新；
- Canvas clip/visible range 错误；
- 只记录 APPLIED，却没有验证 Draw。

### 18.6 滚动正确性与性能指标

必须测量：

- viewport event → 新 viewport TTF；
- viewport event → TTF-Complete；
- 当前视口已覆盖区间比例；
- viewport 内未着色区间长度；
- 缓存命中率、Query 新增执行范围；
- 每次滚动触发的解析/查询/Span 数量；
- 任务合并率、取消率、过期结果丢弃率；
- 预取命中率与无用预取字节；
- 快速滚动期间 UI 帧时间、掉帧和主线程最长任务；
- 当前 viewport 的 READY/APPLIED/DRAWN 延迟分布；
- 进入不同 Piece Tree leaf/chunk 时的覆盖连续性。

验收必须包括慢速滚动、快速 fling、反向滚动、跳转到远端行、跨多个 Piece Tree 分区、滚动同时编辑、主题切换同时滚动、低内存压力和后台恢复。

---

## 19. 渲染响应不准确与速度慢：端到端时序、调度和一致性

响应不准确不仅是匹配结果错，也包括“结果属于正确对象但来得太迟”“正确结果被旧任务覆盖”“只更新部分可视范围”“显示的颜色与当前主题/规则不一致”。性能慢也不能只归因于 Tree-sitter Parser；必须拆解每个阶段的排队、执行、转换、等待和绘制耗时。

### 19.1 响应准确性的四个维度

1. **数据准确**：Match/Capture 对应正确语法节点、Pattern、Rule 和文本。
2. **空间准确**：Range 与当前文档绝对坐标、viewport 和实际行布局一致。
3. **时间准确**：结果属于当前 document/tree/query/theme/layout/renderer generation，没有迟到旧结果。
4. **显示准确**：最终 Renderer 对应范围实际显示了正确颜色/装饰，而不是只完成了中间对象更新。

任一维度失败，都属于渲染响应不准确。

### 19.2 全链路时序模型

每个任务应记录以下时间点：

- createdAt：viewport/change 请求创建；
- enqueuedAt：进入调度队列；
- startedAt：Native/Query 开始；
- inputReadyAt：需要的 TextSource 数据准备好；
- parseReadyAt：必要 Tree 状态可用；
- queryDoneAt：Match/Capture 结果完成；
- normalizedAt：统一 MatchSet 完成；
- styleResolvedAt：样式解析完成；
- planBuiltAt：RenderPlan 创建；
- appliedAt：Renderer 应用完成；
- drawnAt：实际绘制确认；
- cancelledAt/staleAt：取消或过期时间。

据此计算排队耗时、读取耗时、解析耗时、Query 耗时、归一化耗时、样式耗时、Renderer 应用耗时和真实绘制耗时。只记录总耗时会掩盖瓶颈。

### 19.3 任务优先级与公平性

建议任务优先级：

1. 当前 viewport 首次可见范围；
2. 当前 viewport 的缺失/错误高亮修复；
3. 用户正在编辑位置附近的增量更新；
4. 滚动方向上的有限预取；
5. 低优先级远端索引/全文件统计；
6. 非必要后台缓存预热。

必须有公平性与饥饿保护：连续滚动不能让编辑任务永远得不到执行；连续编辑也不能让当前可见区域永远没有高亮。任务优先级不能只依赖提交顺序。

### 19.4 过期任务和结果覆盖

每个任务至少携带：
- documentId/documentRevision；
- treeRevision；
- grammarFingerprint；
- queryRevision/regexRevision；
- theme/styleRevision；
- viewportGeneration；
- layoutGeneration；
- rendererGeneration；
- taskId/priority/cancellation token。

提交 RenderPlan 前必须验证所有相关 generation。对于仅 viewport 变化而文档语义未变的情况，可以复用范围匹配结果，但必须重新裁剪/布局并验证 renderer generation；不能把旧 viewport 的完整计划直接提交。

### 19.5 结果更新必须避免闪烁与错误回退

- 不要在新结果尚未准备时无条件清空仍然有效的旧高亮；
- 旧高亮若因文档内容已改变而不再可信，应明确标记 invalid 并采取安全清除/占位策略；
- 新结果采用 staging，再按 generation 原子替换可见范围；
- 对局部更新只替换目标范围，不清空无关区域；
- 失败时保留仍有效的旧范围，并标记失败区间；
- 多批次到达必须按 page sequence/revision 处理，不能按线程完成顺序覆盖；
- 对同一范围的样式冲突使用确定性优先级，不依赖异步到达顺序。

### 19.6 性能瓶颈检查清单

**输入与解析**
- 是否每次滚动都重新 parse 全文；
- 是否每次打开都复制全文；
- TextSource 是否产生大量小块 JNI 往返；
- read callback 是否重复查找 Piece Tree；
- Parser 是否因错误的 revision/edit range 丢失增量复用；
- 是否在同一个 Parser 上重复排队多个全量任务。

**Query 与匹配**
- 是否每次滚动都从文档开头遍历 Query；
- Query cursor 是否设置合理 range；
- 是否对所有 Pattern 重复执行相同工作；
- 是否先生成全量 Capture 再裁剪 viewport；
- 是否存在 Match/Capture 的重复分配和多次排序；
- 是否因 pending match 限制导致反复重试/扫描。

**坐标与样式**
- 是否对每个 Capture 重复进行昂贵的全局 offset 转换；
- 是否逐 Capture 查找 Style 而没有批量/缓存；
- 是否每次滚动重新解析整份 .tsh/Theme；
- 是否发生 Scope/Style ID 映射重复构建；
- 是否对未变化范围重复生成 RenderInstruction。

**JNI 与对象分配**
- 是否逐 Token/Match/Capture 调 JNI；
- 是否把 Native 紧凑结构逐个装箱为 Kotlin 对象；
- 是否同时保留 Native vector 与 JVM List；
- 是否频繁创建短命 byte[]/char[]/String；
- 是否因跨线程复制和序列化造成额外成本。

**Renderer 与 UI**
- 是否每次 viewport 更新全量清除/重建 Span；
- 是否对单个 Capture 反复 invalidate；
- 是否每次高亮更新都触发不必要 layout；
- 是否在主线程做大量排序、合并和 Span 创建；
- 是否重复绘制视口外范围；
- 是否 Renderer capability 检查和 Theme resolve 每次重复执行。

### 19.7 性能报告最低要求

每个性能结论必须附：
- commit SHA、设备型号/内存、Android API、构建类型；
- 文件大小、行数、Token 密度、语法和 .tsh 规则规模；
- 冷启动/热启动、缓存冷/热；
- TTF、TTF-Complete、TTC；
- 滚动 TTF、viewport coverage、P50/P95/P99；
- parse/query/style/apply/draw 分阶段耗时；
- UI frame time、掉帧、主线程阻塞；
- Native/JVM peak memory、分配与复制字节；
- 结果准确性对照与 completeness；
- 是否真实设备验证。

没有这些数据时，只能说“观察到慢”或“某阶段疑似瓶颈”，不能宣称性能已经优化完成。

---

## 20. 针对首次打开与滚动问题的专项验收

### 20.1 首次打开场景

- 冷启动首次打开小/中/大文件；
- Native 已加载但 Grammar/Query 缓存冷；
- Grammar/Query 缓存热；
- 200–300 MiB 文件打开后立即观察首屏；
- 首屏尚未完整时快速滚动到远端；
- 首屏 Query 与后台全局任务并发；
- 打开期间编辑、关闭、切换文件；
- Theme/Grammar/Query 在首屏计算中更新；
- 低内存设备触发缓存淘汰；
- 检查首屏颜色是否正确、稳定且无旧结果闪回。

### 20.2 滚动场景

- 首屏 → 相邻屏 → 远端跳转；
- 慢速滚动、快速 fling、立即反向；
- 跨 Piece Tree 多个 leaf/chunk；
- 超长行横向滚动（若编辑器支持）；
- 滚动同时输入、删除、撤销/重做；
- 滚动同时切换主题/语言；
- 快速重复 viewport 事件；
- 后台任务晚于当前 viewport 到达；
- Renderer detach/attach 和 Activity 生命周期变化；
- 低内存与后台恢复。

### 20.3 通过标准

- 当前 viewport 不允许出现无法解释的长期无高亮空洞；
- 所有可见高亮必须对应当前 revision 和当前 Style/Theme；
- 不得由旧 viewport/旧文档任务覆盖当前屏幕；
- viewport 外 deferred 不能计入已绘制；
- 快速滚动后最终覆盖必须收敛，取消/丢弃旧任务不影响当前范围；
- 首屏与滚动都必须记录 TTF 和实际 DRAWN；
- 任何截断、预算不足和失败必须显式呈现，不能以默认色或空结果伪装成功；
- 性能阈值按目标设备基线与产品目标定义，P95/P99 必须纳入验收，不能只看平均值。

---

## 21. 与大文本 Native Token 架构的关联

本文新增的首次打开与滚动性能要求，必须与 [TSH / Tree-sitter 超大文本 Token 与 Native 分块处理技术理论及架构设计](TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.md) 一起执行：

- Native TextSource/TSInput 负责按需读取，不能在首次打开或滚动时隐式 flatten 全文；
- Parser 状态跨 ReadWindow 连续，不能把 viewport 当独立语法文件；
- Token/Subtree/Query/Regex 缓存绑定 revision 并受统一内存预算约束；
- 首屏和 viewport 结果采用有限批次/结果页，不能全量物化；
- 当前 viewport 的任务优先级高于远端预取；
- 双/三缓冲只准备输入，不得并发推进同一个 Parser；
- 结果分页必须传播 COMPLETE/PARTIAL/TRUNCATED 等状态；
- Regex/Oniguruma 窗口必须有稳定连续 BufferLease；
- 真实首绘与滚动 Draw 必须通过 Renderer receipt 和设备证据确认。


---

## 22. Android Span Renderer：对象复用、范围更新与宿主污染防护

本节补充 Android Spannable / Editable / TextView / 自定义 Editor 的具体约束。Span 是宿主文本模型中的区间对象，不等同于 RenderPlan，也不等同于像素；Span 的存在、范围更新、样式更新、布局失效和最终绘制必须分别计量。

### 22.1 不得把 DEFERRED 等同于删除或隐藏 Span

- DEFERRED 表示该区间的高亮工作因调度/预算原因尚未完成，不表示必须删除已经正确应用的 Span。
- 已经验证仍属于当前 document/style generation 的 Span，可以在滚出 viewport 后保留为可复用结果；是否保留由有界缓存/内存策略决定。
- 视口外尚未生成的指令不得提前创建无必要的 Span，也不得把“Span 不在视口”当作结果已完成。
- 只有在结果过期、样式失效、文档关闭、目标范围被安全替换或资源回收策略明确要求时，才清理 TSH 自己拥有的 Span。
- 不得通过把 Span 改成透明色、空样式或其他伪样式来冒充 DEFERRED；这会混淆语义并可能污染宿主样式。

### 22.2 Span Recycler 是可选优化，不是正确性前提

不能把“必须实现 Span 缓存池”作为所有 Android Renderer 的硬性要求。Span 是否适合复用取决于宿主实现、Span 类型、文本对象生命周期、样式可变性和范围更新成本。

允许采用以下策略，但必须通过目标 Editor 的实际性能测试选择：

1. **稳定 Span 对象复用**：在同一个 Spannable 生命周期内复用由 TSH 独占的 Span 实例；更新范围时使用宿主支持的范围更新机制，并验证 flags、优先级和重叠语义。
2. **局部 remove/add**：仅替换目标脏区间内由 TSH 所有的 Span；不得清空整份 Editable 或第三方 Span。
3. **按行/块批量管理**：把 Span 索引与行/块或区间索引绑定，避免每个 Capture 都触发独立的全局扫描。
4. **Canvas/自定义绘制**：当 Spannable 更新导致的布局/失效成本不可接受时，可以使用已有 Renderer 抽象支持的自定义绘制后端；不得因此引入第三条匹配管线。

特别注意：
- 同一个 Span 对象被重新设置到新范围时，必须确认宿主 API 对旧范围的迁移行为、Span flags、重叠顺序和回调行为符合预期；不能假定所有 Span 类型都能无副作用地“改起止位置”。
- 共享可变 Span 实例不得同时挂载到多个独立文本容器，除非宿主明确保证其语义安全。
- 若 Span 的样式由可变字段提供，必须在 UI 线程或受控同步策略下更新，并确保后续 draw 能观察到一致快照。
- Span 池必须有容量上限、所有权标记、generation、借出/归还状态；禁止因复用而把旧文档的样式或范围带入新文档。
- 不得为了减少 add/remove 次数而保留无界数量的离屏 Span；对象池和结果缓存都必须纳入统一内存预算。

### 22.3 Span 所有权与索引契约

每个 TSH Span 至少具有可追踪的内部身份：
- ownerId / rendererId；
- documentId；
- documentRevision；
- style/themeRevision；
- rendererGeneration；
- semantic instruction identity；
- 当前绝对 UTF-16 range；
- 生命周期状态（ACTIVE、DETACHED、REUSABLE、RETIRED）。

删除或更新前必须验证 ownerId 和 generation。不得仅凭 Span 的 class 类型判断其属于 TSH，因为宿主或其他插件可能创建相同类型的对象。

Renderer 应维护可增量更新的区间索引，支持：
- 按脏区间定位需要替换的 TSH Span；
- 按 viewport 查询当前有效 Span；
- 检测重叠、嵌套和同范围多属性；
- 统计新增、更新、复用、移除、保留和失败数量；
- 在失败时恢复上一代仍然有效的显示状态。

### 22.4 Span 更新与布局失效必须分别观测

不能笼统认为每次 add/remove Span 都必然触发完整 requestLayout，也不能反过来假定 Span 更新没有布局成本。具体行为受 Span 类型、宿主控件、TextView/Editor 实现和更新 API 影响。

必须在目标宿主上实测并记录：
- addSpan / removeSpan / range update 次数；
- 涉及的文本长度、行数和区间数量；
- requestLayout、invalidate、文本布局重建次数；
- 主线程最长耗时、帧时间和掉帧；
- Span 对象新增/复用/回收数量；
- 滚动前后离屏 Span 保留量；
- 第三方 Span 是否被意外删除或改变。

不得仅凭 JVM 单元测试或 Span 数量下降宣称 Renderer 性能已改善。

---

## 23. DocumentTaskIdentity：跨 Native、Kotlin 与 Renderer 的提交事务

第 8.4 和 19.4 节已经要求旧版本结果禁止回流。本节将其细化为可实现的事务身份和提交屏障。单独比较 documentRevision 不足以覆盖 Query、Grammar、Theme、Viewport、Layout 和 Renderer 变化。

### 23.1 任务身份必须是不可变快照

每个异步任务创建时捕获不可变的 DocumentTaskIdentity，至少包含：

| 字段 | 作用 |
|---|---|
| documentId | 区分文档实例，防止关闭后重新打开同一路径的 ABA 问题 |
| documentRevision | 文本内容版本 |
| editSequence | 文本编辑序列/事务序号 |
| textSnapshotId | TextSource/Piece Tree 快照身份 |
| parserGeneration | Parser 生命周期/状态世代 |
| treeRevision | 任务读取的 Tree 版本 |
| grammarFingerprint | 语法及 ABI 身份 |
| queryRevision | Query 编译配置版本 |
| regexRevision | Regex 规则版本 |
| tshConfigRevision | TSH 配置版本 |
| themeRevision | Theme/Palette/Style 版本 |
| viewportGeneration | 视口请求世代 |
| layoutGeneration | 行布局/坐标映射世代 |
| rendererGeneration | Renderer attach/detach/重建世代 |
| taskGeneration / taskId | 调度任务身份及取消/替换世代 |

字段可以由项目统一 API 的既有类型承载，但语义不得缺失。若某阶段不依赖某字段，应明确声明其依赖集合；不能用默认值或省略字段掩盖依赖关系。

### 23.2 不同阶段使用不同的有效性判定

- **Parse/Query 结果**：至少校验 documentId、documentRevision/textSnapshotId、parser/tree generation、grammarFingerprint、queryRevision；Regex 结果还必须校验 regexRevision 和窗口/BufferLease 身份。
- **Style 结果**：除 Match 身份外，还要校验 tshConfigRevision、themeRevision。
- **RenderPlan**：还要校验 viewportGeneration、layoutGeneration 和 rendererGeneration。
- **Apply**：在真正修改宿主对象前再次校验本次提交所需的全部 generation。
- **Draw/Receipt**：确认绘制的对象仍属于当前 renderer/layout/document generation；迟到回执只能记为 STALE，不得回写当前覆盖账本为 DRAWN。

允许复用不受变化维度影响的中间结果。例如仅 Theme 变化时，可以复用仍有效的 MatchSet，但必须重新解析 Style 并生成新 RenderPlan；仅 viewport 变化时，可以复用文档语义结果，但必须重新裁剪并验证布局/Renderer 身份。

### 23.3 原子提交屏障：校验与写入必须不可分割

“在回调中比较 revision，之后再 apply”仍存在检查与写入之间的竞态窗口。正确契约是：

1. 工作线程只生成不可变结果，不直接修改 UI/Span/Canvas 状态；
2. 结果携带完整 DocumentTaskIdentity 和 completeness；
3. 提交任务切换到宿主规定的 UI/render dispatcher；
4. 在同一串行提交域内读取当前 identity；
5. 校验结果所依赖的所有 generation；
6. 在不允许其他提交插入的临界提交区间内执行局部 staging/commit；
7. 提交后生成 APPLIED receipt；实际绘制确认后才生成 DRAWN；
8. 若校验失败，拒绝提交并记录 STALE、过期维度和丢弃数量。

如果 UI dispatcher 本身保证单线程串行，通常应优先使用该串行化保证，而不是再引入一把覆盖整个渲染过程的全局锁。若当前 identity 会被其他线程修改，则用原子不可变快照/原子引用发布；不能只对 revision 单字段做 Atomic 比较。

### 23.4 Generation 规则与 ABA 防护

- generation 必须单调递增或使用不可复用的唯一身份；文档关闭后重新打开，即使路径相同也必须是新的 documentId。
- revision 回退、计数器重置、对象池复用不得使旧任务重新变成有效。
- 取消任务只是节省资源，不是正确性屏障；取消可能晚于任务完成或回调入队。
- 所有回调、分页、Receipt、缓存项都必须携带相应 identity。
- 批次提交必须校验 page sequence 和 task identity；乱序批次不得覆盖更新批次。
- 不得把 currentRevision > plan.revision 作为唯一判定。revision 相等时，Query/Theme/Renderer/Viewport generation 仍可能已经不同。

### 23.5 可观测诊断

每次拒绝提交至少记录：
- taskId 与 planId；
- 结果 identity 与当前 identity；
- 不匹配字段列表；
- 已完成阶段；
- 已产生/已应用/已丢弃指令数量；
- 是否允许复用其中间结果；
- stale 原因与取消原因。

验收必须模拟“旧任务最后完成”：连续编辑至少三次、快速滚动、切换主题、切换文件、Renderer detach/attach，并人为延迟第一代任务，使其最后返回。最终屏幕不得回退，旧 Receipt 不得污染新 Ledger。

---

## 24. Tree-sitter Query Range：输出裁剪与完整语法上下文

本节澄清首屏/视口查询的关键语义，避免把 Range-limited Query 误实现成“只解析 viewport 文本”。

### 24.1 必须区分三个范围

1. **Parse/Input Domain**：Parser 实际解析的文档输入域及其语法状态。
2. **Query Context Domain**：Query 为正确匹配所需的 Tree 上下文，包括祖先、后代、兄弟、前置状态及 Predicate 所需数据。
3. **Result/Delivery Range**：本次任务希望输出、规范化或提交的文档范围。

三者不是同一个概念。首屏调度通常希望缩小 Result/Delivery Range；这并不自动意味着可以缩小 Parse/Input Domain 或 Query Context Domain。

### 24.2 Range 限制必须明确采用何种语义

- 若在完整、版本一致的 Tree 上执行 Query，并通过 Tree-sitter cursor 的 byte range 限制结果，必须验证具体 Tree-sitter 版本对跨边界节点、包含关系、节点起止点和 captures 的实际匹配规则。
- Query 的 range filter 不是“把 Tree 截断”，也不等同于只对 viewport 子树执行 Query。
- 对跨越 viewport 边界的节点/Match，必须定义输出策略：是否返回完整 Capture、仅返回与目标范围相交的 Capture，还是返回完整 Capture 后由 Normalize/Compose 阶段裁剪。不得在不同后端采用不一致策略。
- Predicate（例如 match/regex 相关 predicate）需要的 Capture 文本必须可读且属于同一 revision；不得因输出范围裁剪而把 Predicate 所需文本截断。
- 外部 Scanner、Parser 状态、祖先/兄弟约束等依赖必须由有效 Tree/Parser 状态提供；不得用 viewport 文本片段伪造上下文。

### 24.3 首屏解析可用性不得被描述成“Parser 只需解析到 viewport”

Tree-sitter 的常规 Parser 产生的是与输入和语法状态相关联的 Tree；输入回调分块读取不等于 Parser 可以任意跳到文档中间并获得与完整解析等价的语法上下文。

因此：
- “按需读取”只说明输入存储/读取策略，不证明任意位置都能独立解析；
- 如果要在完整文件解析尚未完成时交付远端 viewport 结果，必须有经验证的可恢复 Parser 状态、可证明的分区语义或其他明确机制；
- 不允许仅因 viewport 范围可见，就从该字节偏移开始创建一个独立 Parser 并把结果当作完整文档结果；
- 若当前实现必须先建立完整 Tree 才能保证语义，则 TTF 优化应从 Query 输出范围、缓存、调度、Style 和 Renderer 开始，不得牺牲语法正确性；
- 分区解析/局部 Tree 只有在语言边界、状态恢复、跨分区节点、外部 Scanner 和 Query 结果等价均有测试证明后，才能作为独立优化能力启用。

### 24.4 Range Query 的专项验收

必须覆盖：
- Match 完全位于 viewport 内；
- Match 从 viewport 外开始、在 viewport 内结束；
- Match 从 viewport 内开始、在 viewport 外结束；
- Match 完全包围 viewport；
- 祖先节点位于 viewport 外、Capture 位于 viewport 内；
- 兄弟节点/前置语法状态影响 Predicate；
- 外部 Scanner 状态跨越 viewport；
- Unicode 多字节边界与 UTF-8 byte range；
- 同一 Query 在全范围执行后过滤，与 Range-limited 执行的结果等价性对照。

等价性比较必须比较 Pattern/Capture identity、Node 类型、文本切片、绝对 range 和 completeness，而不只是比较最终颜色数量。

---

## 25. Viewport Coverage Ledger：区间状态、背压与收敛

第 18 节定义了覆盖状态，本节补充其数据模型和调度行为。Ledger 是可观测的覆盖事实，不是任务队列本身，也不是“当前屏幕已高亮”的布尔标志。

### 25.1 区间记录模型

每个区间记录至少包含：
- documentId/documentRevision；
- 统一坐标单位和半开区间 [start,end)；
- tree/query/regex/style/theme/layout/renderer generation；
- viewportGeneration / taskId；
- coverage state；
- completeness；
- created/enqueued/started/ready/applied/drawn 时间；
- deferred reason、failure reason、retry policy；
- producer/source provenance；
- 结果页或缓存引用（若存在）。

区间拆分、合并必须保持身份语义。只有 document revision、依赖的配置 generation、坐标单位和状态兼容的区间才能合并。不同 generation 的区间不得因数值范围相邻而合并成 COMPLETE/DRAWN。

### 25.2 Ledger 与任务队列分离

- Ledger 记录“某个范围当前是什么状态”；
- Scheduler 记录“哪些工作正在排队/执行、优先级和资源占用”；
- Cache 记录“哪些语义结果可复用及其依赖身份”；
- Renderer Receipt 记录“哪些指令实际应用/绘制”。

四者不得用一个集合或一个状态字段混为一谈。一个范围可以有缓存结果但尚未 APPLIED；也可以已经 APPLIED 但尚未 DRAWN；也可以处于 DEFERRED 但缓存中存在可复用 MatchSet。

### 25.3 背压与任务合并

Scheduler 至少要限制：
- 同一文档同时执行的 Parser 推进任务数量；
- 同一 Parser/Cursor 的并发访问；
- 每文档排队的 viewport 任务数；
- 预取的最大距离、总字节/行数和结果页数；
- Native/JVM 临时内存预算；
- Span/RenderInstruction 的待提交数量；
- 取消任务仍占用的 lease 和 buffer 数量。

当预算达到上限时：
1. 合并相交或相邻且身份兼容的请求；
2. 优先当前 viewport，淘汰或降级远端预取；
3. 取消无消费者且已过期的任务；
4. 对不能取消但结果仍可复用的工作，允许转为低优先级缓存填充，但禁止提交旧 viewport RenderPlan；
5. 记录 DEFERRED 的明确原因、重试触发条件和公平性保障。

### 25.4 覆盖收敛与饥饿保护

- 当前 viewport 的缺失范围必须最终进入 DRAWN、明确的 EMPTY（完整执行且确实无匹配）或可诊断的 DEFERRED/PARTIAL/FAILED/STALE；不得永久停留在 QUEUED/PARSING。
- 连续滚动时，Scheduler 应优先最新可见范围，但必须防止持续滚动导致编辑任务永久饥饿。
- 编辑持续发生时，应允许短暂合并高频事件，但必须有最大等待时间或公平性策略，防止 viewport 永远不更新。
- 滚动停止后，当前 viewport 应优先收敛；相邻预取不得抢占当前范围的关键资源。
- 每次 viewport generation 变化后，旧范围的 DRAWN 事实可以作为历史记录保留，但不能冒充新 viewport generation 的绘制完成。

### 25.5 账本一致性测试

- 同一区间重复请求、相邻请求和交叠请求；
- 请求乱序完成、分页乱序、任务取消后迟到回调；
- 文档 revision 变化后旧区间失效；
- 仅 Theme 变化时 Match 缓存可复用、Style/Render 必须更新；
- 仅 viewport 变化时语义缓存可复用、旧 RenderPlan 不得直接提交；
- Renderer detach 后旧 DRAWN 回执不得更新新 Renderer generation；
- 内存压力下预取降级，当前 viewport 仍有明确结果；
- 持续滚动和持续编辑下的公平性、队列上限及最终收敛。

---

## 26. 本轮评估意见的采纳边界与实施顺序

外部评估提出的方向总体成立，但落地时采用以下修订口径：

| 评估建议 | 采纳结论 | 本规范中的约束 |
|---|---|---|
| 为 Span 实现缓存池/Recycler | 有条件采纳，不作为强制架构前提 | 先测宿主行为；池必须有界、按 owner/generation 管理；稳定 Span 复用、局部更新或 Canvas 后端均可 |
| 滚出视口时将 Span 标记 DEFERRED 并复用 | 修正 | DEFERRED 是工作覆盖状态；已有效 Span 可保留，不能用隐藏/伪样式代替状态，也不能无界保留 |
| 把 DocumentTaskIdentity 下沉到 Kotlin 回调层 | 完全采纳并细化 | 全链路不可变身份，Apply 前校验所有依赖 generation |
| 用 Atomic 世代锁阻止旧结果 | 部分采纳 | 原子快照可发布当前身份；提交必须在串行提交域内完成校验与写入，不能只比较 documentRevision |
| Query 必须在完整 Tree 上保留上下文 | 完全采纳并澄清 | Parse/Input、Query Context、Result/Delivery 三种范围必须分开 |
| 首屏优先、后台补全 | 采纳但不得破坏 Parser 语义 | 若尚无完整 Tree 或可证明的恢复状态，不得从 viewport 任意起点独立解析并宣称等价 |

### 26.1 建议落地优先级

**P0：版本与生命周期正确性**
- 定义并贯通 DocumentTaskIdentity；
- 固定 TextSource snapshot、Parser/Tree/Query/Theme/Renderer generation；
- 在 UI/render 提交域实现不可分割的 stale 校验与局部提交；
- 对迟到回调、取消、close、detach 和 ABA 场景增加测试。

**P1：Renderer 所有权与真实绘制**
- 审计现有 Span 创建、更新、删除和第三方 Span 共存；
- 建立 TSH Span owner/index/generation；
- 量化 add/remove/update、layout、invalidate、frame time；
- 再决定是否引入 Span Recycler；不预设对象池一定更快；
- 让 APPLIED 与 DRAWN 的证据来源可区分。

**P2：视口 Ledger 与背压**
- 建立与 Scheduler/Cache/Receipt 分离的 Coverage Ledger；
- 统一 UTF-16 viewport 到文档绝对坐标及 Native 坐标转换；
- 增加任务合并、取消、预取上限、公平性和收敛测试；
- 当前 viewport 优先，远端预取受预算约束。

**P3：Range Query 与首屏优化**
- 先建立全范围 Query 与 Range-limited Query 的语义等价测试；
- 明确跨边界 Match 的输出/裁剪策略；
- 保证 Query Predicate 和完整 Tree 上下文；
- 在语义正确前提下优化结果范围、缓存和调度；
- 以真实 Android 设备的 TTF、TTF-Complete、P95/P99 和峰值内存验收。

### 26.2 实施状态不得由本规范自动推定

本节新增内容仍属于规范与验收要求。除非对应源码、构建、自动化测试和设备证据均已核验，不得将 Span Recycler、事务屏障、Ledger 背压或 Range Query 等标记为已实现。状态继续遵循第 14 节的 SPEC_ONLY、SOURCE_PARTIAL、SOURCE_COMPLETE、BUILD_VERIFIED、TEST_VERIFIED、DEVICE_VERIFIED 分级。
