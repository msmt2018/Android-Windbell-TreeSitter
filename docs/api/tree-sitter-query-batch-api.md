# Tree-sitter Query Batch Cursor API

## Purpose

The legacy `TSTags.extract`, `TSHighlight.extract`, and `TSHighlight.extractPackedSpans` methods materialize a complete query result before returning. Their `maxResults`, `maxPendingMatches`, and `maxSpans` parameters are compatibility guards; they are not a scalable result-delivery protocol.

Large-document and dense-query callers should use the resumable cursor APIs:

- `TSTags.openCursor(...)` + `TSTags.Cursor.nextBatch(maxMatches)`
- `TSHighlight.openCursor(...)` + `TSHighlight.Cursor.nextBatch(maxSpans)`

A batch size is a delivery quantum only. It is not a total result count, and exhausting one batch does not end the query. The cursor retains Tree-sitter's native execution state between calls. The caller must close the cursor when complete or abandoned.

## Highlight batch record

Each packed span uses six `long` values:

1. absolute unsigned `startByte`
2. absolute unsigned `endByte`
3. query `captureId`
4. `patternIndex`
5. resolved `styleId`
6. Tree-sitter `matchId` (widened as unsigned uint32)

A match with more captures than the requested batch size is continued across subsequent batches. Match id, pattern index and capture id are preserved. The cursor follows Tree-sitter's match emission order; it does not retain the entire document result merely to perform a global sort.

Negative style ids suppress individual captures without suppressing other captures from the same match.

## Pending matches and work cancellation

A `maxPendingMatches` value of zero leaves Tree-sitter's default dynamically-growing pending-match policy unchanged. A positive value is an explicit caller-selected pending-state policy. This is distinct from the output batch size and does not limit the total number of completed matches.

If Tree-sitter reports `didExceedMatchLimit()`, the cursor has dropped pending matches and the overall result is incomplete. The batch exposes this state; callers must not publish it as a complete highlight/tag result.

The optional progress callback can cancel a long-running native query. Cancellation is terminal for that cursor; it is not a continuation token. Normal batch boundaries are resumable because the cursor is not re-executed between batches.

## Lifetime and coordinate safety

- The query and syntax tree must remain open and unchanged for the cursor's lifetime.
- Close the cursor before closing its query or tree.
- The long byte-range overload validates the full unsigned 32-bit Tree-sitter byte-offset domain before narrowing to the C API.
- Tree-sitter's public `TSQueryMatch.capture_count` remains a uint16 ABI field. The batch APIs do not widen or reinterpret that upstream ABI. A single match cannot be represented beyond that upstream per-match capture-count boundary without an upstream ABI change.
- Java array lengths and Native `jsize` remain checked representation boundaries; they are not used as semantic total-result caps by the cursor APIs.

## Compatibility APIs

The all-at-once extraction methods remain available for source compatibility and are deprecated. They should be used only for intentionally small results. New large-file, editor, tags and highlight integrations should consume batches and retain only the viewport/working set they need.
