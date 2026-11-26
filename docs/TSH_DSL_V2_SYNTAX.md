# TSH DSL v2 语法参考

面向需要手写 .tsh 的语言包作者。本文只描述当前 Native WindbellTshCompiler 实际解析的语法与语义，不把 Tree-sitter/Oniguruma/TextMate 的全部生态能力等同于 TSH 能力。

- [TSH DSL v2 规范](TSH_DSL_V2_SPEC.md)
- [SCM → TSH DSL v2 迁移指南](TSH_SCM_TO_TSH_MIGRATION.md)

## 1. 词法与通用规则

- 新文件使用 tsh 2 { ... }；兼容旧根对象 { ... }。
- 支持标识符、单/双引号字符串、反引号原始字符串、Regex 字面量、颜色、对象与数组。
- 支持 // 行注释与 /* */ 块注释。
- 普通引号字符串不可包含未转义换行；字符串转义遵循 JSON 风格。
- Query source 推荐使用反引号字符串。
- Regex 属性使用 /.../ 或实现明确允许的字符串形式。
- 对象/数组逗号可省略，建议保留逗号以减少 diff 歧义。
- 未闭合字符串/注释/Regex、未知标点与无效转义是错误，不会静默跳过。

## 2. 根级声明

| 声明 | 形式 | 用途 |
|---|---|---|
| language | object | 语言身份、扩展名、MIME、首行识别 |
| name | array | 旧版语言名称/扩展名兼容 |
| ignoreCase | boolean | 旧版 Regex 忽略大小写开关 |
| matching | object | Regex 全局选项 |
| editor | object | 宿主编辑器元数据 |
| styles | named object | 语义 style 目录 |
| schemes | named object | 命名主题 |
| defaultScheme | string/identifier | 默认主题 |
| isDark | boolean | 初始外观标志 |
| defines | key-pattern array | Regex 宏片段 |
| repository | named block/map | 可复用 Regex 片段 |
| treeSitter | object | 原生 Query 集合 |
| ast | object array | ATS selector |
| contains | object array | Oniguruma MATCH/BLOCK |

根区块不可重复。未知根字段会编译失败。不要使用 rules、monarch、injections 等未实现根区块。

## 3. language

~~~tsh
language {
  id: "java"
  name: "Java"
  extensions: [".java", ".jav"]
  aliases: ["Java"]
  mimeTypes: ["text/x-java-source", "text/x-java"]
  firstLine: /^#!.*\\bjava\\b/
}
~~~

id/name 为 string 或 identifier；extensions/aliases/mimeTypes 为字符串数组；firstLine 为 Oniguruma Regex。extensions 会移除前导点并去重。grammar binary/TSLanguage 必须与 language id 和 Query 节点表对应。

旧式 name: ["Java", ".java"] 与 ignoreCase: false 可兼容读取，新文件优先使用 language/matching。

## 4. styles

~~~tsh
styles {
  keyword { scope: "keyword.control", tags: ["keyword", "control"] }
  type { scope: "entity.name.type", tags: ["type"] }
  function { scope: "entity.name.function", tags: ["function"] }
  comment { scope: "comment", tags: ["comment"] }
}
~~~

字段：scope、tags、description、deprecated、parent、metadata。parent 不存在或继承环会失败。子 style 缺失的 light/dark palette 值可继承父 style。

绑定方式：
- ATS：style: "type"。
- Regex：style: "comment" 或 captures { 1: keyword }。
- Native Query：captures { capture_name: style_name }。
- Query 未显式映射时，默认按 capture 名查找同名 style。
- 仅作为 predicate operand 的 capture 可以不绑定输出 style。

## 5. schemes、颜色与 scopeRules

