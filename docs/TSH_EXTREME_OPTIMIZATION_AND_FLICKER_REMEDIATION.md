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

## 2026-11-24 Native ATS/JNI follow-up: cursor traversal and build-scope repair

This follow-up is source-committed on `integration/all-open-prs`. It is not yet BUILD_VERIFIED or TEST_VERIFIED.

### ATS adjacent-sibling and position traversal

- Commit `b38341487ff0ae541772a91d1871a6de3662ceba` initially separated sibling-only constraints from child-position constraints and used Tree-sitter's previous/next sibling APIs.
- A subsequent source audit of the vendored Tree-sitter implementation showed that `ts_node_prev_sibling` and `ts_node_next_sibling` each perform their own tree walk; they are not O(1). Treating them as constant-time would be an inaccurate performance claim and can duplicate traversal when both sides are requested.
- Replaced the separate sibling lookups with one `TSTreeCursor` pass over the parent's visible children. The pass records the previous node, current visible-child index/named-child index, and (when needed) the next node. Anonymous children remain in positional order.
- `lastChild` reuses the same next-sibling probe instead of performing an additional child-count/index traversal.
- Source commits: `2df9a3a79c1cc56fb908c719f964557ba3d3e116`, `d50d5c12d9eaa77b25416bd66b9a1021670b4177`.
- Required verification: `testAstSelectorNodeFlagsAreExecuted`, especially simultaneous previous/next sibling constraints, wrong-left/right rejection, anonymous operator nodes, first/last child and named/unnamed indexes.

### JNI Debug-build blocker

- CI run `45394899848` failed in the local-test job while compiling `ts_windbell_jni.cpp`: document-backed highlight timing referenced `highlight_core_started`, which was declared in a different JNI entry point and therefore out of scope.
- Added the timing origin in the document-backed highlight function immediately before its `highlightHybrid` invocation, guarded by `#ifndef NDEBUG`.
- Source commit: `818939819dbf1ac8ddf1b05e0a9f5695ba826221`.
- The failed CI attempt predates this JNI fix and cannot validate it. A new CI run for the corrected branch head is still required.

### Remaining correctness/performance work (not closed)

- The missing-capture polarity regression for `any-eq?` / `any-not-eq?` remains unverified. Instrument the actual `TSQueryMatch.pattern_index` and capture IDs per OR alternative before changing predicate semantics; do not assume the capture is absent based only on the query source.
- Native predicate evaluation still constructs temporary expected/actual containers and converts immutable query literals during match execution. The next optimization should move immutable operand decoding into compile-time metadata and reuse per-query execution storage, without changing predicate quantifier semantics.
- Native Regex still materializes a complete per-window result array. A resumable Native Regex cursor and end-to-end MatchSet continuation remain outstanding.
- CI device tests are intentionally disabled in the current workflow. Local/native test success is not device verification.


## 2026-11-24 CI #1782 follow-up: cache API and editor adapter drift

CI run #1782 (45397495706, commit ba406d463389fab4d83dad532676146d823e9c35) supplied a more complete validation result than the earlier compile-only attempt:

- The document-backed JNI timing-scope fix compiled: Build host JNI library for local tests passed.
- Run native TSH cache tests failed to compile because NativeHighlightCache::Value no longer owns dependency ranges and put now requires dependency ranges as its third argument. The tests were still written against the removed embedded Value.dependencies field and the former two-argument API.
- Release Kotlin compilation exposed adapter drift: CaptureDisplayRebaser called nonexistent CaptureIntervalIndex.mapSpans and obsolete unsigned-offset properties; TshSyntaxHighlighter referenced an unbound viewport session and the removed Position.line property.
- The same Release log also reported Kotlin metadata mismatch: stdlib 2.4.21 metadata was rejected by a compiler expecting 2.2.0. This is a separate toolchain-resolution problem and remains open until the next CI run identifies the actual compiler/plugin version and the project is made consistent.

### Corrective source commits after #1782

- c33962d06dc5668e1b72d1ff2915c0e25a14db34: add packed-array CaptureIntervalIndex.rebaseEdit. It transforms byte ranges in primitive arrays, drops captures crossing replaced text or insertion points, shifts captures after the edit, and rebuilds the prefix-max-end index without allocating one Kotlin span object per capture.
- 44a4b81cb79cf59537ad10c71cdfd4bf2a7f9feb: route CaptureDisplayRebaser through the packed rebase API rather than a nonexistent object-mapping API.
- b4dedb8300536f21c8dce0d4504d2db9ae37b51b: capture the active large-document session before dispatching the viewport worker and use the current Position.lineNumber contract.
- f8f5be8e7575894c7e9cb9feda7ad3e7c72879ee: migrate native cache tests to the separate dependency-ledger API. Tests now supply dependency proofs to put and validate retained viewport keys through snapshotRanges; dependency metadata is no longer duplicated inside the cached span payload.

