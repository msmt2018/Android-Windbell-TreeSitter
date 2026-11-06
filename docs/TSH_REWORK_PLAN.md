# Windbell TSH Core Rework Plan

## Goals

Rebuild Windbell TreeSitter Highlighting Configuration (TSH) as a declarative highlighting system whose configuration source is the `.tsh` document. The native core must not provide fallback token colors or silently invent a theme. Android consumes compiled styles and renders them through the editor's syntax-highlighter API.

## Target architecture

```text
.tsh source
  -> UTF-8 lexer / parser
  -> validated TSH document
  -> native compiled configuration
       |-- ATS (Tree-sitter node/field selectors and embedded Tree-sitter queries)
       |-- Oniguruma regex rules and capture-group bindings
       |-- style registry and named color schemes
       |-- editor typography / behavior settings
  -> JNI (stable, versioned result contracts)
  -> Kotlin TSH engine / scheme manager
  -> EditorView.SyntaxHighlighter / LineStyler
```

No external `.scm` file is required. Tree-sitter queries, when needed, are embedded in the `.tsh` document and compiled with `ts_query_new`.

## DSL semantics

The new schema is explicit and keeps syntax rules separate from presentation:

- `isDark`: default rendering mode for this document; `false` means light mode and `true` means dark mode. It is not a per-token color and can be overridden at runtime.
- `defaultScheme`: the initial named scheme.
- `schemes`: an unbounded named collection. Each scheme defines independent `light` and `dark` symbol-color maps. A symbol without a color is not assigned a fabricated native color.
- `styles`: stable symbol names and optional typography metadata; style IDs are internal handles, not semantic values.
- `ats`: Tree-sitter node/field/parent selectors. A selector with a field styles the selected child field, not the parent node.
- `treeSitter`: embedded native S-expression query rules. Capture names map to style names.
- `regex`: Oniguruma rules, capture-group style bindings, block delimiters, priority and case/option settings.
- `editor`: font family, size, weight, line height, tab width and related editor presentation settings.

Canonical scheme shape:

```tsh
{
  isDark: false,
  defaultScheme: "windbell",
  schemes {
    windbell {
      light [
        keyword: #0000FF,
        string: #A31515,
        comment: #008000
      ]
      dark [
        keyword: #CC7832,
        string: #6A8759,
        comment: #808080
      ]
    }
  }
}
```

The parser will accept the bracket-list mapping form above and validate colors, duplicate names, missing references, malformed selectors, regex compilation and Tree-sitter query compilation.

## Required implementation phases

### Phase 1 — Native contract and parser

1. Make the C++ header, compiler, core and JNI agree on one set of types and signatures.
2. Replace token-skipping parsing with validated parsing and source-position diagnostics.
3. Register style names before compiling ATS, query and regex rules; style ID zero must be valid.
4. Parse named schemes, both color modes, `isDark`, `defaultScheme` and editor typography.
5. Convert UTF-8 regex patterns to UTF-16 correctly before compiling them with Oniguruma.
6. Ensure all native resources (regexes, queries, cursors and regions) have deterministic ownership.

### Phase 2 — Matching pipelines

1. ATS resolves Tree-sitter symbols and fields against the actual language ABI.
2. Field selectors style the selected child node.
3. Embedded Tree-sitter queries compile from the TSH source and map captures to style IDs.
4. Oniguruma rules execute against UTF-16 editor text and return validated UTF-16 byte ranges.
5. Merge spans by explicit rule priority without hard-coded style IDs or token categories.
6. Return diagnostics when a rule cannot compile; do not silently register a partially working grammar.

### Phase 3 — JNI and Kotlin scheme API

Expose a stable API to load/reload a TSH document, list scheme names, read the default scheme, select a scheme by name, select light/dark rendering, read the selected palette and compute highlight spans. Scheme changes must refresh presentation without reparsing the source.

### Phase 4 — Android editor integration

Implement a real `SyntaxHighlighter` for `EditorView`. It owns the parser/tree lifecycle, updates parsing after edits, returns line-local spans from cached native results and invalidates affected styles. Loading the TSH document must complete before the highlighter is enabled. The UI must use colors and font metadata from the selected TSH scheme only.

### Phase 5 — Remove obsolete features and SCM dependency

Remove TSH fold-range extraction, outline extraction and their JNI/Kotlin/UI/test entry points. Remove TSH runtime reads of external `.scm` files. Existing Tree-sitter APIs may still expose query support, but TSH configuration and capture definitions come from `.tsh`.

### Phase 6 — Verification

