# TSH Memory Budget API

## Purpose

`TshMemoryBudget` is a platform-neutral reservation ledger for memory that TSH can estimate or explicitly request before allocation. It provides a shared accounting boundary across engine subsystems without coupling the API module to Android runtime APIs.

It is not a process-RSS monitor and does not measure allocations made internally by Tree-sitter, JNI, JVM, or third-party providers. Native allocations must be treated as separately estimated or opaque until their allocation sites are explicitly instrumented.

## Policy

- Default soft operating target: 100 MiB.
- Absolute hard limit: 512 MiB.
- A caller may provide `availableMemoryBytes`; by default the effective limit is the minimum of target, hard limit, and one quarter of supplied available memory.
- The API does not probe Android or operating-system memory. The host supplies the available-memory value.
- Every configured byte limit must be positive; target cannot exceed hard limit; hard limit cannot exceed 512 MiB. The reservation ledger is count-bounded by `maxActiveReservations` (65,536 by default, 262,144 absolute maximum) so a flood of tiny reservations cannot exhaust memory through ledger metadata. The limit is enforced atomically by `reserve()` and a released slot can be reused.

## Categories

`TshMemoryCategory` separates reservations for text snapshots, parser input, syntax trees, query cursor working sets, query results, regex windows, match sets, render plans, coordinate indexes, and other TSH-owned allocations.

Category accounting reports reservations, not measured actual allocation sizes.

## Lifecycle

1. Call `reserve(category, bytes, ownerId)` before allocating the corresponding TSH-managed data.
2. On success, retain the returned `TshMemoryReservation` for as long as that data remains live.
3. Close the reservation exactly when the corresponding data is released. Closing is idempotent.
4. Closing the budget prevents new reservations. Existing reservation tokens remain able to release their accounting.
5. Reservation and release operations are synchronized; overflow-safe capacity checks prevent the total from crossing the configured limit.

## Query execution admission

`TshQueryExecutionBudget` keeps explicit caller-policy fields for maximum matches, captures, and estimated result bytes, but their defaults no longer impose semantic result ceilings. Engine-created executors share `engine.memoryBudget`; standalone executors receive a bounded local governor.

Before creating a native cursor, the executor reserves a working-set allowance under `QUERY_CURSOR`. The native pending-match limit is derived from the currently available ledger capacity and a conservative per-pending-match estimate. This is a resource-admission threshold, not a total-match limit. Tree-sitter may report that its pending-match limit was exceeded; in that case the result is explicitly incomplete and carries a warning. This API does not yet resume a cursor after Tree-sitter has discarded pending matches.

Each materialized capture grows a `QUERY_RESULT` reservation before it is published. The resulting `TshQueryResult` owns that reservation and implements `AutoCloseable`; callers must close the result when its captures are no longer retained. The estimates cover retained capture strings and object/list overhead heuristics, not opaque Native cursor/tree allocations or exact JVM heap layout.

## Unified MatchSet merge admission

`TshMatchMerger` accepts `TshMatchMergeBudget`. Its default count and estimated-byte policy fields no longer impose fixed semantic ceilings; the budget's `TshMemoryBudget` controls admission. Use `engine.createMatchMergeBudget()` to share the Engine ledger, or the default constructor for a bounded standalone ledger.

The merger reserves/resizes `MATCH_SET` before accepting each unique Match. The resulting `TshMatchSet` owns the reservation and implements `AutoCloseable`; callers must close it when its matches are no longer retained. It validates and deduplicates in one pass. Its estimate includes Match/provenance fields, nested capture text, scope paths, and conservative map/list overhead. Admission failure returns a `TRUNCATED` MatchSet with a warning. Merge diagnostics are still aggregated with a bounded 4,096-entry diagnostic policy.

The input `Collection` already exists and is not included in the MatchSet reservation. Estimates are not JVM heap measurements.

## Regex window leases

`TshRegexWindowLease.acquire()` copies one contiguous UTF-16 slice from a retained immutable snapshot. A caller must declare a finite maximum match length, maximum lookbehind, and maximum lookahead. The planner derives the window around a core interval; only matches whose absolute start is inside that core interval belong to the window. This prevents adjacent windows from emitting the same match twice.

