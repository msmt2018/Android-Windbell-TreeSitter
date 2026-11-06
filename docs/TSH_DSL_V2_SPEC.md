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

A v2 file is a brace-delimited document. Commas after entries are optional. Strings support single and double quotes; comments support // and /* */. Regex literals use /.../ with optional flags.

```tsh
tsh 2 {
  language { id: "java", name: "Java", extensions: [".java", ".jav"] }
  matching {
    caseSensitive: true
    multiline: false
    dotAll: false
    extended: false
    unicode: true
  }

  styles { /* semantic style declarations */ }
  schemes { /* unlimited named schemes */ }
  rules { /* ats and regex rules */ }
  editor { /* language/editor metadata */ }
}
```

The compiler must reject duplicate IDs, malformed blocks, invalid color values, unresolved style references, invalid regexes and invalid Tree-sitter queries with source diagnostics (file, line, column, code and message). Unknown keys are errors by default; an explicit `extensions` namespace is reserved for vendor/user metadata.

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

A style may declare `scope`, `tags`, `description`, `deprecated`, `parent`, and `metadata`. A child style inherits missing light/dark palette values from its parent style; explicit child values override the inherited values. Parent cycles and unknown parents are compile errors. Scope names follow TextMate-style dotted scope conventions. Tags are open strings; the runtime must not reserve a closed list of token categories. `parent` allows semantic inheritance for theme matching; cycles are compile errors.

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

Style modifiers are composable: `bold`, `italic`, `underline`, `strikethrough`, `dim`, `italicOff`, `boldOff`, `fontFamily`, `fontSize`, `opacity`, `border`, and `background`. Modifiers are optional and independently specified for light/dark. The renderer only applies properties explicitly present in the selected scheme.

## 5. Rule model and arbitration

Language-wide regex behavior is declared in `matching`: `caseSensitive`, `multiline`, `dotAll`, `extended`, and `unicode`. Individual regex rules may override/add flags with `flags: ["i", "m", "s", "x"]`. Named capture groups and numeric capture groups can both bind semantic styles. Unicode source patterns are decoded to UTF-16 before Oniguruma compilation.

Every rule has a stable `id`, a backend, optional `name`, `enabled`, `priority`, `when`, `scope`, `captures`, and metadata. Higher priority wins on overlapping spans; ties are resolved by declaration order unless a rule explicitly sets `overlap: "allow"`. A rule never infers priority from its style name.

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

Query source is embedded and compiled with the provided language. Capture names map to semantic style names. Additional AST selectors may use `node`, `field`, `parent`, `ancestor`, `text`, `hasChild`, and predicates; selectors must target the selected child when a field is specified. Query errors carry exact offsets.

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

Regex rules support `match`, `begin/end`, `while`, `include`, `captures`, named captures, numeric captures, `flags`, `priority`, `enabled`, `when`, `lookbehind/lookahead` (subject to Oniguruma support), `maxIterations`, `applyEndPatternLast`, and nested `patterns/contains`. Empty matches must advance by one Unicode code point to prevent infinite loops. A failed regex compilation invalidates the document instead of silently dropping the rule.

### 5.3 Common rule attributes

- `id`: unique rule identity.
- `priority`: explicit integer precedence.
- `enabled`: runtime-toggleable rule.
- `when`: declarative conditions such as file extension, language version, feature flag, or parent scope.
- `scope`: TextMate-like scope path assigned to captures.
- `captures`: numeric or named capture to style/scope mapping.
- `overlap`: `exclusive`, `allow`, or `contained`.
- `contentName`: scope for the interior of a begin/end region.
- `applyEndPatternLast`: ordering behavior for nested regions.
- `metadata`: arbitrary user-defined key/value data.

## 6. TextMate-inspired features

The runtime should support nested begin/end regions, content scopes, capture maps, repository/include references, injection selectors, scope inheritance, and rule precedence. Includes are resolved within the same .tsh document; there is no external .scm or sidecar grammar file. TextMate scope selectors are matched by a generic selector engine, not by language-specific C++ branches.

## 7. Monarch-inspired tokenizer features

A tokenizer state machine is optional and may coexist with ATS/regex. It is useful for languages where lexical context changes tokenization.

```tsh
rules {
  monarch {
    defaultState: "root"
    tokenizer {
      root: [
        { regex: /\\s+/, action: "whitespace" }
        { regex: /\\b(class|interface)\\b/, action: { token: "keyword", next: "@declaration" } }
        { regex: /"/, action: { token: "string", next: "@string" } }
      ]
      declaration: [
        { regex: /[A-Za-z_$][\\w$]*/, action: { token: "type", next: "@pop" } }
      ]
      string: [
        { regex: /\\\\./, action: "string.escape" }
        { regex: /"/, action: { token: "string", next: "@pop" } }
        { regex: /[^"\\\\]+/, action: "string" }
      ]
    }
  }
}
```

Actions include token assignment, `next`, `switchTo`, `@push`, `@pop`, `goBack`, `cases`, `@rematch`, and `log`. State transitions are validated for unknown state names. This is a tokenizer state machine, not an implicit language definition inside the core.

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

This metadata is declarative and may be exposed to the host editor. TSH does not implement completion providers, language servers, symbol outline, or code folding. It only provides metadata that a host may choose to consume.

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
