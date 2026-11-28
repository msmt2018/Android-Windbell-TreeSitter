# Grammars for android-tree-sitter

This directory contains the Tree-Sitter grammar submodules and configuration.

All grammar modules are dynamically discovered, generated, and compiled based on `grammars/grammars.json`.

---

## 1. Specification of `grammars.json`

The `grammars.json` file describes both **single-parser** and **composite multi-parser** languages.

### Schema & Fields

| Field | Type | Description |
| :--- | :--- | :--- |
| `name` | `String` | Unique grammar identifier (e.g. `"yaml"`, `"markdown"`, `"ocaml"`). Must match a directory under `grammars/`. Maps to Gradle module `:tree-sitter-<name>`. |
| `composite` | `Boolean` | `false` (default) for standard single-parser repositories; `true` for composite multi-parser repositories with nested sub-grammars. |
| `entry` | `String` | **(Single mode)** The exact C export entry symbol extracted from `src/parser.c` (e.g. `"tree_sitter_yaml"`). Prevents broken symbol guessing. |
| `sub.paths` | `Array<String>` | **(Composite mode)** Relative paths to sub-parsers containing `src/parser.c` (e.g. `["tree-sitter-markdown", "tree-sitter-markdown-inline"]`). Deep/recursive paths are fully supported. |
| `sub.entries` | `Array<String>` | **(Composite mode)** Array of exact C export symbols corresponding one-to-one with `sub.paths` (e.g. `["tree_sitter_markdown", "tree_sitter_markdown_inline"]`). |
| `src.extra` | `Array<String>` | Extra external scanner files (e.g. `"src/scanner.c"`, `"scanner.cc"`) relative to the grammar directory. |

---

### Configuration Examples

#### Standard Single Grammar (e.g., YAML / Java)

```json
[
  {
    "name": "yaml",
    "composite": false,
    "entry": "tree_sitter_yaml",
    "src.extra": [
      "src/scanner.c"
    ]
  }
]
```

#### Composite Multi-Parser Grammar (e.g., Markdown / OCaml / CSV)

Compiled into a **single unified `.so` library** with aggressive size/speed optimizations (`-O3 -flto -s`), exposing a unified primary instance (`getInstance()`) as well as full sub-parser access (`preloadAll()` / `get<Sub>Instance()`):

```json
[
  {
    "name": "markdown",
    "composite": true,
    "entry": null,
    "sub.paths": [
      "tree-sitter-markdown",
      "tree-sitter-markdown-inline"
    ],
    "sub.entries": [
      "tree_sitter_markdown",
      "tree_sitter_markdown_inline"
    ],
    "src.extra": []
  },
  {
    "name": "ocaml",
    "composite": true,
    "entry": null,
    "sub.paths": [
      "grammars/interface",
      "grammars/ocaml",
      "grammars/type"
    ],
    "sub.entries": [
      "tree_sitter_ocaml_interface",
      "tree_sitter_ocaml",
      "tree_sitter_ocaml_type"
    ],
    "src.extra": []
  }
]
```

---

## 2. Automation Scripts & Tooling

To ensure zero manual maintenance and avoid configuration typos, two automation scripts are provided in the repository root:

### ① `generate_grammars.py` (Automatic Configuration Generator)
* **Author**: `android_zero <github.com/msmt2018>`
* **Purpose**: Automatically scans `.gitmodules` and physical folders under `grammars/` to construct `grammars/grammars.json`.
* **Key Capabilities**:
  * **Recursive AST/Symbol Extraction**: Parses each `parser.c` to accurately extract `TS_PUBLIC const TSLanguage *tree_sitter_xxx(void)` symbols without guessing.
  * **Deep Subpath Discovery**: Automatically identifies composite multi-parser repositories (e.g. Markdown, OCaml, CSV) and generates matching `sub.paths` and `sub.entries`.
  * **Strict Exclusion Guard**: Automatically skips build caches and dependency directories such as `node_modules`, `.git`, and `build/`.
  * **Safe Atomic Write**: Writes to a temporary buffer first and applies an atomic OS-level replacement to protect existing configurations.

```bash
# Run anytime you add, remove, or modify grammar submodules:
python3 generate_grammars.py
```

---

## First-party TSH configuration grammar

The `.tsh` extension is parsed by the in-repository grammar at `grammars/tsh`.
It is not routed through Java, TOML, JSON, or a generic fallback grammar. The
grammar models TSH's own blocks, key/value properties, arrays, nested objects,
regular-expression literals, CSS color literals, quoted and raw backtick strings,
annotations, numbers, booleans, and comments.

The grammar is registered in `grammars/grammars.json`, generated as the
`:tree-sitter-tsh` native module, and packaged by the sample app. Its editor
profile is `app/src/main/assets/tsh/tsh.tsh`; Tree-sitter capture definitions
are kept in `app/src/main/assets/highlights-tsh.scm`.

Performance policy:
- Structural captures use Tree-sitter nodes and field selectors.
- Common TSH keywords are captured by bounded identifier predicates.
- Color values are parsed as dedicated tokens, preserving the original source
  text rather than normalizing or replacing the configured color.
- The profile avoids document-wide regular-expression highlighting passes.
- The grammar is permissive about property names so new TSH directives remain
  parseable without requiring a parser release for every configuration option.

The first-party grammar is included explicitly by `generate_grammars.py`, so
regenerating the grammar registry does not discard it merely because it is not
a Git submodule.


### TSH grammar generation and build artifact

The independent workflow is `.github/workflows/tsh-tree-sitter-grammar.yml`; it does
not edit or replace the repository's existing build workflows. It pins
`tree-sitter-cli 0.25.10` and ABI 15. The required `grammars/tsh/tree-sitter.json`
metadata declares the grammar package and ABI-15 generation mode. The workflow
first generates the canonical
`grammars/tsh/src/grammar.json` from `grammar.js`, then generates
`src/parser.c` and `src/node-types.json` from that JSON source, including the
`src/tree_sitter/{parser.h,alloc.h,array.h}` support headers. It also parses
the repository's own `tsh.tsh` profile and rejects Tree-sitter `ERROR` or
`MISSING` nodes before compiling.

On successful generation and compilation, the workflow commits the generated
Tree-sitter source files to the feature branch and uploads the
`tsh-tree-sitter-abi15-android` artifact. The verified run generated ABI-15 sources, compiled all four Android ABIs, and
uploaded the artifact. It contains the grammar sources and support headers plus
`libtree-sitter-tsh.a` static archives for
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. These archives contain the
compiled grammar parser object; consumers link them with the matching
Tree-sitter runtime.

### TSH configuration lexer versus ATS

The native `TshLexer` in `WindbellTshCompiler.cpp` tokenizes the outer
`.tsh` configuration. It is currently an internal compiler implementation,
not a public token-stream API. ATS is a separate Tree-sitter Query/selector
front-end; the current engine does not implement standalone ATS Lexer,
Parser, AST, Compiler, or Lowering APIs. The grammar in this section therefore
parses `.tsh` configuration files, not ATS Query source. A reusable LSP-facing
lexer/token API and an ATS Query grammar require separate, explicitly scoped
implementations and must not be represented as already extracted from the
existing C++ compiler.