These corrections are source-committed but not yet verified. CI run #1786 (45403054212) was queued/in progress at the time this section was written. Do not close these build issues until that run and any subsequent retry pass.

### Remaining predicate and performance work

- The any-eq? / any-not-eq? missing-capture regression is still not closed. The TSH evaluator's source branch already computes missing-capture acceptance from predicate polarity, so the next diagnostic must establish the actual pattern_index, per-pattern predicate metadata, and capture IDs produced for each OR alternative. Do not make another polarity-only code change without that evidence.
- Predicate execution still reconstructs expected literal vectors and converts immutable UTF-8 query literals to UTF-16 during match execution. Move immutable decoding to compilation and reuse bounded per-query execution storage.
- Regex window execution still materializes complete per-window result arrays; resumable cursor/continuation semantics remain open.
- Device rendering and 100–300 MiB end-to-end tests remain separate acceptance gates; current workflow's device matrix is skipped.

## 2026-11-24 CI #1787: exact failure causes and follow-up corrections

CI run #1787 (45403311336, tested head 824a214377e2cbf5c4c346aec6f3e4a938b5cf1e) completed with two failing jobs. The downloadable test report was inspected rather than relying only on the Gradle summary.

### Verified passing gates in #1787

- Native large-file storage tests (ASan/UBSan): passed.
- Host JNI library build: passed.
- Native TSH cache tests: passed after the separate dependency-ledger migration.
- testNativeAnyEqualityMissingCaptureUsesPredicatePolarity: passed. Debug evidence showed a missing target capture (capture_id=0, actual match capture index 1) yields any-eq? -> reject and any-not-eq? -> accept. This polarity regression is now covered by a passing CI test; keep the test and do not reintroduce polarity-only workarounds.

### Failures identified and corrective commits

- CaptureIntervalIndexTest had two failures after edits: expected retained styles [1, 3], observed [1, 2]. Root cause: rebaseEdit compacted range arrays but copied style/priority/source arrays from the original prefix, associating retained ranges with the wrong capture metadata. Commit 2e454e0a30f0e9f2372f879592ba3274dbe94e42 replaces the one-pass full-size scratch arrays plus trimming copies with a two-pass exact-size rebase. The second pass copies all fields from the same retained source index. This both fixes data integrity and reduces peak temporary memory.
- Release ABI build failed because the viewport-only ATS fallback dependency initialized uint32 byte ranges from jlong values. The JNI entrypoint had already validated and narrowed these values to requested_start / requested_end; commit de19cf1ae5a69032c1df2a6273dd7c8c2760c81a uses those validated uint32 values instead of narrowing implicitly.
- testLargeTextSourceQueryPredicateDoesNotMaterializeHugeCaptureForEquality failed during TSH compilation because its query predicates were detached from their structural patterns. Commit 0d43ccc066ba0a3ca234f212e6a1e670d60250fd groups each pattern and predicate as one Tree-sitter expression. This corrects the test fixture; it does not weaken the compiler's structural validation.
- testMonarchNestedIncludeStateHotPathRemainsStable expected 4096 identifier spans but counted 8191 because the source filter also included 4095 default-token whitespace spans. Commits 0d43ccc066ba0a3ca234f212e6a1e670d60250fd and d0ddbddd08e8a6fdbc9a4b6a7efeb7a3a5107c89 constrain both full and viewport assertions to the identifier style, preserving the intended nested-include assertion.
- testAstSelectorNodeFlagsAreExecuted exposed a real ATS bug: compile-time dispatch correctly expanded a supertype into its concrete subtypes, but the final candidate check reverted to exact symbol/type equality and discarded those candidates. Commit fd99db73574e961f612ff1f17840a170729b1956 reuses the precomputed reverse supertype index through tshAstNodeMatchesSelector for both ordinary node selectors and field-owner selectors. This keeps runtime matching indexed and avoids recursive subtype traversal per node.
- testJavaAnnotationCapturesAreStableForQualifiedAndParameterizedNames showed one marker capture changing declaration order between full-document and viewport execution. Root cause: emitted precedence included current_order, the number of prior matches in the current cursor range. Commit cb743324aee660e958a91f871ff8899bb486ee83 removes range-dependent match enumeration from precedence; declaration order now derives only from immutable query, pattern and capture declaration metadata.

