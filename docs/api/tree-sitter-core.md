# Tree-sitter Core API

Package: `com.zerostudio.idea.treesitter`

These types expose the Tree-sitter parser, language, syntax tree, node and cursor operations. Byte offsets are Tree-sitter input-byte offsets; row/column coordinates are represented by `TSPoint`. Do not treat byte offsets as Java UTF-16 character indices.

## Native object lifecycle

### `TSClosable`

A closeable contract extending `AutoCloseable`.

* **Method:** `close()` releases the resource according to the implementing type.
* Callers should close native-backed objects when their use is complete. Idempotency is implemented by `TSNativeObject`, not guaranteed for arbitrary implementations of this interface.

### `TSNativeObject`

Base class for wrappers that own or refer to a native pointer.

* `getNativeObject()`: returns the current native handle.
* `canAccess()`: reports whether the wrapper currently has an accessible handle.
* `close()`: clears/releases the native object through `closeNativeObj()`.
* `checkAccess()` and `checkAccessAndGet(Supplier<T>)`: guard access to a live handle.
* **Ownership:** the wrapper controls its native handle. A handle value is an implementation detail and must not be retained as an independent lifetime guarantee.
* **Subclass contract:** implement `closeNativeObj()` to release the corresponding native resource.

### `TreeSitter`

Static library/runtime entry point.

* `loadLibrary()`: loads the native library.
* `getEngineVersion()`: returns the engine version string.
* `getLanguageVersion()` / `getMinimumCompatibleLanguageVersion()`: expose the Tree-sitter language ABI compatibility bounds.

### `TSLanguage`

Represents a loaded grammar language.

* `create(String, long)` and `create(String, long[])`: wrap the supplied native language identity/pointer representation.
* `loadLanguage(Context, String)` / `loadLanguage(String, String)`: load a language from the Android context or a library path.
* Metadata methods expose symbol count/name/type, field count/name/id, parse-state count/transitions, language version, supertypes/subtypes and external-language status.
* `close()`: releases the language wrapper's native resource.
* **Parameters:** symbol and field IDs are grammar-specific integer identifiers; callers must obtain them from the same language.
* **Returns:** lookup methods return the values supplied by the native grammar API; invalid-ID behavior is delegated to the native implementation.

### `TSLanguageCache`

Static cache for language wrappers.

* `cache(name, language)` and `cache(name, libraryPath, language)`: add a language under a name, optionally scoped by library path.
* `get(name)`, `get(name, libraryPath)`, `get(pointer)`: retrieve a cached language.
* `closeExternal()`: closes cached external language entries.
* **Ownership:** cache entries may be shared. Do not close a cached language unless the caller owns the cache lifecycle.

## Parsing

### `TSParser`

Owns a native parser and provides synchronous parse entry points.

* `create()` / `create(long)`: create or wrap a parser.
* `setLanguage(TSLanguage)`: selects the grammar used by subsequent parses.
* `parseString(String)`, `parseString(UTF16String)`, `parseBytes(byte[])`, `parseBytes(byte[], int, int)`, and `parseBuffers(ByteBuffer[])`: parse in-memory input.
* Overloads accepting `TSTree oldTree` perform an incremental parse against the supplied prior tree.
* `parseWindowed(...)`: parses through a `ParseWindowCallback`, allowing the parser to request bounded byte windows rather than requiring one contiguous input array.
* `setTimeout(long)` / `getTimeout()`: configure/read the native parse timeout in microseconds.
* `requestCancellationAsync()`: requests cancellation without waiting.
* `requestCancellationAndWait()`: requests cancellation and waits for parsing to leave its active state.
* `isParsing()`: reports the parser's active parse state.
* `setIncludedRanges(TSRange[])` / `getIncludedRanges()`: configure/read included input ranges.
* `reset()`: resets parser state.
* **Input limits:** `MAX_SUPPORTED_INPUT_BYTES` and `MAX_SEGMENTED_INPUT_BYTES` expose implementation limits. They are not a promise that every host input source can supply a document of that size.
* **Failure:** parse-in-progress and parser failures are represented by the nested `TSParserException` / `ParseInProgressException` types or native parse results as implemented.
* **Threading:** parser operations are guarded by the parser's parse lock/state. A single parser instance must not be assumed to support concurrent parse calls.

### `TSInput`

Input abstraction used by the native binding. Implementations supply the input callback/data required by the binding. Its exact callback contract is defined by the interface source.

### `TSInputEncoding`

Enum mapping supported input encodings to native flags and Java `Charset` values.

* `getFlag()`: returns the native encoding flag.
* `getCharset()`: returns the corresponding charset.

### `TSInputEdit`

Describes a text edit for incremental Tree-sitter parsing.

