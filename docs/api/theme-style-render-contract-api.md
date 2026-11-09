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

## 4. Render Plan 与 Receipt（本节为唯一权威契约）

本节是 RenderPlan/Provider/Receipt 的唯一权威契约；后端实际绘制能力与像素验证见 `render-backend-conformance-api.md`。当前 Kotlin 源码模型位于 `TshRenderApi.kt`：

- `TshRenderRevisions`：绑定 document/version、config、theme、viewport、renderer，以及可选 Tree-sitter/Regex revision。
- `TshRenderPlan`：携带稳定 Plan ID、完整 revisions、RenderInstruction 与 required capabilities。
- `TshRenderProvider`：由宿主拥有并实现；负责实际 View/Canvas/Span/Compose 绘制，不转移 UI 对象所有权。
- `TshRenderAcknowledger`：Provider 必须在每次实际绘制/提交前调用 `isCurrent`，完成后提交 Receipt。
- `TshRenderCoordinator`：检查 revision、能力、重复 Plan ID、Provider 异常、回执一致性、过期提交和取消。

Receipt 状态为：

| 状态 | 含义 | appliedInstructionCount |
|---|---|---|
| QUEUED | 已排队，尚未确认宿主更新或绘制 | 必须为 0 |
| APPLIED | 所有指令已写入宿主显示对象；不代表已执行 View/Canvas draw | 必须等于 Plan 指令数 |
| PARTIALLY_APPLIED | 部分属性/范围写入宿主显示对象；不代表已执行 View/Canvas draw | 0 到 Plan 指令数 |
| DRAWN | 所有指令已由后端实际绘制 | 必须等于 Plan 指令数 |
| PARTIAL | 部分指令已由后端实际绘制 | 必须大于 0 且小于 Plan 指令数 |
| REJECTED | 后端拒绝，未更新/绘制 | 必须为 0 |
| FAILED | 后端执行失败且未提交有效更新/绘制 | 必须为 0 |

`confirmsHostMutation` 仅在 APPLIED/PARTIALLY_APPLIED 且数量大于 0 时为 true；`confirmsActualDraw` 仅在 DRAWN/PARTIAL 且数量大于 0 时为 true。Span mutation + invalidate 只能返回 APPLIED/PARTIALLY_APPLIED，不能谎报像素已绘制。QUEUED 不是成功绘制。异步 Provider 可先返回 QUEUED，再通过 Acknowledger 提交最终回执。过期回执必须拒绝；PARTIAL/PARTIALLY_APPLIED 必须产生可观察的降级诊断。

源码与回归测试目前为 SOURCE_PARTIAL；未运行构建、JUnit 或 Android 真实控件验证。RenderPlan 被接受/排队不代表屏幕已显示颜色。

## 5. 绘制后端适配

Backend 声明文本前景/背景、字体、装饰、颜色空间、混合模式、裁剪和坐标能力。Span backend 只能管理 TSH 自己的 Span；旧 WindbellTshEngine Spannable 路径现使用 TSH-owned CharacterStyle/MetricAffectingSpan 标记，只移除自身 Span，并校验 UTF-16LE byte range 对齐和边界。此修复有源码，尚未做真实 Android 控件测试。Canvas backend 在宿主绘制回调内执行；Compose backend 只输出不可变渲染状态，不让核心模块依赖 Compose。坐标和可见范围由最小 Layout/Coordinate Provider 提供。

## 6. 真实绘制闭环

必须建立应用可观察状态：plan created → submitted → accepted → rendered/partially-rendered/rejected。对于每个属性保留 source rule → resolved value → instruction → backend outcome 的链路。若 font family 被系统替换、颜色空间降级或装饰不支持，Receipt 必须报告。不得用“API 返回成功”代替设备端像素/视图验证。

## 7. 验收

Android 实际控件测试 foreground/background、font family/weight/style/size/letterSpacing、underline/wavy/strike、主题热切换、快速更新、宿主 Span 共存、过期 plan 拒绝和 backend 降级报告。
