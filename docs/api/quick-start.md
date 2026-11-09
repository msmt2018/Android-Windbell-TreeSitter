# TSH API 快速接入（当前阶段）

> 本文演示已提交的基础 API 形态，不代表现有 Native 高亮服务已经全部迁移到 TshEngine。真实解析/高亮仍需通过当前 WindbellTshEngine 兼容入口调用，直到后续服务接线完成。

## 1. 创建 Engine

```kotlin
val engine = TshEngine.create(
    TshEngineOptions(
        enableDiagnostics = true,
        strictProviderValidation = true
    )
)

when (val result = engine.initialize()) {
    is TshResult.Success -> {
        // Engine API registries are ready.
    }
    is TshResult.Failure -> {
        result.diagnostics.forEach(::reportDiagnostic)
    }
}
```

## 2. 注册真实编辑器实例

```kotlin
val editorDescriptor = engine.objects.register(
    instance = editorView,
    type = "editor",
    capabilities = TshCapabilitySet.of(
        TshCapability("text.read"),
        TshCapability("text.change.listen"),
        TshCapability("render.custom")
    ),
    metadata = mapOf("integration" to "my-editor")
)
```

注册表按实例身份识别对象。相同实例重复注册会更新能力和元数据并保留 ObjectId；同一实例不能用另一个 type 覆盖原类型。

## 3. 实现 Provider

```kotlin
class MyEditorProvider : TshProvider {
    override val id = "sample.editor"
    override val apiVersion = 1
    override val capabilities = TshCapabilitySet.of(
        TshCapability("text.read"),
        TshCapability("render.custom")
    )

    override fun supports(
        instance: Any,
        descriptor: TshObjectDescriptor
    ): Boolean = descriptor.type == "editor" && instance is MyEditorView
}
```

Provider discovery 只负责能力匹配，不会自动 attach 或绘制。生产接入应使用 OwnerScope 管理 attach/detach，而不是在 Activity 生命周期之外长期持有 View。

## 4. 宿主文本 Provider 注册

Piece Tree/Rope/Gap Buffer 宿主实现 TshTextAccessProvider，并注册为 Engine Provider；它不需要把全文转换为 String。

```kotlin
val textBinding = (TshTextAccessBinding.attach(
    engine,
    textAccessProvider,
    editorDocument,
    documentOwnerScope
) as TshResult.Success<TshTextAccessBinding>).value

// Each parse/query operation acquires a fixed-version snapshot lease and closes it when done.
val snapshotLease = (textBinding.acquireSnapshot() as TshResult.Success<TshTextSnapshotLease>).value
try {
    // parserOwner.parse(snapshotLease, ...)
} finally {
    snapshotLease.close()
}

// If the provider is registered once for many documents, use registerProvider = false.
```

provider 必须声明正确的 threading，并对 UI_THREAD_ONLY/UNKNOWN 实现 isAccessAllowedOnCurrentThread；没有 change/disposal 能力时返回结构化 unsupported 诊断，不得伪造订阅成功。

## 5. Android Spannable Renderer 绑定

Android 模块提供首个可用 RenderProvider。宿主无需继承 TSH 类型，只需实现一个薄适配器；必须提供有界 viewport，防止在 200–300 MB 文档上为全文创建大量 Span。

```kotlin
val target = object : TshAndroidSpannableRenderTarget {
    override val hostObject: Any = editorView
    override fun isOnRenderThread() =
        Looper.myLooper() == Looper.getMainLooper()
    override fun currentRevisions(documentId: String) = editorRevisions(documentId)
    override fun resolveSpannable(documentId: String): Spannable? =
        editorText(documentId) as? Spannable
    override fun visibleRangeUtf16(documentId: String) =
        TshAndroidVisibleRange(firstVisibleUtf16(), lastVisibleUtf16Exclusive())
    override fun resolveTextSizePx(value: TshDslValue.MeasureValue): Float? =
        hostResolveTextSize(value)
    override fun invalidate(documentId: String) = editorView.invalidate()
    override fun postToRenderThread(action: () -> Unit) =
        Handler(Looper.getMainLooper()).post(action)
}

val ownerScope = TshOwnerScope("editor-view")
val renderer = TshAndroidSpannableRenderer(target)
val binding = (renderer.attachTo(
    engine,
    ownerScope,
    requiredCapabilities = TshCapabilitySet.of(TshCapability("render.foreground"))
) as TshResult.Success<TshAndroidSpannableBinding>).value

val renderCoordinator = binding.renderCoordinator
// On each host document/theme/viewport revision change, install the exact current revisions.
renderCoordinator.updateCurrentRevisions(currentRenderRevisions)
renderCoordinator.submit(renderPlan)

// Editor/View is detached or destroyed:
val ownerDiagnostics = ownerScope.closeWithReport() // attachment cleanup
val bindingDiagnostics = binding.closeDiagnostics // renderer/provider cleanup
// Report both lists; neither operation closes the host editor object
```

visibleRangeUtf16 是必需契约；返回 null 或缺失 viewport 时拒绝 Span 渲染。Span 更新回执为 APPLIED/PARTIALLY_APPLIED，不代表 Android 已完成真实像素绘制。真实 Draw 必须由宿主 View 绘制/像素测试确认。宿主原有 Span 不会被 TSH 清除。Binding detach/Engine close 会关闭 RenderCoordinator 并清理本 Renderer 拥有的 Span；若关闭可能发生在后台线程，宿主应实现 postToRenderThread，清理会排队到 UI/render thread。

## 6. 坐标转换

```kotlin
val result = TshCoordinateConverter.convert(
    text = sourceText,
    offset = treeSitterByteOffset,
    from = TshCoordinateSystem.UTF8_BYTE,
    to = TshCoordinateSystem.UTF16_CODE_UNIT
)
```

转换失败时返回结构化诊断。SCREEN_PIXEL 与 LINE_COLUMN 必须由 Layout-aware Provider 处理，不能通过文本长度猜测。

## 7. 关闭资源

```kotlin
engine.objects.unregister(editorDescriptor.id)
engine.close()
```

宿主应优先关闭 OwnerScope/Binding；Engine close 会永久关闭 Provider、Object、CompiledQuery 等 Registry。不要让 Activity 销毁前仍有异步任务持有其 View。

## 8. 当前可用范围

已提交的基础 API 包括 Engine 状态、对象注册、Capability、Provider Registry、诊断/结果、文本快照契约和内存文本坐标转换。Session、自动适配、Layout/Canvas/Compose Renderer、完整 TSH Language Service 和 ATS/Regex 服务门面仍是后续开发目标。Android Spannable Renderer 已有源码接入，但当前仅 SOURCE_PARTIAL，尚未构建和运行测试。
