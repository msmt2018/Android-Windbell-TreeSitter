# ATS 与 Tree-sitter 统一查询 API 契约

> 状态：API 数据模型、CompiledQuery Registry、基于既有 TSQuery/TSQueryCursor 的 Tree-sitter Compiler/Executor Adapter 已有 Kotlin 源码；ATS Lexer/Parser/Compiler、Tree/Language lease 已有首版源码接入，增量查询和真实 Native 验证仍未完成；Native Query lease 已有源码。仅 Tree-sitter + ATS 查询管线；Regex 保持独立，不引入第三管线。

## 1. 分层

GrammarProvider → TshParserSession → TshSyntaxTreeSnapshot → TshAtsCompiler → TshCompiledQuery → TshQueryExecutor → TshQueryResult。ATS 是 Tree-sitter Query/SCM 的受控简化混合语法，最终编译为现有 Tree-sitter Query 表示或等价 Native query plan，不是独立 parser engine。

## 2. Query 语法能力

目标支持节点模式、匿名节点、字段约束、子节点关系、捕获名、量化、alternation、predicate 和受控 directive。每项语法必须定义 AST、编译映射、运行时语义和诊断。predicate 首批允许 eq/not-eq、match/not-match、any-of、set、is?、is-not?；执行必须有成本预算，不允许任意回调脚本。

## 3. 当前 Kotlin API

源码位置：`com.zerostudio.idea.treesitter.tsh.api`

- `TshGrammarDescriptor`：languageId、grammarVersion、ABI、fingerprint、capabilities。
- `TshByteRange`：UTF-8 byte offset 半开范围。
- `TshAtsSource` / `TshQuerySourceLanguage`：区分 ATS 与原生 Tree-sitter Query 输入。
- `TshQueryCompileOptions`：谓词开关、严格节点验证、模式数量上限。
- `TshCompiledQuery`：queryId/revision、Grammar 绑定、source hash、compiler version、options、可选 native object id。
- `TshSyntaxTreeSnapshot`：document/version/tree revision、Grammar、root byte range、对象 ID。
- `TshCapture` / `TshQueryResult`：捕获元数据、文档/树/查询版本和执行步数。
- `TshQueryExecutionBudget` / `TshQueryCancellation`：执行预算与协作式取消契约。
- `TshAtsCompiler` / `TshQueryExecutor`：编译和执行接口。
- `TshCompiledQueryRegistry`：编译查询注册、替换、查找和 Grammar 兼容检查。

注意：这些是公共模型和扩展点。Tree-sitter 原生查询 Adapter 已实现源码，但 ATS parser/compiler 尚未实现；源码提交不代表已构建或通过 Native 运行测试。

## 4. Grammar 绑定与兼容

CompiledQuery 必须绑定 grammar identity/version、ABI 和 fingerprint；只有 languageId、grammarVersion、abiVersion、fingerprint 全部相同才允许执行。fingerprint 应覆盖生成语言 ABI 和节点/字段 schema，不能只使用语言显示名。

## 5. 增量查询

输入变化通过 Tree-sitter edit 更新 Tree；查询可按 changed ranges 限定执行，但必须处理可能跨越 changed range 的祖先/后代依赖。结果带 documentVersion、treeRevision、grammarFingerprint、queryRevision。旧版本结果必须被丢弃或标记 stale。当前 `TshQueryResult.isCurrentFor(tree)` 只提供版本身份判断，不实现增量查询调度。

## 6. 捕获语义

捕获范围以 UTF-8 byte offset 表示，半开区间 [start,end)。Capture 必须携带 pattern index、capture name、节点类型、节点身份及来源 query。重复 capture 的排序稳定；零长度 capture 策略明确。当前数据模型提供 capture name/type/range/nodeId/patternIndex，稳定排序与来源追踪仍待执行器实现。

## 7. 既有执行入口与适配边界

仓库已有 `TSQuery`、`TSQueryCursor` Java 封装；`TSQuery.create(TSLanguage, String)` 创建原生查询，`TSQueryCursor.exec(TSQuery, TSNode)` 执行查询，Cursor 支持 byte range、match limit 和 PredicateHandler。Executor 会预检查询所需 predicate 名称是否被任一 Handler 声明支持；Native Cursor 本身对 Handler 返回值的语义仍需单独验证。C++ TSH 侧已有 `TshGrammar.dynamic_ts_query`，由 `TshCompiler` 使用 `ts_query_new` 编译动态查询。

