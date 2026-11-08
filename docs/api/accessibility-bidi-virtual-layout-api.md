# Accessibility、RTL、Bidi 与 Virtual Layout API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

确保 TSH 的坐标、装饰和导航能力不以 LTR、单行布局或固定字宽为前提。屏幕几何由宿主 Layout Provider 提供；TSH 核心保留逻辑文本坐标与视觉布局坐标的明确边界。

## 2. Layout Snapshot

Layout snapshot 至少包含 documentVersion、layoutGeneration、viewport、line fragments、visual runs、bidi levels、折行信息、可见区域和坐标变换版本。所有像素坐标使用明确单位与缩放因子。

## 3. 坐标映射

- 逻辑位置到屏幕点：允许一对多（双向文本边界、折行）。
- 屏幕点到逻辑位置：必须定义 affinity 与命中策略。
- Tab、宽字符、组合字符、emoji、ZWJ 序列不得按单个 UTF-16 code unit 推断视觉宽度。
- Layout 失效后旧像素坐标立即 stale；逻辑 range 可继续有效。

## 4. Accessibility

装饰与符号应提供可访问语义标签；纯颜色不能作为唯一状态表达。Gutter marker、诊断、折叠控件和 CodeLens 应暴露可读文本与可操作动作描述。宿主负责接入平台 Accessibility tree。

## 5. RTL/Bidi

文本顺序、视觉顺序和 selection anchor direction 必须分别建模。不可用简单 x 坐标排序替代逻辑顺序。行内装饰和命中测试应尊重 visual runs。

## 6. 验收

阿拉伯/希伯来混排、LTR 数字段、组合字符、emoji、Tab、软换行、缩放、屏幕阅读器语义、键盘导航和布局变化后命中测试。
