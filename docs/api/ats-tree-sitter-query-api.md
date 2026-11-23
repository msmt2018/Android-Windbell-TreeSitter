# ATS 与 Tree-sitter 统一查询 API 契约

> 状态：公共查询模型、Tree-sitter S-expression Compiler/Executor Adapter、Engine 资源 Lease 接线已有源码。ATS Lexer/Parser/Compiler 尚未实现；Tree-sitter 与 ATS 仍共用同一查询管线，Regex 保持独立，不引入第三条执行管线。源码存在不等于所有设备矩阵和 Native 回归已通过。

## 1. 分层

GrammarProvider → TshParserSession → TshSyntaxTreeSnapshot → TshAtsCompiler → TshCompiledQuery → TshQueryExecutor → TshQueryResult。

ATS 是 Tree-sitter Query/SCM 的受控简化混合语法，最终编译为现有 Tree-sitter Query 表示或等价 Native query plan。ATS 不创建独立 Parser/Query Engine；Regex 不进入该管线。

## 2. Query 语法能力与边界

目标支持节点模式、匿名节点、字段约束、子节点关系、捕获名、量化、alternation、predicate 和受控 directive。每项语法必须定义 AST、编译映射、运行时语义、成本预算和诊断。

首批 predicate 允许 eq/not-eq、match/not-match、any-of、set、is?/is-not?。任意脚本回调不属于查询语言。尚未实现的 ATS 语法必须明确返回不支持诊断，不能被误当作 Tree-sitter S-expression 执行。

## 3. 公共 Kotlin API

源码位置：`com.zerostudio.idea.treesitter.tsh.api`

- `TshGrammarDescriptor`：languageId、grammarVersion、ABI、fingerprint、capabilities。
- `TshByteRange`：文档输入字节坐标系中的半开范围。它不是固定 UTF-8 坐标；必须与对应 `TshSyntaxTreeSnapshot.inputEncoding` 一起解释。
- `TshAtsSource` / `TshQuerySourceLanguage`：区分 ATS 与原生 Tree-sitter Query 输入。
- `TshQueryCompileOptions`：谓词开关、严格节点验证、模式数量上限。
- `TshCompiledQuery`：queryId/revision、Grammar 绑定、source hash、compiler version、options、可选 native object id。
- `TshSyntaxTreeSnapshot`：document/version/tree revision、Grammar、root byte range、Native 对象 ID、输入编码。
- `TshCapture` / `TshQueryResult`：capture name/type、文档字节范围、node identity、pattern index，以及文档/树/查询版本和完整性状态。
- `TshQueryExecutionBudget` / `TshQueryCancellation`：match、capture、结果内存预算及取消契约。
- `TshAtsCompiler` / `TshQueryExecutor`：编译和执行接口。
- `TshCompiledQueryRegistry`：编译查询注册、替换、查找和 Grammar 兼容检查。

注意：当前 Android Tree-sitter Parser Bridge 使用 UTF-16LE 输入时，Tree-sitter 节点的 byte offset 是 UTF-16LE 字节偏移；Query 源本身由 Java/Kotlin String 编译时使用其 Native Query 编码。Query 编译诊断中的 Query-source byte offset 与捕获结果中的 document byte offset 是两个不同坐标域，不能混用。

## 4. Grammar 绑定与兼容

CompiledQuery 必须绑定 grammar identity/version、ABI 和 fingerprint；执行前校验 languageId、grammarVersion、abiVersion、fingerprint。仅语言显示名相同不构成兼容。fingerprint 应覆盖生成语言 ABI 和节点/字段 schema。

## 5. 查询执行、预算与取消

当前 `TshTreeSitterQueryCompiler` 使用仓库已有 `TSQuery.create(TSLanguage, source)` 编译原生 S-expression Query，并将 Native Query 注册到对象/查询 Registry。ATS source 会被明确拒绝，不会被当作 S-expression 误解析。

当前 `TshTreeSitterQueryExecutor` 使用 `TSQueryCursor`，支持 byte range、match limit、PredicateHandler、capture 元数据适配，以及总 match 数、capture 数和估算结果内存预算。Tree-sitter 的 cursor match limit 仅限制 pending matches，不等同于总结果上限；总结果上限由执行器在取回 match 时执行。

