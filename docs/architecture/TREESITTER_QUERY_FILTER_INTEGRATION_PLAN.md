# Tree-sitter Query Filter Integration and Large-Document Highlighting Plan

Status: implementation plan for `fix/tsh-core-architecture`  
Baseline reviewed: `407efa422972fbfe98a3248c9d45b61b89a1bf40`  
Reference implementation: `treesitter/src/main/cpp/ts_query_filter.{h,cpp}` on `main`

## Goals and non-negotiable invariants

1. Keep `android-tree-sitter` as the sole public JNI binding and keep TSH's two existing execution paths: ATS + Tree-sitter Query, and the independent Oniguruma Regex path. Do not add Monarch, TextMate, or a third execution pipeline.
2. Absorb the useful `treesitter` capabilities: native query predicate filtering, Lua 5.1/LuaJIT pattern semantics, native-decision fast path, callback-based parser input, explicit cursor ranges, and predictable native resource ownership.
3. Do not copy a complete 100–200 MiB document into a second `std::u16string`. Predicate text must be fetched lazily from `TextSource`/Piece Tree with bounded scratch memory.
4. Never silently drop a match, capture, or highlight. Unsupported or undecidable predicates must fall back to the existing authoritative evaluator; resource exhaustion or incomplete traversal must become an explicit failure/diagnostic.
5. Preserve UTF-16LE source offsets used by TSH and Tree-sitter's UTF-8/UTF-16 input contract. Validate all conversions and 32-bit Tree-sitter ABI boundaries before narrowing 64-bit source offsets.
6. Keep native object lifetime tied to existing `TSNativeObject` / query and cursor ownership. No global pointer-keyed state may outlive its query/cursor or race with close/re-exec.

## Reviewed baseline and concrete gaps

### Reference module strengths

- `ts_query_filter.cpp/.h`: compiles query predicate steps once per query; evaluates supported predicates before JNI match materialization; distinguishes ACCEPT, REJECT, and UNDECIDED; carries the `predicatesDecided` fast-path bit.
- Lua pattern matcher ports Lua 5.1/LuaJIT `string.find` behavior, including classes, sets, quantifiers, anchors, captures, backreferences, balanced delimiters and frontier patterns.
- Cursor API includes text injection, byte/point ranges, containing byte range, max start depth, progress callback, and both match/capture iteration.
- Parser callback input and progress callback enable non-monolithic input and cooperative cancellation.
- Kotlin API includes query predicate representations, cursor helpers, and cleaner-backed fallback cleanup.

### Reference implementation weaknesses that must not be copied

- `set_text()` stores an entire UTF-16 document in a per-cursor `std::u16string`. This duplicates large documents and is incompatible with our memory budget for 100–200 MiB sources.
- Pointer-keyed global maps and one global mutex couple query/cursor lifetime to external cleanup calls; a missed cleanup can retain state and address reuse can associate stale state with a new object.
- The UTF-8 to UTF-16 helper shown in the filter is permissive and does not fully reject malformed, overlong, surrogate, or truncated sequences. The new adapter must validate strictly.
- Lua pattern matching is recursive/backtracking. Its depth guard prevents unbounded recursion but does not by itself bound worst-case CPU time. Add operation budgets/cancellation and report pattern errors separately from a normal non-match.
- Predicate support is intentionally partial. Unsupported predicates must remain UNDECIDED and continue through the existing Kotlin/TSH evaluator; never treat an unsupported predicate as true for a final accepted match.
- The reference `UInt` offsets are narrowed to JNI `Int` in places. Large-document boundaries need explicit checked conversions.
- Cleaner availability and behavior differ across Android API levels; deterministic `close()` remains primary.

### Current TSH gaps observed

- `tshEvaluateNativePredicates()` currently rebuilds predicate-step vectors and capture-text vectors while evaluating each match. Repeated captures can therefore allocate and copy the same text many times.
- Current TSH predicate handling is narrower than the reference filter: it handles a subset of eq/not-eq, any-of/not-any-of and match/not-match forms; it does not yet provide the reference's full any-eq, Lua-pattern, capture-to-capture, contains, ancestor/parent and kind predicate family.
- Current TSH query traversal already checks `ts_query_cursor_did_exceed_match_limit()` and fails the block instead of publishing incomplete captures. This all-or-nothing behavior must be retained.
- Query spans are appended directly to the shared output with a checkpoint, avoiding a second full temporary span vector. Preserve this.
- Query execution currently uses a very high match limit. A byte-budgeted capture-list policy belongs in the Tree-sitter core/submodule and cannot be solved by changing the JNI wrapper alone.
- The current native query evaluator runs directly in TSH; adding a filter only to `TSQueryCursor_nextMatch()` JNI would not improve the TSH highlighting path. The filter must be shared by the direct TSH query loop and the public JNI cursor path.

