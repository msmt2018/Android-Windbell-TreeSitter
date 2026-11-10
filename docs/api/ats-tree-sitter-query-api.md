# ATS 与 Tree-sitter 统一查询 API 契约

> 状态：API 数据模型、CompiledQuery Registry、基于既有 TSQuery/TSQueryCursor 的 Tree-sitter Compiler/Executor Adapter 已有 Kotlin 源码；ATS Lexer/Parser/Compiler、Native lease/refcount、增量查询和真实 Native 验证仍未完成。仅 Tree-sitter + ATS 查询管线；Regex 保持独立，不引入第三管线。

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

仓库已有 `TSQuery`、`TSQueryCursor` Java 封装；`TSQuery.create(TSLanguage, String)` 创建原生查询，`TSQueryCursor.exec(TSQuery, TSNode)` 执行查询，Cursor 支持 byte range、match limit 和 PredicateHandler。C++ TSH 侧已有 `TshGrammar.dynamic_ts_query`，由 `TshCompiler` 使用 `ts_query_new` 编译动态查询。

当前 `TshTreeSitterQueryCompiler` 已通过 `TSQuery.create(language, source)` 编译原生 S-expression Query，并把 `TSQuery` 注册到 `TshObjectRegistry`；编译失败映射为结构化诊断（包含 Native error type 与 UTF-8 byte offset）。ATS source 会被明确拒绝，不会被当作 Tree-sitter S-expression 误解析。\n\n当前 `TshTreeSitterQueryExecutor` 通过 `TSQueryCursor.create()`、`setByteRange()`、`exec()`、`nextMatch()` 执行，并把 `TSQueryMatch` / `TSQueryCapture` 转为 TSH Capture。它只关闭自己创建的 Cursor；Query 由 Compiler 管理并通过 `release(queryId)` 显式释放，Tree/Language 仍由调用方持有。替换同 queryId 的编译结果时会释放旧 Query。**当前没有 Native refcount/lease，调用方必须保证 Query release、Tree/Language close 不与执行并发。**

## 8. 错误与生命周期

语法节点/字段不存在、谓词参数类型错误、grammar ABI 不兼容、query 编译失败、预算耗尽、取消和 Native 资源关闭必须有稳定错误码。当前 Compiler/Executor 接口返回 `TshResult`；具体错误映射、Native lease/refcount 和取消边界待实现。

## 9. 验收

- 同一查询在 ATS 源码和等价 Tree-sitter Query 上产生一致 capture。
- 验证字段、匿名节点、量化、predicate、Unicode byte range、增量 changed range、取消、预算耗尽和版本失效。
- 验证 Native Query/Tree/Cursor 在异常、并发和关闭情况下无 use-after-free。\n- 验证 query release 与 execute 并发时由 lease/refcount 保证安全；当前尚不满足，属于未完成项。
- 执行 Gradle/JUnit、Native 单测和 Android 集成测试；没有执行的验证不得标记完成。
