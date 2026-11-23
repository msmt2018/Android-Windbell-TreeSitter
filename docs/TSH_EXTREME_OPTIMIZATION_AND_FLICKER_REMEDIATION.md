# TSH / Android-tree-sitter Extreme Optimization and Rendering Flicker Remediation

Status: active remediation contract consolidated into PR #26. Items are not considered fixed until code changes, tests, build evidence, and (for rendering) device/pixel evidence exist.

## Scope
Repository: Android-Windbell-TreeSitter. Active integration branch: `integration/all-open-prs`; review entry: PR #26.

Goals:
- Extreme but evidence-based optimization of Android-tree-sitter JNI/Native input, parse/query, match/capture transfer, memory management, and large-text I/O.
- Reliable input/output for very large text without whole-document flattening, duplicate residency, unbounded allocations, or silently truncated results.
- Fix previously reported TSH highlighting/rendering correctness issues, including the unresolved flicker when comment-symbol regions move and during text insertion/deletion.
- Keep ATS/Tree-sitter Query and Regex/Oniguruma as the only two execution pipelines.
- Preserve UTF-8 byte offsets as Tree-sitter's native coordinate; explicitly convert only at API/editor boundaries.
- Memory budgets remain adaptive with hard ceiling 512 MiB, pressure protection, cancellation, and bounded/degraded paths. Normal target is 100 MiB; device tests should cover 100–300 MiB and larger sizes when feasible.
- Do not claim correctness from non-empty results, cache updates, or invalidate calls; rendering validation must reach actual Canvas/View output.

## Prior issue inventory carried forward
- Large PieceTree snapshot cost and avoiding flattening.
- Disk/source loading, space, cancellation, and failure hardening.
- Native/JVM duplicate result residency; bounded/paged JNI capture delivery.
- Tree-sitter CaptureList memory budget and reclamation.
- 100/200/300 MiB end-to-end highlighting/rendering validation.
- Native Predicate Filter migration/complete predicate semantics; explicit unsupported diagnostics.
- Cursor depth/range controls, result limits, explicit completeness states, cancellation/progress.
- Regex large-capture materialization, Lua-pattern semantics, result transfer peaks, and cross-window correctness.
- API synchronization across JNI, Java, Kotlin, and TSH.
- Highlight accuracy/provenance, query/capture/node identity, byte/UTF-16/document coordinate conversion, stale result rejection, cache invalidation.
- Previously reported render defect: moving comment delimiters/regions and typing/deleting text still flashes; diagnose and fix rather than mask with broad invalidation or plain-text fallback.

## Flicker investigation: required instrumentation and invariants
Audit `RowCache`, `TextRenderer`, `EditorView`, TSH render plan, style cache, and edit/invalidation callbacks.
- A cached RenderNode must never be replaced by a plain recording for an already styled row solely because asynchronous styling is temporarily unavailable.
- During text edits, the recording must match the new row text; old-text recording must not be replayed over new text.
- Style changes caused by comment delimiter movement must invalidate the complete conservative dependency range (not just capture ranges that overlap the edit).
- Keep a visible stable rendering while a new style result is pending; only apply results matching document/query/theme/layout generation.
- Row movement through insert/delete must shift cache entries and invalidation ranges consistently, without invalidating or re-recording unrelated stable rows.
- Direct rendering and RenderNode rendering must have consistent style fallback semantics.
- Instrument transitions: row id, cache state/action, text revision, style revision, parse pending/complete, frame budget/defer, render generation. Keep instrumentation opt-in and bounded.

## Large-text optimization sequence
1. Establish repository and build baseline; record commit, Gradle/NDK/toolchain, tests, and benchmark target devices.
2. Trace UTF/JNI ingress and egress; remove redundant conversions, fix Modified UTF-8 misuse, check byte-vs-code-unit length and range narrowing; preserve encoding declaration.
3. Audit JNI copies/lifetimes; benchmark array APIs and DirectByteBuffer/native-owned buffers before adopting a zero-copy path. Do not hold JNI critical arrays across parser work.
4. Audit native TextSource, mapped file, chunk store, PieceTree snapshot and callback windows for bounded reads, overflow-safe offsets, UTF-8 boundary correctness, short reads, cancellation, and I/O failures.
5. Establish end-to-end large text pipeline with bounded allocations and no unnecessary flattening; target adaptive memory budget and 512 MiB hard cap.
6. Audit Query/Capture/predicate semantics, result transfer, cursor limits, completeness states, pagination/leases and reclamation; prevent silent truncation and Native/JVM duplicate lists.
7. Audit Regex windows, capture materialization, cross-window correctness, lookaround/anchors and explicit unsupported/degraded semantics.
8. Diagnose and fix render flicker and stale colors; preserve comment-region context; test insertion, deletion, delimiter movement, rapid edits, scrolling, async parsing, and frame-budget exhaustion.
9. Verify renderer application and actual drawing, including viewport capture tests; add device tests for 100, 200, 300 MiB files and mixed Unicode/long-line inputs.
10. Run full unit, native, instrumentation, build and benchmark suites; publish measured before/after results and unresolved items.

## Required benchmark matrix
- UTF-8 ASCII, Chinese-heavy, Emoji/ZWJ, mixed, invalid input, and very long lines.
- File sizes 1, 10, 32, 100, 200, 300 MiB; larger sizes on devices with safe headroom.
- Load/read, snapshot, parse, incremental edit, query, regex, capture transfer, style resolution, render/apply, memory peak, allocations, and GC pause.
- Compare median/p95 latency, MB/s, peak RSS/native heap, Java heap, temporary bytes, copies, frame time, missed frames and correctness.
- Benchmarks must use same device, build variant, warmup, repetitions, and dataset; avoid asserting performance claims without measurement.

## Acceptance criteria
- No unbounded memory growth or silent truncation; bounded output is explicitly paged or marked incomplete.
- UTF-8 source bytes and Tree-sitter byte offsets remain lossless and coordinate conversions are tested.
- Large text paths avoid full-document String/byte-array duplication where possible.
- Cancellation, pressure handling, and I/O errors are observable and safely reclaim resources.
- Comment delimiter movement and edits/deletes do not produce visible flash of plain text or stale colors; pixel/device evidence and regression tests prove it.
- Full test/build evidence is recorded; any untested change is explicitly identified.

## Historical CI baseline investigation (2026-11-21)
- Latest examined run #1338, commit `8c170ee202069d48b76fbe168a23f09a96667cdf`: Release APK build passed; local tests passed; API 26 and API 30 device tests passed; API 35 device test job failed.
- API 35 artifact XML for both `:editor` and `:app` reported `tests=0`, `failures=0`, `errors=0`. This is instrumentation/test-discovery or runner-startup failure, not an assertion failure. The workflow correctly refuses to treat empty reports as success.
- The API 35 failed job was re-run; rerun job id `136778449039` was queued at the time of investigation. Its result must be checked before deciding whether this is flaky or reproducible.
- API 35 artifact report inspected directly: empty XML reports confirmed. Logcat artifact was reviewed for runner/Instrumentation evidence; no definitive test-discovery root cause established. Do not mask this by allowing zero-test reports.