~~~tsh
schemes {
  graphite {
    light [ keyword: #0000FF @bold ]
    dark [ keyword: #79C0FF @bold ]
    ui {
      light { editor.background: #FFFFFF }
      dark { editor.background: #1E1E1E }
    }
    scopeRules: [
      { selector: "source.java keyword", light: #0000FF, dark: #79C0FF }
    ]
  }
}
defaultScheme: "graphite"
isDark: false
~~~

主题字段：light、dark、ui、extends、scopeRules。extends 指向其他 scheme；未知父主题或循环继承失败。light/dark 是 style→color map。ui token 是任意点分隔名称，由宿主消费。缺失颜色代表不覆盖。

颜色：
- #RGB：RGB 扩展为 8 bit，alpha=FF。
- #RGBA：RGBA 扩展为 8 bit，alpha 在最后。
- #RRGGBB：RGB，alpha=FF。
- #AARRGGBB：Android ARGB。
内部统一 AARRGGBB；不要把 CSS #RRGGBBAA 与 Android #AARRGGBB 混淆。

Font modifiers：
- 开启：@bold/@B、@italic/@I、@underline/@U、@strike/@strikethrough/@S、@dim。
- 关闭：@no-bold、@no-italic、@no-underline、@no-strike/@no-strikethrough、@no-dim。
- @dim 在 Android renderer 降低文本 alpha。
- font family/size、任意 opacity 与 background decoration 不是当前 modifier。

scopeRules 当前支持逗号 alternatives、分组和 | alternatives（可嵌套）、descendant、direct-child >、exclusion -、exact/prefix scope、* 与 .* wildcard；L:/R: 前缀会被剥离。不是完整 TextMate selector 引擎。显式 style 颜色优先于 scopeRules。

## 6. matching

~~~tsh
matching {
  caseSensitive: true
  multiline: false
  dotAll: false
  extended: false
  unicode: true
}
~~~

字段均为布尔值：
- caseSensitive：true 区分大小写，false 忽略大小写。
- multiline：控制行锚点行为。
- dotAll：控制点号是否匹配换行。
- extended：Oniguruma 扩展模式。
- unicode：必须 true，Native 固定 UTF-16LE。

单条 Regex flags：i/ignoreCase、m/dotAll、s/singlelineAnchors、x/extended。按 Oniguruma 语义使用。

## 7. defines 与 repository

~~~tsh
defines: [
  "identifier": /[A-Za-z_$][A-Za-z0-9_$]*/
  "docTags": /(?:@param|@return|@throws)\\b/
]
repository {
  identifier: /[A-Za-z_$][A-Za-z0-9_$]*/
  todo: /\\b(TODO|FIXME)\\b/
}
~~~

defines 通过 match: include("identifier") 或 builtin: identifier 引用；keywordsToRegex("if else return") 可展开关键字列表。repository 通过 include("#todo") 引用。两者都是 Regex 片段，不是 Query 或 pattern object。未知引用与循环引用失败。

## 8. ast：ATS selector 字段

~~~tsh
ast: [
  {
    id: "method-name"
    node: "method_declaration"
    field: "name"
    style: "function"
    priority: 120
    enabled: true
    overlap: deny
  }
]
~~~

| 字段 | 类型 | 语义 |
|---|---|---|
| id | string/identifier | 规则标识，建议唯一 |
| node | grammar node type | 目标节点类型 |
| field | grammar field name | 字段约束，通常与 node 配合 |
| parent | node type | 直接父节点 |
| ancestor | node type | 任意祖先；从目标节点 parent 开始，不含目标自身 |
| child | node type | 是否存在指定直接子节点 |
| previousSibling | node type | 前一个兄弟节点类型 |
| nextSibling | node type | 后一个兄弟节点类型 |
| childIndex | unsigned integer | 所有 child 的 0-based 索引 |
| namedChildIndex | unsigned integer | named children 的 0-based 索引 |
| firstChild / lastChild | boolean | 是否首/末 child |
| named / missing / extra / error / hasError | boolean | Tree-sitter 节点标记 |
| text | string | 节点文本完全相等 |
| textPrefix / textSuffix | string | 节点文本前缀/后缀 |
| textRegex | Regex literal/string | 节点 UTF-16 文本匹配 Oniguruma Regex |
| style | string/identifier | 输出 style 名 |
| priority | integer | 优先级 |
| enabled | boolean | 是否启用 |
| overlap | allow/deny | 是否允许 span 重叠 |

所有结构名称必须来自目标 grammar。field 不是 style。child 是直接子节点，不是任意后代。ancestor 不包含目标节点自身。sibling 按真实 Tree-sitter sibling 顺序判断。textRegex 在编译阶段编译，运行时针对节点文本执行。ATS 不接受 query/source。

## 9. treeSitter.queries

~~~tsh
treeSitter {
  queries: [
    {
      id: "java-functions"
      source: `
        (method_declaration
          name: (identifier) @method_name)
      `
      captures {
        method_name: function
      }
      priority: 100
      enabled: true
      patterns: [
        { index: 0, enabled: true, priority: 110, overlap: deny, order: 0 }
      ]
    }
  ]
}
~~~

Query block 字段：
- source：必需、非空、Tree-sitter Query 原文。
- id：可选，建议唯一。
- captures：可选，capture 名到 style 名映射。
- enabled：可选，默认 true。
- priority：可选，Query 默认优先级。
- patterns：可选，pattern index 元数据。

captures 左侧是不带 @ 的 capture 名，右侧是 style 名。映射必须指向 Query 中真实 capture。未映射的输出 capture 按同名 style 解析；找不到则失败。predicate-only capture 可不绑定。

patterns 项字段：
- index：必需，0-based Query pattern index。
- enabled：可选。
- priority：可选。
- overlap：allow/deny。
- order：显式声明顺序。

patterns 是 TSH 元数据，不是 Query 文法。启用 Query 必须提供 TSLanguage。Query 编译错误带 Tree-sitter source offset。未知 block 字段失败。

## 10. Tree-sitter Query predicates

Query source 使用 Tree-sitter Query 结构语法：node pattern、field constraint、child pattern、capture、匿名 token、alternation 与 predicate。Predicate 必须附着于对应结构 pattern。

当前 Native TSH 编译器显式支持：

| Predicate | 参数 | 语义 |
|---|---|---|
| #eq? | capture + string 或 capture | 文本相等 |
| #not-eq? | capture + string 或 capture | 文本不相等 |
| #any-eq? | capture + string 或 capture | 任一 capture 满足相等 |
| #any-not-eq? | capture + string 或 capture | 任一 capture 满足不相等 |
| #any-of? | capture + 1+ strings | 文本属于给定集合 |
| #not-any-of? | capture + 1+ strings | 文本不属于给定集合 |
| #match? | capture + regex string | 文本匹配 Regex |
| #not-match? | capture + regex string | 文本不匹配 Regex |
| #contains? | capture + 1+ strings | 文本包含任一子串 |
| #not-contains? | capture + 1+ strings | 不包含给定子串 |
| #any-contains? | capture + 1+ strings | 至少一个 capture 满足包含条件 |
| #any-not-contains? | capture + 1+ strings | 至少一个 capture 满足不包含条件 |
| #has-ancestor? | capture + 1+ node types | capture 节点存在指定祖先类型 |
| #not-has-ancestor? | capture + 1+ node types | 不存在指定祖先类型 |
| #any-has-ancestor? | capture + 1+ node types | 至少一个 capture 满足祖先条件 |
| #any-not-has-ancestor? | capture + 1+ node types | 至少一个 capture 不满足祖先条件 |
| #has-parent? | capture + 1+ node types | 直接父节点类型匹配 |
| #not-has-parent? | capture + 1+ node types | 直接父节点类型不匹配 |
| #any-has-parent? | capture + 1+ node types | 至少一个 capture 满足父节点条件 |
| #any-not-has-parent? | capture + 1+ node types | 至少一个 capture 不满足父节点条件 |
| #kind-eq? | capture + 1+ node types | 节点类型属于给定集合 |
| #not-kind-eq? | capture + 1+ node types | 节点类型不属于集合 |
| #any-kind-eq? | capture + 1+ node types | 至少一个 capture 节点类型属于集合 |
| #any-not-kind-eq? | capture + 1+ node types | 至少一个 capture 节点类型不属于集合 |

第一个参数必须为 capture。eq/match 是固定参数形式；除 equality 的 capture-to-capture 比较外，后续参数必须是字符串 literal。结构 predicate 的 node type 必须能在目标 grammar 中解析。Regex predicate 在编译阶段建立。

正确分组：

~~~scheme
(
  [(identifier) (type_identifier)] @name
  (#any-of? @name "Foo" "Bar")
)
~~~

不要写成 predicate 与 alternation 脱离的两个顶层 pattern；Native compiler 会拒绝可能导致过滤器失效的形式。

Tree-sitter 通用生态的所有 predicate/directive 并非都被 TSH 支持。特别是 #set!、#is?、#is-not? 不在当前 Native TSH 白名单。Java TSPredicateHandler 与 Native TSH Query predicate validator 是不同执行路径，不能互相推断。

## 11. contains：Oniguruma Regex

### 11.1 MATCH

~~~tsh
contains: [
  {
    id: "todo"
    match: /\\b(TODO|FIXME|XXX)\\b/
    style: constant
    priority: 120
    flags: [i]
    overlap: deny
  }
]
~~~

MATCH 支持 match（Regex/string/include/keywordsToRegex）、builtin（defines 兼容别名）、style/name、id、priority、enabled、overlap、flags、captures、contains/patterns。match 与 builtin 是同一槽位，不要重复声明。

### 11.2 BLOCK

~~~tsh
contains: [
  {
    id: "javadoc"
    style: docComment
    contentName: comment
    start: { match: /\\/\\*\\*/ }
    end: { match: /\\*\\// }
    startCaptures { 0: docTag }
    endCaptures { 0: docTag }
    contains: [
      { match: /\\{@[A-Za-z]+/, 0: annotation, priority: 140 }
    ]
  }
]
~~~

BLOCK 必须有 start 与 end；begin 是 start 兼容别名。start/end 可直接写 Regex，也可写 { match: /.../ }。style 是区域样式，contentName 是内部样式。startCaptures/beginCaptures 负责开始分隔符，endCaptures 负责结束分隔符。contains/patterns 递归声明内部规则。BLOCK 至少要有 region style、contentName 或 delimiter captures 之一。

### 11.3 捕获组

~~~tsh
{
  match: /([A-Z][A-Za-z0-9_]*)(\\s*)(=)/
  captures {
    1: type
    3: operator
  }
}
~~~

key 可为数字组索引或命名组名；0 表示整个匹配。captures 作用于主体，startCaptures/beginCaptures 作用于开始 delimiter，endCaptures 作用于结束 delimiter。重复 group 声明失败。

### 11.4 Regex flags

| Flag | 别名 | 语义 |
|---|---|---|
| i | ignoreCase | 忽略大小写 |
| m | dotAll | 点号匹配换行 |
| s | singlelineAnchors | 锚点只匹配整体输入 |
| x | extended | 扩展模式 |

Regex 以全局 matching 选项为基础再叠加 flags，Native 按 UTF-16LE 编译。

## 12. editor 元数据字段

~~~tsh
editor {
  tabSize: 4
  fontSize: 14
  lineHeight: 1.4
  letterSpacing: 0
  insertSpaces: true
  autoIndent: true
  trimTrailingWhitespace: true
  formatOnType: false
  formatOnPaste: false
  renderWhitespace: "selection"
  lineNumbers: true
  maxTokenizationLineLength: 20000
  comments {
    line: ["//"]
    block: [["/*", "*/"]]
  }
  brackets: ["{}", "[]", "()", "<>"]
  bracketNotIn: ["string", "comment"]
  autoClosingPairs: [
    { open: "(", close: ")", notIn: ["string", "comment"] }
  ]
  surroundingPairs: ["{}", "[]", "()", "\"\""]
  wordPattern: /[A-Za-z_$][A-Za-z0-9_$]*/
  autoCloseBefore: /[\\s)\\]};,]/
  indentation {
    increaseIndentPattern: /\\{[^}]*$/
    decreaseIndentPattern: /^\\s*\\}/
    indentNextLine: /=>\\s*$/
    unIndentedLinePattern: /^\\s*(\\/\\/|\\*)/
  }
  completion { triggerCharacters: [".", "@", ":"] }
  electricCharacters: ["}", ";"]
  onEnterRules: [
    {
      beforeText: /\\{[^}]*$/
      afterText: /^\\s*\\}/
      action: { indent: "indentOutdent", appendText: "", removeText: 0 }
    }
  ]
}
~~~

tabSize/maxTokenizationLineLength 为正整数；fontSize/lineHeight 为正数；letterSpacing 为有限数。布尔项必须 true/false。renderWhitespace 仅 none/boundary/selection/all。autoClosingPairs 项必须有非空 open/close，可选 notIn。indentation 的值必须是可编译 Regex。completion 当前仅接受 triggerCharacters。onEnterRules 必须有 beforeText Regex 与 action.indent；afterText/previousLineText 可选 Regex；indent 仅 none/indent/indentOutdent/outdent；appendText 为字符串，removeText 为非负整数。宿主必须显式执行这些编辑行为。

## 13. 常见错误

| 错误 | 正确做法 |
|---|---|
| 使用未知根字段 | 删除或先实现对应 parser |
| 使用 rules.ats / rules.regex | 改用 ast/treeSitter/contains |
| 在 ast 中写 query | 放入 treeSitter.queries[].source |
| grammar 节点/field 拼错 | 对照目标 TSLanguage |
| Query capture 没 style | 显式 captures 映射或声明同名 style |
| 使用未支持 predicate | 只使用本章白名单 |
| predicate 与 pattern 脱离 | 将结构 pattern 与 predicate 放入同一组 |
| include 不存在/循环 | 修正 defines/repository |
| BLOCK 缺 start/end | 补齐起止规则 |
| overlap 写 exclusive/contained | 只用 allow/deny |
| scheme extends 循环 | 修正继承链 |
| 期望缺色自动补黑/白 | 显式声明颜色 |
| 用 Java predicate handler 推断 Native 白名单 | 以 Native compiler 校验器为准 |

## 14. 推荐骨架

~~~tsh
tsh 2 {
  language { id: "sample" name: "Sample" extensions: [".sample"] }
  matching {
    caseSensitive: true
    multiline: false
    dotAll: false
    extended: false
    unicode: true
  }
  styles {
    keyword { scope: "keyword.control", tags: ["keyword"] }
    type { scope: "entity.name.type", tags: ["type"] }
    function { scope: "entity.name.function", tags: ["function"] }
    string { scope: "string.quoted", tags: ["string"] }
    comment { scope: "comment", tags: ["comment"] }
  }
  schemes {
    default {
      light [ keyword: #0000FF @bold type: #267F99 function: #795E26 string: #A31515 comment: #008000 ]
      dark [ keyword: #CC7832 @bold type: #4EC9B0 function: #FFC66D string: #6A8759 comment: #808080 ]
    }
  }
  ast: [
    { node: "class_declaration", field: "name", style: type, priority: 100 }
  ]
  treeSitter {
    queries: [
      {
        id: "keywords"
        source: `
          ["if" "else" "return"] @keyword
        `
        captures { keyword: keyword }
      }
    ]
  }
  contains: [
    { id: "todo", match: /\\b(TODO|FIXME)\\b/, style: keyword, priority: 120 }
  ]
}
~~~

按需移除不用的 ATS、Query 或 Regex 区块；不要为了模板完整而无意义启用所有管线。
