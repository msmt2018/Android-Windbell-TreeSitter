# TSH DSL v2 — Language, Theme and Editor Behavior Specification

> Status: architecture contract for the TSH rework. This document describes the target v2 DSL. A construct is not considered implemented merely because it is documented; implementation status is tracked in the rework plan and tests.

## 1. Design invariants

1. **The .tsh file is the source of truth.** Language names, file matching, symbol names, semantic scopes, colors, fonts, theme tokens, rule order, regexes, Tree-sitter queries, bracket pairs and editor behavior are declared by the language package.
2. **The C++ core is a generic runtime, not a language/theme catalog.** It may implement lexing, parsing, regex execution, Tree-sitter query execution, interval arbitration, capture transport and JNI. It must not contain language-specific keyword lists, token names, colors, theme defaults, editor behavior defaults, or assumptions such as “style id 2 is a string”.
3. **No implicit visible color.** Missing colors are represented as absent values. The renderer must not substitute black/white or any other visible color. A host editor may decide to leave its existing foreground unchanged.
4. **Stable semantic names, dynamic numeric IDs.** IDs are compiled implementation details. Rules and themes refer to names; IDs are assigned deterministically from declarations and are never interpreted by meaning.
5. **Two independent matching backends.** ATS (Tree-sitter AST/query) and Oniguruma regex are peers. Either may be disabled or used alone. Their results are normalized into the same capture/span model.
6. **No external .scm dependency.** Tree-sitter query source is embedded in the .tsh file and compiled with the grammar's TSLanguage.
7. **No outline or folding extraction in TSH.** Those APIs and heuristics are outside this highlighting/configuration core and are not part of the v2 runtime contract.

## 2. File shape

A v2 file is a brace-delimited document. Commas after entries are optional. Strings support single and double quotes with JSON escapes (`\\n`, `\\r`, `\\t`, quotes, backslash and `\\uXXXX` including valid surrogate pairs); backticks are raw strings. Comments support // and /* */. Regex literals use /.../ with optional flags. Unknown punctuation, malformed escapes, unterminated strings/comments/regexes, and unescaped newlines in quoted strings are lexical errors rather than silently skipped input.

```tsh
tsh 2 {
  language {
    id: "java"
    name: "Java"
    extensions: [".java", ".jav"]
    aliases: ["Java"]
    mimeTypes: ["text/x-java"]
    firstLine: /^#!.*\bjava\b/
  }
  matching {
    caseSensitive: true
    multiline: false
    dotAll: false
    extended: false
    unicode: true
  }

  styles { /* semantic style declarations */ }
  schemes { /* unlimited named schemes */ }
  treeSitter { queries: [ /* embedded query sources */ ] }
  ast: [ /* Tree-sitter node/field selectors */ ]
  repository { /* reusable Oniguruma fragments */ }
  contains: [ /* Oniguruma match and begin/end rules */ ]
  editor {
    tabSize: 4
    fontSize: 14
    insertSpaces: true
    autoIndent: true
    trimTrailingWhitespace: true
    formatOnType: false
    renderWhitespace: "selection"
    lineNumbers: true
  }
}
```

The compiler accepts the versioned `tsh 2 { ... }` envelope and the legacy root-object form. The currently implemented root rule sections are `treeSitter.queries`, `ast`, `repository`, and `contains`; the higher-level unified `rules { ats { ... } regex { ... } }` façade remains a planned syntax layer. It rejects unsupported DSL versions, duplicate root sections/style/scheme IDs, malformed blocks, unknown root/style/editor properties, invalid color values, unresolved style references, inheritance cycles, invalid regexes and invalid Tree-sitter queries with source diagnostics (file, line, column, code and message). Unknown keys are errors by default. A vendor/user extension namespace is not yet implemented and must not be used as if it were accepted syntax.

## 3. Semantic style catalog

Styles declare semantic roles, not colors. The catalog is user-authored and can contain any number of entries.