当前 `TshTreeSitterQueryCompiler` 已通过 `TSQuery.create(language, source)` 编译原生 S-expression Query，并把 `TSQuery` 注册到 `TshObjectRegistry`；编译失败映射为结构化诊断（包含 Native error type 与 UTF-8 byte offset）。ATS source 会被明确拒绝，不会被当作 Tree-sitter S-expression 误解析。\n\n当前 `TshTreeSitterQueryExecutor` 通过 `TSQueryCursor.create()`、`setByteRange()`、`exec()`、`nextMatch()` 执行，并把 `TSQueryMatch` / `TSQueryCapture` 转为 TSH Capture。它只关闭自己创建的 Cursor；Query 由共享 `TshNativeQueryLeaseStore` 管理。Executor 开始执行时 acquire Lease；`release(queryId)` 或同 ID 替换会先退役旧 Query，阻止新 Lease，已有执行完成后最后一个 Lease 释放才真正 close。对象注册表条目在退役时移除，但活动 Lease 保留 Query 强引用。 Query Registry 的事务锁同时保护 revision 发布/注销与 Executor 的当前 revision 检查及 Lease acquire，避免替换窗口中的跨 Registry 状态撕裂。Tree 与 Grammar Language 已接入共享 `TshNativeResourceLeaseStore`：Tree Executor acquire Tree lease 并在执行完成后释放；Language Resolver 返回带 objectId 的注册 Handle，Compiler 在 `TSQuery.create` 期间持有 Language lease。资源 owner 必须先调用 `retire(objectId)`，不得绕过 Store 直接 close；最后一个 Lease 释放后 Store 才 close。对象仍需在 `TshObjectRegistry` 注册，以便按 ID 查找。

## 8. 错误与生命周期

语法节点/字段不存在、谓词参数类型错误、grammar ABI 不兼容、query 编译失败、预算耗尽、取消和 Native 资源关闭必须有稳定错误码。当前 Compiler/Executor 接口返回 `TshResult`；Query lease 已实现源码，但未构建验证；Tree/Language lease 已有首版源码与单测源码，但尚未构建验证；可中断 Native 取消边界待实现。

## 9. 验收

- 同一查询在 ATS 源码和等价 Tree-sitter Query 上产生一致 capture。
- 验证字段、匿名节点、量化、predicate、Unicode byte range、增量 changed range、取消、预算耗尽和版本失效。
- 验证 Native Query/Tree/Cursor 在异常、并发和关闭情况下无 use-after-free。\n- 验证 query release/替换与 execute 并发时由 Query lease/refcount 保证安全；目前仅有源码与单测源码，尚未运行。
- 验证 Tree/Language close 与 execute 并发安全；当前仍未满足。
- 执行 Gradle/JUnit、Native 单测和 Android 集成测试；没有执行的验证不得标记完成。

### Predicate handler 结果语义

现有 `TSQueryCursor` 的 Predicate Handler 接口现已按结果过滤匹配：
- `OK`：当前 Handler 已处理并接受该 match，停止尝试后续 Handler。
- `ERR`：当前 predicate 对该 match 不成立，丢弃该 match 并继续读取下一个 Native match。
- `UNHANDLED`：当前 Handler 不负责该 predicate，继续尝试后续 Handler。
- 所有 Handler 均返回 `UNHANDLED` 时，predicate match 不通过；TSH Executor 会先校验所有 pattern 中每个 predicate 的名称均有 Handler 声明支持，避免进入此状态。

`TSQueryCursor.setPredicateEvaluationEnabled(false)` 可关闭 Java Handler 求值并原样返回 Native matches；TSH 根据 `TshQueryCompileOptions.enablePredicates` 设置此开关。启用 predicate 时，Executor 会逐个扫描 pattern 的 predicate step 序列（每段以 `Done` 结束），而不是只检查第一个 predicate。


### Predicate expression grouping correction

Predicate steps are not one flat argument list. Tree-sitter terminates each predicate/directive expression with a `Done` step. The cursor now splits the sequence at each `Done` and invokes handlers once per complete expression, preserving the terminator in that handler's argument list. This is required for handlers whose argument arity is defined per expression, including the existing `SetDirectiveHandler` for `#set!`.

Each expression is evaluated independently and all expressions attached to a pattern must be accepted (logical AND). For each expression, handlers run in registration order: `OK` accepts that expression and stops dispatch; `ERR` rejects the match; `UNHANDLED` tries the next handler. A group with no accepting handler rejects the match. Consequently, directives such as `#set!` can apply metadata without accidentally swallowing adjacent predicates or being rejected because several expressions were concatenated.

This correction is source-only at present. It has not been compiled or exercised against a real Native query.


### Native Tree/Language resource lease contract

`TshNativeResourceLeaseStore` 是 Tree 和 Language 共用的 Native resource ownership store。注册时所有权转交给 Store；资源 owner 必须使用 `retire(objectId)` 结束资源，不得直接调用 `close()`。退役后拒绝新 Lease，已有执行持有的 Lease 继续有效，最后一个 Lease 释放时才调用 Native wrapper 的 `close()`。Store 以 objectId 和资源实例双重校验 Handle，防止错误实例借用同一 ID。

Tree Executor 从 `TshObjectRegistry` 解析 TSTree 后，必须成功 acquire 对应 Handle 才能访问 root/capture；finally 释放 Tree Lease。Grammar Language Resolver 不再返回裸 TSLanguage，而返回 `TshNativeResourceLeaseStore.Handle<TSLanguage>`；Compiler acquire Language Lease 并覆盖整个 `TSQuery.create` 调用。对外部 Grammar，这同时保护 TSLanguage 的 `dlclose` 生命周期。

当前仍有明确边界：直接从其他代码调用 `TSTree.close()` / `TSLanguage.close()` 会绕过 Store；所有权契约要求宿主迁移到 retire。此改动只保护经过同一个 Store 注册和退役的资源，不会自动拦截任意旧调用点。源码与测试源码已提交，未运行 Gradle/JUnit/Native 测试。
