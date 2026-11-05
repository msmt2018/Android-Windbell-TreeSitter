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