```tsh
styles {
  keyword       { scope: "keyword.control", tags: ["keyword"] }
  keywordType   { scope: "keyword.type", tags: ["keyword", "type"] }
  type          { scope: "entity.name.type", tags: ["type", "declaration"] }
  typeBuiltin   { scope: "support.type", tags: ["type", "builtin"] }
  function      { scope: "entity.name.function", tags: ["function"] }
  variable      { scope: "variable.other", tags: ["variable"] }
  parameter     { scope: "variable.parameter", tags: ["variable", "parameter"] }
  property      { scope: "variable.other.property", tags: ["property"] }
  string        { scope: "string.quoted", tags: ["string"] }
  comment       { scope: "comment", tags: ["comment"] }
  number        { scope: "constant.numeric", tags: ["number", "constant"] }
  operator      { scope: "keyword.operator", tags: ["operator"] }
}
```

A style may declare `scope`, `tags`, `description`, `deprecated`, `parent`, and arbitrary string-valued `metadata`. Unknown style properties are compile errors. A child style inherits missing light/dark palette values from its parent style; explicit child values override the inherited values. Parent cycles and unknown parents are compile errors. Scope names follow TextMate-style dotted scope conventions. Tags are open strings; the runtime must not reserve a closed list of token categories. `parent` allows semantic inheritance for theme matching; cycles are compile errors.

## 4. Unlimited named schemes and appearance

`schemes` has no fixed count. Each scheme has independent light and dark maps. A symbol may be omitted in one mode; omission means “do not override”, not black/white.

```tsh
schemes {
  darcula {
    light [
      keyword: #0000FF @bold
      string: #A31515
      comment: #008000 @italic
    ]
    dark [
      keyword: #CC7832 @bold
      string: #6A8759
      comment: #808080 @italic
    ]
    ui {
      light {
        editor.background: #FFFFFF
        editor.foreground: #202020
        editor.selection: #ADD6FF
        editor.lineHighlight: #F2F2F2
        editor.gutter: #858585
      }
      dark {
        editor.background: #2B2B2B
        editor.foreground: #A9B7C6
        editor.selection: #214283
        editor.lineHighlight: #323232
        editor.gutter: #6B6B6B
      }
      diagnostics.error: #E51400
      diagnostics.warning: #BF8803
    }
  }

  github {
    extends: "darcula"
    light [ keyword: #0550AE, string: #0A3069, comment: #6E7781 ]
    dark  [ keyword: #79C0FF, string: #A5D6FF, comment: #8B949E ]
  }
}
```

Color syntax accepts #RGB, #RGBA, #RRGGBB and #AARRGGBB; canonical runtime representation is AARRGGBB. Theme values are data, never C++ constants. Theme UI tokens are arbitrary dotted names, not a hard-coded enum. A scheme may optionally declare `extends: "otherScheme"`; inheritance is resolved by the compiler and cycles/missing parents are errors. The active appearance is a runtime selection (`light` or `dark`) and does not require reparsing the syntax tree.

TextMate block rules support separate `beginCaptures`/`startCaptures` and `endCaptures` maps, including named Oniguruma capture groups. The begin/end delimiter captures are emitted independently from the enclosing region style and nested patterns. A `repository` block currently supports named reusable regex fragments referenced with `include("#name")`, including recursive references with cycle detection. Pattern-object repositories, external `$self`/`$base` includes and injection selectors remain planned. Scope-based theme selectors are implemented separately through `scopeRules`.

Implemented scheme modifiers are `@bold`, `@italic`, `@underline`, `@strikethrough`, and `@dim`, independently tracked for light/dark. `@dim` applies a reduced text alpha in the Android renderer. Reset modifiers, font family/size, independent opacity, border and background decorations remain planned and must not be treated as implemented.

## 5. Rule model and arbitration

The `language` block defines `id`, `name`, `extensions`, `aliases`, `mimeTypes`, and a first-line detection regex. Extensions are normalized by removing a leading dot and deduplicated while preserving declaration order.

The `editor` block includes host-consumable editing options such as `tabSize`, `fontSize`, `lineHeight`, `letterSpacing`, `insertSpaces`, `autoIndent`, `trimTrailingWhitespace`, `formatOnType`, `formatOnPaste`, `renderWhitespace`, `lineNumbers`, and `maxTokenizationLineLength`. Options are exported as typed-at-source values for host adapters; a host only applies options supported by its editor API.

