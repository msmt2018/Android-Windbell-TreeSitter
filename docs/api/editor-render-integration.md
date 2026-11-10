# EditorView 渲染接入

本文说明如何把已经完成 Query/Match/Scope/Style 解析的 TSH `TshRenderPlan` 接入仓库内 `EditorView` 的真实行绘制链路。

## 实际绘制链路

```
TshRenderPlan
  -> TshRenderPlanSyntaxHighlighter.updateRenderPlan()
  -> SyntaxHighlighter / LineStyler.styleLine()
  -> UTF-16 行区间索引查询
  -> ForegroundColorSpan / BackgroundColorSpan / StyleSpan / UnderlineSpan
  -> StyledTextPainter
  -> EditorView Canvas 绘制
```

`TshRenderPlanSyntaxHighlighter` 不替换或持有 EditorView、EditorDocument、PieceTree、Parser 或 SyntaxTree。它只实现编辑器已经公开的 `SyntaxHighlighter` 接口。

## 接入方式

```kotlin
val highlighter = TshRenderPlanSyntaxHighlighter(
    documentId = TshDocumentId(documentId)
) {
    // 必须与 TshPieceTreeDocument.snapshotFactory 使用同一个版本源。
    editorView.getTextBuffer().getVersionId().toLong()
}

editorView.setSyntaxHighlighter(highlighter)

// 在 Query -> Match -> StyleResolver 产生当前版本 RenderPlan 后：
when (val installed = highlighter.updateRenderPlan(renderPlan)) {
    is TshResult.Success -> {
        // 样式已发布给 EditorView；真正像素绘制发生在后续 View draw。
        installed.diagnostics.forEach(::reportDiagnostic)
    }
    is TshResult.Failure -> installed.diagnostics.forEach(::reportDiagnostic)
}

// 关闭编辑器时：
editorView.setSyntaxHighlighter(null)
```

如果同时使用 `TshPieceTreeIncrementalSession`，应在它发布新语法树后执行 Query、Match 合并与 StyleResolver，并把新 RenderPlan 交给同一个 highlighter。传入的 `currentDocumentVersion` 必须与该 session 的 PieceTree Snapshot Provider 版本一致。

## 版本和生命周期规则

- RenderPlan 的 documentId 必须与 highlighter 绑定的 documentId 一致。
- RenderPlan 的 documentVersion 必须等于当前 PieceTree 版本；旧版本计划会被拒绝。
- 任意文本编辑会立即使旧 RenderPlan 失效；编辑事务结束后 EditorView 会重新请求样式。新解析结果到达后再调用 `updateRenderPlan`。
- 全文替换会清除旧计划；调用方应重新解析并发布新计划。
- `updateRenderPlan` 会复制指令集合、样式集合和 sourceMatchIds，避免调用方后续修改可变集合影响绘制。
- 指令按 UTF-16 半开区间索引。绘制每一行时只查询与当前绘制片段相交的指令，不会在每次 draw 中扫描整个 RenderPlan。
- 行级 `Spanned` 是只读轻量视图，复用原始行 String，不复制整行文本。

## 当前支持范围

当前仓库的 `StyledTextPainter` 直接绘制：

- foreground / background
- bold / italic
- underline
- strike-through

`fontFamilies`、`fontSize` 暂未由该 Painter 实现。highlighter 会返回 warning，不会把这些属性静默伪装成已绘制。

本桥接证明的是 TSH RenderPlan 已进入 EditorView 的 Span 绘制路径，不等于真实设备像素验证。仍需在 Android 设备上验证滚动、换行、长行、主题切换、编辑/撤销重做及大文件场景。
