# 平台边界、线程与调度契约

## 模块边界

核心 API 应尽量保持平台无关。Android View、Canvas、Spannable、Compose 等类型只允许出现在 Android adapter 模块，不得渗透到核心对象模型、配置编译器或 ATS/Regex 引擎接口。

建议模块职责：

- tsh-api：公共纯 Kotlin/Java 契约和值对象。
- tsh-core：Engine、Session、调度、缓存和诊断实现。
- tsh-text：TextSnapshot、Buffer、坐标和增量编辑。
- tsh-language：.tsh Lexer/Parser/Schema/Compiler/Language Service。
- tsh-syntax：Tree-sitter/ATS Grammar、Parser、Query。
- tsh-regex：Regex 编译、窗口输入和安全预算。
- tsh-highlight：Match 合并、Scope、Attribute、Theme。
- tsh-render-api：RenderPlan 与 Provider 协议。
- tsh-android：View/Canvas/Span/Compose 适配。
- tsh-native：JNI/C++ 核心与资源管理。

以上是逻辑模块边界，可按现有 Gradle 工程组织合并模块，但不能因此形成循环依赖。

## 线程契约

| 操作 | 默认线程 |
|---|---|
| Engine/Registry 轻量状态 | 任意线程，内部同步 |
| Document 变更提交 | 宿主指定 dispatcher，按版本串行 |
| Parse/ATS Query | Engine worker |
| Regex 扫描 | Engine worker，带执行预算 |
| Style resolve | Engine worker |
| View/Layout/Canvas/Span 修改 | Host UI thread |
| Native resource dispose | 创建该资源的 Native owner 或安全释放队列 |

## 回调规则

Provider 必须声明线程策略。TSH 不得持有内部锁调用外部代码。回调异常转换为诊断。若 Provider 要求 UI 线程，调度器必须经宿主提供的 UiDispatcher 调用，不得在核心模块直接依赖 Android Looper。

## 阻塞与重入

- 禁止 UI 线程执行全量解析或 Regex。
- Provider 回调期间允许查询只读 Engine 状态，但不得同步等待同一队列上的任务。
- 同一 Session 的文档变更和 parse commit 必须有顺序保证。
- Render callback 不得在锁持有期间调用应用代码。
- 取消操作必须幂等且可重入。

## 验收

使用 StrictMode/线程断言验证 UI 无阻塞；Provider 测试验证回调线程、重入、异常和锁安全；多线程压力测试验证文档版本单调递增。