Unbounded patterns are rejected. Core boundaries that split surrogate pairs are rejected, while context edges are expanded to keep complete code points. The lease exposes whether the window touches the actual document start/end so the Regex executor can preserve anchor semantics; the executor must not treat an interior window edge as a document boundary. The optional `REGEX_WINDOW` reservation covers a conservative estimate for both the provider-returned temporary sequence and the retained String copy, and is released by `close()`.

This is an input-window resource contract only. It does not compile or execute Regex, preserve Oniguruma engine state across windows, or prove correctness for patterns whose finite maximum match/lookaround cannot be established.

## Current limitations

- `TEXT_SNAPSHOT` remains provider-owned: snapshot allocation and backing-store memory are not currently charged to the Engine ledger. The lease contract controls lifetime, but does not estimate provider storage.
- The Native Tags/Highlight JNI path still accumulates all query matches/captures and the final span array before returning. Its current `maxResults`, `maxPendingMatches`, total-capture, `maxSpans`, and progress-callback ceilings are not yet derived from the shared ledger. This requires a Native result arena/batch-cursor contract; simply deleting those ceilings would make peak allocations uncontrolled.
- The Native Regex executor still has a fixed 1 Mi UTF-16-unit input-window ceiling and caller-supplied match/capture-group budgets. The 1 Mi window ceiling is a tuning restriction, not Oniguruma's actual `int` ABI boundary; the ABI boundary must be checked separately when replacing it with adaptive window admission.
- Sparse coordinate-index construction always reserves its checkpoint upper bound under `COORDINATE_INDEX` before allocating the checkpoint list. Standalone builds use a local bounded ledger; pass `engine.memoryBudget` to share the Engine ledger. The reservation is held until the index closes.
- Pending RenderPlans reserve an estimated byte amount under `RENDER_PLAN` before entering the provider queue. The reservation is released on completion, rejection, cancellation, or coordinator shutdown. `engine.createRenderCoordinator(provider)` connects the coordinator to the Engine ledger; standalone coordinators receive a local bounded ledger. The explicit `maxPendingPlans` option remains available as caller policy, but its default is not a fixed count ceiling.
- `TshEngine` owns one shared `TshMemoryBudget`, configured by `TshEngineOptions.memoryBudgetOptions`, and exposes its accounting snapshot through `engine.memoryBudget.snapshot()`. Engine shutdown retires Native resources before closing the ledger; outstanding reservations remain releasable after the budget is closed.
- Snapshot-backed `TshTreeSitterParserOwner.parse(snapshotLease, ...)` reserves `2 * chunkSizeBytes + fixed overhead` under `PARSER_INPUT` for the duration of the parse. This reflects the current callback implementation, which allocates a destination `ByteArray` and may allocate a trimmed copy. It does not count provider-created UTF-16 `String` objects or opaque allocations made by Tree-sitter/JNI. The reservation is released in `finally` on success or failure.
- The legacy `TshTreeSitterParserOwner.parse(..., source: String)` path reserves `2 * source.length + fixed overhead` before `TSParser.parseString()` starts, then releases it after parsing. This does not account for the caller's already-existing JVM String; large host documents should use the Snapshot/Piece Tree streaming overload.
- `TshEngine.openTreeSitterParser()` and `TshTreeSitterParserOwner.open()` accept an optional `TshNativeTreeMemoryEstimator`. It receives document/version/grammar/source-size/incremental metadata and supplies the tree reservation amount. The Engine pairs it with its shared budget. No default multiplier is guessed because tree density and Native allocator usage are grammar- and input-dependent.
- When an estimator is configured, the parser reserves its positive estimate under `SYNTAX_TREE` before parsing and transfers that reservation into the Native resource registry with the resulting tree. The reservation remains live across active tree leases and is released only after the tree is actually closed. During incremental parsing, the old tree's reservation remains live while the new tree's estimate is admitted, accounting for the overlap peak. This is admission accounting based on a host estimate, not a measurement or hard guarantee of Tree-sitter Native heap usage.
- Regex windows can optionally reserve a bounded temporary-plus-retained UTF-16 payload estimate under `REGEX_WINDOW`; the reservation is held until the window lease closes.
- Tree-sitter Native allocation size is not precisely observable through the current public binding; the ledger must not be presented as a guarantee over those opaque allocations.
- The API does not itself implement eviction, task scheduling, cancellation, or process-wide memory-pressure callbacks. Callers must react to reservation failures by reducing work, deferring, cancelling safely, or using bounded windows.
