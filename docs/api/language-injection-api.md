# TSH Language Injection API 契约

> 状态：目标契约。此处的 Injection 专指语法配置声明的嵌入语言区域，不是宿主编辑器的通用 Language Layer。

## 职责

允许一个主语言 Grammar/ATS 匹配结果识别嵌入区域，并将该区域交给另一语言定义的 Tree-sitter + ATS 或 Regex 管线。仍然只有两类执行管线，不增加第三类。

## DSL 声明示例

~~~tsh
injection "html.script" {
  host: "text.html"
  selector: "tag.script.content"
  language: "source.javascript"
  range: capture("script_body")
  priority: 100
  boundary: exclusive
}
~~~

## API 草案

~~~kotlin
interface TshInjectionRegistry {
  fun list(hostLanguage: TshLanguageId): List<TshInjectionDefinition>
  fun get(id: TshInjectionId): TshResult<TshInjectionDefinition>
  fun validate(definition: TshInjectionDefinition): List<TshDiagnostic>
}
interface TshInjectionResolver {
  fun resolve(host: TshParseSnapshot, definition: TshInjectionDefinition): TshResult<List<TshLanguageRegion>>
}
~~~

每个 LanguageRegion 必须携带 host range、embedded range、映射关系、宿主/子语言 ID、配置 revision 和来源 Match。映射必须支持 UTF-8/UTF-16 偏移差异、边界裁剪和不连续片段；不允许假设所有嵌入区域都是连续字符串。

## 语义与限制

- 注入定义必须绑定已注册的宿主语言、目标语言和可验证的范围来源。
- 重叠注入按显式 priority 与 boundary policy 解决；相同优先级冲突产生诊断。
- 默认禁止无限递归注入；最大深度和总区域数由 Runtime 配额控制。
- 嵌入区域的 Tree-sitter/ATS 或 Regex 执行结果映射回宿主坐标后进入统一 TshMatch。
- 注入范围变化必须使对应子语言解析、Match、Style 和 Render 缓存失效。
- 子语言缺失或编译失败时报告诊断，不得把区域静默按宿主语言高亮。

## 验收

覆盖嵌入区域边界、偏移映射、Unicode、重叠冲突、递归深度、子语言缺失、增量变更和宿主坐标回映射。当前仅为契约文档。
