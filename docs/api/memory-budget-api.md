# TSH Memory Budget API

## Purpose and accounting boundary

`TshMemoryBudget` is the shared admission ledger for TSH-owned or explicitly estimated allocations. It coordinates reservations across parser input, syntax-tree estimates, query cursors/results, Regex windows, MatchSets, render plans and sparse coordinate indexes.

It is not a process-RSS monitor. It does not measure allocations internal to Tree-sitter, JNI, the JVM, Oniguruma or third-party providers. Native and JVM estimates must remain labelled estimates; a successful reservation is admission control, not proof that the process has that exact amount of free physical memory.

## Policy

- Default operating target: 100 MiB.
- Absolute hard limit: 512 MiB.
- If the host supplies `availableMemoryBytes`, the effective limit is the minimum of target, hard limit and one quarter of supplied available memory.
- The API does not probe Android or operating-system memory itself; the host supplies available-memory information.
- The reservation ledger is count-bounded (65,536 active reservations by default; 262,144 absolute maximum) to prevent a flood of tiny reservations from exhausting ledger metadata.
- Reservation and resize operations enforce overflow-safe limits atomically. Closing a reservation is idempotent; closing the budget prevents new reservations but does not prevent existing reservations from being released.

## Categories

`TshMemoryCategory` currently separates:

- `TEXT_SNAPSHOT`, `PARSER_INPUT`, `SYNTAX_TREE`
- `QUERY_CURSOR`, `QUERY_RESULT`
- `REGEX_WINDOW`
- `MATCH_SET`, `RENDER_PLAN`
- `COORDINATE_INDEX`, `OTHER`

Category totals are reservation estimates, not measured allocation sizes.

## Lifecycle contract

1. Reserve before allocating the corresponding TSH-managed or estimated data.
2. Keep the returned `TshMemoryReservation` alive for as long as that data remains retained.
3. Resize the same reservation when a retained buffer grows; do not create one reservation per append.
4. Close the reservation when the corresponding result, cache entry, index, window or pending render plan is released.
5. On admission failure, reduce the work unit, release stale/cold state, defer, cancel safely or return an explicit incomplete result. Never silently publish a partial result as complete.

## Tree-sitter query execution

`TshQueryExecutionBudget` defaults `maxMatches`, `maxCaptures` and `maxResultBytes` to their representation maxima; these defaults are not semantic result-count ceilings. Callers may still specify stricter per-operation policies.

The Engine-backed executor:

- reserves a query-cursor working set under `QUERY_CURSOR` before starting native execution;
- derives the pending-match limit from currently available shared budget and the compiled query's capture vocabulary, rather than using a fixed total-match limit;
- grows one `QUERY_RESULT` reservation as captures are retained;
- transfers that reservation to `TshQueryResult`, which releases it when closed;
- marks output incomplete when a caller policy, memory admission or Tree-sitter pending-match limit is reached.

The pending-match estimate is deliberately conservative guidance, not an exact measurement of Tree-sitter's internal query-cursor allocator. Tree-sitter may still allocate opaque internal state.

## MatchSet merge

`TshMatchMergeBudget` defaults `maxMatches` and `maxEstimatedBytes` to representation maxima. The shared memory ledger, rather than a default count ceiling, controls normal Engine-backed admission.

The merger reserves and resizes one `MATCH_SET` reservation while it validates, deduplicates and retains accepted matches. The reservation is transferred to `TshMatchSet` and released when the set is closed. The estimate includes retained Match/capture/provenance payload and merge-map/list overhead; it is not a JVM heap measurement.

The input `Collection` already exists before merge and is not included in the merger reservation. Upstream Tree-sitter and Regex execution must account for their own working sets. A merge that cannot admit another match returns an explicitly `TRUNCATED` MatchSet with a warning; it does not claim completeness.

Diagnostic metadata has a separate bounded aggregation policy (4,096 retained diagnostic entries with an overflow summary). This is not a cap on query matches, captures or Regex results.

## Regex window leases

`TshRegexWindowLease.acquire()` always reserves `REGEX_WINDOW` before reading the contiguous UTF-16 slice. A supplied budget is shared with the caller/Engine; otherwise the Regex window sequence creates a default 100 MiB target / 512 MiB hard-limit budget.

`maxWindowUtf16` defaults to `Int.MAX_VALUE`; there is no library-wide 256 Ki-unit default ceiling. Actual window admission is governed by the shared reservation, the caller's optional stricter ceiling, and Kotlin/JNI/Oniguruma signed-length representation boundaries. The reservation estimate accounts for the provider-returned temporary sequence, retained String and Native sentinel/input overhead. It remains live until the window lease closes.

A finite maximum match length and finite lookbehind/lookahead declarations are still required for safe windowing. Unbounded or unproven patterns are not silently treated as window-local.

This is an input-window reservation only. The current Native Regex adapter still materializes a complete per-window result array; resumable Native Regex result batches and end-to-end MatchSet continuation remain separate work.

## Sparse coordinate indexes

The sparse coordinate index estimates the full checkpoint upper bound and reserves `COORDINATE_INDEX` before allocating the checkpoint structure. The reservation remains attached to the index and is released on close. If admission fails, the index build returns a diagnostic rather than allocating beyond the shared budget.

## Render plans

`TshRenderCoordinator` reserves `RENDER_PLAN` bytes before retaining a pending plan. The reservation is released when the plan is acknowledged, cancelled, invalidated or the coordinator closes. `maxPendingPlans` remains an optional caller policy; the default is not a fixed plan-count ceiling.

## Parser input and syntax-tree lifetime

Snapshot-backed parser input reserves `PARSER_INPUT` for the duration of the parse, including the current callback's bounded temporary buffers. The reservation is released in `finally`.

A host may supply `TshNativeTreeMemoryEstimator` to estimate Native syntax-tree retention. The Engine reserves that estimate under `SYNTAX_TREE` before parsing and transfers it into the Native resource registry with the resulting tree. The reservation remains live across active tree leases and is released only when the tree is actually closed. Incremental parsing accounts for overlap between the old and new tree estimates.

This is admission based on a host estimate, not a measurement or hard guarantee over Tree-sitter's internal allocator.

## Current limitations

- The ledger does not yet observe every Native allocation or allocator-retained page. Third-party allocations and upstream Tree-sitter internals remain opaque unless explicitly instrumented.
- The legacy `ts_tags.cc` all-at-once extraction API still has compatibility result/pending-match/span parameters. New `TSTags.Cursor` and `TSHighlight.Cursor` APIs deliver completed results in resumable batches and avoid full-result materialization, but their Tree-sitter pending-match working set still needs a caller policy or Engine-level integration.
- Native Regex still materializes a full result array per bounded input window. Its input window is budgeted; its Native result delivery is not yet a resumable batch cursor.
- The API does not itself implement Android process-memory probing or a global OS pressure callback. Hosts must provide available-memory context and react to reservation failures.