### Current validation status after the corrections

The corrections above are source-committed after #1787 and have not yet been validated by a completed CI run. The Kotlin metadata diagnostics (stdlib 2.4.21 metadata 2.4.0 versus a compiler expecting 2.2.0) appeared in the Release log but did not stop Kotlin compilation; the Release job proceeded to the native CMake task and failed on the JNI narrowing error described above. Keep this version alignment under observation and only change the toolchain if a subsequent build demonstrates it is a fatal compiler-resolution problem.

### Still-open performance work

- Native predicate evaluation still allocates expected/actual containers and decodes immutable UTF-8 string literals to UTF-16 for each match. Compile immutable operands once and reuse bounded per-query execution storage; do not add a global unbounded cache.
- Regex execution still needs a resumable result cursor/continuation to avoid materializing complete per-window result arrays.
- Device rendering and 100–300 MiB end-to-end memory/latency validation remain open; CI's Android device matrix is skipped.

## 2026-11-25 CI #1795: completed validation of the CI #1787 remediation batch

CI run #1795 (run ID 45427572294, tested head 56fe94434e9c4df648abbd9165002cab400556aa) completed after the earlier #1794 run was cancelled before validation.

### Verified gates

- Build Release APK: passed, including Release assembly and artifact upload.
- Run local tests: passed.
- Gradle HTML reports: 843 JVM tests passed across modules (android-tree-sitter 206, editor 331, piecetable 294, app 12); no failures in these reports.
- Native large-file storage tests with ASan/UBSan: passed.
- Host JNI library build: passed.
- Native TSH cache tests: passed.
- Android device test matrix: skipped by workflow configuration. This is not device validation.

### Source corrections included in the tested head

- 2e454e0a30f0e9f2372f879592ba3274dbe94e42: preserve packed capture metadata when rebasing edits; exact-size two-pass compaction also reduces temporary memory.
- de19cf1ae5a69032c1df2a6273dd7c8c2760c81a: use validated uint32 viewport byte ranges in the JNI fallback dependency path.
- 0d43ccc066ba0a3ca234f212e6a1e670d60250fd and d0ddbddd08e8a6fdbc9a4b6a7efeb7a3a5107c89: correct query predicate grouping and constrain Monarch assertions to identifier-style spans.
- cb743324aee660e958a91f871ff8899bb486ee83: make query capture precedence independent of viewport-local match enumeration.
- fd99db73574e961f612ff1f17840a170729b1956 plus 56fe94434e9c4df648abbd9165002cab400556aa: use the precomputed reverse supertype selector index at runtime and ensure target supertype selectors are inserted into that index.

### Closure status

The above fixes are validated by CI #1795's Release and local-test jobs. The earlier report's statement that the any-eq? / any-not-eq? missing-capture polarity regression was unverified is superseded by the passing #1787 regression test and its debug evidence; retain that test as a guard.

The following are still open and must not be marked complete:

- Predicate hot-path optimization: compile immutable string operands (including UTF-16 representations) once and eliminate per-match expected/actual container construction where semantics permit. The current evaluator still constructs expected/expected_types/actual containers and converts query literals during match execution.
- Regex resumability: current window execution still materializes complete per-window result arrays; a resumable cursor/continuation and MatchSet continuation are not implemented.
- JNI result transfer: audit and reduce duplicate large Capture/Highlight arrays across Native/JNI/Kotlin boundaries.
- Device and large-file end-to-end acceptance: add/run Android device tests for 100–300 MiB parse/highlight/viewport/edit workloads, recording latency, peak RSS/native heap, memory-pressure fallback and visual correctness. The #1795 device matrix was skipped, so no device-level closure is claimed.

### Active remediation baseline

Use #1795 head 56fe94434e9c4df648abbd9165002cab400556aa as the regression baseline. Do not reopen the fixes above without a new failing test or reproducible runtime evidence. Prioritize immutable Predicate operand compilation next, then Regex continuation, then JNI transfer and device-level memory/performance validation.

## 2026-11-25 user-reported regression baseline: annotation color, cache churn, first-open latency

These reports remain open until the exact runtime and device acceptance gates below pass.

### User-observed measurements

