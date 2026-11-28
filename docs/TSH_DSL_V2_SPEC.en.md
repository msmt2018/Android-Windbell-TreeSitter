# TSH DSL v2 Specification

> Subject: Windbell Tree-sitter Highlighting (TSH) `.tsh` configuration  
> Version: 2  
> Implementation baseline: the current Native WindbellTshCompiler, WindbellTshCore, and Java/Kotlin TSH bindings.

## 1. Design Goals and Hard Boundaries

TSH is a language-highlighting configuration and runtime. Language-specific node names, keywords, regular expressions, style names, colors, themes, and rule priorities belong in `.tsh`. The Native core implements generic parsing, matching, capture/span arbitration, and render-data transport; it must not contain language-specific color or keyword tables.

Mandatory rules:

1. Configuration is the source of truth for language behavior and theme data.
2. There are exactly two matching pipelines: ATS (Tree-sitter AST Selector) and Oniguruma Regex. They execute independently and their results are normalized to captures/spans before arbitration.
3. ATS is not Tree-sitter Query. ATS is TSH's structural node selector; native Query is the Tree-sitter Query language itself.
4. Query source is embedded in `treeSitter.queries[].source` and passed directly to `ts_query_new` for the target TSLanguage. TSH does not generate, load, or depend on external `.scm` files.
5. Regex does not replace syntax-tree structure analysis. Prefer ATS/Query for semantics accurately expressed by grammar nodes, fields, and parent/child/sibling relationships.
6. Rules use semantic style names. Numeric style IDs are compiler outputs; do not hard-code them or assign fixed meanings.
7. Missing foreground/background means “do not override.” Never synthesize black, white, or another visible color.
8. Unknown root fields/properties, duplicate declarations, invalid style/capture bindings, invalid Regex/Query, and invalid grammar node/field names must fail explicitly, not be silently ignored.
9. TSH does not implement Outline, Folding, locals, tags, or embedded-language injection pipelines.

## 2. File Envelope and Minimal Structure

New files use a versioned envelope:

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

The Native compiler retains the legacy root-object form (starting with `{`, without `tsh 2`) for compatibility. New configurations must use the versioned envelope. The only accepted version is integer `2`.

### 2.1 Required and Conditionally Required Declarations

| Declaration | Requirement | Notes |
|---|---|---|
| `tsh 2` envelope | Required for new files | Version must be 2 |
| `language.id/name` | Strongly recommended | Stable language ID and display name |
| `language.extensions` | Required for extension-based detection | e.g. `.java`; normalized and deduplicated |
| `styles` | Required for visible captures | Define semantic styles |
| `schemes` | Required when theme colors are needed | May define multiple themes and light/dark palettes |
| `ast` | Required when ATS is used | May be empty, but then emits no ATS captures |
| `treeSitter.queries` | Required when native Query is used | Query execution must bind to a target grammar |
| `contains` | Required when Regex is used | MATCH or BLOCK rules |
| `defines/repository` | Optional | Reusable Regex fragments |
| `editor` | Optional | Host-editor metadata |
| `matching` | Optional | Global Regex options |

ATS-only, Regex-only, and Hybrid configurations are all valid. A file does not need to enable both pipelines.

## 3. The Two Pipelines and Configuration Areas

~~~text
.tsh
 ├─ language / editor / matching
 ├─ styles / schemes
 ├─ defines / repository
 ├─ ast                         ATS selector pipeline
 ├─ treeSitter.queries          native Tree-sitter Query
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

Native Tree-sitter Query is a Query execution entry on the ATS/Tree-sitter side, not a third pipeline. ATS selectors and native Query may be used together; Regex executes independently.

### 3.1 `ast`: ATS Structural Selectors

ATS selects nodes using grammar node types, fields, parent/ancestor/child/sibling relationships, node flags, and node-text conditions. It is suitable for declaration names, type nodes, annotations, literals, comments, and structural context. ATS does not accept `query` or `source` properties.

### 3.2 `treeSitter.queries`: Native Query

