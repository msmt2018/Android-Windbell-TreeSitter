# TSH DSL v2 规范

> 对象：Windbell Tree-sitter Highlighting（TSH）.tsh 配置文件  
> 版本：2  
> 实现基线：当前 Native WindbellTshCompiler、WindbellTshCore 与 Java/Kotlin TSH 绑定源码。

## 1. 设计目标与硬性边界

TSH 是语言高亮配置与运行时。语言特有的节点名、关键字、正则、样式名称、颜色、主题与规则优先级应由 .tsh 声明。Native 内核只实现通用解析、匹配、capture/span 仲裁与渲染数据传输，不保存语言特有颜色或关键字表。

必须遵守：

1. 配置是语言行为与主题数据的事实来源。
2. 只有两条匹配管线：ATS（Tree-sitter AST Selector）与 Oniguruma Regex。二者独立执行，结果统一为 capture/span 后仲裁。
3. ATS 不等于 Tree-sitter Query。ATS 是 TSH 的结构化节点选择器；原生 Query 是 Tree-sitter Query 语言本身。
4. Query 源码内嵌于 treeSitter.queries[].source，编译时直接交给目标 TSLanguage 的 ts_query_new；不生成、不加载、不依赖外部 .scm。
5. Regex 不代替语法树结构解析。能由 grammar 节点、field、父子/兄弟关系准确表达的语义，应优先使用 ATS/Query。
6. 规则使用语义 style 名称。数值 style ID 是编译产物，不得硬编码或赋予固定含义。
7. 缺失 foreground/background 表示不覆盖。不得自动补黑、白或其他可见颜色。
8. 未知根字段、未知属性、重复声明、无效 style/capture 绑定、无效 Regex/Query、无效 grammar 节点/字段名必须明确失败，不得静默忽略。
9. TSH 不负责 Outline、Folding、locals、tags 或嵌入语言 injection 管线。

## 2. 文件封套与最小结构

新文件必须使用版本封套：