## Target architecture

### A. Query predicate compiler

Compile each query's predicate steps once into immutable per-pattern metadata owned by the query/grammar snapshot:

- predicate operator and polarity;
- capture IDs and literal/capture operands;
- whether evaluation is complete in native code;
- compiled Oniguruma/Vim-regex handles where applicable;
- compiled Lua-pattern representation or validated source pattern;
- operator-specific limits and diagnostics.

The compiled object is immutable after publication and destroyed with its owning query. Avoid process-global raw-pointer maps.

### B. Bounded text access

Replace whole-document `setText` with a source adapter contract:

- source identity/version and absolute UTF-16LE byte range;
- checked `read(offset, maxBytes)` / range comparison operations;
- bounded scratch budget, initially 64 KiB per active evaluator;
- direct literal equality/prefix/suffix and set membership checks without materializing node text;
- bounded node-text materialization only for predicates that truly require it;
- source version validation before and after evaluation to reject stale reads.

For ordinary JNI query cursors, preserve the existing API and add an optional TextSource-backed provider. The legacy string setter may remain for small inputs but must reject/route large inputs rather than silently duplicate them.

### C. Predicate execution semantics

- Return tri-state `ACCEPT`, `REJECT`, or `UNDECIDED`.
- REJECT removes a match before JNI materialization.
- ACCEPT marks the match as natively decided so Kotlin does not repeat the same work.
- UNDECIDED preserves the match and invokes the existing authoritative evaluator.
- Implement operator semantics and empty-capture behavior with tests before enabling native fast paths.
- Keep TSH's custom selector semantics and user-registered predicate/directive handlers intact.

### D. Lua pattern engine

Port the reference Lua 5.1/LuaJIT matcher with strict input validation and improvements:

- no unbounded recursion; use an explicit bounded stack or strict depth and step budgets;
- cancellation checks inside long scans/backtracking;
- maximum pattern length, capture count and execution steps;
- distinguish invalid pattern, budget exhaustion, cancellation and ordinary no-match;
- operate on bounded UTF-8 bytes sourced from the UTF-16LE TextSource adapter, correctly handling code-point boundaries;
- do not claim compatibility until differential tests against LuaJIT's `string.find` pass.

### E. Query/cursor and parser integration

- Share the predicate compiler/evaluator between direct TSH execution and `TSQueryCursor` JNI.
- Add native `predicatesDecided` metadata without changing the existing public match ABI unless a versioned extension is required.
- Preserve byte range, containing range, point range, max depth, match limit, and progress cancellation semantics.
- Use the parser's read callback to feed bounded chunks from Piece Tree/TextSource; verify Tree-sitter callback lifetime and chunk-boundary behavior.
- Keep incremental edits and included ranges correct; callback input is not a substitute for the complete parse tree.

### F. Large-document highlight result pipeline

- Keep the complete authoritative TSTree and existing ATS + Tree-sitter Query + Regex semantics.
- Avoid simultaneous full native span vector and full Java `int[]` copies; introduce bounded page/batch retrieval or a lease-backed native result handle.
- Ensure the UI requests visible ranges and adjacent prefetch ranges without making visible-range results look like full-document completion.
- Cache by source version, grammar/query revision and partition version.
- On cancellation, stale source, budget exhaustion, match-limit overflow or failed batch, expose explicit FAILED/PARTIAL state and retry/backoff; never report successful complete highlighting.

## Current implementation progress