- A 33.7 MiB document's full Tree-sitter AST parse was measured at approximately 148–153 ms.
- This is only the parse-stage baseline. First-open TSH token/capture preparation and visible color rendering are separate stages and have been reported as roughly 5–10 times slower than the parse baseline on some inputs, with larger files taking tens of seconds and occasionally 1–2 minutes.
- The user requests an eventual 100x-or-greater improvement. Treat this as an optimization objective, not a claim or a reason to omit AST nodes, captures, predicates, viewport coverage, or visual semantics. Report per-stage median/p95 before claiming any multiplier.

### Three required regressions

1. **Dark-mode annotation marker color:** the literal `@` capture must preserve the exact ARGB value authored by the active `.tsh` scheme through native palette export, Kotlin palette decoding/fallback, `ForegroundColorSpan`, `StyledTextPainter`, and final Canvas pixels. Test both an explicit dark yellow override and a missing-dark-override fallback. A green base TextPaint must never replace an authored yellow TSH color.
2. **Large-file first-open and query performance:** measure snapshot acquisition, full AST parse, native viewport highlight, Regex/state restoration, ATS/Tree-sitter Query execution, JNI encoding/transfer, Kotlin capture packing/indexing/cache admission, span generation, and final Canvas drawing separately. Full AST parse time is not a proxy for first-visible-color latency.
3. **Stable rendered ATS symbols:** a cache eviction is not a semantic invalidation. An unchanged document revision and unchanged dependency proof must not cause a previously successful viewport request to become permanently suppressed or force unrelated ranges to be recaptured. Only reopen/replacement, intersecting edits, proven dependency invalidation, grammar/style-rule changes, or explicit semantic invalidation may require recapture. Theme-only changes repaint from retained captures and must not rerun parsing/query.

### Confirmed code-path findings and source changes

- `TshSyntaxHighlighter` has a process-global 32 MiB capture LRU. Native documents each have a 4 MiB payload cache, with a 32 MiB process-wide Native cache budget and a separate per-document dependency ledger. These are capacity policies, not semantic validity rules.
- A concrete retry-state defect was found: `executeNativeRangeNow` treated `RangeWorkState.SUCCEEDED` as permanent even after the corresponding LRU capture page had been evicted. The method already proved that the requested range was uncovered, but the stale success marker returned early and blocked recapture until range-work bookkeeping was eventually trimmed. The current fix permits a visible uncovered range to retry a previously succeeded or cancelled request; failed requests retain their retry backoff.
- `CaptureRangeLookupIndex.findOverlappingRanges` performed a redundant sort after an in-order AVL traversal and traversed right subtrees even after the BST key had passed the requested session/range. The lookup now relies on in-order output and prunes impossible left/right subtrees. `TshSyntaxHighlighter.cachedRanges` no longer sorts the already ordered result a second time.
- Source commits: `79fb08798e0a394d2248ae7cf9c47e3759a8ff33`, `992f1a5affd83c9e042fd91bae6f55abe7ce9db4`, `fd4ab07d0bd7f78b47a75fede362b44831b9c979`, `4f6e1502977ba3a888348aca4472234d73737c23`, `985b4958c22bd7561fd75b68a627df3fdcf98e2d`, `d6629a4907c1174a8a7eedb56e52d64699315646`.
- Added ordered-range lookup regression coverage and an Android instrumentation regression that asserts an explicit dark-scheme yellow annotation marker remains yellow in the final Canvas, in addition to the existing missing-dark-override fallback test.
- Android device tests were re-enabled for API 26, 30 and 35 because Canvas-level color correctness and render flicker cannot be closed by JVM/native-only tests. Current validation run for head `d6629a4907c1174a8a7eedb56e52d64699315646` is tracked as run `45469265449`; its result must be recorded before these changes are marked validated.

### Remaining acceptance work

- Profile a cold open and warm reopen on the same 33.7 MiB and 50 MiB datasets, then 100 MiB low-density and high-density Java fixtures. Record parse and first-visible-color separately.
- Record capture-cache hits, misses, evictions, rejected admissions, native cache hits/misses/evictions, dependency-proof retention, range-work retries, and actual re-query counts. A cache eviction must not be counted as a semantic invalidation.
- Exercise repeated scroll-away/scroll-back, theme switching, insertion/deletion before and inside ATS dependencies, and multiple edits before one render frame. Already-painted unaffected symbols must remain visually stable.
- If a range cannot be retained under cache pressure, use explicit page/batch continuation or bounded retry with diagnostics; do not silently report a successful cache operation when no page was retained.
- Do not close the flicker or color issue until the API 26/30/35 Canvas/device tests and repeated-edit/scroll scenarios pass. Do not claim a 100x speedup without same-device, same-input, same-output measurements.