Add native parser/compiler tests, scheme switching tests, ATS field-selection tests, query-capture tests, Oniguruma matching tests, UTF-16/surrogate-pair range tests, invalid-input diagnostics tests and Android editor integration tests. Build the Android library and sample app for all configured ABIs before merging.

## Initial audit findings

The current implementation has concrete integration defects that must be corrected during the phases above:

- The sample document declares `styles`, while the native compiler does not parse that property.
- The current `schemes` parser consumes a colon before its opening brace, conflicting with the requested `schemes { ... }` form.
- JNI reads `TshGrammar::styles`, but the current grammar header does not declare that member.
- Core code reads AST fold/outline fields that are absent from the declared AST rule type.
- Style ID zero is allocated but treated as an invalid AST style.
- The AST field selector currently compares the parent-field ID of the current node rather than styling the selected child field.
- The sample uses numeric regex capture bindings and nested `contains` rules that the compiler does not fully parse.
- The app asynchronously loads the TSH asset while editor parsing can run independently; there is no readiness barrier.
- The app computes spans for diagnostics but does not install a TSH-backed `SyntaxHighlighter` on `EditorView`.
- The Kotlin sample selects the `kt` extension without loading a corresponding Kotlin TSH grammar.
- The current Kotlin renderer falls back to hard-coded black/white colors when a style is missing. The rework removes this fallback.

## Implementation progress (branch: `fix/tsh-core-architecture`)

### Implemented in the current working branch

- Added `docs/TSH_DSL_V2_SPEC.md` as the expanded DSL contract.
- Removed hard-coded black/white fallback colors from the Kotlin renderer and removed built-in Java/C regex fragments from the C++ compiler. Regex fragments must now be declared in the .tsh `defines` section.
- Added semantic style catalog parsing (`scope`, `tags`, `description`, `parent`) and Kotlin catalog access.
- Added `schemes { name { light [...] dark [...] ui {...} } }` parsing, arbitrary scheme enumeration, explicit `defaultScheme`, `isDark` default appearance, named scheme selection and runtime appearance overrides.
- Added arbitrary shared/light/dark UI color-token export and a sample-app bridge for editor background, foreground, selection, current-line and gutter colors.
- Added DSL editor metadata for line/block comments, brackets and exclusions, auto-closing/surrounding pairs, word patterns, indentation regexes and completion trigger characters; exposed it through Kotlin and wired bracket matching plus scope-aware auto-pair filtering into the sample editor.
- Added per-appearance bold/italic/underline/strikethrough modifiers, correct style-ID-zero handling, explicit color-presence transport (including transparent colors), and RGB/RGBA/ARGB normalization.
- Fixed ATS field selectors so `node + field` styles the selected child node; removed numeric style-ID assumptions from span arbitration.
- Added recursive Oniguruma block/contains parsing, numeric capture maps, `captures { ... }`, named rule IDs, priority, enablement and overlap declarations. Capture results carry priority and declaration order; nested regions are range-constrained and recursively released.
- Added a Kotlin `TshSyntaxHighlighter` adapter and installed it in the sample `EditorView`; captures are indexed by line and recalculated off the drawing thread.
- Removed TSH fold/outline/hover extraction calls from the app diagnostics and removed the obsolete native/Kotlin fold and outline APIs.
- Migrated the active Java asset to semantic styles, named schemes, UI tokens and DSL-defined regex fragments.

### Still pending / not yet verified

- The compiler still needs structured source diagnostics (line/column, error code, precise source range) and transactional preservation of the last valid grammar.
- Embedded Tree-sitter query parsing/capture maps need full validation and dedicated tests.
- TextMate selector inheritance/injections and Monarch state-machine execution remain target features, not implemented runtime features yet.
- Additional rule attributes such as lookarounds/options, begin/while/end state, named capture groups, content scopes and rule repository includes need implementation.
- Advanced editor metadata such as on-enter rules, electric characters, comment toggling behavior, and host-specific application of word/indent/completion metadata remains pending.
- The sample currently loads the Java TSH only; Kotlin and other language-specific TSH packages still need to be added and wired.
- A full Android/NDK build and device/editor integration run have not yet been performed. Do not treat the current commits as build-verified.

## Acceptance criteria

- Every visible syntax color and font style comes from the selected `.tsh` scheme.
- Switching light/dark mode or named schemes changes rendered spans without recompiling the syntax tree.
- ATS, embedded Tree-sitter query and Oniguruma rules each have independently testable positive matches.
- Invalid TSH source produces actionable diagnostics and does not replace the last valid configuration.
- No TSH source path requires an external `.scm` file.
- Fold and outline extraction are absent from the TSH public API.
- The sample app visibly installs the TSH editor highlighter and applies its output to actual editor rows.