* `create(startByte, oldEndByte, newEndByte, startPoint, oldEndPoint, newEndPoint)`: constructs an edit.
* Getters expose the three byte offsets and the corresponding start/old-end/new-end points.
* **Coordinate contract:** offsets are input bytes; points are row/column positions in the parser's input coordinate system.
* `equals`, `hashCode` and `toString` provide value semantics.

### `TSPoint`

Immutable-by-interface row/column coordinate value.

* `create(row, column)`: creates a point.
* `getRow()` / `getColumn()`: return the stored coordinates.
* Equality and string conversion are value-based.

### `TSRange`

Represents a byte range with start/end points.

* `create(startByte, endByte, startPoint, endPoint)`: constructs a range.
* Getters expose byte offsets and endpoint coordinates.
* Equality and hash code compare range values.

## Syntax tree

### `TSTree`

Owns a parsed syntax tree.

* `create(long)`: wraps a native tree.
* `getRootNode()`: returns the root node.
* `getRootNodeWithOffset(offsetBytes, offsetExtent)`: returns a root node translated by the supplied byte/point offset.
* `getChangedRanges(oldTree)`: returns ranges changed between trees.
* `getIncludedRanges()`: returns the ranges used by the parse.
* `copy()`: creates a tree copy.
* `edit(TSInputEdit)`: applies an edit to the tree before incremental reparsing.
* `getLanguage()`: returns the associated language.
* Close the tree when it is no longer needed.

### `TSNode`

A value-like native syntax-node wrapper associated with a tree.

* Navigation: `getParent()`, `getChild(index)`, `getNamedChild(index)`, `getNextSibling()`, `getPreviousSibling()`, named-sibling variants and field-based child lookup.
* Search: `findNodeWithType`, `findChildrenWithType`, `getDescendantForByteRange`, `getDescendantForPointRange`, and named-descendant variants.
* Metadata: `getType()`, `getSymbol()`, `getGrammarSymbol()`, `getLanguage()`, `getGrammarType()`, `getFieldNameForChild()`, `getParseState()` and `getNextParseState()`.
* Ranges: `getStartByte()`, `getEndByte()`, `getStartPoint()`, `getEndPoint()`; native-specific byte/point accessors are also exposed.
* Flags: `isNull()`, `isNamed()`, `isExtra()`, `isMissing()`, `hasChanges()`, `hasErrors()`, `isError()`.
* `edit(TSInputEdit)`: updates the node after an edit.
* `walk()`: creates a cursor rooted at this node.
* `isEqualTo(TSNode)`: compares node identity/equality as implemented by the binding.
* **Lifetime:** a node is associated with its syntax tree. Keep the owning tree alive while using nodes derived from it.

### `TSTreeCursor`

Efficient mutable traversal cursor.

* `create(TSNode)` / `create(long)`: creates or wraps a cursor.
* Navigation methods move to first/last child, next/previous sibling, parent, or a descendant index and return whether the requested move succeeded where the method is boolean.
* `gotoFirstChildForByte` / `gotoFirstChildForPoint`: move to the first child containing the requested position and return the native distance/result.
* `getCurrentNode()`, `getCurrentFieldName()`, `getCurrentFieldId()`, `getCurrentTreeCursorNode()`: inspect the current cursor position.
* `getDepth()` / `getCurrentDescendantIndex()`: report traversal position.
* `reset(TSNode)`, `resetTo(TSTreeCursor)` and `copy()`: reset or duplicate cursor state.
* Close the cursor when finished.

### `TSTreeCursorNode`

Lightweight cursor-node information object containing type, field/name and byte range. It is not a replacement for `TSNode` and does not expose the full syntax-node API.

### `TSLookaheadIterator`

Iterates symbols valid from a parser state.

* `create(TSLanguage, short stateId)`: creates the iterator for a language/state pair.
* `next()`: advances to the next valid symbol.
* `getCurrentSymbol()` / `getCurrentSymbolName()`: inspect the current symbol.
* `resetState(stateId)` / `reset(language, stateId)`: reset traversal.
* `getLanguage()`: returns the associated language.
* Close the iterator when finished.

## Factories and enums

* `DefaultObjectFactory` is the default `TSObjectFactory` implementation and creates Java wrappers for native pointers and value records.
* `TSObjectFactory`, `TSObjectFactoryProvider` and `NonNullTSObjectFactory` are described in [Support and internal types](support-and-internal.md).
* `TSSymbolType`, `TSQueryError` and `TSQuantifier` map native integer identifiers to Java enum values. Use `forId` / `valueOf` rather than assuming enum ordinal values match native IDs.

## Source files

[`TSParser.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSParser.java) · [`TSLanguage.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSLanguage.java) · [`TSNode.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSNode.java) · [`TSTree.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSTree.java) · [all package sources](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter)
