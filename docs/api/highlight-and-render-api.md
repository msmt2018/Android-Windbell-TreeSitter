# Highlight、Attribute、Theme 与 Render API 规范

## 端到端数据流

Document Snapshot -> Tree-sitter/ATS Query + Regex Pipeline -> TshMatch -> Scope Resolution -> Attribute Cascade -> TshResolvedStyle -> TshRenderPlan -> RenderProvider -> Editor Draw/Decoration。

解析/匹配只负责产生语义范围；样式解析只负责产生最终属性；Render Provider 才负责把结果转化为真实屏幕绘制。不得把计算结果数组或 Spannable 写入等同于所有编辑器都完成绘制。

## 目标 API

| API | 职责 |
|---|---|
| TshHighlightService | 对文档版本执行高亮 |
| TshMatch | 匹配来源、规则 ID、范围、优先级和捕获组 |
| TshScope | 语义作用域与层级 |
| TshAttributeRegistry | 注册属性定义、类型、默认值和继承策略 |
| TshTheme | Light/Dark/自定义主题及变量 |
| TshStyleResolver | 级联、优先级和最终属性计算 |
| TshResolvedStyle | 与渲染后端无关的最终样式 |
| TshRenderInstruction | 绘制文本、背景、边框、波浪线等指令 |
| TshRenderPlan | 某文档版本的不可变绘制计划 |
| TshRenderProvider | 将 RenderPlan 提交到宿主控件 |
| TshRenderReceipt | 实际提交状态、帧/版本和失败诊断 |

## 样式属性

属性应有类型定义，而非任意字符串到颜色的临时映射。最低覆盖 foreground、background、fontWeight、fontStyle、textDecoration、underlineStyle、strikeThrough、opacity、border、cornerRadius、padding、baselineShift、letterSpacing、fontFamily、fontSize、zIndex 和 blendMode。每个属性声明类型、默认值、继承规则、可动画性、后端支持要求。

未知属性必须产生诊断；可配置为严格失败或忽略并警告。Theme 变量支持引用解析和循环检测。

## 级联与冲突

样式解析必须定义来源优先级：默认主题、语言作用域、规则属性、宿主覆盖、临时装饰。优先级必须稳定、可测试且可解释。相同优先级冲突使用确定性 tie-breaker，诊断中可追溯来源规则。

## RenderPlan

RenderPlan 必须携带 documentId、documentVersion、configVersion、themeVersion、viewportVersion 和指令列表。每条指令带坐标系、范围、属性快照、层级和来源。Renderer 收到过期版本必须拒绝或请求重算。

## Render Provider

Provider 声明能力，例如 render.text.foreground、render.background、render.underline、render.inline-widget、render.viewport-only。attach 时进行能力协商；不支持的属性要报告降级，而不是静默丢失。

- Span 后端：仅管理 TSH 自己的 Span ID。
- Canvas 后端：提供绘制阶段、裁剪区域和坐标映射。
- Compose 后端：以不可变状态/Decoration 输出，不在引擎模块直接依赖 Compose。
- Overlay 后端：提供定位、命中测试和生命周期回收。

## 增量与性能

只重算受文本编辑影响的语法范围和受影响样式；viewport-only Renderer 只提交可见范围及必要上下文。缓存键至少包含文档版本、配置版本、主题版本和 Provider 能力版本。所有缓存必须有容量上限和失效策略。

## 验收

- 真实控件上确认 foreground/background/fontStyle 等样式可见。
- TSH 更新不删除宿主自己的 Span/Decoration。
- 多主题、多编辑器、多 Session 不串状态。
- 快速连续输入时旧 RenderPlan 不覆盖新版本。
- 不支持的样式属性有诊断与明确降级结果。