~~~tsh
treeSitter {
  queries: [
    {
      id: "java-declarations"
      source: `
        (class_declaration name: (identifier) @class_name)
        (method_declaration name: (identifier) @method_name)
      `
      captures: {
        class_name: type
        method_name: function
      }
      priority: 100
      enabled: true
    }
  ]
}
~~~

`source` is native Tree-sitter Query text. Every output capture must bind to a style. If not explicitly mapped, the compiler tries the capture name as a style name. Captures used only as predicate operands do not need output-style bindings. An enabled Query requires a target TSLanguage; node names, fields, Query syntax, and predicates are validated against that grammar.

A Query block supports `id`, `source`, `captures`, `enabled`, `priority`, and `patterns`. Pattern metadata may specify `enabled`, `priority`, `overlap`, and `order` by zero-based pattern index. This is TSH metadata, not Query syntax. Disabled Queries must not declare pattern metadata.

### 3.3 `contains`: Oniguruma Regex

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

MATCH uses `match` (compatibility alias: `builtin`). BLOCK uses `start/end` (compatibility alias: `begin` for start). BLOCK may use `style`, `contentName`, `startCaptures/beginCaptures`, `endCaptures`, and nested `contains/patterns`.

### 3.4 Composition Strategies

- **ATS-only:** `ast` and/or `treeSitter.queries`; omit `contains`.
- **Regex-only:** declare `contains`; omit ATS/Query rules.
- **Hybrid:** declare ATS/Query and Regex; they execute independently.
- The unified `rules { ats { ... } regex { ... } }` facade is not currently supported.
- Do not introduce Monarch or TextMate tokenizers as a third pipeline.

## 4. `language` and `editor`

`language` accepts `id`, `name`, `extensions`, `aliases`, `mimeTypes` (compatibility spelling: `mimetypes`), and `firstLine` (Oniguruma Regex). Extensions may include a leading dot; they are normalized and deduplicated. Legacy root fields `name: [...]` and `ignoreCase: true/false` are compatibility-only.

Current `editor` fields include:

- Basic options: `tabSize`, `fontSize`, `lineHeight`, `letterSpacing`, `insertSpaces`, `autoIndent`, `trimTrailingWhitespace`, `formatOnType`, `formatOnPaste`, `renderWhitespace`, `lineNumbers`, `maxTokenizationLineLength`.
- `comments.line/block`, `brackets`, `bracketNotIn`, `autoClosingPairs`, `surroundingPairs`.
- `wordPattern`, `autoCloseBefore`.
- `indentation.increaseIndentPattern/decreaseIndentPattern/indentNextLine/unIndentedLinePattern`.
- `completion.triggerCharacters`, `electricCharacters`, `onEnterRules`.

These are host-consumable metadata; they do not automatically implement completion, auto-indent, formatting, or edit events. The host must consume them explicitly. Unknown editor fields cause Native compilation to fail.

## 5. `matching` and Global Regex Options

`matching` supports Boolean fields `caseSensitive`, `multiline`, `dotAll`, `extended`, and `unicode`. `unicode` must currently be true because Oniguruma uses UTF-16LE. Per-Regex flags support `i/ignoreCase`, `m/dotAll`, `s/singlelineAnchors`, and `x/extended`. Use the semantics defined here for Oniguruma; do not import assumptions from other engines.

## 6. `styles` and `schemes`

`styles` defines semantic styles, not colors. Supported fields: `scope`, `tags`, `description`, `deprecated`, `parent`, and `metadata`. Style names are user-defined identifiers, not a fixed token enum. `parent` supports semantic inheritance; unknown parents and inheritance cycles fail. Missing light/dark palette values may inherit from a parent style; explicit child values override inherited values.

`schemes` may define any number of named themes. Each theme may define `light`, `dark`, `ui`, and `extends`. Colors accept `#RGB`, `#RGBA`, `#RRGGBB`, and `#AARRGGBB`, normalized internally to AARRGGBB. Omitting a style color in light/dark means no override. UI tokens use arbitrary dot-separated names such as `editor.background` and `activity.toolbar.foreground`.

