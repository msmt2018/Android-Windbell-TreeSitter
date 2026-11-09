# Editor、View 与组件适配 API 规范

> 本文提供适配模式与示例；Provider/Binding 生命周期的规范权威来源为 [binding-events-and-extension.md](binding-events-and-extension.md)，RenderPlan/Receipt 权威契约为 [theme-style-render-contract-api.md](theme-style-render-contract-api.md)。

## 核心原则

TSH 不要求宿主编辑器继承某个 TSH 基类，也不要求编辑器开放内部实现。宿主只需注册真实对象实例；TSH 通过能力协商选择 Provider。一个对象可以同时具有多个角色，例如同一个控件既是 Editor，又是 TextInputTarget、ViewportOwner 和 RenderHost。

## 目标 API

| API | 职责 |
|---|---|
| TshEditorAdapter | 统一编辑器会话入口 |
| TshObjectAdapter | 把第三方对象暴露为 TSH 能力 |
| TshEditorProvider | 发现编辑器文本、选择区和事件 |
| TshTextAccessProvider | 读取宿主文本，不要求复制整份文档 |
| TshLayoutProvider | 行列、可见行、字符边界和屏幕坐标 |
| TshViewportProvider | 可视区域、滚动偏移和可见范围 |
| TshRenderProvider | 把 TSH RenderPlan 提交给宿主绘制机制 |
| TshDecorationProvider | 安装/更新/移除装饰对象 |
| TshInputProvider | 编辑输入、组合输入和光标事件（可选） |
| TshAccessibilityProvider | 对辅助功能暴露语义化高亮（可选） |

## 对象注册流程

1. 宿主调用 objects.register(instance, type, capabilities, metadata)。
2. TSH 返回 ObjectDescriptor 和稳定 ObjectId。
3. ProviderRegistry 根据实例类型、能力版本和 Provider.supports 进行匹配。
4. TshBindingManager 记录对象与 Session/Document/Renderer 的绑定。
5. attach 返回实际绑定能力、缺失可选能力、诊断和降级模式。
6. 宿主在对象销毁时 detach/unregister；TSH 取消相关任务并释放引用。

不得仅凭类名猜测对象可用能力。反射探测只能作为可选辅助，必须有显式能力验证和失败回退。

## 适配等级

- Level 0：仅提供文本读取，可运行解析和静态高亮结果。
- Level 1：提供文档变更事件，可增量更新。
- Level 2：提供 Layout/Viewport，可计算可见区域和行列坐标。
- Level 3：提供 Render Provider，可将结果真正绘制到编辑器。
- Level 4：提供 Decoration/Input/Accessibility 等完整交互能力。

TSH 必须在 attach report 中明确显示当前等级及缺失能力，不得把“算出了高亮范围”说成“已经绘制到屏幕”。

## 编辑器控件兼容策略

- Spannable 编辑器：可使用 TshAndroidSpannableRenderer 与 TshAndroidSpannableBinding.attachTo；宿主提供精确 hostObject、render-thread check、revision、Spannable、UTF-16 viewport、字体尺寸转换和 invalidate。只移除 TSH 自己拥有的 Span，不能清除宿主 CharacterStyle；viewport 必须有界。Binding detach 会清除该 Renderer 自己拥有的 Span，并在 OwnerScope/Engine 生命周期结束时关闭 RenderCoordinator；后台关闭需要宿主提供 postToRenderThread。
- Canvas 自绘编辑器：提供 Render Provider，将绘制命令提交到控件既有 draw pipeline。
- Compose 编辑器：提供 Compose state/decorator adapter，不要求引擎依赖 Compose。
- WebView/自定义渲染：提供 Decoration/Overlay Provider。
- Piece Tree/Rope：通过 TextSnapshot 与索引化 TextAccessProvider 读取，禁止每次更新都 flatten 全文。

## 线程和所有权

TSH 不拥有宿主 View/Editor/Document；除非注册选项明确声明强引用，否则适配层应避免延长宿主对象生命周期。UI 绘制调用必须在 UI 线程。Provider 不得在锁内被调用。Provider 卸载后所有 binding 必须失效。

## 验收

用至少三种不同编辑器模型验证：Spannable、Canvas 自绘、Piece Tree/Rope。每种验证文本读取、版本更新、坐标映射、真实屏幕渲染、销毁清理和宿主原有样式不受影响。
