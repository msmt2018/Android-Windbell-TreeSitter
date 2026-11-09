# ATS 与 Tree-sitter 统一查询 API 契约

> 状态（按证据分级）：Tree-sitter Query 数据模型、Registry、Native Compiler/Executor Adapter、Query/Tree/Language Lease 为 SOURCE_PARTIAL（源码存在，未构建/测试验证）；ATS Lexer/Parser/AST/Compiler/Lowering 为 SPEC_ONLY（尚未实现）。当前 Tree-sitter Compiler 对 ATS 输入明确返回 TSH-ATS-COMPILER-001。ATS 最终 Lowering 到现有 Tree-sitter Query 管线，不是第三执行管线；Regex 独立。

## 1. 分层

GrammarProvider → TshParserSession → TshSyntaxTreeSnapshot → TshAtsCompiler → TshCompiledQuery → TshQueryExecutor → TshQueryResult。ATS 是 Tree-sitter Query/SCM 的受控简化混合语法，最终编译为现有 Tree-sitter Query 表示或等价 Native query plan，不是独立 parser engine。

## 2. Query 语法能力

目标支持节点模式、匿名节点、字段约束、子节点关系、捕获名、量化、alternation、predicate 和受控 directive。每项语法必须定义 AST、编译映射、运行时语义和诊断。predicate 首批允许 eq/not-eq、match/not-match、any-of、set、is?、is-not?；执行必须有成本预算，不允许任意回调脚本。

## 3. 当前 Kotlin API

源码位置：`com.zerostudio.idea.treesitter.tsh.api`

- `TshGrammarDescriptor`：languageId、grammarVersion、ABI、fingerprint、capabilities。
- `TshByteRange`：Long 型 Native Tree-sitter byte offset 半开范围；实际字节编码由 `TshSyntaxTreeSnapshot.inputEncoding` 指明。当前 TSQueryCursor Java ABI 接收 Int，Executor 在边界检查并显式 narrowing，超过 Int.MAX_VALUE 会拒绝。
- `TshAtsSource` / `TshQuerySourceLanguage`：区分 ATS 与原生 Tree-sitter Query 输入。
- `TshQueryCompileOptions`：谓词开关、严格节点验证开关、模式数量上限。strictNodeValidation 默认 false；显式设为 true 时，当前 Compiler 返回 unsupported 错误，因为 Grammar Node Schema Provider 尚未接入。
- `TshCompiledQuery`：queryId/revision、Grammar 绑定、source hash、compiler version、options、可选 native object id。
- `TshSyntaxTreeSnapshot`：document/version/tree revision、Grammar、root byte range、Native tree object ID 和源文本编码（UTF8/UTF16_LE/UTF16_BE）。
- `TshCapture` / `TshQueryResult`：捕获元数据、文档/树/查询版本、可空执行步数（null 表示 Native API 不提供计数）及捕获 byte range 所属的源文本编码。
- `TshQueryExecutionBudget` / `TshQueryCancellation`：总 Match 数/Native pending-match limit 与协作式取消契约。maxSteps 默认 null；显式设置时当前 Executor 在进入 Native 前返回 unsupported 错误。
- `TshQueryCompiler` / `TshQueryExecutor`：通用 Tree-sitter 查询编译和执行接口。
- `TshAtsCompiler`：ATS 前端专用扩展契约，ATS 编译器必须 Lower 到同一 Tree-sitter Query 执行管线。当前 Native S-expression Adapter 不再错误实现 ATS 接口。
- `TshCompiledQueryRegistry`：编译查询注册、替换、查找和 Grammar 兼容检查。
- `TshEngine.createTreeSitterQueryCompiler/Executor` 与 `openTreeSitterParser`：使用 Engine 统一拥有的对象索引、CompiledQuery catalog、Native Query Lease Store 和 Native Resource Registry；生产装配优先使用 Engine 工厂，不要为同一 Engine 创建彼此独立的 Lease Store。

注意：这些是公共模型和扩展点。Tree-sitter 原生查询 Adapter 已实现源码，但 ATS parser/compiler 尚未实现；源码提交不代表已构建或通过 Native 运行测试。

## 4. Grammar 绑定与兼容

CompiledQuery 必须绑定 grammar identity/version、ABI 和 fingerprint；只有 languageId、grammarVersion、abiVersion、fingerprint 全部相同才允许执行。fingerprint 应覆盖生成语言 ABI 和节点/字段 schema，不能只使用语言显示名。

## 5. 增量查询

输入变化通过 Tree-sitter edit 更新 Tree；查询可按 changed ranges 限定执行，但必须处理可能跨越 changed range 的祖先/后代依赖。结果带 documentVersion、treeRevision、grammarFingerprint、queryRevision。旧版本结果必须被丢弃或标记 stale。当前 `TshQueryResult.isCurrentFor(tree)` 只提供版本身份判断，不实现增量查询调度。当前 Cursor 设置 pending match limit，并在 drain 阶段另行限制总 Match 数；maxSteps 不支持时明确拒绝。