Language-wide regex behavior is declared in `matching`: `caseSensitive`, `multiline` (line-anchor behavior), `dotAll` (dot matches newline), `extended`, and `unicode`. Individual regex rules may add Oniguruma flags with `flags: ["i", "m", "s", "x"]`: `i` ignores case, Oniguruma `m` enables dot-newline matching, `s` makes anchors match only the whole input, and `x` enables extended syntax. Named capture groups and numeric capture groups can both bind semantic styles. UTF-8/modified-UTF-8 source patterns are decoded to UTF-16 before Oniguruma compilation.

The currently executable regex-rule attributes are `id`, `match` (or `start/end`), `style` (with `name` as a compatibility alias), `contentName`, `flags`, `enabled`, `priority`, `overlap: "allow"|"deny"`, `captures`, `beginCaptures`/`startCaptures`, `endCaptures`, and nested `contains`/`patterns`. Numeric and named capture keys are resolved against the compiled Oniguruma expression. Higher priority participates in span arbitration; declaration order breaks ties. `overlap: "allow"` opts a span out of exclusive overlap suppression. The parser rejects unknown rule attributes rather than silently accepting them.

Planned, not currently executable: rule-level `when` predicates, independent capture `scope` assignment, arbitrary rule `metadata`, `applyEndPatternLast`, `while`, `maxIterations`, and lookaround convenience properties. Do not add these keys to a production .tsh file until the compiler and runtime implement them. A rule never infers priority from its style name.

### 5.1 Tree-sitter query / ATS

```tsh
rules {
  ats {
    query "declarations" {
      source: `
        (class_declaration
          name: (identifier) @type)
        (method_declaration
          name: (identifier) @function)
      `
      captures {
        type: { style: "type", priority: 100 }
        function: { style: "function", priority: 90 }
      }
    }
  }
}
```

Query source is embedded and compiled unchanged with the provided language; it is never generated from ATS and no `.scm` file is emitted or required. Capture names map to semantic style names. ATS is a separate selector pipeline. Implemented ATS selector properties include `node`, `field`, `parent`, `ancestor`, `child`, `previousSibling`, `nextSibling`, `childIndex`, `namedChildIndex`, `firstChild`, `lastChild`, `named`, `missing`, `extra`, `error`, `hasError`, `text`, `textPrefix`, `textSuffix`, and `textRegex`, plus style, priority, enabled, id, and overlap controls. `textRegex` is compiled once during TSH compilation and evaluated against the selected node's UTF-16 text at runtime. Span resolution is shared by ATS, native Tree-sitter queries, Oniguruma rules, and Monarch tokens. Invalid or empty ranges are discarded before conflict handling. Candidate precedence is deterministic: higher priority first, then lower declaration order, then stable range/style tie-breakers. Exact duplicate emissions with identical range, style, priority, declaration order, overlap policy, and flags are collapsed. Strict partial overlaps are rejected unless either span explicitly allows overlap; proper containment is retained so nested scopes can coexist. Final output is ordered by start offset, outer range before inner range, then priority and declaration order.

Unknown or unsupported ATS properties are rejected rather than ignored. At compilation, ATS rules are partitioned into immutable dispatch indexes keyed by target node symbol, field-owner symbol, and wildcard selectors. During tree traversal, only the relevant indexed rule sets are evaluated; field selectors are dispatched from the owner node symbol and then checked against the selected child's field ID. Query errors carry exact offsets.

### 5.2 Oniguruma regex

```tsh
rules {
  regex {
    rule "line-comment" {
      match: /\/\/[^\\r\\n]*/
      style: "comment"
      priority: 30
      flags: [unicode]
      captures { 0: "comment" }
    }

    rule "block-comment" {
      begin: /\/\*/
      end: /\*\//
      style: "comment"
      priority: 80
      while: /\\* /
      contains: ["todo-marker"]
      captures { 0: "comment" }
    }

    rule "todo-marker" {
      match: /\\b(TODO|FIXME|XXX)\\b/
      captures { 1: "keyword" }
      priority: 110
    }
  }
}
```

