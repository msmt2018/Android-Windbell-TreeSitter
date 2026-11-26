# Text, UTF-16 and Large-Document API

Packages: `com.zerostudio.idea.treesitter.string`, `com.zerostudio.idea.treesitter.largefile`, and selected `tsh.api` text types.

## UTF-16 native string

### `UTF16String`

A native-backed mutable `CharSequence` storing UTF-16 data.

* `byteAt(index)` / `setByteAt(index, value)`: access an individual stored byte.
* `charAt(index)` / `setCharAt(index, value)`: access a UTF-16 code unit.
* `append`, `insert`, `delete`, `replaceChars`: mutate character ranges.
* `deleteBytes`, `replaceBytes`: mutate byte ranges.
* `subseqChars` / `substringChars`: operate in character-index coordinates.
* `subseqBytes` / `substringBytes`: operate in byte-index coordinates.
* `length()`: UTF-16 code-unit count; `byteLength()`: stored byte count.
* `subSequence(start, end)`: implements the `CharSequence` contract.
* `forEachChar` / `forEachByte`: visit the selected range.
* `synchronizedString()`: returns the synchronized representation exposed by the implementation.
* **Important:** byte indices and character indices are different coordinate spaces. Do not pass one where the other is expected.
* **Ownership:** this is a native-backed object; call `close()` when finished.

### `UTF16StringFactory`

Creates `UTF16String` instances from empty input, Java strings or byte arrays. Overloads allow selecting synchronization behavior and byte-array offset/length.

### `StringDecoder`

Converts byte arrays to Java strings using the default decoder or an explicitly supplied `Charset`.

### `Assertions`

Package-private range/index validation helpers; not a public application API.

## Large-file primitives

### `BoundedLruCache<K, V>`

A bounded least-recently-used cache. The constructor configures the bound; cache operations and eviction behavior are defined by the class implementation. It is an internal utility rather than a document store.

### `CapturePageCache<T>`

Stores capture pages keyed by the cache's page identity and manages bounded reuse. `CapturePage<T>` is the page value type. Cache correctness still depends on the caller supplying keys that include the relevant document/query revisions.

### `CharSequenceParseWindowCallback`

Adapts a `CharSequence` to the parser's window-read callback. Reads are requested using byte offsets and a maximum byte count; the adapter translates those requests to UTF-16 text and returns encoded bytes.

### `CharSequenceUtf16DocumentSource`

Provides UTF-16 document access over a `CharSequence` for the window callback path. It exposes the source size and read operations required by the adapter.

### `Utf16DocumentSource`

Document source abstraction used by partition management. Its offsets are UTF-16 based as named in the interface.

### `DocumentPartition`

Describes a partition in a document, including its range and associated partition metadata.

### `DocumentPartitionManager`

Maintains document partitions and viewport-related plans.

* `ViewportPlan` represents the computed viewport work/range information.
* `CaptureCacheKey` identifies a capture-cache entry.
* `TokenizerCheckpoint` stores tokenizer checkpoint state.
* `TokenizerCheckpointStore` stores and retrieves tokenizer checkpoints.
* Internal compact offset/point indexes and `ByteBudgetLruCache` provide storage/indexing support.
* The class uses explicit document/version and byte/line metadata; callers must keep those values coherent with the source document.

### `PartitionManager`

Maintains character-range partitions and computes a viewport over the partition model.

* Nested `Partition` describes a partition.
* Nested `Viewport(startChar, endChar)` describes the requested character range.
* Use the coordinate units specified by the nested type; these are character offsets, not Tree-sitter byte offsets.

## TSH document abstractions

### `TshTextSnapshot`

Read-only snapshot interface with document ID, version, UTF-16 length and `readUtf16(start, end)`. Optional line/column lookup is advertised by `supportsLineColumnLookup`.

### `TshTextSnapshotLease`

Retained access to a snapshot. `retain()` acquires another lease when supported; `close()` releases the lease. A lease must remain open while the snapshot is being consumed.

### `TshSharedTextSnapshotLease`

Reference-counted lease implementation. The final release invokes the supplied cleanup callback; release diagnostics are exposed by the implementation.

### `TshTextDocument`

Host document contract exposing document ID, language ID, version and a snapshot.

### `TshTextEdit` and `TshTextChange`

Represent edits and a version transition. Edit offsets and replacement lengths are UTF-16 code units. Optional line/column and line-feed metadata allow the edit compiler to avoid rescanning text for endpoint coordinates.

### `TshTextAccessProvider`

Provider contract for reading host documents. It advertises threading/read concurrency and optional change/disposal notifications, then supplies snapshots, leases, chunks and subscriptions. See [TSH API foundation](tsh-api-foundation.md) for provider lifecycle details.

## Coordinate indexes and parse input

* `TshSparseCoordinateIndex` builds sparse checkpoints to convert among UTF-16, UTF-8, code-point and line/column coordinates without retaining a full per-character index.
* `TshLineColumn` stores a line and UTF-16 column.
* `TshSnapshotTreeSitterInput` adapts a retained text snapshot to the parser's byte-window callback. The implementation uses UTF-16LE byte offsets; byte offsets and lengths must therefore be aligned to UTF-16 code-unit boundaries.
* `TshTreeSitterEditCompiler` converts `TshTextChange` edits into native `TSInputEdit` values, resolving points from snapshot text when endpoint metadata is absent.
* `TshTreeSitterIncrementalUpdate` applies a document change to the owned tree/parser path and returns a structured `TshResult`.

## Sources

[largefile](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/largefile) · [string](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/string) · [TshTextDocument.kt](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh/api/TshTextDocument.kt)
