# TSH Runtime API

Package: `com.zerostudio.idea.treesitter.tsh`

This package contains the grammar discovery/binding runtime and the existing Windbell TSH engine facade.

## Grammar resolution

### `TshLanguageRequest`

Describes the input used for language resolution: file name, optional MIME type, optional shebang and a content prefix.

### `TshLanguageResolution`

Contains the selected language ID, extension and the `Match` reason. Match reasons distinguish compound extension, extension, MIME, shebang, first-line and content matching.

### `TshLanguageResolver`

Resolves a request against a `TshGrammarRegistry`. The implementation checks the available metadata and uses its declared resolution order; ambiguous or absent matches may produce no resolution.

### `TshGrammarDescriptor`

Describes a grammar installation: ID, language symbol, extensions, aliases, MIME types, native library file, TSH source file, source origin and optional first-line pattern.

### `TshGrammarRegistry`

Stores grammar descriptors and their runtime state. Entries can be `AVAILABLE`, `CONFLICT`, `ABI_INCOMPATIBLE`, `LOAD_FAILED` or `READY`; `register`, `update`, `find` and `snapshot` expose registry operations.

### `TshGrammarProvider`

Discovers APK-provided and external grammar assets/libraries, reads TSH metadata, and registers descriptors. Discovery and loading can fail because files, ABI compatibility or grammar metadata are unavailable; inspect the resulting registry state/diagnostic.

### `TshGrammarBinding`

Binds a loaded `TSLanguage`, extension, parsed `WindbellTshEngine.LanguageMetadata` and optional descriptor. `requireSameLanguage` verifies language identity for operations that must share a grammar.

## Parser runtime

### `TshParserFactory`

Creates native document/parser sessions for a grammar binding and supplied document reader/storage configuration.

### `TshParserSession`

Serializes access to its native document handle. `applyEdit(start, oldEnd, inserted)` applies an edit; `highlight(start, end, diagnostics)` requests highlights for a byte range. The session is closeable through its owning document runtime.

### `TshDocumentRuntime`

Groups a grammar binding and parser session and closes the runtime as a unit.

## Engine facade

### `WindbellTshEngine`

Singleton facade over the native TSH implementation. It loads TSH source and exposes language metadata, compile diagnostics, editor metadata, style catalog, scheme palettes, theme tokens, appearance selection and highlight application.

Notable nested data types:

* `LanguageMetadata`: language identity, display name, extensions, aliases, MIME types and first-line pattern.
* `CompileDiagnostic`: diagnostic code/message and source offset/line/column.
* `HighlightSpan` / `NativeHighlightRange`: capture ranges and style/source metadata.
* `SemanticStyle` / `StylePalette`: style catalog entries and light/dark palette/font declarations.
* `EditorMetadata`: editor-facing comment, bracket, auto-closing, indentation, trigger-character and on-enter metadata.
* `AutoClosingPair` / `OnEnterRule`: structured editor behavior declarations.

Key operations:

* `loadTshSource(extension, source, language)`: compiles/loads the TSH source for an extension and optional grammar language.
* `isGrammarLoaded(extension)`, `matchesFirstLine(extension, line)`: inspect grammar availability and first-line matching.
* `getLanguageMetadata`, `getCompileDiagnostics`, `getEditorMetadata`, `getStyleCatalog`: retrieve compiled metadata.
* `listSchemes`, `getDefaultScheme`, `selectScheme`, `getSelectedScheme`: manage scheme selection.
* `getSchemePalette`, `getStylePalette`, `getThemeTokens`, `getSchemeThemeTokens`: retrieve palette/theme data.
* `setAppearance`, `clearAppearanceOverride`, `isDark`: manage appearance selection.
* `applyHighlightsToSpannable`, `applyHighlightSpansToSpannable`, `applyHighlightCaptureBatchToSpannable`: apply highlight results to Android spannable text.
* **Boundary:** this facade remains present in the source and is used by the runtime. Its existence does not mean every newer `tsh.api` abstraction is already routed through it, nor that all editor metadata is rendered by this package.

## Capture batch

### `HighlightCaptureBatch`

Compact capture batch backed by parallel arrays. Indexed accessors expose start/end byte offsets, style ID, font flags, priority, declaration order and source. Unsigned byte accessors widen the stored 32-bit value to `Long`. `writeRecord` and `copyRecordFrom` support record population/copying.

## Sources

[tsh package](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh)
