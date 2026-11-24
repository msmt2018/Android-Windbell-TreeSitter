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