The implemented regex rule parser accepts `match` or `start/end`, `style`/`name`, `contentName`, `flags`, `enabled`, `priority`, `overlap`, numeric/named `captures`, `beginCaptures`/`startCaptures`, `endCaptures`, and nested `contains`/`patterns`. `while`, `include` on rule objects, `when`, lookbehind/lookahead convenience properties, `maxIterations`, and `applyEndPatternLast` are not implemented by the current regex runtime. Empty matches in active scanners advance by one Unicode code point to prevent infinite loops. A failed regex compilation invalidates the document instead of silently dropping the rule.

### 5.3 Common rule attributes

- `id`: unique rule identity.
- `priority`: explicit integer precedence.
- `enabled`: runtime-toggleable rule.
- `when`: planned conditional rule activation; not accepted by the current regex rule parser.
- `scope`: semantic style scope is declared under `styles`; per-capture scope assignment is not implemented.
- `captures`: numeric or named capture-to-style mapping is implemented.
- `overlap`: currently accepts `allow` or `deny`; `exclusive` and `contained` aliases are not accepted.
- `contentName`: style name for the interior of a begin/end region.
- `applyEndPatternLast`: planned ordering behavior; not currently accepted.
- `metadata`: arbitrary string metadata is supported on style declarations, not on regex rule declarations.

## 6. TextMate-inspired features

Implemented today:
- Nested Oniguruma match and begin/end block rules.
- Numeric and named capture-to-style bindings.
- Separate `beginCaptures`/`startCaptures` and `endCaptures`.
- Region-wide `style` and interior-only `contentName` style spans.
- A `repository` block for reusable regex fragments, referenced with `include("#name")`.
- Recursive repository fragment expansion with cycle detection.

Scope theme rules use `scopeRules: [{ selector, light, dark }]`. The current selector evaluator supports comma and grouped `|` alternatives (including nested parenthesized groups), descendant selectors, direct-child `>`, exclusion terms with `-`, exact/prefix scope matching, `*` and `.*` wildcards, and strips the `L:`/`R:` priority prefixes. Full TextMate selector precedence/priority semantics and injection-selector semantics are not implemented. Specificity is resolved before palette assignment. Matching stacks are built from `source.<language.id>` and the semantic style's parent-scope chain. Explicit per-style colors take precedence over scope rules.

Still planned:
- Pattern-object repositories and external `$self`/`$base` scope includes.
- Injection selectors, scope inheritance across nested patterns, `while` continuation and complete begin/end precedence semantics.
- Per-rule metadata, full TextMate begin/end precedence, `while` continuation and per-capture scope assignment.

Includes are resolved within the same .tsh document; there is no external .scm or sidecar grammar file.

## 7. Monarch-inspired tokenizer features

The currently executable Monarch subset is optional and may coexist with ATS and Oniguruma rules. It supports ordered state-local regex rules, token styles, state includes, persistent state stacks, `variables`, `cases` with literal/variable matchers and `@default`, `next`, `push`, `switchTo`, `pop`, `rematch`, `enabled`, `priority`, `ignoreCase`, `tokenPostfix`, and per-rule Oniguruma flags. The full Monarch action object model (for example `goBack`, `log`, `bracket`, `nextEmbedded`) is not implemented.

```tsh
monarch {
  start: "root"
  ignoreCase: false
  tokenPostfix: ".java"
  variables {
    identifier: /[A-Za-z_$][A-Za-z0-9_$]*/
    digits: /[0-9]+/
    keywords: /(?:if|else|return)/
  }
  defaultToken: "invalid"
  tokenizer {
    root: [
      { match: /"/, token: "string", push: "@string" }
      { include: "@identifiers" }
    ]
    identifiers: [
      { match: /@identifier/, cases: {
          "@keywords": "keyword"
          "@default": "identifier"
        }
      }
      { match: /@digits/, token: "number" }
    ]
    string: [
      { match: /"/, token: "string", pop: true }
      { match: /[^"]+/, token: "string" }
    ]
  }
}
```

