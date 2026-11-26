# Support and Internal API Types

Packages: `com.zerostudio.idea.treesitter.internal`, `predicate`, `util`, and package-private support types.

These classes support the public wrappers and runtime. Types marked package-private/internal are not stable application integration points.

## Object factories

### `TSObjectFactory`

Factory interface used by the binding to construct Java wrapper/value objects from native results. It creates parser/query/cursor/tree/node/language/string wrappers and query/range/point/edit records.

### `TSObjectFactoryProvider`

Static access point for the active `TSObjectFactory`. `setFactory(factory)` replaces the configured factory; `getFactory()` returns it. Changing the factory affects subsequent wrapper construction and should be done during controlled initialization, not concurrently with native object creation.

### `DefaultObjectFactory`

Default implementation of `TSObjectFactory`. It constructs the standard wrapper classes and arrays used by the binding.

### `NonNullTSObjectFactory`

Internal factory variant that creates non-null wrapper/value objects for native results. It is an implementation detail and is not a public construction API.

### `NativeObjectFactory`

Package-internal native-object construction helper. Not an application API.

## Predicate support

### `TSPredicateHandler`

See [Query and tags](query-and-tags.md). The predicate package contains the custom predicate contract and built-in `SetDirectiveHandler`.

### `TSPredicateHandlerCompat`

Compatibility helper exposing the supported predicate names for a handler.

## Utility types

* `Consumer<T>`: minimal single-method consumer callback used by the Java binding.
* `Pair<F, S>`: simple pair with public `first` and `second` fields and `of(first, second)` factory.
* `PredicateUtils`: predicate argument-type matching helper.
* `StringDecoder`: byte-array to string conversion; see [Text and large documents](text-and-large-documents.md).
* `Assertions`: package-private validation helper; not a public API.

## Source inventory

| Source | Visibility / role |
|---|---|
| `internal/NativeObjectFactory.java` | package-private native wrapper factory |
| `predicate/TSPredicateHandler.java` | public predicate extension contract |
| `predicate/SetDirectiveHandler.java` | public built-in directive handler |
| `tsh/api/TSPredicateHandlerCompat.java` | public compatibility helper |
| `util/TSObjectFactory.java` | public factory contract |
| `util/TSObjectFactoryProvider.java` | public factory provider |
| `util/NonNullTSObjectFactory.java` | package-private implementation |
| `util/Consumer.java` | public callback interface |
| `util/Pair.java` | public pair value |
| `util/PredicateUtils.java` | public predicate helper |
| `string/Assertions.java` | package-private validation helper |
| `string/StringDecoder.java` | public string decoder |
| `string/UTF16StringFactory.java` | public native string factory |

## Source links

[internal](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/internal) · [predicate](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/predicate) · [util](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/util)
