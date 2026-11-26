# Java/Kotlin API Reference

> **Source of truth:** the implementation under [`android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter). This reference describes the checked-in declarations and behavior only. It does not define planned APIs.

This directory is a source-oriented API reference. Its organization follows JavaDoc/KDoc conventions: type purpose, ownership, parameter and return semantics, failure behavior, and lifecycle notes. Public signatures remain defined by the source files; when a detail is not stated or enforced there, this reference marks it as unspecified rather than inventing a contract.

## Packages

| Reference | Package | Responsibility |
|---|---|---|
| [Tree-sitter core](tree-sitter-core.md) | `com.zerostudio.idea.treesitter` | Native wrapper types, parsing, syntax nodes, trees and cursors |
| [Query and tags](query-and-tags.md) | `com.zerostudio.idea.treesitter` | Query compilation/execution, captures, predicates, highlighting and tag extraction |
| [Text and large documents](text-and-large-documents.md) | `largefile`, `string` | UTF-16 storage, parse windows, document partitions and bounded caches |
| [TSH runtime](tsh-runtime.md) | `tsh` | Grammar discovery/resolution, binding, parser sessions and the legacy engine facade |
| [TSH API foundation](tsh-api-foundation.md) | `tsh.api` | Engine lifecycle, diagnostics, providers, attachments, documents, matching and rendering contracts |
| [TSH Android rendering](tsh-android-rendering.md) | `tsh.android` | Android Spannable binding and renderer integration |
| [Support and internal types](support-and-internal.md) | `internal`, `predicate`, `util` | Factories, predicate handlers and implementation support types |

## API status and boundaries

* The root Tree-sitter wrappers are Java-facing wrappers around native Tree-sitter objects. Their native handles are managed by `TSNativeObject` and `TSClosable`.
* The TSH implementation currently contains both `WindbellTshEngine` and the newer `tsh.api` foundation. The latter must not be described as a complete replacement for the former.
* `tsh.api` contains models and runtime adapters for the capabilities represented by its source. It does not, by itself, imply that a complete text DSL parser/compiler, LSP, completion engine, editor framework, or every planned feature exists.
* Large-document classes expose concrete window, partition, cache and snapshot primitives. Actual limits and guarantees are those enforced by the implementation and the supplied host objects.
* Android rendering classes operate on Android text/spans. They do not establish a renderer-independent guarantee that every style attribute is painted by every host.

## Documentation conventions

* **Parameters** describe the values accepted by the declaration.
* **Returns** describe the value produced, including nullable/empty outcomes where visible in the implementation.
* **Throws / failure** is documented only where the source explicitly throws, returns a failure result, or delegates to a native operation with a defined error value.
* **Ownership** distinguishes caller-owned objects, retained leases and objects closed by an owner.
* **Threading** is documented only where synchronization, locks, callbacks or provider contracts establish it. No implicit main-thread guarantee is assumed.

## Source navigation

[Java/Kotlin source package](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter) · [Android module](../../android-tree-sitter)
