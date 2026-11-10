# Cache、调度与性能 API 规范

## 目标

性能能力应作为可观察、可配置的运行时服务，而不是散落在 Parser、Regex、Theme 和 Renderer 内部的无边界缓存。所有缓存都必须有键、容量、失效和生命周期策略。

## 目标 API

| API | 职责 |
|---|---|
| TshTaskScheduler | 解析、查询、样式和后台任务调度 |
| TshTaskPriority | 可见区域、交互请求和后台预热优先级 |
| TshCancellationToken | 跨 Kotlin/JNI/Native 的取消信号 |
| TshCache<K,V> | 统一容量、淘汰和统计 |
| TshCachePolicy | LRU/LFU/TTL/容量及内存预算 |
| TshCacheKey | 文档、配置、Grammar、主题和 Provider 版本 |
| TshPerformanceSnapshot | 阶段耗时、吞吐和命中率 |
| TshBackpressurePolicy | 输入高峰时任务合并、丢弃或降级策略 |

## 调度原则

- UI 线程只做轻量状态更新和宿主绘制回调。
- Parse/Query/Regex/Style resolve 默认在工作线程。
- 当前可视区域的任务优先于远端预热。
- 同一文档版本连续变更可合并，但最终结果必须对应最新版本。
- 任务取消要传播到 Native 查询和 Regex 扫描。
- 任务队列有上限；背压策略必须明确，不得无限堆积。

## 缓存分层

Grammar/Query 编译缓存、Syntax Tree 增量缓存、Regex 编译缓存、Match 缓存、Resolved Style 缓存、RenderPlan 缓存分别管理。每类缓存声明 key schema、版本依赖、内存估算和淘汰策略。

## 失效规则

- 文本编辑：失效受影响语法/匹配范围及必要上下文。
- .tsh 规则变更：失效依赖规则的 Query/Match。
- Theme 变更：通常只失效 Style/Render，不应无谓重建 Syntax Tree。
- Provider 能力变更：失效对应 RenderPlan。
- Grammar 变更：失效 Parser/Tree/Query 相关缓存。
- Session close：释放 Session 私有缓存，保留可共享的不可变全局缓存。

## 性能报告

报告 P50/P95/P99 阶段耗时、输入规模、编辑影响范围、缓存命中、任务取消、队列深度、Native 内存和渲染指令数。性能采样不得默认记录源代码内容。

## 验收

- 高频输入下队列深度有界。
- 主题变化不会触发不必要的全量解析。
- 缓存命中不跨越不兼容版本。
- 大文档场景有内存预算和背压。
- 性能基准覆盖冷启动、首屏、高频编辑和多 Session。
