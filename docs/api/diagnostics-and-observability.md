# Diagnostics、日志与可观测性 API 规范

## 目标

开发者遇到任何集成问题时，TSH 必须能回答：哪个阶段失败、哪个对象不支持、缺少什么能力、哪条规则产生结果、哪个版本过期、Renderer 是否真正提交，以及推荐怎样修复。

## 目标 API

| API | 职责 |
|---|---|
| TshDiagnostic | 单条结构化诊断 |
| TshDiagnosticCode | 稳定错误码目录 |
| TshDiagnosticCollector | 收集、去重和排序 |
| TshDiagnosticReporter | 输出给 IDE/应用日志/语言服务 |
| TshTrace | 跨阶段追踪上下文 |
| TshMetrics | 耗时、缓存命中、匹配数量和渲染数量 |
| TshHealthReport | Engine/Session/Provider 健康状态 |
| TshIntegrationReport | attach 能力、降级情况和实际渲染状态 |

## 诊断字段

稳定 code、severity、stage、message、source URI、range、documentVersion、objectId、sessionId、providerId、ruleId、cause、relatedInformation、quickFixes 和 traceId。字段可选，但适用时必须填写。

## 错误码分段

- TSH-ENG：Engine/Session 生命周期。
- TSH-CFG：配置加载和编译。
- TSH-LEX：词法。
- TSH-PAR：语法解析。
- TSH-SCH：Schema。
- TSH-SEM：语义。
- TSH-ATS：Tree-sitter/ATS。
- TSH-REG：Regex。
- TSH-TXT：文本访问。
- TSH-COO：坐标转换。
- TSH-OBJ：对象注册。
- TSH-PRV：Provider。
- TSH-BND：Binding。
- TSH-HLT：高亮。
- TSH-ATR：属性和主题。
- TSH-RND：渲染提交。
- TSH-JNI：Native/JNI。
- TSH-THR：线程/并发。
- TSH-PER：性能/资源。

当前已实现的 TSH-ENGINE-001/002 与 TSH-COORD-001..004 应保留兼容；新码注册时不得重用旧码表达不同含义。

## 可观测性

Trace 从 config load 开始，贯穿 parse、compile、query、match、style resolve、render submit。每个 span 记录阶段、对象、文档版本、耗时和结果数量。日志应结构化、可配置级别，并避免默认记录完整用户源代码。

## 用户可见报告

IntegrationReport 至少展示：
- 已识别对象及对象类型。
- 匹配到的 Provider 及版本。
- 已满足/缺失能力。
- 当前适配等级。
- 配置编译状态和诊断数。
- 最近一次高亮文档版本。
- RenderPlan 生成状态。
- Renderer 提交状态和失败原因。
- 降级能力和建议修复步骤。

必须区分 HIGHLIGHT_COMPUTED、RENDER_PLAN_CREATED、RENDER_SUBMITTED、FRAME_PRESENTED 四个阶段；只有最后阶段有宿主确认时，才能声称用户屏幕已经显示。

## 验收

- 任一 attach/render 失败均能通过 report 定位。
- 同一错误在增量更新时可去重，不无限刷屏。
- 日志可按 traceId 关联完整管线。
- 不记录敏感文本内容，诊断范围只包含必要位置。


## 新增内存、Regex 与 Parser 诊断码

### Memory / Match merge

- `TSH-MEMORY-001`：reservation size 非正数。
- `TSH-MEMORY-002`：ownerId 为空白。
- `TSH-MEMORY-003`：预算已关闭。
- `TSH-MEMORY-004`：reservation 超出剩余预算。
- `TSH-MEMORY-005`：reservation identity 空间耗尽。
- `TSH-MEMORY-006`：Native Tree estimator 返回非正数。
- `TSH-MEMORY-007`：Native Tree estimator 抛出 Exception。
- `TSH-MEMORY-008`：legacy String parser input 的文档身份/长度无效。
- `TSH-MEMORY-009`：streaming JNI input buffer 的文档身份/长度/大小无效。
- `TSH-MEMORY-010`：active reservation 数量达到配置上限。
- `TSH-MATCH-006`：Match 合并达到数量或估算内存上限，结果为 TRUNCATED。
- `TSH-MATCH-007`：诊断列表达到 4,096 条上限，后续诊断被省略。

### Regex Window / Sequence

- `TSH-REGEX-WINDOW-001..011`：Snapshot lease、有限匹配跨度、core/window 范围、Unicode 边界、容量、预算或读取失败。
- `TSH-REGEX-SEQUENCE-001..007`：Sequence 生命周期、core 前进、Snapshot 保留和边界读取失败。

### Parser cancellation / progress / timeout

- `TSH-PARSER-OWNER-029`：没有活动 Native parse 接受取消请求。
- `TSH-PARSER-OWNER-030`：Native cancellation 已接受，parse 结果被丢弃。
- `TSH-PARSER-OWNER-031`：配置 timeout 且测量耗时达到阈值；这是 likely timeout，不是 Native 精确 stop reason。
- `TSH-PARSER-OWNER-033/034`：Native cancellation linkage/请求失败。
- `TSH-PARSER-OWNER-035..038`：timeout 配置/读取参数或并发状态失败。
- `TSH-PARSER-OWNER-039`：进度回调主动停止 parse。
- `TSH-PARSER-OWNER-040`：进度回调抛出 Exception。

Native progress callback 的用户回调在解析线程执行；offset 先以 UTF-16LE bytes 到达 Java binding，再由 TSH 校验偶数字节对齐并转换为 UTF-16 code units。