The runtime tokenizes the complete UTF-16 document with a persistent state stack and emits normalized spans into the shared arbitration pipeline. State references and include cycles are validated. Regex variables declared in `variables` before `tokenizer` are recursively expanded (with unknown references and cycles rejected, including unused cyclic definitions); escaped references and character classes are not rewritten. `tokenPostfix` is appended to token names and `defaultToken` after parsing, so the postfix declaration itself may appear in any position. `ignoreCase` must be declared before `tokenizer` and sets the tokenizer's default Oniguruma option. The currently implemented `cases` map supports ordered exact-literal keys, regex-variable keys such as `"@keywords"`, and a final `"@default"` token fallback. Case entries select token styles; state-transition actions inside cases are not yet implemented. Remaining planned Monarch features include action arrays, embedded-language tokens, `goBack`, `@rematch` action syntax, and bracket actions.

## 8. Editor/language metadata

```tsh
editor {
  comments {
    line: ["//"]
    block: [["/*", "*/"]]
  }
  brackets: ["{}", "[]", "()", "<>"]
  bracketNotIn: ["string", "comment"]
  autoClosingPairs: [
    { open: "(", close: ")", notIn: ["string", "comment"] }
    { open: "\"", close: "\"", notIn: ["string", "comment"] }
  ]
  surroundingPairs: ["{}", "[]", "()", "\"\""]
  wordPattern: /[A-Za-z_$][A-Za-z0-9_$]*/
  indentation {
    increaseIndentPattern: /\\{[^}]*$/
    decreaseIndentPattern: /^\\s*\\}/
    indentNextLine: /=>\\s*$/
    unIndentedLinePattern: /^\\s*(//|\\*)/
  }
  onEnterRules: [
    { beforeText: /\\{[^}]*$/, action: { indent: "indentOutdent" } }
  ]
  completion {
    triggerCharacters: [".", "@", ":"]
  }
}
```

This metadata is declarative and may be exposed to the host editor. The native compiler validates all on-enter regexes with Oniguruma before accepting the grammar. An on-enter action supports `none`, `indent`, `indentOutdent`, and `outdent`, plus optional `appendText` and non-negative `removeText`. `electricCharacters` identifies characters that may trigger host-side indentation recalculation; `autoCloseBefore` declares the allowed following-character pattern for auto-closing pairs. The Kotlin API exposes these rules as structured metadata. The current sample adapter consumes bracket and auto-pair scopes; it does not yet execute on-enter/electric-character rules inside the editor's Enter/type event pipeline. TSH does not implement completion providers, language servers, symbol outline, or code folding. It only provides metadata that a host may choose to consume.

## 9. Runtime API contract

The Kotlin API should expose:
- `load(extension, source, language)` returning structured diagnostics and a compiled grammar handle;
- `listSchemes(extension)`;
- `getDefaultScheme(extension)`;
- `selectScheme(extension, name)`;
- `setAppearance(extension, isDark)`;
- `getStyleCatalog(extension)`;
- `getThemeTokens(extension)`;
- `setRuleEnabled(extension, ruleId, enabled)`;
- `computeCaptures(tree, text, extension)` returning ordered captures with byte ranges, style name/id, scope, flags, priority and source backend;
- `getEditorMetadata(extension)`.

Scheme and appearance changes must only change rendering data; they must not reparse source or rebuild the Tree-sitter tree. The JNI boundary must use versioned, length-checked data structures (or structured objects) and never rely on undocumented magic style IDs.

## 10. Implementation layers

1. Lexer/parser and source diagnostics.
2. Immutable compiled document model and validation.
3. Scheme/style catalog and runtime selection.
4. Embedded Tree-sitter query compilation and capture mapping.
5. Oniguruma match/begin-end/capture/nesting engine.
6. Common capture arbitration and Unicode-safe UTF-16 ranges.
7. JNI API and Kotlin public facade.
8. Android editor adapter and theme-token bridge.
9. Tests: parser fixtures, malformed input, query errors, regex errors, Unicode, overlaps, scheme switching, no-color behavior, and editor integration.

## 11. Non-goals and safety rails

- No hard-coded syntax colors, semantic token names, language keywords, language-specific regex snippets, or theme values in C++.
- No numeric style-ID meaning in arbitration (e.g. no special cases for ID 2 or 3).
- No black/white fallback when a style or theme color is missing.
- No external .scm files.
- No outline/folding APIs or AST heuristics.
- No silent parser recovery that yields a partially valid grammar without diagnostics.
