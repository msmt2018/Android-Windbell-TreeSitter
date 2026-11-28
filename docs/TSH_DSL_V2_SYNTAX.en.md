# TSH DSL v2 Syntax Reference

This reference is for language-package authors who write `.tsh` files. It describes only syntax and semantics actually parsed by the current Native WindbellTshCompiler. Capabilities from the broader Tree-sitter, Oniguruma, or TextMate ecosystems must not be assumed to be TSH capabilities.

- [TSH DSL v2 Specification](TSH_DSL_V2_SPEC.en.md)
- [SCM-to-TSH DSL v2 Migration Guide](TSH_SCM_TO_TSH_MIGRATION.en.md)

## 1. Lexical and General Rules

- New files use `tsh 2 { ... }`; the legacy root-object form `{ ... }` remains compatible.
- Supported forms include identifiers, single/double-quoted strings, raw backtick strings, Regex literals, colors, objects, and arrays.
- Both `//` line comments and `/* */` block comments are supported.
- Ordinary quoted strings cannot contain unescaped newlines; escaping follows JSON-style rules.
- Backtick strings are recommended for Query source.
- Regex properties use `/…/` or a string form explicitly accepted by the implementation.
- Commas in objects/arrays may be omitted, but retaining them is recommended to reduce ambiguous diffs.
- Unterminated strings/comments/Regex, unknown punctuation, and invalid escapes are errors; they are not silently skipped.

## 2. Root Declarations

| Declaration | Form | Purpose |
|---|---|---|
| `language` | object | Language identity, extensions, MIME types, first-line detection |
| `name` | array | Legacy language-name/extension compatibility |
| `ignoreCase` | Boolean | Legacy Regex case-insensitivity switch |
| `matching` | object | Global Regex options |
| `editor` | object | Host-editor metadata |
| `styles` | named object | Semantic style catalog |
| `schemes` | named object | Named themes |
| `defaultScheme` | string/identifier | Default theme |
| `isDark` | Boolean | Initial appearance flag |
| `defines` | key-pattern array | Regex macro fragments |
| `repository` | named block/map | Reusable Regex fragments |
| `treeSitter` | object | Native Query collection |
| `ast` | object array | ATS selectors |
| `contains` | object array | Oniguruma MATCH/BLOCK rules |

Root blocks cannot be repeated. Unknown root fields fail compilation. Do not use unimplemented root blocks such as `rules`, `monarch`, or `injections`.

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

`id/name` accept a string or identifier. `extensions/aliases/mimeTypes` are string arrays. `firstLine` is an Oniguruma Regex. Extensions have leading dots removed and are deduplicated. The grammar binary/TSLanguage must correspond to the language ID and Query node table.

Legacy `name: ["Java", ".java"]` and `ignoreCase: false` remain readable for compatibility. New files should prefer `language` and `matching`.

## 4. styles

~~~tsh
styles {
  keyword { scope: "keyword.control", tags: ["keyword", "control"] }
  type { scope: "entity.name.type", tags: ["type"] }
  function { scope: "entity.name.function", tags: ["function"] }
  comment { scope: "comment", tags: ["comment"] }
}
~~~

Fields: `scope`, `tags`, `description`, `deprecated`, `parent`, and `metadata`. A missing parent or inheritance cycle fails. Missing light/dark palette values of a child style may inherit from its parent.

Binding forms:

- ATS: `style: "type"`.
- Regex: `style: "comment"` or `captures: { 1: keyword }`.
- Native Query: `captures: { capture_name: style_name }`.
- If a Query capture is not explicitly mapped, the compiler looks for a style with the same name.
- Captures used only as predicate operands do not need output-style bindings.

## 5. schemes, Colors, and scopeRules

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

Theme fields: `light`, `dark`, `ui`, `extends`, and `scopeRules`. `extends` references another scheme; an unknown parent or inheritance cycle fails. Light/dark are style-to-color maps. UI tokens are arbitrary dot-separated names consumed by the host. A missing color means no override.

Color formats:

- `#RGB`: expand RGB channels to 8 bits; alpha is FF.
- `#RGBA`: expand RGBA channels to 8 bits; alpha is last.
- `#RRGGBB`: RGB with alpha FF.
- `#AARRGGBB`: Android ARGB.

Internally colors are normalized to AARRGGBB. Do not confuse CSS `#RRGGBBAA` with Android `#AARRGGBB`.

Font modifiers:

- Enable: `@bold/@B`, `@italic/@I`, `@underline/@U`, `@strike/@strikethrough/@S`, `@dim`.
- Disable: `@no-bold`, `@no-italic`, `@no-underline`, `@no-strike/@no-strikethrough`, `@no-dim`.
- `@dim` lowers text alpha in the Android renderer.
- Font family/size, arbitrary opacity, and background decoration are not current modifiers.

Current `scopeRules` support comma alternatives, groups and nested `|` alternatives, descendant, direct-child `>`, exclusion `-`, exact/prefix scopes, and `*`/ `.*` wildcards. `L:/R:` prefixes are stripped. This is not a complete TextMate selector engine. Explicit style colors take precedence over scopeRules.

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

All fields are Boolean:

- `caseSensitive`: true is case-sensitive; false ignores case.
- `multiline`: controls line-anchor behavior.
- `dotAll`: controls whether dot matches newlines.
- `extended`: enables Oniguruma extended mode.
- `unicode`: must be true; Native uses UTF-16LE.

Per-Regex flags: `i/ignoreCase`, `m/dotAll`, `s/singlelineAnchors`, and `x/extended`. Follow Oniguruma semantics.

## 7. defines and repository

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

Reference `defines` with `match: include("identifier")` or `builtin: identifier`. `keywordsToRegex("if else return")` expands a space-separated keyword list. Reference repository fragments with `include("#todo")`. Both are Regex fragments, not Query or pattern objects. Unknown and cyclic references fail.

## 8. ast: ATS Selector Fields

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

| Field | Type | Meaning |
|---|---|---|
| `id` | string/identifier | Rule identifier; uniqueness recommended |
| `node` | grammar node type | Target node type |
| `field` | grammar field name | Field constraint, usually paired with node |
| `parent` | node type | Direct parent |
| `ancestor` | node type | Any ancestor, starting at target.parent; excludes target itself |
| `child` | node type | Existence of a specified direct child |
| `previousSibling` | node type | Previous sibling type |
| `nextSibling` | node type | Next sibling type |
| `childIndex` | unsigned integer | Zero-based index among all children |
| `namedChildIndex` | unsigned integer | Zero-based index among named children |
| `firstChild/lastChild` | Boolean | Whether first/last child |
| `named/missing/extra/error/hasError` | Boolean | Tree-sitter node flags |
| `text` | string | Exact node-text equality |
| `textPrefix/textSuffix` | string | Node-text prefix/suffix |
| `textRegex` | Regex literal/string | Oniguruma Regex against node UTF-16 text |
| `style` | string/identifier | Output style name |
| `priority` | integer | Priority |
| `enabled` | Boolean | Whether enabled |
| `overlap` | allow/deny | Whether span overlap is allowed |

All structural names must exist in the target grammar. A field is not a style. **When both node and field are declared, node identifies the field-owner (parent) node and field selects/highlights its child field. Without field, the rule applies to node itself.** child means a direct child, not any descendant. ancestor excludes the target node. Siblings follow actual Tree-sitter sibling order. `textRegex` is compiled during configuration compilation and runs against node text. ATS does not accept `query/source`.

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
      captures: { method_name: function }
      priority: 100
      enabled: true
      patterns: [
        { index: 0, enabled: true, priority: 110, overlap: deny, order: 0 }
      ]
    }
  ]
}
~~~

Query block fields:

- `source`: required, non-empty native Tree-sitter Query source.
- `id`: optional; uniqueness recommended.
- `captures`: optional capture-name-to-style-name map.
- `enabled`: optional; defaults to true.
- `priority`: optional default Query priority.
- `patterns`: optional metadata indexed by Query pattern.

The left side of `captures` is the capture name without `@`; the right side is a style name. Mappings must refer to captures that actually exist in the Query. Unmapped output captures resolve by same-name style; failure to find one is an error. Predicate-only captures need no output binding.