~~~tsh
tsh 2 {
  language {
    id: "java"
    name: "Java"
    extensions: [".java"]
  }

  styles {
    keyword { scope: "keyword.control", tags: ["keyword"] }
  }

  schemes {
    default {
      light [ keyword: #0000FF ]
      dark  [ keyword: #79C0FF ]
    }
  }

  ast: [
    { node: "if_statement", style: "keyword" }
  ]
}
~~~

Native 编译器保留旧根对象形式（以 { 开始，不带 tsh 2）用于兼容；新配置不得继续采用无版本封套形式。版本目前只接受整数 2。

### 2.1 必需项与条件必需项

| 声明 | 要求 | 说明 |
|---|---|---|
| tsh 2 封套 | 新文件必须 | 版本必须为 2 |
| language.id/name | 强烈建议定义 | 稳定语言标识与显示名 |
| language.extensions | 需要按扩展名识别时必需 | 例如 .java；内部去点并去重 |
| styles | 产生可见 capture 时必须有对应 style | 集中定义语义样式 |
| schemes | 需要主题着色时必需 | 可定义多个主题与 light/dark |
| ast | 使用 ATS 时必需 | 数组可为空，但不会产生 ATS capture |
| treeSitter.queries | 使用原生 Query 时必需 | 启用 Query 必须绑定目标 grammar |
| contains | 使用 Regex 时必需 | MATCH 或 BLOCK 规则 |
| defines/repository | 可选 | Regex 片段复用 |
| editor | 可选 | 宿主编辑器元数据 |
| matching | 可选 | Regex 全局选项 |

ATS-only、Regex-only 与 Hybrid 均合法。不要求每个文件都启用两条管线。

## 3. 两条管线与配置分区

~~~text
.tsh
 ├─ language / editor / matching
 ├─ styles / schemes
 ├─ defines / repository
 ├─ ast                         ATS selector pipeline
 ├─ treeSitter.queries          原生 Tree-sitter Query
 └─ contains                    Oniguruma Regex
                   |
                   v
        normalized capture/span
                   |
                   v
       priority/order arbitration
                   |
                   v
        style/theme resolution
                   |
                   v
             Android renderer
~~~

原生 Tree-sitter Query 是 ATS/Tree-sitter 侧的 Query 执行入口，不是第三条管线。ATS selector 与 native Query 可以同时使用；Regex 独立执行。

### 3.1 ast：ATS 结构选择器

ATS 根据 grammar 的节点类型、field、父/祖先/子/兄弟关系、节点标记与节点文本条件选择节点。适合声明名称、类型节点、注解、字面量、注释以及结构上下文条件。ATS 不接受 query/source 属性。

### 3.2 treeSitter.queries：原生 Query

~~~tsh
treeSitter {
  queries: [
    {
      id: "java-declarations"
      source: `
        (class_declaration name: (identifier) @class_name)
        (method_declaration name: (identifier) @method_name)
      `
      captures {
        class_name: type
        method_name: function
      }
      priority: 100
      enabled: true
    }
  ]
}
~~~

source 是原始 Tree-sitter Query。每个输出 capture 必须绑定 style；未显式绑定时，编译器尝试用 capture 名作为 style 名。仅作为 predicate operand 的 capture 可不绑定输出 style。启用 Query 必须提供目标 TSLanguage；节点名、field、Query 与 predicate 都在目标 grammar 上校验。

Query block 支持 id、source、captures、enabled、priority、patterns。patterns 可按 0-based pattern index 设置 enabled、priority、overlap、order；这是 TSH 元数据，不属于 Query 文法。禁用 Query 不得同时声明 patterns 元数据。

### 3.3 contains：Oniguruma Regex

~~~tsh
contains: [
  {
    id: "line-comment"
    match: /\/\/[^\\r\\n]*/
    style: comment
    priority: 30
  }
  {
    id: "block-comment"
    style: comment
    start: { match: /\/\*/ }
    end: { match: /\*\// }
    contains: [
      { match: /\\b(TODO|FIXME)\\b/, 0: keyword, priority: 120 }
    ]
  }
]
~~~

MATCH 使用 match（兼容别名 builtin）；BLOCK 使用 start/end（begin 是 start 的兼容别名）。BLOCK 可使用 style、contentName、startCaptures/beginCaptures、endCaptures 与嵌套 contains/patterns。

### 3.4 组合策略

- ATS-only：ast 和/或 treeSitter.queries；不声明 contains。
- Regex-only：声明 contains；不声明 ATS/Query 规则。
- Hybrid：ATS/Query 与 Regex 同时声明，彼此独立执行。
- 当前不支持统一 façade rules { ats { ... } regex { ... } }。
- 不得引入 Monarch 或 TextMate tokenizer 作为第三条管线。

## 4. language 与 editor

language 接受 id、name、extensions、aliases、mimeTypes（兼容 mimetypes）和 firstLine（Oniguruma Regex）。扩展名可带点，内部会去点并去重。旧根字段 name: [...] 与 ignoreCase: true/false 仅作兼容。

editor 当前接受：
- 基础选项：tabSize、fontSize、lineHeight、letterSpacing、insertSpaces、autoIndent、trimTrailingWhitespace、formatOnType、formatOnPaste、renderWhitespace、lineNumbers、maxTokenizationLineLength。
- comments.line/block、brackets、bracketNotIn、autoClosingPairs、surroundingPairs。
- wordPattern、autoCloseBefore。
- indentation.increaseIndentPattern/decreaseIndentPattern/indentNextLine/unIndentedLinePattern。
- completion.triggerCharacters、electricCharacters、onEnterRules。

这些是宿主可消费的元数据，不会自动实现补全、自动缩进、格式化或编辑事件。宿主必须明确消费。未知 editor 字段会使 Native 编译失败。

## 5. matching 与 Regex 全局选项

matching 支持 caseSensitive、multiline、dotAll、extended、unicode，值均为布尔值。unicode 当前必须为 true，因为 Oniguruma 固定使用 UTF-16LE。单条 Regex flags 支持 i/ignoreCase、m/dotAll、s/singlelineAnchors、x/extended。注意 Oniguruma m/s 语义按本规范解释，不要照搬其他引擎的惯例。

## 6. styles 与 schemes

styles 定义语义样式，不在其中写颜色。可用字段：scope、tags、description、deprecated、parent、metadata。style 名称是用户自定义标识，不存在固定 token 枚举。parent 允许语义继承；未知父样式或继承环会失败。子样式缺失的 light/dark palette 值可继承父样式，显式子值覆盖。

schemes 可定义任意多个命名主题。每个主题可定义 light、dark、ui 与 extends。颜色格式为 #RGB、#RGBA、#RRGGBB、#AARRGGBB，内部规范化为 AARRGGBB。light/dark 中省略某样式颜色表示不覆盖。ui token 使用点分隔名称，例如 editor.background、activity.toolbar.foreground。

已实现 modifier：@bold/@B、@italic/@I、@underline/@U、@strike/@strikethrough/@S、@dim，以及 @no-bold、@no-italic、@no-underline、@no-strike/@no-strikethrough、@no-dim。@dim 会降低 Android renderer 的文本 alpha。字体 family/size、任意 opacity、background decoration 等不属于当前 palette modifier。

scopeRules 当前支持逗号 alternatives、分组和 | alternatives、descendant、direct-child >、exclusion -、exact/prefix scope、* 与 .* wildcard；L:/R: 前缀会被剥离。它不是完整 TextMate selector 引擎。显式 style 颜色优先于 scopeRules。

## 7. Regex 复用、规则与捕获

defines 使用数组形式的 name → Regex/string 映射，可通过 match: include("name") 或 builtin: name 引用；keywordsToRegex("if else return") 可将空格分隔词展开。repository 使用命名 Regex 片段，可通过 include("#name") 引用。未知引用与循环引用失败。

MATCH 规则支持 match/builtin、style/name、id、priority、enabled、overlap、flags、captures、contains/patterns。BLOCK 支持 start/begin、end、style/name、contentName、id、priority、enabled、overlap、flags、captures、startCaptures/beginCaptures、endCaptures、contains/patterns。捕获组 key 可为数字索引或命名组名；0 表示整个匹配。

overlap 仅接受 allow/deny。priority 为整数，越大越优先；声明顺序用于同优先级决胜。空匹配会推进一个 Unicode code point 防止死循环。Regex 编译失败会使整个配置编译失败。

当前不支持 rule-level when、while、maxIterations、applyEndPatternLast、规则对象 include、lookahead/lookbehind 便利字段和任意 rule metadata。Oniguruma 本身支持的 Regex 语法不代表这些 TSH 结构字段可用。

## 8. Capture 仲裁与渲染

ATS、native Query 与 Regex 结果统一为 capture/span。候选 span 按优先级、声明顺序、范围、来源与 style 稳定排序。完全重复项去重；严格部分重叠按优先级/声明顺序切分；allow span 保留完整范围；父子包含关系允许嵌套 scope 共存。规则优先级是显式配置，不从 style 名称推断。来源只用于稳定决胜，不覆盖显式 priority/order。

最终 renderer 必须保留显式颜色与 alpha。缺失颜色不能被自动补色。

## 9. SCM 迁移原则

SCM 是 Tree-sitter Query 源码。将 highlights.scm 原文放进 treeSitter.queries[].source，并用 captures 将 capture 名绑定到 semantic style。不要转换为 ATS，也不要生成新的 .scm。只有可明确表示为单个节点结构条件的规则，才考虑人工重写为 ATS。TextMate Regex grammar 应迁移到 contains。locals/tags/injections 不属于当前 TSH 高亮管线。

逐项转换见 [SCM → TSH DSL v2 迁移指南](TSH_SCM_TO_TSH_MIGRATION.md)。

## 10. 非当前实现语法

以下不能作为生产配置使用：rules { ats/regex } façade、Monarch、Regex when/while/maxIterations/applyEndPatternLast、TextMate 外部 $self/$base、pattern-object repository、injection selector、完整 TextMate precedence、per-capture scope assignment、完整 decoration/font family/size/opacity 模型、Outline/Folding。

Kotlin TshDslModel/TshSchemaRegistry 可表示的通用 declaration kind、unit、color space 或 value，不代表 Native .tsh 文件解析器已支持对应语法。

## 11. 编写检查表

- 使用 tsh 2 封套。
- language 与目标 grammar/文件类型一致。
- 每个输出 capture 都能解析到有效 style。
- ATS 节点/field 名来自目标 grammar。
- Query 能在目标 TSLanguage 编译，predicate 在支持清单内。
- Regex 使用 Oniguruma 语法，include 存在且无循环。
- BLOCK 具备 start/end，捕获组绑定正确。
- light/dark 分别声明，不依赖隐式颜色回退。
- 不包含 Monarch 或规划字段。
- 通过实际加载/编译验证后，才能标记配置可用。