Implemented modifiers: `@bold/@B`, `@italic/@I`, `@underline/@U`, `@strike/@strikethrough/@S`, `@dim`, and their corresponding `@no-...` forms. `@dim` lowers text alpha in the Android renderer. Font family/size, arbitrary opacity, and background decoration are not current palette modifiers.

Current `scopeRules` supports comma alternatives, groups and `|` alternatives, descendant, direct-child `>`, exclusion `-`, exact/prefix scopes, `*` and `.*` wildcards. `L:/R:` prefixes are stripped. This is not a complete TextMate selector engine. Explicit style colors take precedence over scopeRules.

## 7. Regex Reuse, Rules, and Captures

`defines` uses name-to-Regex/string mappings and can be referenced through `match: include("name")` or `builtin: name`. `keywordsToRegex("if else return")` expands space-separated keywords. `repository` provides named Regex fragments referenced by `include("#name")`. Unknown references and cyclic references fail.

MATCH rules support `match/builtin`, `style/name`, `id`, `priority`, `enabled`, `overlap`, `flags`, `captures`, and `contains/patterns`. BLOCK rules support `start/begin`, `end`, `style/name`, `contentName`, `id`, `priority`, `enabled`, `overlap`, `flags`, `captures`, `startCaptures/beginCaptures`, `endCaptures`, and `contains/patterns`. Capture keys may be numeric indexes or named groups; `0` means the entire match.

`overlap` accepts only `allow/deny`. Priority is an integer; higher values win. Declaration order breaks ties. Empty matches advance by one Unicode code point to prevent infinite loops. Regex compilation failure fails the entire configuration.

Currently unsupported: rule-level `when`, `while`, `maxIterations`, `applyEndPatternLast`, rule-object include, lookahead/lookbehind convenience fields, and arbitrary rule metadata. Support in Oniguruma's Regex syntax does not imply support for these TSH structural fields.

## 8. Capture Arbitration and Rendering

ATS, native Query, and Regex results are normalized to captures/spans. Candidate spans are stably ordered by priority, declaration order, range, source, and style. Exact duplicates are removed; strict partial overlaps are split according to priority/declaration order; `allow` spans retain their full ranges; parent/child containment permits nested scopes. Priority is explicit configuration and is never inferred from style names. Source is only a stable tie-breaker and does not override explicit priority/order.

The final renderer must preserve explicit colors and alpha. Missing colors must not be filled automatically.

## 9. SCM Migration Principles

SCM is Tree-sitter Query source. Embed the original `highlights.scm` text in `treeSitter.queries[].source`, then bind capture names to semantic styles through `captures`. Do not translate all SCM into ATS and do not generate new `.scm` files. Consider manually rewriting a rule as ATS only when it is clearly expressible as a single-node structural condition. Migrate TextMate Regex grammars to `contains`. locals/tags/injections are outside the current TSH highlighting pipeline.

See the [SCM-to-TSH DSL v2 Migration Guide](TSH_SCM_TO_TSH_MIGRATION.en.md).

## 10. Syntax Not Implemented Today

Do not use the following in production configurations: `rules { ats/regex }` facade, Monarch, Regex `when/while/maxIterations/applyEndPatternLast`, TextMate external `$self/$base`, pattern-object repository, injection selectors, complete TextMate precedence, per-capture scope assignment, full decoration/font-family/font-size/opacity model, Outline, or Folding.

Generic declaration kinds, units, color spaces, or values representable by Kotlin TshDslModel/TshSchemaRegistry do not prove that the Native .tsh parser supports the corresponding syntax.

## 11. Authoring Checklist

- Use the `tsh 2` envelope.
- Match language metadata to the target grammar/file type.
- Ensure every output capture resolves to a valid style.
- Take ATS node/field names from the target grammar.
- Compile Query against the target TSLanguage and use only supported predicates.
- Use Oniguruma syntax for Regex; ensure includes exist and are acyclic.
- Give BLOCK rules valid start/end delimiters and correct capture bindings.
- Declare light/dark palettes explicitly; do not rely on implicit color fallback.
- Do not include Monarch or planned-but-unimplemented fields.
- Mark a configuration usable only after actual loading/compilation verification.
