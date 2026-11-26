# SCM → TSH DSL v2 迁移指南

**核心结论：SCM 本身就是 Tree-sitter Query 语言。最稳妥的迁移是将 SCM Query 原文内嵌到 TSH 的 treeSitter.queries[].source，再把 capture 名绑定到 TSH semantic style。不要把 SCM 全部翻译成 ATS，也不要生成新的 .scm。**

## 1. 先识别 SCM 文件职责

- highlights.scm：节点/匿名 token Query 与 capture，通常可迁移到 TSH 高亮 Query。
- locals.scm：局部变量作用域/定义/引用元数据，不等于纯高亮 Query。
- tags.scm：定义/引用标签提取，不属于 TSH 高亮职责。
- injections.scm：嵌入语言注入元数据；当前 TSH 不实现 injection runtime。
- 自定义 .scm：先确认实际消费者，不要只按扩展名推断用途。

TSH 当前只承接高亮相关的 ATS、原生 Tree-sitter Query 与 Oniguruma Regex。不要把 locals/tags/folding/outline/injection 功能伪装成高亮规则。

## 2. 最简单迁移：SCM 原文嵌入

原始 highlights-java.scm：

~~~scheme
(method_declaration
  name: (identifier) @function.method)

(class_declaration
  name: (identifier) @type)

(string_literal) @string
~~~

TSH：

