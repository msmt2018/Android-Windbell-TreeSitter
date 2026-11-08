# Injection、Embedded Language 与 Language Layer API

> 状态：目标契约（规范文档）；不代表对应运行时 API 已实现。

## 1. 目标

支持 HTML 中的脚本/样式、模板中的表达式、Markdown 代码块等嵌入语言场景。Language Layer 是文档区域到语言配置的映射，不是新增语法执行引擎。

每层仍使用现有 Tree-sitter + ATS 与独立 Regex 管线；层管理器负责区域识别、坐标映射、优先级和生命周期。

## 2. 核心模型

- `TshLanguageLayer`：layerId、parentLayerId、languageId、hostRange、embeddedRange、mapping、priority、source。
- `TshInjectionRule`：ruleId、hostLanguageId、targetLanguageId、selector/query、include delimiters、priority。
- `TshLayerSnapshot`：documentVersion、layers、diagnostics、generation。
- `TshRangeMapping`：host 与 embedded 坐标之间的双向映射能力。

## 3. 层级规则

1. Layer 必须属于同一文档快照和版本。
2. 嵌入范围必须包含于父层范围；不允许形成循环。
3. 分隔符是否包含在子层由规则显式决定。
4. 多条 InjectionRule 冲突时按显式 priority、范围特异性和稳定 ruleId 决策。
5. 映射遇到不可表示边界时返回结构化失败，不得静默偏移。
6. 最大层级、规则数量、嵌入文档长度均应有资源配额。

## 4. Provider 契约

```kotlin
interface TshInjectionProvider : TshProvider {
    fun resolveLayers(
        document: TshTextDocument,
        snapshot: TshTextSnapshot
    ): TshResult<TshLayerSnapshot>
}
```

具体解析可使用 Tree-sitter Query、ATS 结构规则或 Regex 管线各自已定义的能力；不得通过把规则转译成 Monarch/TextMate grammar 来实现。

## 5. 增量更新

宿主文档发生编辑时，只使受影响的 layer 子树及其派生解析缓存失效。若编辑跨越注入边界或改变 delimiter，必须重新评估相邻区域。结果必须标记 documentVersion 和 parent generation。

## 6. 渲染与诊断

嵌入语言样式映射到 host document 的原始范围后，才可进入统一样式合成与 RenderPlan。子层诊断需携带 languageId/layerId，并转换到宿主坐标；转换失败时保留原始子层坐标和诊断原因。

## 7. 验收测试

嵌套模板、相同语言重复嵌入、delimiter 编辑、跨边界编辑、Unicode offset、父层失效、递归注入限制、恶意深层嵌套和部分 Provider 失败。