## 2026-11-25 Native query predicate hot-path optimization

- Added immutable per-pattern predicate operand records alongside compiled Tree-sitter predicate steps.
- UTF-8 query literals are decoded to UTF-16 once during grammar compilation; structural node-type operands are retained as immutable strings.
- The per-match evaluator reads precompiled operations and operand arrays instead of allocating nested vectors and retranscoding identical literals for every AST match.
- Existing equality, membership, contains, structural, and regex predicate evaluation branches remain in place. This changes operand preparation, not predicate semantics.
- Verification pending: native host tests, JVM tests, Release build, and Android API 26/30/35 rendering tests. No speedup multiplier is claimed without same-device measurements.

### 2026-11-25 cache-churn and viewport-latency observability

- TshSyntaxHighlighter now emits a rate-limited pipelineMetrics record with styled-line calls/time, capture-cache hit/miss counts, native range count, native mutex wait time, native query time, Kotlin capture-cache bytes/entries, LRU evictions, and rejected admissions.
- These counters are diagnostic only and do not alter cache admission, invalidation, query scheduling, or rendering decisions.
- Use the record to distinguish repeated native query work, Kotlin cache pressure/eviction, lock contention, and repeated line-span projection work. Correlate with native TSHCache editRebase logs to separate semantic invalidation from capacity loss.
- The metrics are cumulative for the highlighter instance except process-global capture-cache size/eviction/rejection counters. Compare deltas over controlled cold-open, scroll-away/back, and theme-switch runs.


## 2026-11-25 CI #1833: predicate hot-path compile regression and correction

- CI run #1833 (run ID 45476894992, tested head 542fef84b523c35185d2f9a0cad4d101b1096628) failed in the Host JNI native build. The compiler reported `actual` undeclared in the Regex predicate fallback at `WindbellTshCore.cpp:1029/1031`. Release and Android instrumentation jobs also failed downstream at native compilation; their failures must not be misclassified as independent rendering regressions.
- Root cause: predicate operand precompilation removed the former per-match `actual` temporary from the shared evaluator scope, but the non-contiguous, non-windowed Regex fallback still used that scratch vector to materialize capture text.
- Corrective commit `66ab378883ff9c3e96eeace1df58fb8be526d094` restores the scratch vector locally inside the Regex fallback branch. The contiguous-source and bounded-window fast paths do not allocate or use it. Predicate operands remain immutable and precompiled; this correction does not restore per-match literal decoding or the old general-purpose containers.
- Validation is pending on the workflow triggered by the corrective commit. Do not mark predicate optimization complete until Host JNI, Release, native cache tests, JVM tests, and Android API 26/30/35 jobs are checked.

### Additional rendering-path finding

- `styleLine()` still obtains a newly materialized `List<HighlightSpan>` for each draw by querying the packed interval index. `CaptureIntervalIndex.appendQuery()` creates one `HighlightSpan` object per overlapping capture. The painter then allocates `ClippedCapture` and two `BoundaryEvent` objects per clipped capture, sorts the boundary events, builds active ordered sets, and allocates Android style spans. Thus a capture-cache hit avoids Native Query but does not avoid per-frame projection and paint-work allocations.
- This is a separate hot path from AST parsing and Native Query. Instrumentation must distinguish cache-hit projection time, painter CPU time, allocation/GC, and actual Native range query time before choosing the next optimization.
- Do not add an unbounded line-span cache or retain stale projections across edits. Any projection reuse must be keyed by session/document revision, exact source slice, and style/palette generation; its retained bytes must be charged to a bounded budget, and cache eviction must never be treated as semantic invalidation.
- Current source has not yet introduced such a projection cache. This remains open work, not a claimed optimization.


## 2026-11-25 CI #1837 follow-up: cache proof accounting and Android span precedence