Pattern metadata fields:

- `index`: required zero-based Query pattern index.
- `enabled`: optional.
- `priority`: optional.
- `overlap`: `allow/deny`.
- `order`: explicit declaration order.

`patterns` is TSH metadata, not Query syntax. An enabled Query requires a TSLanguage. Query compilation errors include Tree-sitter source offsets. Unknown block fields fail.

## 10. Tree-sitter Query Predicates

Query source uses Tree-sitter Query structure syntax: node patterns, field constraints, child patterns, captures, anonymous tokens, alternations, and predicates. A predicate must be attached to its corresponding structural pattern.

The current Native TSH compiler explicitly supports:

| Predicate | Arguments | Meaning |
|---|---|---|
| `#eq?` | capture + string or capture | Text equality |
| `#not-eq?` | capture + string or capture | Text inequality |
| `#any-eq?` | capture + string or capture | At least one capture/pair is equal |
| `#any-not-eq?` | capture + string or capture | At least one capture/pair is not equal |
| `#any-of?` | capture + 1+ strings | Every value of that capture belongs to the set |
| `#not-any-of?` | capture + 1+ strings | No value of that capture belongs to the set |
| `#match?` | capture + Regex string | Text matches Regex |
| `#not-match?` | capture + Regex string | Text does not match Regex |
| `#contains?` | capture + 1+ strings | Text contains any supplied substring |
| `#not-contains?` | capture + 1+ strings | Text contains none of the supplied substrings |
| `#any-contains?` | capture + 1+ strings | At least one capture satisfies contains |
| `#any-not-contains?` | capture + 1+ strings | At least one capture satisfies not-contains |
| `#has-ancestor?` | capture + 1+ node types | Capture node has a specified ancestor |
| `#not-has-ancestor?` | capture + 1+ node types | Capture node has none of the specified ancestors |
| `#any-has-ancestor?` | capture + 1+ node types | At least one capture satisfies ancestor condition |
| `#any-not-has-ancestor?` | capture + 1+ node types | At least one capture does not satisfy ancestor condition |
| `#has-parent?` | capture + 1+ node types | Direct parent type matches |
| `#not-has-parent?` | capture + 1+ node types | Direct parent type does not match |
| `#any-has-parent?` | capture + 1+ node types | At least one capture satisfies parent condition |
| `#any-not-has-parent?` | capture + 1+ node types | At least one capture does not satisfy parent condition |
| `#kind-eq?` | capture + 1+ node types | Node type belongs to the supplied set |
| `#not-kind-eq?` | capture + 1+ node types | Node type does not belong to the set |
| `#any-kind-eq?` | capture + 1+ node types | At least one capture node type belongs to the set |
| `#any-not-kind-eq?` | capture + 1+ node types | At least one capture node type does not belong to the set |

The first argument must be a capture. `eq/match` use fixed argument forms. Except for capture-to-capture equality, subsequent arguments must be string literals. Structural predicate node types must resolve in the target grammar. Regex predicates are compiled during compilation.

Ordinary eq/not-eq, contains, and structural predicates use all semantics for multiple existing captures; any-* variants require at least one match. The implementation of any-of? requires every captured value to belong to the supplied set; not-any-of? requires none to belong. For string eq?, a missing capture makes eq? false and not-eq? true; any-eq? and any-not-eq? follow the same missing-value polarity. For capture-to-capture equality, a missing left capture passes the ordinary form under empty-set semantics; an existing left capture with a missing right capture fails. The any form requires both sides and at least one matching pair. Missing target captures for contains/structural predicates use vacuous-true semantics.

Correct grouping:

