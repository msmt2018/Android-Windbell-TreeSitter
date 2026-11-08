# Theme、Style 与 Render 完整 API 契约

> 状态：目标契约。目标是保证 .tsh 颜色、字体和装饰真正到达宿主绘制，而不是只得到一个样式对象。

## 1. Theme 与 Style 管理

~~~kotlin
interface TshThemeService {
  fun list(filter: TshThemeFilter = TshThemeFilter()): List<TshThemeDescriptor>
  fun get(id: TshThemeId): TshResult<TshThemeDefinition>
  fun active(target: TshStyleTarget): TshResult<TshThemeId>
  fun preview(id: TshThemeId, sample: TshStyleSample): TshResult<TshStylePreview>
  fun activate(id: TshThemeId, target: TshStyleTarget): TshResult<TshThemeRevision>
  fun reset(target: TshStyleTarget): TshResult<TshThemeRevision>
}
interface TshStyleService {
  fun list(theme: TshThemeId, filter: TshStyleFilter = TshStyleFilter()): List<TshStyleRule>
  fun get(id: TshStyleRuleId): TshResult<TshStyleRule>
  fun resolve(context: TshStyleContext): TshResult<TshResolvedStyle>
  fun explain(context: TshStyleContext): TshResult<TshStyleExplanation>
}
~~~

Theme 继承必须无环；Style cascade 对每个属性独立排序并记录 winning source、被覆盖候选和 specificity。Theme 切换生成新 revision 并触发依赖缓存失效。

## 2. 字体属性完整性

FontSpec 支持 family 列表、weight、style normal/italic/oblique、size+unit、stretch、lineHeight、letterSpacing、baselineShift、OpenType features、variable axes、fallback、synthesis。FontResolver 必须返回实际解析 family、实际 weight/style、fallback index、资源句柄、缺失特性和降级原因。资源由 TshFontResourceProvider 提供；不得把字体文件路径暴露为 Native 裸路径。

## 3. 颜色和装饰

ColorValue 包含 colorSpace、components、alpha。解析器支持十六进制、rgb/rgba、hsl/hsla、token 和变量引用。Decoration 包括 underline solid/dotted/dashed/wavy/double、strike、overline、border、background fill、outline；定义 thickness、offset、range、layer、blend 和合成策略。Gutter/Inlay/CodeLens 不属于 TSH。

## 4. Render Plan

~~~kotlin
data class TshRenderPlan(
  val documentId: TshDocumentId,
  val documentVersion: Long,
  val configRevision: String,
  val themeRevision: String,
  val instructions: List<TshRenderInstruction>,
  val diagnostics: List<TshDiagnostic>
)
interface TshRenderBackend {
  val capabilities: TshRenderCapabilities
  fun submit(plan: TshRenderPlan): TshResult<TshRenderReceipt>
}
data class TshRenderReceipt(
  val planId: TshRenderPlanId,
  val accepted: Boolean,
  val renderedRevision: String?,
  val renderedInstructionCount: Int,
  val degraded: List<TshStyleDegradation>,
  val rejected: List<TshDiagnostic>
)
~~~

Backend 必须对每个 instruction 返回 applied/degraded/rejected 状态。单纯接收计划不代表绘制完成；异步后端必须提供完成回执或可查询 fence。过期版本拒绝或明确丢弃。

## 5. 绘制后端适配

Backend 声明文本前景/背景、字体、装饰、颜色空间、混合模式、裁剪和坐标能力。Span backend 只能管理 TSH 自己的 Span；Canvas backend 在宿主绘制回调内执行；Compose backend 只输出不可变渲染状态，不让核心模块依赖 Compose。坐标和可见范围由最小 Layout/Coordinate Provider 提供。

## 6. 真实绘制闭环

必须建立应用可观察状态：plan created → submitted → accepted → rendered/partially-rendered/rejected。对于每个属性保留 source rule → resolved value → instruction → backend outcome 的链路。若 font family 被系统替换、颜色空间降级或装饰不支持，Receipt 必须报告。不得用“API 返回成功”代替设备端像素/视图验证。

## 7. 验收

Android 实际控件测试 foreground/background、font family/weight/style/size/letterSpacing、underline/wavy/strike、主题热切换、快速更新、宿主 Span 共存、过期 plan 拒绝和 backend 降级报告。