- Added this integration plan and reviewed the reference filter against the current TSH execution path.
- Cached each compiled TSH query pattern's predicate-step vectors in `NativeQueryProgram`. The compiler now records them once, and the per-match evaluator reuses immutable steps instead of rebuilding a temporary vector for every match. This is a hot-loop allocation reduction only; predicate coverage and capture-text materialization are not yet expanded.
- Equality and membership predicates now compare capture byte ranges directly. Contiguous UTF-16 input uses `memcmp`; a `TextSource` is read through a fixed 16 KiB scratch buffer. This removes the per-match full-capture temporary allocation for `#eq?`, `#not-eq?`, `#any-of?` and `#not-any-of?`. Source read/validation failures are kept distinct from ordinary mismatches so negative predicates cannot turn an I/O failure into an accepted match. The evaluator now returns ACCEPT/REJECT/ERROR; ERROR aborts the current highlight operation and is translated at the JNI boundary into an explicit failure instead of silently dropping the query match.
- Added an Android/JVM-side native-document regression fixture with a 32 MiB Java block-comment capture and a short `#not-eq?` literal. It asserts that the clipped query result still produces the configured comment style. This test is committed but has not yet completed CI/device execution.
- Added a second regression test that allows the initial parse, then forces subsequent TextSource reads to fail while `#eq?` evaluates a capture. The expected contract is an explicit `IllegalStateException`, never an apparently successful empty highlight result. Both tests are awaiting the latest CI run.
- Repaired a missing `#endif` for `TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS` discovered in the historical failed build log (`42756666389`): the query-cursor implementation was inside an unterminated feature guard. The latest branch includes the closing guard; the latest build has not yet reached compilation.
- Regex predicates now reuse the compiler's conservative finite-width analyzer. Patterns in the proven subset (no anchors, groups, alternation, backreferences, lookarounds or unbounded repetition) receive a maximum UTF-16 byte width. For TextSource captures, `#match?` / `#not-match?` scan 256 KiB core windows with a bounded overlap, and retain the ACCEPT/REJECT/ERROR distinction. The lookahead width is capped at 8 MiB; unsupported/context-dependent patterns keep the full-capture fallback rather than being reinterpreted as window-local.
- Contiguous UTF-16 documents now pass capture pointers directly to Oniguruma instead of copying each capture into a temporary vector. Empty-capture matching is handled explicitly. A 32 MiB block-comment regression fixture independently asserts the `regexComment` style produced by bounded `#match?`; the matched `az` sequence straddles the 256 KiB window boundary to verify overlap handling. It is committed but still awaits CI execution.
- Context-dependent/unbounded Regex Predicate captures on TextSource still require contiguous materialization. This is an explicit remaining large-capture memory hotspot; future work must support those semantics through a compatible file-backed/mapped input or fail with a clear bounded-resource error, never silently truncate.
- The earlier Actions run `42761510543` was cancelled when subsequent commits superseded it. Runs for the streamed-comparison fix and its regression test are queued/running; no build or test success is claimed until the latest head's jobs complete.

## Delivery sequence and acceptance gates

1. **Contract and tests**: document operator semantics; add predicate truth-table tests (including absent and repeated captures), malformed UTF-8, Unicode supplementary characters, and unsupported-predicate fallback.
2. **Shared compiler/evaluator**: move predicate compilation out of per-match loops; add immutable ownership and tri-state results; no whole-document text copy.
3. **TextSource adapter**: implement bounded reads and direct comparisons; verify PieceTreeSource, FileWindowSource and MappedFileSource, source-version changes and cross-chunk capture text.
4. **Lua matcher**: port and harden Lua patterns with budgets, cancellation and differential tests.
5. **JNI integration**: expose the same evaluator through query cursor JNI while preserving Java/Kotlin predicate handlers and resource lifecycle.
6. **Parser callback integration**: route parser reads through TextSource and test chunk boundaries, cancellation, included ranges and incremental edits.
7. **Result transport**: replace duplicate full result staging with bounded batches/leases; update Java/Kotlin APIs and generated JNI declarations together.
8. **Stress and device validation**: 1, 32, 100, 200 and 300 MiB fixtures; dense and sparse captures; UTF-8/UTF-16 mixed content; long lines; pathological regex/Lua patterns; memory pressure; cancellation; repeated open/close/reparse.

Acceptance requires:
- no missing captures versus an unbounded reference run on supported predicates;
- unsupported predicates demonstrably fall back;
- no silent match-limit or output truncation;
- bounded evaluator scratch memory independent of total document size;
- no extra full-document UTF-16 copy;
- no ATS ERROR/MISSING regression on valid fixtures;
- exact style/color IDs and byte ranges verified from native output through the app rendering layer;
- measured peak native + Java memory and frame/render latency on Android devices for 100 and 200 MiB files.

## Evidence status

The source review establishes that the reference module contains these designs and that the current TSH evaluator has the allocation and predicate-coverage gaps listed above. It does **not** establish a reproducible 100–200 MiB device benchmark for this branch. That claim must be validated with instrumented device tests and end-to-end color-span assertions, not inferred from source structure alone.
