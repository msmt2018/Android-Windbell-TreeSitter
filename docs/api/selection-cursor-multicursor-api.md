# Selection、Cursor、Caret 与 Multi-Cursor API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标与边界

统一描述编辑器光标、主选区、多选区、矩形选择和虚拟列位置。TSH 负责坐标校验、变更通知、语义查询所需的只读上下文；光标绘制、键盘输入和编辑行为仍由宿主 Editor 控制。

## 2. 核心模型

- `TshCaret`：position、affinity（Upstream/Downstream）、visualColumn（可选）、isPrimary。
- `TshSelection`：anchor、active、normalizedRange、direction、kind。
- `TshSelectionSet`：documentId、documentVersion、primaryIndex、selections。
- `TshCursorSnapshot`：selectionSet、generation、origin。
- `TshSelectionKind`：Character、Line、Block、Node、Unknown。

逻辑坐标与屏幕像素坐标必须分离。软换行后的视觉行列不得伪装成文档 offset。

## 3. Provider 契约

```kotlin
interface TshSelectionProvider : TshProvider {
    fun currentSelection(documentId: String): TshResult<TshSelectionSet>
    fun observeSelection(
        documentId: String,
        listener: (TshSelectionSet) -> Unit
    ): TshResult<TshDisposable>
}
```

此处 TshDisposable 表示可重复安全调用的订阅释放句柄；实际 API 必须定义线程安全与关闭行为。

## 4. 不变量

- 每个 SelectionSet 至少有一个主选区；空选区用 anchor == active 表示。
- primaryIndex 必须有效，且主选区标记与索引一致。
- 所有位置属于同一 documentId/version。
- 多选区排序、重叠合并策略必须由宿主明确配置；不得静默改变用户选择。
- Block selection 需要 Layout/VisualLine Provider 支持；缺失时返回 UnsupportedCapability，不得猜测矩形范围。

## 5. 事件与并发

选择变化事件应包含旧/新 generation、触发来源和文档版本。连续拖动可合并通知，但最终状态不得丢失。订阅回调不得在内部锁持有期间调用。释放订阅后，不得再投递新回调；已排队回调需通过 generation 或 disposed 标记抑制。

## 6. 与语言服务的关系

Completion、Hover、Rename 等请求可携带主光标或明确位置列表。多光标请求必须声明是否逐光标执行、是否要求结果一致以及冲突如何处理。引擎不得默认把主光标结果复制到其他光标。

## 7. 验收测试

覆盖反向选择、空选区、多选区重叠、文档编辑后映射、软换行视觉列、RTL 文本、emoji/grapheme 边界、订阅取消和并发事件顺序。