- Workflow #1837 (run ID 45498790317, head `d66f208412b734bd59dbcc05a0f174643f7369cc`) built the Release APK successfully, but native cache tests and Android instrumentation failed.
- Native failure: `NativeHighlightCacheTest.cpp::testMissingDependencyIsNeverReused` expected `missing_dependency_proof == 1`, while the implementation only incremented that counter when an empty dependency entry existed in the ledger. Empty dependency vectors are not admitted to the ledger, so the actual payload-without-proof case was counted only as `payload_without_proof`. Commit `fa15d5bf99eb58d5ce320969e2df55d1a4933a47` now increments `missing_dependency_proof` at the payload validation point when the retained proof lookup fails; the payload remains conservatively evicted. This fixes diagnostic accounting without weakening cache validity.
- Android API 26/30/35 instrumentation exposed `nestedTshCapturePriorityReachesFinalCanvasColor`: the sweep-line correctly selected the native winner for foreground/background, but the generated font spans discarded the originating capture's native precedence. Commits `30104f49bda079c1ed207969a20b890b3804048a` and `99ef2dbac1e7b9c6ac09551752a7e726d2a8291a` map native (priority, declarationOrder) ordering into Android's 8-bit span priority metadata and track the winning capture independently for bold, italic, underline, and strike-through. The sweep remains responsible for disjoint paint runs; Android priority metadata is preserved for consumers and diagnostics, not used as a substitute for native overlap resolution.
- API 35 also exposed a test race: `EditorView.isLayoutBusy()` becoming false can precede `layoutCoordinator.runEdit()` accepting an edit. Commit `bbb1a8db4355a682f7ea81e499869260e6d6b86b` changes the instrumentation test to retry only the documented non-mutating refusal result for a bounded 10 seconds. This is a test synchronization correction, not a production editor behavior change.
- Latest code head at the time of this entry: `99ef2dbac1e7b9c6ac09551752a7e726d2a8291a`. Workflow #1839 (run ID 45505958299) has been created and is pending. Do not mark these fixes verified until the new native cache test and Android API 26/30/35 instrumentation jobs complete. Release build success from #1837 does not prove instrumentation correctness.

### Remaining optimization and flicker work

- The projection/paint allocation path remains open: cache hits still rebuild clipped captures, boundary events, active sets, and Android spans for each line draw. Implement a bounded immutable projection cache only after the current correctness workflow passes; key it by document/session revision, exact visible range, and style/palette generation, with precise edit-range invalidation and explicit byte accounting.
- The no-reset invariant remains mandatory: cache eviction, viewport movement, theme change, and budget pressure must not be treated as source edits or cause unrelated already-painted ATS ranges to be invalidated. Only the edited text/dependency envelope may invalidate captures; theme changes may re-project colors without rerunning Native Query.
- Measure cold-open to first visible color, scroll-away/back, repeated unchanged redraw, local edit, and theme switch separately. Report Native Query, projection, painter, allocation/GC, and lock-wait times independently. No 100x improvement claim until measured on the same device and corpus.


## 2026-11-25: preserve unaffected captures across edit publication

- Root cause confirmed in `TshSyntaxHighlighter.applyNativeEdits`: after native dependency rebasing and Kotlin capture-cache rebasing had already decided which viewport pages remained valid, the code also appended every `readyDisplayRanges` entry (temporary old-revision visual bridges) to the style-invalidation ranges. That forced unchanged rows to discard/re-request styles even when their capture page had survived the authoritative native dependency check. Commit `05d2bf6af51920013cfc44b6763d59b6cad247f` removes this redundant invalidation. Only native dirty intervals and capture pages actually rejected by the rebase now invalidate styles. Incomplete retained coverage still schedules missing viewport work while preserving already-known captures.
- Commit `38c399eec34b84f34c38c481b891396190e6f328` removes the all-or-nothing dependency-ledger reset on shared-budget reservation failure during rebase. Rebase temporarily holds old and replacement maps; if the replacement cannot reserve extra shared bytes, it now evicts only the oldest rebased proofs until the new ledger fits within the old reservation, then swaps and releases the unused difference. This preserves hot dependency proofs instead of invalidating every cached viewport because of transient duplicate-map accounting.
- Commit `fa15d5bf99eb58d5ce320969e2df55d1a4933a47` fixes the missing-proof diagnostic count at payload validation. Commit `30104f49bda079c1ed207969a20b890b3804048a` plus `99ef2dbac1e7b9c6ac09551752a7e726d2a8291a` preserve native precedence in Android foreground/background and each font-attribute span. Commit `bbb1a8db4355a682f7ea81e499869260e6d6b86b` makes the API 35 test wait for the editor's actual edit gate rather than assuming `isLayoutBusy()==false` means `runEdit()` is already available.
- The global Kotlin capture LRU is currently fixed at 32 MiB and its eviction callback removes the corresponding range-index entry. This is a separate, confirmed route to a cache miss and asynchronous recapture after capacity pressure. Do not solve it by disabling the byte budget or retaining an unbounded second copy. Next architecture task is a bounded, revision-aware retained-capture tier (or disk-backed packed-page tier) that can serve a previously rendered page without a Native Query round trip, with a shared process budget and exact edit-range invalidation.
- Projection and Canvas allocation remain separately open: cache hits still materialize `HighlightSpan` objects, clipped captures, boundary events, active sets and Android spans on repeated line draws. Any projection cache must account for palette generation and document/session revision, and must not invalidate semantic captures on theme-only changes.
- Latest source commit at this entry: `05d2bf6af51920013cfc44b6763d59b6cad247f`. The prior run #1840 was superseded/cancelled by subsequent source commits; run #1841 was likewise superseded. Verify the workflow for the latest head before claiming any fix is CI-green.


