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

注册 Provider 后可用 providers.findFor(editorView, editorDescriptor) 查询匹配项。Provider discovery 只负责能力匹配，不会自动 attach 或绘制。

## 4. 坐标转换

```kotlin
val result = TshCoordinateConverter.convert(
    text = sourceText,
    offset = treeSitterByteOffset,
    from = TshCoordinateSystem.UTF8_BYTE,
    to = TshCoordinateSystem.UTF16_CODE_UNIT
)
```

转换失败时返回结构化诊断。SCREEN_PIXEL 与 LINE_COLUMN 必须由 Layout-aware Provider 处理，不能通过文本长度猜测。

## 5. 关闭资源

```kotlin
engine.objects.unregister(editorDescriptor.id)
engine.close()
```

宿主应在编辑器销毁时解除注册。Engine close 会清空 Provider 和 Object Registry。不要让 Activity 销毁前仍有异步任务持有其 View。

## 6. 当前可用范围

已提交的基础 API 包括 Engine 状态、对象注册、Capability、Provider Registry、诊断/结果、文本快照契约和内存文本坐标转换。Session、自动适配、Layout、RenderPlan、Renderer attach、TSH Language Service 和完整 ATS/Regex 服务门面仍是后续开发目标。