~~~tsh
tsh 2 {
  language {
    id: "java"
    name: "Java"
    extensions: [".java"]
  }
  styles {
    function { scope: "entity.name.function", tags: ["function"] }
    type { scope: "entity.name.type", tags: ["type"] }
    string { scope: "string.quoted", tags: ["string"] }
  }
  schemes {
    default {
      light [ function: #795E26 type: #267F99 string: #A31515 ]
      dark [ function: #FFC66D type: #4EC9B0 string: #6A8759 ]
    }
  }
  treeSitter {
    queries: [
      {
        id: "java-highlights"
        source: `
          (method_declaration
            name: (identifier) @function_method)

          (class_declaration
            name: (identifier) @type)

          (string_literal) @string
        `
        captures: {
          function_method: function
          type: type
          string: string
        }
      }
    ]
  }
}
~~~

建议把含点号的 capture 名（如 @function.method）改成稳定的简单 capture 名（如 @function_method），再通过 captures 映射到 style。避免 DSL capture map 的 key 词法歧义。

## 3. SCM → TSH 概念映射

| SCM 概念 | TSH 写法 | 说明 |
|---|---|---|
| (node) | treeSitter.queries[].source 原样保留 | ATS 只能人工重写简单条件 |
| field: (child) | Query 原样保留；或 ATS node + field | 仅简单单节点字段条件可考虑 |
| (node) @capture | Query 原样保留 + captures binding | capture 是输出名，不是颜色 |
| "anonymous_token" @capture | Query 原样保留；或 ATS 匿名节点选择 | 需真实 grammar symbol |
| [...] alternation | Query source 原样保留 | ATS 无通用等价表达 |
| (#eq? ...) | Query 原样保留，且在 TSH 白名单内 | 不要手工拆成字符串规则 |
| (#match? ...) | Query 原样保留，且在 TSH 白名单内 | 或单节点 textRegex ATS |
| (#any-of? ...) | Query 原样保留，且在 TSH 白名单内 |  |
| #set! metadata directive | 当前 Native TSH 不接受 | 不要直接复制 |
| capture 名称 | captures: { name: semanticStyle } | 显式绑定 |
| highlights.scm | treeSitter.queries[].source | Query 原文嵌入 |
| TextMate Regex match | contains 的 MATCH rule | 重新表达 Regex |
| TextMate begin/end | contains 的 BLOCK start/end | 只支持当前 BLOCK 子集 |
| repository Regex fragment | defines 或 repository | 不是 pattern object |
| injections.scm | 当前无对应 TSH 可运行语法 | 不可伪迁移 |
| locals.scm / tags.scm | 当前无对应 TSH 高亮语义 | 属于其他功能 |

## 4. 何时保留 Query，何时改写 ATS

### 4.1 默认保留 Query

以下情况应保留 SCM 原文：
- 多层嵌套节点模式；
- field pattern、匿名 token、alternation；
- capture 与 predicate；
- 多个结构约束组合；
- 希望跟随上游 grammar 的 highlights.scm 更新。

操作：原样放入 source 字符串，建立 capture → style 映射。

### 4.2 可以改写 ATS 的情况

规则能清楚表达为一个候选节点的有限结构条件时，可考虑 ATS：
- 节点类型、field；
- 直接 parent、ancestor、直接 child；
- previous/next sibling；
- childIndex/namedChildIndex；
- named/missing/extra/error/hasError；
- text/textPrefix/textSuffix/textRegex。

例如 SCM：

~~~scheme
(class_declaration
  name: (identifier) @type)
~~~

ATS 形式：

~~~tsh
ast: [
  { node: "class_declaration", field: "name", style: type, priority: 100 }
]
~~~

注意：ATS 的 target node 与 field 组合语义必须按当前 compiler/runtime 及目标 grammar 验证。对于不确定的 field/capture 语义，保留 Query 更安全。复杂 Query 不应为了减少 Query 数量而强行转换 ATS。

### 4.3 不要把 Query 转成 Regex

(method_declaration name: (identifier) @function) 表达语法树上的声明节点与 name field 关系。Regex 只看到字符流，无法可靠地区分注释、字符串、语法错误与真实声明。使用 Regex 模拟会产生误匹配并丢失 grammar 结构信息。

## 5. Predicate 迁移

SCM predicate 附着于一个 Query pattern，并在完整 match 上运行。

文本匹配：

~~~scheme
((identifier) @constant
 (#match? @constant "^[A-Z][A-Z0-9_]+$"))
~~~

直接嵌入：

~~~tsh
treeSitter {
  queries: [
    {
      id: "constants"
      source: `
        ((identifier) @constant
         (#match? @constant "^[A-Z][A-Z0-9_]+$"))
      `
      captures: { constant: constant }
    }
  ]
}
~~~

capture 与 capture 比较：

~~~scheme
((assignment_expression
   left: (identifier) @left
   right: (identifier) @right)
 (#eq? @left @right))
~~~

该形式保留在 source 中；ATS 当前没有通用的跨两个 capture 节点比较操作。

alternation predicate 必须和结构 pattern 放在同一组：

~~~scheme
(
  [(identifier) (type_identifier)] @name
  (#any-of? @name "Foo" "Bar")
)
~~~

不要拆成两个顶层 pattern，否则结构模式可能在无 predicate 过滤时独立输出。TSH 会拒绝这类可能削弱过滤语义的写法。

迁移前逐条对照 [TSH DSL v2 语法参考](TSH_DSL_V2_SYNTAX.md) 中的 Native predicate 白名单。Tree-sitter CLI、其他编辑器插件支持的所有 predicates/directives 不代表 TSH 支持。#set! 不能当作 capture-to-style 映射；TSH 使用 captures block。

## 6. Capture 名与 Style 名绑定

SCM capture 是 Query 结果标签，不是 TSH 颜色。TSH 必须把 capture 映射到语义 style。

~~~scheme
(method_declaration name: (identifier) @function)
~~~

~~~tsh
styles {
  function { scope: "entity.name.function", tags: ["function"] }
}
treeSitter {
  queries: [
    {
      source: `
        (method_declaration name: (identifier) @function)
      `
      captures: { function: function }
    }
  ]
}
~~~

建议 capture 名与 style 名保持同名。若旧 SCM 使用 @function.method、@variable.parameter 等 scope 风格名称，建议改成 @method_name、@parameter，再映射到 style。这样减少 capture map key 的词法歧义。

## 7. TextMate Regex grammar → contains

SCM Query 与 TextMate Regex grammar 不是同一种语言。若旧语言包另有 JSON/TextMate grammar：

| TextMate 概念 | TSH Regex |
|---|---|
| match | match |
| begin | start |
| end | end |
| beginCaptures | startCaptures / beginCaptures |
| endCaptures | endCaptures |
| name | style |
| contentName | contentName |
| nested patterns | contains |
| reusable Regex fragment | defines / repository |

示例：

~~~tsh
styles {
  docComment { scope: "comment.block.documentation" }
  docTag { scope: "storage.type.annotation" }
}
contains: [
  {
    style: docComment
    start: /\/\*\*/
    end: /\*\//
    contains: [
      { match: /@\w+/, 0: docTag }
    ]
  }
]
~~~

只覆盖当前 TSH BLOCK 子集。TextMate while、applyEndPatternLast、external include、injection selector、完整 precedence 与 pattern-object repository 不可直接照搬。

## 8. 仓库 Java highlights.scm 迁移建议

当前测试夹具 android-tree-sitter/src/test/resources/highlights-java.scm 包含：

| SCM 内容 | 推荐迁移 |
|---|---|
| method_declaration/method_invocation name | Query 原样保留；简单字段场景可验证后改 ATS |
| annotation/marker_annotation | Query 保留；annotation name 可 ATS |
| "@" 匿名 token | Query 保留；或 ATS anonymous node + parent |
| type_identifier/scoped_type_identifier | ATS 或 Query |
| class/interface/enum declaration name | ATS field selector 或 Query |
| field_access/scoped_identifier/method_reference receiver | 推荐 Query |
| #match? 大写标识符 | Query predicate；单节点也可评估 ATS textRegex |
| number/string/character/comment nodes | ATS 或 Query，优先 grammar node |
| true/false/null literal | Query/ATS |
| keyword anonymous token 集合 | Query alternation；ATS 逐项声明维护成本更高 |

示例：

~~~scheme
((field_access
  object: (identifier) @type)
 (#match? @type "^[A-Z]"))
~~~

~~~tsh
treeSitter {
  queries: [
    {
      id: "java-type-receiver"
      source: `
        ((field_access
          object: (identifier) @type)
         (#match? @type "^[A-Z]"))
      `
      captures: { type: type }
    }
  ]
}
~~~

## 9. 批量迁移流程

1. 分类每个 SCM：highlights、locals、tags、injections 或自定义。
2. 找到配套 grammar binary/TSLanguage 版本。
3. 先将 Query 原文嵌入 source，不要先做 ATS 改写。
4. 提取所有 @capture，建立 capture → semantic style 对照表。
5. 声明 styles，并按需声明 light/dark palette。
6. 逐条审查 predicates，与 Native TSH 白名单核对。
7. 将 TextMate Regex 规则单独迁移到 contains。
8. 按功能拆分 Query block，配置稳定 id 与 priority。
9. 用实际 TSLanguage 调用 Native compiler；Parser 接受不代表 Query 编译成功。
10. 对照旧 SCM 与新 TSH capture 的范围、capture 名、style 与 predicate 结果。
11. 确认应用不再运行时读取外部 SCM 后，才移除旧资源。
12. 验证 light/dark、alpha、重叠、嵌套 capture 与大文本 viewport 的最终 Canvas。

## 10. 禁止的迁移错误

- 不要把 SCM Query 放进 ast。
- 不要把 ATS selector 伪装成 Query。
- 不要把 Query 结构条件粗暴转换为全局 Regex。
- 不要把 capture 名当作颜色值或固定 style ID。
- 不要假定所有 Tree-sitter predicates 在 TSH 可用。
- 不要把 #set! 当作 style binding。
- 不要把 locals/tags/injections 当作普通高亮规则。
- 不要把 Monarch 或 TextMate 未实现能力写进生产 .tsh。

## 11. 迁移完成定义

迁移完成需满足：高亮职责已由 TSH ATS/Query/Regex 覆盖；capture 范围和语义映射已核对；所有 predicates 在 Native 支持范围内；Query 与目标 grammar ABI/节点表一致；运行时不再隐式依赖外部 SCM；light/dark、alpha、overlap 与 renderer 输出已验证。文档写完不等于编译、测试或设备验证完成。
