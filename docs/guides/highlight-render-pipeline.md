# 高亮与真实渲染管线

## 1. 必须闭环

`.tsh` -> Config AST/Validated Model -> ATS/Tree-sitter 与 Regex -> Match Result -> Text Range Mapping -> Highlight Scope -> Attribute/Theme Resolution -> Render Instruction -> Render Provider -> Editor Invalidation -> 真实绘制。

生成 HighlightSpan 或 RenderInstruction 不代表已经完成渲染。只有目标编辑器真实显示且可在配置变化后更新，才算端到端通过。

## 2. 统一中间结果

匹配结果应保留来源（ATS/Regex）、规则 ID、capture/scope、文档版本、坐标系和范围。样式解析结果应包含前景、背景、字体、装饰、优先级和来源链。

RenderInstruction 应为不可变值对象，包含目标对象 ID、文档 ID/版本、范围、绘制属性、层级和失效策略。

## 3. Provider 契约

RenderProvider 声明支持的绘制能力，如 text foreground/background、underline、line decoration、gutter、inlay、overlay。Provider 将统一指令映射到编辑器原生 Canvas、Span、Decoration 或 Renderer。不同 Provider 能力不同，必须通过能力协商而非猜测。

## 4. 坐标与可视区域

文档范围先转换为目标编辑器坐标；可视区域外的任务可延迟。绘制前必须验证文档版本、范围边界、布局版本和目标对象生命周期。局部刷新必须覆盖旧样式与新样式的联合失效区域。

## 5. 主题更新

主题/颜色变化应重新解析样式并刷新受影响范围，不应无必要重做语法树解析。配置规则变化则按依赖关系使语法匹配缓存失效。

## 6. 验收场景

- ATS 关键词与 Regex 字符串/注释同时出现。
- UTF-16 与 UTF-8 范围映射（含 Emoji、补充平面字符、组合字符）。
- 滚动时只刷新可视区域。
- 编辑文本后增量更新且旧结果不覆盖新版本。
- Light/Dark 与自定义主题切换。
- Span、Canvas 和编辑器原生 Renderer Provider。
- 编辑器销毁/文档切换时取消渲染。
- .tsh 改色后无需应用层重写适配即可更新真实显示。
