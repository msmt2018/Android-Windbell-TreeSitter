> **范围声明：非 TSH 核心 API。** 本文仅作为宿主 Editor 集成边界/历史设计参考，不构成 TSH 引擎公共 API。TSH 不负责通用编辑器功能；请以 docs/api/README.md 的 TSH 核心 API 总目录为准。

# Decoration、Gutter、Inlay 与 CodeLens API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

统一描述文本范围装饰、行号边栏标记、行内提示、CodeLens、诊断标记和自定义绘制内容。该 API 只产生声明式装饰数据与绘制指令；真实像素绘制必须由宿主 Renderer/Decoration Provider 完成。

## 2. 装饰模型

- `TshDecoration`：id、ownerId、documentVersion、range、layer、priority、style、kind。
- `TshGutterMarker`：lineAnchor、icon/resource key、tooltip、actionId。
- `TshInlayHint`：position、label parts、kind、padding、tooltip、optional text edits。
- `TshCodeLens`：range、title、command descriptor、resolve state。
- `TshDecorationStyle`：foreground/background、font attributes、border/underline、margin/padding、custom renderer key。

颜色与样式使用主题 token 或显式值；不得把 Android Color、Spannable 或 Canvas 类型放入平台无关核心模型。

## 3. 所有权与清理

每个装饰必须由 ownerId/providerId 所有。更新时只替换同一 owner 的装饰，不得清除宿主或其他 Provider 的样式。dispose(ownerId) 只移除该 owner 的资源。重复提交相同 id 应具备明确的 upsert 语义。

## 4. 合成规则

样式合成需定义 layer 顺序、priority、冲突属性策略和可组合属性。至少区分语法高亮、语义 token、诊断、搜索结果、用户选择、当前行与扩展装饰。选择背景等宿主交互层不应被普通语法样式覆盖。

## 5. Renderer 契约

Renderer 必须声明支持的 decoration kind、坐标系统、可视区域策略和 invalidation 粒度。Viewport 外装饰可延迟物化；进入 viewport 时必须在对应帧/刷新周期内可见。自定义 renderer 不得在内部持有宿主 View 的生命周期强引用，除非通过显式绑定 Scope 管理。

## 6. 交互命令

GutterMarker、CodeLens 的 action 仅提交 command descriptor，由宿主授权并执行；引擎不得直接执行任意脚本或反射调用。Tooltip 内容默认视为不可信文本。

## 7. 验收测试

多 owner 样式共存、单 owner 更新/卸载、主题切换、viewport 滚动、行高变化、软换行、RTL、inlay 点击、CodeLens 延迟 resolve、无 Renderer 能力时的诊断。