## 2026-11-25: separate cache projection from Android paint cost

- The existing `styleLineMs` metric combined cache lookup/materialization, edit-display rebasing, `SpannableStringBuilder` creation, native-order sorting, sweep-line overlap resolution, and Android span insertion. It could not identify which non-Native stage dominated first paint or repeated redraw.
- Commit `5843b1e495b5bc787c7b7c8bbe54495aee935ea2` adds cumulative `captureProjectionMs`, `captureProjectionSpans`, `androidPaintMs`, and `androidPaintRuns` counters. These are emitted with the existing five-second throttled pipeline metrics. The projection timer measures the synchronized capture-cache lookup/materialization and edit-display mapping stage; the paint timer measures `applyHighlightSpansToSpannable`; run count measures the actual Android CharacterStyle spans produced.
- This is observability, not a claimed speedup. The new counters are intended to decide whether the next optimization should remove per-capture `HighlightSpan` materialization, reduce painter boundary/event allocation and sorting, or optimize cache lookup/locking. Compare cold-open first-visible-color, stationary redraw, scroll-away/back, and local-edit scenarios separately on the same device and source corpus.
- Latest PR head after this instrumentation commit is `5843b1e495b5bc787c7b7c8bbe54495aee935ea2`. No workflow run was yet returned for this exact head. Workflow #1843 (run ID 45506582133) was cancelled after further commits superseded its tested head; its results cannot be used as verification. Do not claim the cache accounting, Android span precedence, or editor synchronization corrections are CI-green until a workflow for the current head completes.

### Architecture constraints retained

- The global 32 MiB capture LRU remains bounded. Do not disable its budget or add a second unaccounted in-memory copy to hide recapture. A future retained-page tier must be backed by a shared byte budget and have precise document/session/revision identity plus local edit invalidation.
- Theme changes remain paint-only. They must not invalidate native captures or issue Native Query work. Capture-cache eviction and budget pressure are storage events, not source edits; they must not clear already-valid ATS colors in unaffected ranges.
- No line-projection cache is added yet. It would be incorrect to key it only by line number or document revision without accounting for shifted lines, multiline dependency changes, exact text, and palette generation. The new metrics are the prerequisite for choosing a bounded representation with a provable invalidation model.


## 2026-11-25: primitive painter sweep events

- The line painter previously allocated two `BoundaryEvent` Kotlin objects for every clipped capture, then sorted an object list by offset and start/end state. This is on the per-line paint path, after capture-cache lookup, so it can remain expensive even when Native Query is never rerun.
- Commits `986c5c2c39b1ef4e0dd26d072edaf9b9f383a5c2` and `a9b44d2379e44af863a9307becf7599eaa7b8035` replace those objects with one primitive `LongArray` containing two packed events per capture. Sorting is now primitive-array sorting; the packed key orders by line-local offset, then end before start, then capture index. The explicit end-before-start bit is important for adjacent spans that share a boundary.
- Added instrumentation regression `packedPainterEventsKeepAdjacentCaptureBoundariesDisjoint`, which paints adjacent red/green captures and asserts that the resulting Android foreground spans occupy exactly [0,1) and [1,2), with no overlap or color bleed.
- This removes two per-capture event objects and comparator-driven object sorting from each paint call. It does not yet eliminate `ClippedCapture`, boxed TreeSet entries, or Android span allocation. The performance gain must be measured using `androidPaintMs` and allocation/GC profiling; no speedup percentage is claimed without device measurements.
- Latest change also retains the previous full native-priority sweep and dark/light palette selection. The event packing is a representation change only; it must not alter ATS overlap precedence or annotation colors.

### Current validation baseline

- Workflow #1845 (run ID `45507572731`) was queued for head `0706804d169b5c89beea5a34cdca3889b2023ca5`; it does not include the primitive-event commits above and cannot validate them.
- The current PR head after primitive-event implementation and regression test is `0e30312114519d8e7c0d0705040816412e9ae741`. A new workflow result for this exact head is required before marking this optimization verified. Earlier queued/cancelled workflow results must not be reused as evidence.


