# Runtime、Session 与生命周期 API 规范

## 目标

TshEngine 是进程或应用级服务容器；TshSession 是一次编辑器/文档工作上下文。一个 Engine 可以同时承载多个 Session。Session 隔离文档状态、配置快照、Provider 绑定、诊断和异步任务，避免多个编辑器标签页互相污染。

## 目标 API

| API | 职责 |
|---|---|
| TshEngine | 创建服务容器、注册全局 Provider、关闭资源 |
| TshEngineOptions | 配置线程池、缓存、诊断策略和兼容策略 |
| TshSession | 绑定一个或多个文档与编辑器对象 |
| TshSessionOptions | 语言、配置、增量解析和渲染策略 |
| TshSessionState | CREATED、ATTACHING、ACTIVE、SUSPENDED、CLOSING、CLOSED、FAILED |
| TshSessionListener | 生命周期和错误事件 |
| TshTaskHandle | 取消任务、查询任务状态 |
| TshResourceScope | 统一管理 listener、binding、provider lease 和 Native handle |

这些是目标契约名称；尚未出现在源码中的类型不得视为已经实现。

## 生命周期规则

1. Engine 创建后只分配轻量服务，不应自动初始化所有 Grammar 或加载全部语言资源。
2. Session 创建时不隐式持有 Android Activity；宿主通过对象绑定显式提供 Editor、Document、Renderer 等实例。
3. attach 成功后进入 ACTIVE；缺少可选 Provider 可以降级运行，缺少必需能力必须返回明确诊断。
4. suspend 保留文档与配置状态，但停止非必要后台任务。
5. close 幂等；取消任务、解除绑定、注销监听、释放 Native 资源，随后进入 CLOSED。
6. Engine close 必须关闭其所有 Session；Session close 不得关闭共享 Engine。

## 并发与线程

- Engine 注册表允许多线程访问。
- UI View/Layout/Canvas 操作只能由宿主 UI 线程 Provider 执行。
- 解析、查询和 Regex 扫描默认在工作线程执行。
- 同一 Session 的文档变更必须按版本顺序处理。
- 异步结果必须携带 sessionId、documentId、documentVersion、configVersion；版本落后时丢弃，不得覆盖新结果。
- 不允许在注册表锁内调用第三方 Provider 回调。

## 错误与恢复

所有生命周期失败使用结构化诊断，至少包含 stage=LIFECYCLE、稳定错误码、对象 ID（若可用）和可恢复建议。close 后操作返回 ENGINE_CLOSED/SESSION_CLOSED，而不是空指针或静默忽略。

## 验收

- 两个 Session 使用不同主题和文档时互不串色。
- 一个 Session 关闭不影响另一个 Session。
- Engine close 后所有任务被取消、监听被释放、对象注册表清空。
- 过期异步高亮结果不会覆盖新版本。
- attach 失败后可查看缺失能力清单并安全重试。