## 6. 捕获语义

捕获范围以 Native Tree-sitter byte offset 表示，半开区间 [start,end)。字节单位由该 SyntaxTree 的输入编码决定；例如当前 `TSParser.parseString(String)` 使用 UTF-16LE，因此节点和 Capture byte offset 是 UTF-16LE 字节偏移，不能直接当作 UTF-8 offset。跨 API 边界转换必须使用明确的编码映射。Capture 必须携带 pattern index、capture name、节点类型、节点身份及来源 query。重复 capture 的排序稳定；零长度 capture 策略明确。当前数据模型提供 capture name/type/range/nodeId/patternIndex，稳定排序与来源追踪仍待执行器实现。

## 7. 既有执行入口与适配边界

仓库已有 `TSQuery`、`TSQueryCursor` Java 封装；`TSQuery.create(TSLanguage, String)` 创建原生查询，`TSQueryCursor.exec(TSQuery, TSNode)` 执行查询，Cursor 支持 byte range、match limit 和 PredicateHandler。Executor 会预检查询所需 predicate 名称是否被任一 Handler 声明支持；Native Cursor 本身对 Handler 返回值的语义仍需单独验证。C++ TSH 侧已有 `TshGrammar.dynamic_ts_query`，由 `TshCompiler` 使用 `ts_query_new` 编译动态查询。

当前 `TshTreeSitterQueryCompiler` 已通过 `TSQuery.create(language, source)` 编译原生 S-expression Query，并把 `TSQuery` 注册到 `TshObjectRegistry`；编译失败映射为结构化诊断（包含 Native error type 与 UTF-8 byte offset）。`TSQuery.create(String)` JNI 现在把 Java UTF-16 源码显式编码为标准 UTF-8，并以 UTF-8 实际字节数调用 `ts_query_new`；Native 返回的 capture name 与 query string value 也按标准 UTF-8 解码，不再将 Modified UTF-8 和 UTF-16 长度混用。ATS source 会被明确拒绝，不会被当作 Tree-sitter S-expression 误解析。\n\n当前 `TshTreeSitterQueryExecutor` 通过 `TSQueryCursor.create()`、`setByteRange()`、`exec()`、`nextMatch()` 执行，并把 `TSQueryMatch` / `TSQueryCapture` 转为 TSH Capture。它只关闭自己创建的 Cursor；Query 由共享 `TshNativeQueryLeaseStore` 管理。Executor 开始执行时 acquire Lease；`release(queryId)` 或同 ID 替换会先退役旧 Query，阻止新 Lease，已有执行完成后最后一个 Lease 释放才真正 close。对象注册表条目在退役时移除，但活动 Lease 保留 Query 强引用。 Query Registry 的事务锁同时保护 revision 发布/注销与 Executor 的当前 revision 检查及 Lease acquire，避免替换窗口中的跨 Registry 状态撕裂。Tree 与 Grammar Language 已接入共享 `TshNativeResourceLeaseStore`：Tree Executor acquire Tree lease 并在执行完成后释放；Language Resolver 返回带 objectId 的注册 Handle，Compiler 在 `TSQuery.create` 期间持有 Language lease。新增 `TshTreeSitterParserOwner`：Parser 注册时保留 Language Lease，解析出的每棵 Tree 再独立保留 Language Lease，确保 Parser 关闭或 Grammar Provider 退役后，仍在使用的 Tree 不会引用已卸载的 Grammar。Native Resource Lease Store 支持依赖 Lease，并在 Native resource close 后逆序释放依赖；活动 Lease 可 `retain()` 派生子 Lease，即使外部资源 Handle 已退役，仍存活的 Parser dependency lease 仍可安全为新 Tree 派生 Language Lease。ParserOwner 使用当前 Java TSParser 的 UTF-16LE 输入，因此生成的 SyntaxTreeSnapshot 明确标记 `UTF16_LE`。Engine 拥有统一 Query catalog/Query Lease Store，关闭顺序为 Query → Parser/Tree → Language；Query Store 永久关闭后拒绝新 Query 发布。Native Query JNI 输入输出的 UTF-8 字节契约已加入源码，并有 `é😀` 回读测试源码，尚未执行 C++/JNI 测试。相同公共转换工具也用于旧 TSH DSL JNI 源码加载与动态 Grammar 路径/符号名，避免不同 JNI 入口各自使用 Modified UTF-8。资源 owner 应通过 `TshNativeResourceRegistry` 统一注册、按类型获取 Lease 并退役；该协调入口先阻止新 Lease，再注销 `TshObjectRegistry` 索引，已有 Lease 释放后 Store 才 close。底层 Store 仍可供内部 Adapter 使用，但宿主不应分别操作两个 Registry。