## 2026-11-25: remove per-capture clipped wrapper allocation

- The painter's packed boundary-event change removed the two event objects per capture, but inspection showed that it still built a `ClippedCapture` data object for every capture intersecting the line. This wrapper existed only to retain the source-span index and clipped start/end offsets; it carried no independent semantic state.
- Commit `fcd07e09e22dd5e4bc6b47af93f1bea6b3d1a687` replaces the wrapper list with three primitive parallel arrays: source capture indices, clipped starts, and clipped ends, plus an explicit valid-entry count. The active ordered sets continue to store compact indices, and native precedence is resolved against the original immutable `HighlightSpan` list.
- Commit `397d58415e5cdb70b890d40fca05f2bfeee35f8b` removes an additional per-line palette-style reference array; the painter resolves the palette entry from the original source capture when it emits a foreground/background run. This trades a small number of palette lookups per emitted run for one fewer array allocation and initialization per line.
- These changes remove per-capture wrapper-object allocation and one per-span reference-array allocation from the line paint path. The three primitive arrays are still allocated per invocation and sized to the incoming span list; this is bounded by the current viewport query result, but reuse or scratch-buffer pooling is not introduced because it would require explicit concurrency and memory-accounting rules.
- Existing native ordering, dark/light color fallback, and packed end-before-start boundary ordering are retained. The adjacent red/green boundary regression remains the key no-bleed test. Verification is pending on the exact current head; do not infer performance percentages without allocation profiling and same-device timing.

### Follow-up optimization targets

- The painter still allocates a boxed `MutableList<Int>` for native-priority ranking and boxed indices in five `TreeSet` instances. These are now the next obvious allocation sources, but replacing them requires preserving exact total ordering and per-font-attribute winner selection. Do not replace them with a single shared winner because bold/italic/underline/strike-through may have different native winners.
- The Kotlin capture projection still materializes `HighlightSpan` objects from the interval index. A future compact capture view should avoid object-per-capture materialization without lazy getters that allocate on every access, and must keep immutable revision identity and byte accounting.


## 2026-11-25: primitive active-winner heaps for the painter

- The previous sweep used seven `TreeSet<Int>` instances (foreground, background, and five independent font attributes). Every insertion boxed an index and allocated a tree node; each end event removed the node. This was a high-frequency per-line allocation source even after boundary events and clipped-capture wrappers had been made primitive.
- Commits `1edab5e82b370e982c82db54a42caa7789878ef3` and `1cf51bea794d9e2c628a410e273f39a9b1e4f152` replace those sets with seven primitive `IntArray` min-heaps. The heaps use the same native-order comparator; ending a capture marks its index inactive, and inactive roots are lazily discarded. A capture is inserted at most once into each applicable heap because every capture has one start/end pair.
- Heap capacity is counted from the actual active foreground/background/font attributes before allocation. Each heap is sized to the number of captures that can enter it, rather than allocating seven full-size arrays for every line. This avoids a fixed 7x capture-count scratch allocation for grammars that use only a subset of style attributes.
- Bold, italic, underline, strike-through, and dim/alpha remain independent winner queues. Do not collapse them into one font winner: nested captures may contribute different font attributes with different native precedence.
- Added instrumentation regression `primitivePainterHeapsKeepIndependentFontAttributeWinners`, which supplies overlapping captures with independent bold and italic attributes and asserts that both spans survive on the final Spannable. Existing nested-priority and adjacent-boundary tests continue to cover foreground/background precedence and boundary ordering.
- This removes boxed TreeSet nodes and per-event tree removals from the paint sweep. The heap still uses bounded primitive scratch arrays and comparator calls; no device-level speedup is claimed until CI and allocation profiling complete.

### Updated open optimization targets

- The painter still builds a boxed `MutableList<Int>` to rank all source spans for Android's 8-bit span priority metadata. A primitive index sort is a candidate next step, but it must preserve the full comparator and equal (priority, declarationOrder) rank grouping.
- `CaptureIntervalIndex.appendQuery` still materializes one `HighlightSpan` data object per overlapping capture. The next architectural step is a primitive capture batch contract shared by the interval index and painter, rather than a lazy list whose getters recreate objects. Any batch must be immutable for the duration of painting and must not escape the cache entry's revision lifetime.
- Cache eviction remains a storage event, not semantic invalidation. Do not introduce a second unaccounted capture copy while adding the primitive batch path; charge retained payloads to the existing shared budget and keep precise edit/dependency invalidation.
