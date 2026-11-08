# Theme、Style、Font、Color 与 Decoration API 契约

> 状态：目标契约。定义 TSH 核心样式模型及应用查询/管理方式；实际绘制仍由 Render Backend 完成。

## 1. 核心对象

TshThemeDefinition：主题 ID、版本、父主题、变量、颜色、字体和样式规则。TshStyleRule：规则 ID、Selector、属性集合、priority、来源范围。TshColor：带颜色空间和 alpha 的不可变颜色值。TshFontSpec：字体族、字重、字形、字号、变体、特性和回退。TshDecoration：下划线、波浪线、删除线、边框、背景标记等装饰。TshResolvedStyle：对特定 Match/Scope、主题和配置 revision 解析后的不可变样式。

## 2. Theme 查询与管理

~~~kotlin
interface TshThemeService {
  fun listThemes(filter: TshThemeFilter = TshThemeFilter()): List<TshThemeDescriptor>
  fun getTheme(id: TshThemeId): TshResult<TshThemeDefinition>
  fun getActiveTheme(target: TshStyleTarget): TshResult<TshThemeId>
  fun previewTheme(id: TshThemeId, context: TshStyleContext): TshResult<TshStylePreview>
  fun activateTheme(id: TshThemeId, target: TshStyleTarget): TshResult<TshThemeRevision>
  fun resetTheme(target: TshStyleTarget): TshResult<TshThemeRevision>
}
~~~

activateTheme 只切换 TSH 样式解析上下文，不负责宿主 UI 主题系统。切换原子化并产生 themeRevision；旧 RenderPlan 不得冒充新主题结果。

## 3. Style 查询与解释

~~~kotlin
interface TshStyleCatalog {
  fun listRules(themeId: TshThemeId, filter: TshStyleFilter = TshStyleFilter()): List<TshStyleRuleDescriptor>
  fun getRule(id: TshStyleRuleId): TshResult<TshStyleRule>
  fun resolve(context: TshStyleContext): TshResult<TshResolvedStyle>
  fun explain(context: TshStyleContext): TshResult<TshStyleExplanation>
}
~~~

StyleExplanation 列出参与匹配的 Selector、规则、每个属性的胜出值、被覆盖值及来源，用于解释某段文本为什么得到某种颜色/字体。

## 4. 字体契约

TshFontSpec 至少支持：
- family：有序字体族候选列表及 generic family。
- weight：100–900，或后端明确支持的离散值。
- style：normal、italic、oblique(angle)。
- size：带单位尺寸；sp/em/rem/px 换算上下文明确。
- stretch：字体宽度等级或百分比。
- lineHeight：行高倍率或长度；仅作为样式输出，不接管宿主排版。
- letterSpacing：带单位字距。
- baselineShift：基线偏移。
- features：OpenType 特性标签，如 ligatures、kern、tnum。
- axes：可变字体轴值，如 wght、wdth、slnt。
- fallback：有序字体回退族。
- synthesis：是否允许后端合成粗体/斜体。

字体资源通过 TshFontResourceProvider 加载，TSH 不自行读取任意文件路径。字体句柄由 Provider 持有并声明生命周期、线程安全、许可/可用性错误。缺失时按 fallback 顺序降级并产生可查询诊断。

~~~kotlin
interface TshFontResolver {
  fun resolve(spec: TshFontSpec, capabilities: TshRenderCapabilities): TshResult<TshResolvedFont>
}
interface TshFontResourceProvider {
  fun load(family: TshFontFamily, weight: Int, style: TshFontStyle): TshResult<TshFontResource>
}
~~~

## 5. 颜色契约

颜色内部使用明确颜色空间（至少 sRGB；后续可扩展 Display-P3），包含 alpha。支持字面颜色、Theme Token、变量引用和有限颜色函数。透明度合成、混合模式和颜色空间转换必须定义在 Resolver/Backend 契约中，不得因后端不同而静默改变语义。

## 6. Decoration 契约

支持 underline（solid/dotted/dashed/wavy/double）、strikethrough、overline、边框、背景填充、圆角、outline 和语义标记。每项定义范围、颜色、厚度、偏移、层级、混合模式和合成策略。Decoration 是 TSH 样式输出，不包含 Gutter、Inlay、CodeLens 或交互控件。

## 7. 继承、级联和覆盖

Theme extends 形成无环有向图；子主题可覆盖父主题 Token/规则。属性级 merge policy 逐项定义：replace、fallback、append、set-union 或 numeric-compose。禁止模糊深合并。应用覆盖仅通过明确的 TshStyleOverride 输入，且必须可追踪来源。

## 8. 渲染能力协商

Resolver 输出后端无关样式；RenderBackend 声明支持的字体轴、OpenType 特性、颜色空间、装饰类型和尺寸单位。无法表达的属性返回 TshStyleDegradation，说明原值、降级值和原因。解析成功不等于绘制成功，必须由后端确认真实字体与装饰提交。

## 9. 测试

字体回退、字重映射、italic/oblique、字号单位、字距、行高、可变轴、OpenType 特性、颜色 alpha/颜色空间、Theme extends、循环引用、级联解释、后端能力降级和真实文本绘制均需独立测试。当前仅为 API 契约。
