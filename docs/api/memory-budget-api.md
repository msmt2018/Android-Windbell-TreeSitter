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

`TshMemoryCategory` separates reservations for text snapshots, parser input, syntax trees, query results, regex windows, match sets, render plans, coordinate indexes, and other TSH-owned allocations.

Category accounting reports reservations, not measured actual allocation sizes.

## Lifecycle

1. Call `reserve(category, bytes, ownerId)` before allocating the corresponding TSH-managed data.
2. On success, retain the returned `TshMemoryReservation` for as long as that data remains live.
3. Close the reservation exactly when the corresponding data is released. Closing is idempotent.
4. Closing the budget prevents new reservations. Existing reservation tokens remain able to release their accounting.
5. Reservation and release operations are synchronized; overflow-safe capacity checks prevent the total from crossing the configured limit.

## Query execution limits

`TshQueryExecutionBudget` independently bounds:
- total matches drained from the cursor;
- total captures materialized into the public result;
- estimated retained result bytes.

The capture estimate accounts for UTF-16 string payload lengths and a fixed per-capture overhead. It is a conservative accounting heuristic, not a JVM object-layout guarantee. If any query result limit is reached, the executor returns a partial result and warning diagnostic rather than continuing to accumulate results.

## Unified MatchSet merge limits

`TshMatchMerger` accepts `TshMatchMergeBudget` (default 250,000 candidate matches and 64 MiB estimated merge working set). It validates and deduplicates in one pass instead of materializing several full-size filter/groupBy/map copies. `TshMatchSet` constructor invariants are also checked in one pass with a single ID set, avoiding repeated `filter`/`map`/`distinct` temporary lists. Its estimate includes Match/provenance fields, nested capture text, scope paths, and fixed map/sort overhead. Reaching either limit returns a `TRUNCATED` MatchSet with a memory warning. Merge diagnostics are capped at 4,096 entries.

These limits account for the merger's additional working set only. The input `Collection` already exists and is not included; upstream Tree-sitter/Regex execution must enforce its own result limits. The estimate is not a JVM heap measurement.

## Regex window leases

`TshRegexWindowLease.acquire()` copies one contiguous UTF-16 slice from a retained immutable snapshot. A caller must declare a finite maximum match length, maximum lookbehind, and maximum lookahead. The planner derives the window around a core interval; only matches whose absolute start is inside that core interval belong to the window. This prevents adjacent windows from emitting the same match twice.

Unbounded patterns are rejected. Core boundaries that split surrogate pairs are rejected, while context edges are expanded to keep complete code points. The lease exposes whether the window touches the actual document start/end so the Regex executor can preserve anchor semantics; the executor must not treat an interior window edge as a document boundary. The optional `REGEX_WINDOW` reservation covers a conservative estimate for both the provider-returned temporary sequence and the retained String copy, and is released by `close()`.

This is an input-window resource contract only. It does not compile or execute Regex, preserve Oniguruma engine state across windows, or prove correctness for patterns whose finite maximum match/lookaround cannot be established.

## Current limitations

- Query result limits do not yet retain a `TshMemoryReservation` for the full lifetime of `TshQueryResult`.
- The enum also exposes `TEXT_SNAPSHOT`, `QUERY_RESULT`, and `MATCH_SET`, but these categories are not yet wired to shared-ledger reservations. Query `maxResultBytes` and MatchSet merge limits are per-operation estimates/caps, not global admission reservations; snapshot lease lifetime is currently managed by the provider/lease contract.
- Sparse coordinate-index checkpoints can optionally reserve an estimated budget before allocation; the reservation is held until the index closes.
- `TshRenderCoordinator` does **not** yet reserve RenderPlan bytes in the shared ledger. It currently bounds pending work by `maxPendingPlans` (a count limit), not by estimated bytes; `TshMemoryCategory.RENDER_PLAN` is reserved for a future integration.
- `TshEngine` owns one shared `TshMemoryBudget`, configured by `TshEngineOptions.memoryBudgetOptions`, and exposes its accounting snapshot through `engine.memoryBudget.snapshot()`. Engine shutdown retires Native resources before closing the ledger; outstanding reservations remain releasable after the budget is closed.
- Snapshot-backed `TshTreeSitterParserOwner.parse(snapshotLease, ...)` reserves `2 * chunkSizeBytes + fixed overhead` under `PARSER_INPUT` for the duration of the parse. This reflects the current callback implementation, which allocates a destination `ByteArray` and may allocate a trimmed copy. It does not count provider-created UTF-16 `String` objects or opaque allocations made by Tree-sitter/JNI. The reservation is released in `finally` on success or failure.
- The legacy `TshTreeSitterParserOwner.parse(..., source: String)` path reserves `2 * source.length + fixed overhead` before `TSParser.parseString()` starts, then releases it after parsing. This does not account for the caller's already-existing JVM String; large host documents should use the Snapshot/Piece Tree streaming overload.
- `TshEngine.openTreeSitterParser()` and `TshTreeSitterParserOwner.open()` accept an optional `TshNativeTreeMemoryEstimator`. It receives document/version/grammar/source-size/incremental metadata and supplies the tree reservation amount. The Engine pairs it with its shared budget. No default multiplier is guessed because tree density and Native allocator usage are grammar- and input-dependent.
- When an estimator is configured, the parser reserves its positive estimate under `SYNTAX_TREE` before parsing and transfers that reservation into the Native resource registry with the resulting tree. The reservation remains live across active tree leases and is released only after the tree is actually closed. During incremental parsing, the old tree's reservation remains live while the new tree's estimate is admitted, accounting for the overlap peak. This is admission accounting based on a host estimate, not a measurement or hard guarantee of Tree-sitter Native heap usage.
- Regex windows can optionally reserve a bounded temporary-plus-retained UTF-16 payload estimate under `REGEX_WINDOW`; the reservation is held until the window lease closes.
- Tree-sitter Native allocation size is not precisely observable through the current public binding; the ledger must not be presented as a guarantee over those opaque allocations.
- The API does not itself implement eviction, task scheduling, cancellation, or process-wide memory-pressure callbacks. Callers must react to reservation failures by reducing work, deferring, cancelling safely, or using bounded windows.
