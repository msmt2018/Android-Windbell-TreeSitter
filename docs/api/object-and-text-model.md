# 对象与文本模型 API

## 当前代码落地

当前源码包括 `TshTextDocument`、`TshTextSnapshot`、`TshTextSnapshotLease`、`TshTextChunk`、`TshTextAccessProvider` 和 `TshCoordinateConverter`。Provider 有 bounded chunk read、Snapshot Lease 入口和可选变更订阅；默认 Lease 仅对 intrinsically immutable Snapshot 安全，默认 subscribe 明确返回 unsupported 诊断。Edit 采用 before-version 坐标、升序且不重叠。坐标转换拒绝不成对 surrogate，并提供 Long offset 入口；旧 Int API 保留兼容。Piece Tree/Rope 的索引化转换、真正固定底层快照的宿主 Lease、变更订阅实现和设备验证仍未完成。测试源码已添加，尚未运行。

## 1. 对象模型

TSH 必须能处理外部编辑器的真实实例，而不是要求宿主继承 TSH 类型。对象通过稳定的运行时 ID 注册，并附带类型标签、能力集合、可选父子关系、所有者和生命周期状态。

目标契约：
- `TshObjectId`：会话内稳定且不可复用的标识。
- `TshObjectDescriptor`：对象类型、实例引用、能力、元数据和生命周期。
- `TshObjectRegistry`：register/find/unregister/resolve。
- `TshCapability`：明确、可版本化的能力标识。
- `TshObjectAdapter`：为第三方对象提供能力实现。
- `TshObjectLifecycle`：attach/detach/dispose。

禁止通过不安全反射强制访问私有字段。反射只可用于公开方法/属性的安全探测，并必须允许关闭。

## 2. 对象类型

覆盖 Editor、View、Document、TextBuffer、TextSequence、LineIndex、Position、Range、Selection、Cursor、Layout、Viewport、Scroll、Fold、Gutter、Inlay、Decoration、Composition、Clipboard、Undo/Redo、Input 和 Accessibility。不是每个对象都必须由 TSH 实例化；可以由宿主提供并注册。

## 3. 文本坐标

必须区分：
- UTF-8 byte offset（Tree-sitter 常用）。
- UTF-16 code-unit offset（Android/JVM String 常用）。
- Unicode code point offset。
- Grapheme cluster offset（用户感知字符）。
- Line/column（列单位必须声明）。
- 屏幕像素坐标。

公共 SourcePosition Offset 与 Tree-sitter ByteRange 使用 Long；兼容 Int 构造仅作为迁移入口。Native TSQueryCursor 当前仍接收 Int，Adapter 必须在边界显式检查并拒绝溢出。任何 Position/Range 都必须携带坐标系或绑定明确的文档坐标约定。禁止通过 `byteOffset / 2` 假设所有文本都是 UTF-16LE 且偏移天然可除 2。

## 4. 转换要求

坐标转换服务必须考虑 Unicode 补充平面字符、组合字符、Emoji ZWJ 序列、CRLF/LF、制表符、增量编辑、Piece Tree/Rope 分块和文档版本。转换必须能报告非法边界、过期版本和不可逆转换。

## 5. 文档与缓冲区

`TshTextDocument` 提供 URI/ID、语言、版本、长度、文本读取和编辑事件。底层可以是 String、Spannable、Piece Tree、Rope、Gap Buffer 或分块流；TSH 通过 Text Access Provider 读取，不要求复制整份文本。

增量编辑必须表达旧范围、新范围、替换文本、版本前后关系和坐标系。缓冲区 Provider 负责把抽象读取映射到底层数据结构。

## 6. Snapshot Lease、分块和变更

`acquireSnapshot` 返回固定版本的 Snapshot Lease；调用方必须在异步任务结束时关闭 Lease。默认实现只包装 Snapshot，不额外固定宿主底层版本，因此仅适用于不可变 Snapshot；可变 Piece Tree/Rope Provider 必须覆盖该方法并真正 pin 住版本。`readChunk` 限制单次读取长度，不得在实现中偷偷复制全文。`subscribe` 默认返回明确的 unsupported 诊断，宿主 Provider 应实现文档变更与销毁通知。当前源码提供接口与默认适配，不代表所有宿主存储已接通。

TshTextChange 的 Edit 列表使用 beforeVersion 坐标，必须升序且不重叠；实际应用顺序由 Provider 负责，不得把更新后的 offset 混入同一批旧版本 Edit。

## 7. 生命周期与线程

文档、编辑器和布局可能独立销毁。所有异步结果必须携带文档版本和会话标识；过期结果不可提交。对象注销后，引用它的绑定和任务必须取消或失效。