取消边界：
- 执行前检查取消状态。
- 如果 Native Tree-sitter 提供 0.28 `TSQueryCursorOptions.progress_callback`，使用 progress callback 在 Native 查询过程中协作取消。
- 如果当前 Native 库不支持该 API，执行器回退到普通 `TSQueryCursor.exec(query, root)`，不因此拒绝普通查询；此时只能在 Native 查询返回后、逐个取回 match 时检查取消。诊断必须明确指出无法中断正在运行的 Native 查询。
- 当前 TSQueryCursor 不提供可强制执行的 VM step count；请求 `maxSteps` 必须返回明确的不支持错误，不能伪造 `stepsUsed`。

## 6. 捕获与增量语义

捕获范围以文档输入编码对应的 byte offset 表示，使用半开区间 [start,end)。Capture 携带 pattern index、capture name、节点类型、节点身份。当前 Adapter 从 `TSQueryMatch` / `TSQueryCapture` 转换这些字段。

输入变化通过 Tree-sitter edit 更新 Tree；查询可按 changed ranges 限定执行，但必须处理可能跨越 changed range 的祖先/后代依赖。结果带 documentVersion、treeRevision、grammarFingerprint、queryRevision。旧版本结果必须被丢弃或标记 stale。当前版本身份检查已存在；ATS 完整增量编译和查询调度仍未完成。

## 7. Native 生命周期与所有权

Engine 工厂构造的 Compiler/Executor 使用 Engine 统一 Native Resource Registry、Query Lease Store：
- Compiler 为 Native Query 保留 Grammar Language dependency lease。
- Executor 在一次执行期间获取 Query lease 和 Tree lease。
- 每次执行创建并关闭自己的 Query Cursor。
- Query 被 release 或替换时先退役；活动执行的 lease 释放前不得关闭 Native Query。
- Tree/Language 的 Native close 由资源 Registry 的 Lease 生命周期管理。
- Native close action 不应在查询目录锁内执行。

兼容构造仍允许直接注入旧 Lease Store；生产装配应统一通过 `TshEngine.createTreeSitterQueryCompiler()` 和 `TshEngine.createTreeSitterQueryExecutor()`，避免绕开 Engine 资源协调。并发 Query/Tree Edit、Engine shutdown 与 lease 生命周期仍须持续执行压力回归。

## 8. 错误与诊断

语法节点/字段不存在、predicate 参数类型错误、Grammar ABI 不兼容、Query 编译失败、预算耗尽、取消、Native 资源关闭、Native API 能力降级都必须有稳定错误码和明确阶段。不能把部分结果标记为完整，也不能把 Native Query 创建成功当作执行成功。

## 9. 当前未完成项

- ATS Lexer、Parser、AST、Schema 驱动的 Compiler 和完整 Predicate Runtime。
- Grammar Node Schema Provider 与 strict node validation。
- 生产构造全面迁移至统一 Native Resource Registry；清理兼容旧 Lease Store 路径。
- 可强制执行的 Native Query VM step budget。
- 查询的完整增量依赖分析、changed-range 调度和跨文档任务调度。
- 对所有 Native 操作/Handle/Generation 的 ABI 清单和并发关闭压力测试。
- 多版本 Android 设备矩阵及密集大文档 Native Heap/PSS/延迟验证。

## 10. 验收

- 同一 Tree-sitter Query 在等价输入编码下产生一致 capture，且 byte offset 与 snapshot inputEncoding 一致。
- 验证字段、匿名节点、量化、alternation、predicate、Unicode、半开范围、预算、取消和版本失效。
- 验证 Tree/Query/Language/Cursor 在异常、并发、替换和 Engine shutdown 下无 use-after-free。
- 分别验证支持 0.28 progress callback 与不支持该 API 的 Native 构建；降级必须有诊断且普通查询仍可执行。
- 执行 Gradle/JUnit、Native 单测和 Android 集成测试；没有执行的验证不得标记完成。
