# TSH Memory Budget API

## Purpose

`TshMemoryBudget` is a platform-neutral reservation ledger for memory that TSH can estimate or explicitly request before allocation. It provides a shared accounting boundary across engine subsystems without coupling the API module to Android runtime APIs.

It is not a process-RSS monitor and does not measure allocations made internally by Tree-sitter, JNI, JVM, or third-party providers. Native allocations must be treated as separately estimated or opaque until their allocation sites are explicitly instrumented.

## Policy

- Default soft operating target: 100 MiB.
- Absolute hard limit: 512 MiB.
- A caller may provide `availableMemoryBytes`; by default the effective limit is the minimum of target, hard limit, and one quarter of supplied available memory.
- The API does not probe Android or operating-system memory. The host supplies the available-memory value.
- Every configured limit must be positive; target cannot exceed hard limit; hard limit cannot exceed 512 MiB.

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

## Current limitations

- Query result limits do not yet retain a `TshMemoryReservation` for the full lifetime of `TshQueryResult`.
- Sparse coordinate-index checkpoints can optionally reserve an estimated budget before allocation; the reservation is held until the index closes.
- RenderCoordinator can optionally reserve an estimated immutable RenderPlan footprint while a plan is pending. The reservation is released on terminal receipt, successful cancellation, submission failure, invalid/stale receipt, or coordinator close.
- Snapshot-backed Tree-sitter parser input can optionally reserve the maximum temporary UTF-16 chunk estimate for each callback; the reservation is released in `finally` after copying or failure. The Native destination buffer and Tree-sitter internal allocations are not counted.
- Native syntax-tree internals and Regex windows are not yet comprehensively wired to this ledger.
- Tree-sitter Native allocation size is not precisely observable through the current public binding; the ledger must not be presented as a guarantee over those opaque allocations.
- The API does not itself implement eviction, task scheduling, cancellation, or process-wide memory-pressure callbacks. Callers must react to reservation failures by reducing work, deferring, cancelling safely, or using bounded windows.