## 8. 错误与生命周期

语法节点/字段不存在、谓词参数类型错误、grammar ABI 不兼容、query 编译失败、预算耗尽、取消和 Native 资源关闭必须有稳定错误码。当前 Compiler/Executor 接口返回 `TshResult`；Query lease 已实现源码，但未构建验证；Tree/Language/Parser lease、ParserOwner、Engine 统一 Adapter 工厂与 Store shutdown 已有首版源码/测试源码，但尚未构建验证；可中断 Native 取消边界待实现。

## 9. 验收

- 同一查询在 ATS 源码和等价 Tree-sitter Query 上产生一致 capture。
- 验证字段、匿名节点、量化、predicate、Unicode byte range、增量 changed range、取消、预算耗尽和版本失效。
- 验证 Native Query/Tree/Cursor 在异常、并发和关闭情况下无 use-after-free。\n- 验证 query release/替换与 execute 并发时由 Query lease/refcount 保证安全；目前仅有源码与单测源码，尚未运行。
- 验证 Tree/Language/Parser close 与 execute/parse 并发安全；目前只有 Lease 源码与测试源码，尚未运行验证。
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

`TshNativeResourceLeaseStore` 是 Tree 和 Language 共用的 Native resource ownership store。注册时所有权转交给 Store；资源 owner 必须使用 `retire(objectId)` 结束资源，不得直接调用 `close()`。退役后拒绝新 Lease，已有执行持有的 Lease 继续有效，最后一个 Lease 释放时才调用 Native wrapper 的 `close()`。Query Lease Store 还支持依赖 Lease：编译成功后把 Language Lease 绑定到 Query，保证 Tree-sitter Query 可能引用的 Grammar 静态数据在 Query 退役前始终有效；Query 关闭后再释放 Language Lease。Store 以 objectId 和资源实例双重校验 Handle，防止错误实例借用同一 ID；支持单项 retire 和 retireAll，便于 Engine/Grammar Provider 关闭时统一退役。

Tree Executor 从 `TshObjectRegistry` 解析 TSTree 后，必须成功 acquire 对应 Handle 才能访问 root/capture；finally 释放 Tree Lease。Grammar Language Resolver 不再返回裸 TSLanguage，而返回 `TshNativeResourceLeaseStore.Handle<TSLanguage>`；Compiler acquire Language Lease 并将其作为 Query 的依赖 Lease 转交给 Query Lease Store；它覆盖编译和整个 Query 存活期，直到 Query 退役且最后一个执行 Lease 释放后才关闭。对外部 Grammar，这同时保护 TSLanguage 的 `dlclose` 生命周期。

当前仍有明确边界：直接从其他代码调用 `TSTree.close()` / `TSLanguage.close()` 会绕过 Store；所有权契约要求宿主迁移到 retire。此改动只保护经过同一个 Store 注册和退役的资源，不会自动拦截任意旧调用点。源码与测试源码已提交，未运行 Gradle/JUnit/Native 测试。


**关键生命周期约束：** Tree-sitter Query 可能依赖 Grammar Language 的静态数据/动态库代码。仅在 `TSQuery.create` 调用期间持有 Language Lease 不够。因此 Compiler 将 Language Lease 转交给 `TshNativeQueryLeaseStore` 作为 Query 的依赖 Lease；Query 正常执行时，Query Lease 间接保证 Language Lease 存活。只有 Query 已退役、活动 Query Lease 清零、Native Query close 完成后，依赖 Language Lease 才释放并允许 `TSLanguage.close()/dlclose`。


### Coordinated native resource registry

新增 `TshNativeResourceRegistry` 作为宿主侧 Tree/Language 资源入口，并在 `TshObjectRegistry` 增加原子 `registerUnique`，避免多个协调器并发发布同一实例时误注销其他 owner 的对象索引。它封装 `TshObjectRegistry` 与 `TshNativeResourceLeaseStore`，提供统一 register、带 Java 类型令牌或实例 Handle 的 acquire、retire、作用域化 retireAll 和活动 Lease 计数。retireAll 只退役当前协调器登记的资源，不会误退役共享底层 Store 中其他 owner 的资源。retire 先让 Lease Store 拒绝新获取，再从对象索引注销；已取得的 Lease 仍保持 Native 资源存活，直到最后一次 close。

该 Facade 和测试目前仅有源码提交。Tree-sitter Compiler/Executor Adapter 已支持注入协调 Registry，启用后 Language/Tree Lease 通过统一入口获取；旧 Store 直连仍保留为兼容路径。宿主生产装配尚未全面迁移；因此不能将“资源所有权已统一”或“并发关闭已验证”标记为完成。后续需把 Grammar/Tree owner 与 Executor 迁移到协调入口，并执行 Gradle/JUnit/Native 并发测试。
