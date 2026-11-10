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
