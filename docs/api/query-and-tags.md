# Query, Predicate, Highlight and Tag API

Package: `com.zerostudio.idea.treesitter`

## Query compilation

### `TSQuery`

Represents a compiled Tree-sitter query.

* `create(TSLanguage, String querySource)`: compiles query source against the supplied grammar.
* `isValid()`: reports whether compilation produced a valid query.
* `getErrorOffset()` / `getErrorType()`: expose compilation error location/type.
* Query metadata: capture count/names, pattern count, string count, pattern start byte, predicates for a pattern, rooted/non-local flags and whether a pattern is guaranteed at a step.
* `getCaptureNameForId(id)`, `getStringValueForId(id)`, `getCaptureQuantifierForId(pattern, capture)`: resolve query-local identifiers.
* **Grammar affinity:** a query is compiled for the supplied `TSLanguage`; execute it only with syntax trees built using a compatible grammar.
* **Lifetime:** close the query after all executions using it have completed.

### `TSQueryError`, `TSQuantifier`, `TSQueryPredicateStep`

* `TSQueryError.valueOf(int)` maps native query compilation error codes.
* `TSQuantifier.forId(int)` maps capture quantifier IDs.
* `TSQueryPredicateStep` stores a predicate argument's type and query-local value ID. `getType()` and `getValueId()` expose those values. Its nested `Type.forId(int)` maps native step types.

## Query execution

### `TSQueryCursor`

Executes a compiled query against a syntax node.

* `create()` / `create(long)`: creates or wraps a native cursor.
* `exec(query, node)` and `exec(query, node, ProgressCallback)`: begin execution.
* `iterator()` and `nextMatch()`: retrieve matches.
* `setByteRange(int, int)` / `setByteRange(long, long)`: restrict execution to a byte range.
* `setPointRange(start, end)`: restrict execution to a point range.
* `setMatchLimit`, `getMatchLimit`, `didExceedMatchLimit`: configure and inspect match limits.
* `setStepLimit`, `getStepsUsed`, `didExceedStepLimit`: configure and inspect step budgets.
* `setAllowChangedNodes` / `isAllowChangedNodes`: control whether changed nodes are eligible.
* `setPredicateEvaluationEnabled(boolean)`: enable/disable predicate evaluation.
* `addPredicateHandler` / `removePredicateHandler`: register custom predicate handlers.
* `removeMatch(id)`: removes a match from cursor tracking.
* **Execution state:** range/limit/predicate settings are cursor execution controls. Configure them before execution unless the implementation explicitly permits otherwise.
* Close the cursor after iteration.

### `TSQueryMatch`

Contains a match ID, pattern index and array of captures.

* `create(id, patternIndex, captures)`: creates a match wrapper.
* `getId()`, `getPatternIndex()`, `getCaptures()`, `getCapture(index)`: access match data.
* `getMetadata()`: returns match metadata.
* Nested `Metadata` supports key/value storage, typed string access and per-capture metadata.
* Capture indices are query-local capture-name IDs, not source offsets.

### `TSQueryCapture`

Pairs a `TSNode` with its capture-name index.

* `create(node, index)`: creates a capture.
* `getNode()` / `getIndex()`: return the captured node and capture index.

## Predicates

### `TSPredicateHandler`

Custom query-predicate extension contract.

* `getSupportedPredicates()`: names handled by the implementation.
* `handle(query, match, args)`: evaluates the predicate and returns a `Result`.
* `PredicateStep` contains the parsed argument `Type` and string value.
* **Result:** use the enum values defined in the source to distinguish predicate handling outcomes; do not infer success solely from handler registration.

### `SetDirectiveHandler`

Built-in handler for the `set!` directive.

* `DIRECTIVE` and `SUPPORTED_PREDICATES` identify the directive name.
* `handle(...)` interprets the directive arguments and stores the directive metadata on the match according to the implementation.

## Highlight extraction

### `TSHighlight`

Convenience API that runs a query and converts capture results to highlight spans.

* `extract(...)`: returns query matches for the supplied tree/query inputs.
* `toSpans(...)`: converts matches/captures to `TSHighlightSpan` values.
* `extractPackedSpans(...)`: returns the compact packed representation.
* `unpackSpans(long[])`: decodes packed span records.
* `extractSpans(...)`: extracts span objects directly.
* `openCursor(...)`: opens a batched highlight cursor. Overloads accept the source/query/tree and optional execution controls exposed by the signatures.
* **Batching:** `Cursor.nextBatch(maxSpans)` returns at most the requested number of spans in one batch. Inspect `Batch.isComplete()`, `didExceedMatchLimit()` and `isCancelled()` to determine completion state.
* `Cursor.didExceedMatchLimit()`: reports whether the query cursor exceeded its configured match limit.
* Close the cursor when no further batches are required.

### `TSHighlightSpan`

Immutable highlight record containing start/end byte offsets, capture ID, pattern index, style ID and match ID.

* Constructor parameters define the stored capture span and its metadata.
* Getters expose each field.
* `getStartByte()` / `getEndByte()` return unsigned 32-bit byte offsets widened to `long`.

### `TSTags` and `TSTag`

Extract language tags (definitions/references/scopes/documentation nodes) from query matches.

* `TSTags.extract(...)`: executes/extracts matches for the supplied query and syntax input.
* `TSTags.toTags(query, matches)`: maps query matches to `TSTag` records.
* `TSTags.openCursor(...)`: provides batched match extraction. `Cursor.nextBatch(maxMatches)` returns a batch; inspect completion and limit/cancellation flags.
* `TSTag.Kind` identifies the tag kind. Getters expose subject, name, scope, documentation, pattern index, match ID and byte range.
* Tag interpretation depends on the query capture conventions used by the caller; this API does not infer arbitrary language semantics without a matching query.

## Predicate utility

`PredicateUtils.matchesArgTypes(expected, actual)` checks whether predicate argument-step types match the expected type sequence.

## Sources

[`TSQuery.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSQuery.java) · [`TSQueryCursor.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSQueryCursor.java) · [`TSHighlight.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSHighlight.java) · [`TSTags.java`](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/TSTags.java)
