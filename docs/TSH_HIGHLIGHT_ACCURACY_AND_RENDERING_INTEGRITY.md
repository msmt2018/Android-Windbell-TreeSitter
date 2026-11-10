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

