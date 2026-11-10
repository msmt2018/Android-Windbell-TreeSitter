# TSH Regex Window Lease API

## Purpose

Oniguruma and other Regex engines may require contiguous input. `TshRegexWindowLease` supplies one bounded contiguous UTF-16 slice from an immutable `TshTextSnapshotLease` without flattening the full document.

This is an input-resource contract for the independent Regex pipeline. It does not route Regex through Tree-sitter and does not implement compilation or execution.

## Finite-window requirement

A caller supplies `TshRegexWindowConstraints`:

- `coreWindowUtf16`: maximum length of the non-overlapping ownership interval.
- `maxWindowUtf16`: hard maximum contiguous slice length (256 KiB by default; maximum 1 Mi UTF-16 units).
- `maxMatchLengthUtf16`: finite maximum consumed match length. Null is rejected by `acquire()`.
- `maxLookbehindUtf16` and `maxLookaheadUtf16`: finite context required by the pattern.

The required worst-case context must fit within `maxWindowUtf16`. A pattern with unbounded consumption or unbounded lookaround cannot use this window API; it must be rejected or handled by a separately designed bounded strategy. Do not silently read the complete document.

## Core ownership and overlap

The lease exposes:
- absolute window start/end;
- absolute core start/end;
- local core start/end inside the window;
- document length and whether the window touches the true document start/end.

A Regex executor should emit a match from this window only when the match's absolute start is in `[coreStartUtf16, coreEndUtf16)`. The final window additionally owns a zero-width match exactly at document EOF, so empty matches at the terminal boundary are not lost. Adjacent windows may include overlapping context, but their core intervals must partition the document. An empty document still yields one empty first/last window, allowing zero-width matches at offset zero. This gives each match start exactly one owning window.

The window includes:
- finite lookbehind before the core;
- enough forward context for the maximum match length and lookahead;
- a one-code-unit edge expansion when necessary to avoid splitting a surrogate pair.

Core boundaries that split a surrogate pair are rejected. Returned text must have the expected length and well-formed UTF-16.

## Memory and lifetime

If a `TshMemoryBudget` is supplied, the lease reserves `REGEX_WINDOW` before reading the contiguous slice. The estimate allows for both a temporary provider-returned CharSequence and the retained String copy. The reservation remains live until `TshRegexWindowLease.close()`; close is idempotent and drops the lease's reference to its content.

A budget failure is returned before the full contiguous window read. The tiny boundary probes used to detect surrogate splits happen before reservation.

## Anchor semantics

The executor must not interpret an interior window start/end as the document's beginning/end. It should disable window-local BOL/EOL behavior at interior boundaries where supported by the Regex engine. Absolute document anchors such as `\\A` and `\\z` must only be considered at windows whose `isDocumentStart` or `isDocumentEnd` flag is true.

## Limitations

- The lease does not run Oniguruma or any other Regex engine.
- It does not carry engine match state between windows.
- It does not solve unbounded patterns; those are rejected.
- The executor must still implement cancellation, match/capture count limits, empty-match progress, and cross-window correctness tests.
- A lease's text must not be retained after close; callers that keep their own String/CharSequence reference also keep that memory alive outside this lease's accounting.
