# Render Backend Conformance 与 Invalidation API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

定义所有宿主渲染后端必须满足的契约，确保 .tsh 配置的颜色和样式最终进入真实绘制闭环，而不是仅停留在匹配结果或样式对象中。

## 2. 渲染阶段

1. Match：Tree-sitter/ATS 与独立 Regex 产出带来源的匹配范围。
2. Resolve：匹配映射到属性、主题 token 与样式声明。
3. Compose：按优先级合成语法、语义、诊断、搜索、选择等样式层。
4. Plan：生成版本化 RenderPlan 与局部失效区域。
5. Apply：宿主 Renderer 消费计划并更新实际显示对象。
6. Acknowledge：Renderer 回报应用版本、绘制代次、失败与可见状态。

任何阶段失败都必须产生可追踪诊断，不能把“已生成样式”误报为“已显示”。

## 3. Backend Capability

后端需声明支持的样式属性、装饰类型、坐标系统、局部更新粒度、viewport clipping、异步提交和确认机制。缺失属性应明确降级或返回 UnsupportedCapability，不得静默吞掉颜色/字体属性。

## 4. Invalidation

失效范围应尽可能局部化，并携带 documentVersion、styleGeneration、layoutGeneration。全量失效必须说明原因，例如主题整体切换、语言配置替换或后端重建。

## 5. Android Span 安全

Span 后端必须由宿主提供明确的 UTF-16 可见范围；缺失范围时拒绝全量 Span 渲染，避免大文档无界分配。Span 后端只能移除 TSH 自己标记/拥有的 Span；不得清空目标 Spannable 中所有 CharacterStyle。应采用可识别 TSH Span 类型或 owner marker，并测试第三方 Span 保留。Canvas/View 后端需在宿主绘制生命周期内执行，不得依赖离屏生成后无人消费的 RenderPlan。

## 6. 一致性与回执

Renderer ack 应说明 planId、applied document/style/layout generation、visible range，以及 applied/deferred/dropped instruction count 和 diagnostics。viewport 外指令应 deferred，不应被当作失败或静默丢弃。`APPLIED/PARTIALLY_APPLIED` 仅表示宿主显示对象已变更；`DRAWN/PARTIAL` 才能表示后端确认实际执行绘制。Span mutation + invalidate 不等同于像素已绘制。未 ack 的计划不得宣称已绘制。过期计划应拒绝或丢弃。

## 7. Conformance 测试

同一 fixture 在 Span/Canvas/自定义 Editor 后端对比；主题切换、局部编辑、滚动、缩放、部分能力缺失、第三方 Span 共存、渲染失败恢复和 ack 过期。