~~~scheme
(
  [(identifier) (type_identifier)] @name
  (#any-of? @name "Foo" "Bar")
)
~~~

Do not separate the predicate and alternation into unrelated top-level patterns. The Native compiler rejects forms that could disable filtering.

Not every predicate/directive in the broader Tree-sitter ecosystem is supported. In particular, `#set!`, `#is?`, and `#is-not?` are not on the current Native TSH allowlist. Java TSPredicateHandler and the Native TSH Query predicate validator are separate execution paths; do not infer support in one from the other.

## 11. contains: Oniguruma Regex

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

MATCH supports `match` (Regex/string/include/keywordsToRegex), `builtin` (compatibility alias for defines), `style/name`, `id`, `priority`, `enabled`, `overlap`, `flags`, `captures`, and `contains/patterns`. `match` and `builtin` occupy the same slot; do not declare both.

### 11.2 BLOCK

~~~tsh
contains: [
  {
    id: "javadoc"
    style: docComment
    contentName: comment
    start: { match: /\\/\\*\\*/ }
    end: { match: /\\*\\// }
    startCaptures: { 0: docTag }
    endCaptures: { 0: docTag }
    contains: [
      { match: /\\{@[A-Za-z]+/, 0: annotation, priority: 140 }
    ]
  }
]
~~~

BLOCK requires both `start` and `end`; `begin` is a compatibility alias for start. start/end may be Regex literals or `{ match: /.../ }`. `style` styles the region; `contentName` styles its content. `startCaptures/beginCaptures` apply to the opening delimiter; `endCaptures` apply to the closing delimiter. `contains/patterns` recursively declares nested rules. A BLOCK must provide at least one of region style, contentName, or delimiter captures.

### 11.3 Capture Groups

~~~tsh
{
  match: /([A-Z][A-Za-z0-9_]*)(\\s*)(=)/
  captures: {
    1: type
    3: operator
  }
}
~~~

Keys may be numeric group indexes or named group names; `0` means the entire match. `captures` applies to the body, `startCaptures/beginCaptures` to the opening delimiter, and `endCaptures` to the closing delimiter. Duplicate group declarations fail.

### 11.4 Regex Flags

| Flag | Alias | Meaning |
|---|---|---|
| `i` | ignoreCase | Ignore case |
| `m` | dotAll | Dot matches newline |
| `s` | singlelineAnchors | Anchors match only the whole input |
| `x` | extended | Extended mode |

Regex compilation starts with global `matching` options and then applies per-rule flags. Native compiles using UTF-16LE.

## 12. editor Metadata Fields

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

`tabSize/maxTokenizationLineLength` must be positive integers; `fontSize/lineHeight` positive numbers; `letterSpacing` a finite number. Boolean fields must be true/false. `renderWhitespace` accepts only none/boundary/selection/all. Each autoClosingPairs item requires non-empty open/close and may include notIn. Indentation values must be compilable Regex. Completion currently accepts only triggerCharacters. Each onEnterRules item requires a beforeText Regex and action.indent; afterText/previousLineText are optional Regex. indent accepts only none/indent/indentOutdent/outdent; appendText is a string and removeText a non-negative integer. The host must explicitly execute these editing behaviors.

## 13. Common Errors

| Error | Correct approach |
|---|---|
| Unknown root field | Remove it or implement the corresponding parser first |
| `rules.ats / rules.regex` | Use `ast/treeSitter/contains` |
| Query written inside `ast` | Put it in `treeSitter.queries[].source` |
| Misspelled grammar node/field | Check the target TSLanguage |
| Query capture has no style | Add an explicit captures mapping or same-name style |
| Unsupported predicate | Use only the allowlist in this reference |
| Predicate detached from pattern | Put structural pattern and predicate in the same group |
| Missing/cyclic include | Fix defines/repository references |
| BLOCK missing start/end | Provide both delimiters |
| overlap set to exclusive/contained | Use only allow/deny |
| Scheme extends cycle | Fix the inheritance chain |
| Expecting missing colors to become black/white | Declare colors explicitly |
| Inferring Native allowlist from Java predicate handler | Use the Native compiler validator |

## 14. Recommended Skeleton

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
        captures: { keyword: keyword }
      }
    ]
  }
  contains: [
    { id: "todo", match: /\\b(TODO|FIXME)\\b/, style: keyword, priority: 120 }
  ]
}
~~~

Remove unused ATS, Query, or Regex sections as needed. Do not enable every pipeline merely to make the template look complete.
