# TSH Engine Development Roadmap

> Product goal: allow third-party developers to integrate TSH with an existing Editor, Document, Piece Tree/Rope, Layout, Theme, and Renderer without replacing host objects or restructuring the editor. TSH owns syntax/query/Regex/matching/scope/style/render-plan responsibilities; it is not a general-purpose editor framework.

> Highest-level contract: [TSH Public API Design Principles and Host Integration Contract](api/public-api-design-principles-and-integration-contract.md). The authoritative overall roadmap for performance, memory strategy, and global priorities is [TSH Engine Completion, Performance Remediation, and Development Backlog](../android-tree-sitter/TSH_ENGINE_COMPLETION_AND_PERFORMANCE_ROADMAP.md).

## Status Vocabulary

- **SPEC_ONLY:** specification only.
- **SOURCE_PARTIAL:** source implementation exists but does not cover the full contract.
- **SOURCE_COMPLETE:** source covers the specified scope; this does not imply it builds.
- **BUILD_VERIFIED:** the specified module/variant builds; command and commit are recorded.
- **TEST_VERIFIED:** the specified test suite passes; command and result are recorded.
- **DEVICE_VERIFIED:** end-to-end validation passes on the specified device/API/real host widget.

Changes in the referenced development round were at most SOURCE_PARTIAL. Without Gradle/JUnit/CMake/NDK/JNI/Android-device evidence, do not mark work BUILD_VERIFIED, TEST_VERIFIED, or DEVICE_VERIFIED.

## Implemented Source Slices (Verification Still Required)

### Public API and Host Integration

- [x] Added the top-level public API design contract: Host Ownership First, No Framework Lock-in, Capability-based Integration, Zero-copy Where Possible, Explicit Coordinates, Version-safe Execution, Two Pipelines Only, Renderer-owned Drawing, and No Silent Degradation.
- [x] SourcePosition offsets and Tree-sitter ByteRange use Long, retaining Int compatibility constructors.
- [x] Native TSQueryCursor Int byte-range boundaries are checked explicitly; values above Int.MAX_VALUE are rejected instead of truncated.
- [x] Provider API-version ranges, minimum Capability versions, priorities, stable ordering, conflict/missing diagnostics, and isolation of exceptions from supports callbacks.
- [x] TshEngineOptions.strictProviderValidation and enableDiagnostics are connected to Provider resolution.
- [x] Added OwnerScope, AttachmentRequest/Handle/Report, AttachmentManager, and Engine attach/inspect/refresh/detach.
- [x] OwnerScope automatically detaches. Detach unregisters only TSH's identity registration and does not close host objects; reentrant close does not cause cleanup deadlocks.
- [x] Native resource/query raw-object getters are internal to prevent hosts from bypassing Lease ownership and calling close directly.
- [x] Engine lifecycle locking linearizes initialize/attach/provider resolution/adapter creation/parser open against close. Public Object/Provider/CompiledQuery registries reject new registrations permanently after Engine close.
- [x] Engine-level shared TshMemoryBudget is configurable through TshEngineOptions.memoryBudgetOptions and exposes categorized reservations through engine.memoryBudget.snapshot().
- [x] Tree-sitter String parsing and Snapshot/Piece Tree streaming parsing reserve input-buffer budget before Native parsing; all failure/early-return paths release reservations in finally.
- [x] Optional TshNativeTreeMemoryEstimator admits estimated syntax-tree memory before parsing; successful reservations are owned as Native Tree dependencies and released only when the actual Tree closes, including after active Leases finish.
- [x] Incremental parsing admits the peak where old and new Trees coexist.
- [x] Reservation ledger limits active reservation count (default 65,536; absolute cap 262,144).
- [ ] QUERY_RESULT / MATCH_SET / RENDER_PLAN / TEXT_SNAPSHOT are not yet integrated into the shared Engine reservation ledger. Query/Match currently have local estimated limits; RenderCoordinator limits pending count only. These are not global byte admission controls.
- [ ] Provider temporary UTF-16 Strings, Tree-sitter internal allocators, and other hidden JNI/JVM allocations are not fully accounted for. The current ledger covers bounded input buffers and host-estimated Trees, not a hard process Native-heap cap.
- [x] strictProviderValidation covers attach/refresh/resolveProviders. Strict attach rolls back via detach when required capabilities are missing; lenient mode retains an INCOMPLETE report.
- [x] Multiple OwnerScopes/Attachments may share one host object through reference-counted registration. Registrations owned externally by the host are not removed by TSH detach.
- [x] Attachment detach notifications close Android Binding RenderCoordinator and clear only Spans owned by that Renderer. Engine/OwnerScope close rejects new RenderPlans and late receipts; background cleanup is dispatched through the host's postToRenderThread.
- [x] Native close/dependency-close errors become structured diagnostics; Engine exposes shutdownDiagnostics and OwnerScope exposes closeWithReport.

### Text Access and Coordinates

- [x] TextSnapshot Lease, bounded chunk reads, TextChange subscription extension points, and Document-disposed subscription extension points.
- [x] TshTextSnapshotLease.retain() and TshSharedTextSnapshotLease provide independent handle reference counts and final-release callbacks. Legacy custom Leases report explicit unsupported diagnostics; immutable default snapshot Leases can be retained. Default snapshot acquisition validates metadata and structures Provider exceptions (SOURCE_PARTIAL).
- [x] Text Providers declare threading policy and concurrent-read capability; UNKNOWN does not imply thread safety.
- [x] TextEdit replacements reject unpaired surrogates. TextChange edit lists require before-version coordinates, ascending order, and non-overlapping ranges.
- [x] Chunk reads cannot split UTF-16 surrogate pairs; reading a closed Snapshot Lease fails.
- [x] Coordinate conversion rejects unpaired UTF-16 surrogates; UTF-8 byte accumulation uses Long; non-String CharSequence inputs are explicitly rejected rather than implicitly flattened/copied through toString().
- [x] The editor module adds a real PieceTreeTextBuffer -> PieceTreeSnapshot -> TSH Snapshot Provider. Immutable PieceTreeSnapshot retains original chunk references and copies only valid text from the active change buffer; a Lease can independently retain an older version (SOURCE_PARTIAL).
- [x] Real EditorDocument snapshotFactory is connected. A reentrant transaction lock serializes buffer edits/replacement and PieceTreeSnapshot creation; EditorView exposes a controlled snapshot entry. TSH before/after snapshots cannot observe intermediate states of a multi-edit transaction (SOURCE_PARTIAL; not runtime-verified).
- [ ] Other mutable Piece Tree/Rope implementations still need persistent-root pin/unpin. Indexed UTF-8/UTF-16/Code Point/Grapheme conversions are not complete.
- [ ] Indexed Piece Tree/Rope UTF-8/UTF-16/Code Point/Grapheme conversion is not implemented.
- [ ] Grapheme handling currently uses runtime BreakIterator and does not claim full UAX #29 compliance.
- [ ] Actual thread scheduling, EditorDocument change events, and disposal events are not connected for all host Providers. The PieceTree host supports snapshots from any thread, serialized snapshot factories, and concurrent reads of immutable snapshots; other Provider thread policies still require individual verification (SOURCE_PARTIAL).

### DSL / Schema / Runtime Consumer

- [x] Fixed nullable vs OptionalType confusion, default-value validation, Measure range units, Map key types, Capability versions, and non-numeric ranges.
- [x] Schema numeric-range comparisons preserve Long integer precision and avoid Double precision loss above 2^53; boundary regression-test source was added.
- [x] Unknown properties are no longer silently discarded; they enter unappliedProperties and produce diagnostics.
- [x] Added RuntimeConsumerRegistry/Dispatcher; passing Schema validation no longer implies a property took effect.
- [x] Runtime Dispatcher reports applied/unapplied per property, checks Capability versions, and isolates Consumer exceptions.
- [ ] Built-in Schema Catalog, ReferenceResolver, complete SemanticAnalyzer, ATS Lowering, and built-in Runtime Consumer Catalog are incomplete.
- [ ] The .tsh Lexer/Parser is not implemented; the typed AST remains a parser-neutral model.
- [ ] The existing C++ TshCompiler is not yet integrated with the unified Schema/Runtime Consumer contract.
- [ ] Runtime Consumers currently use per-property best effort; no cross-Consumer atomic transaction is provided.

### Tree-sitter / ATS Query

- [x] Tree-sitter Native Query Compiler/Executor Adapter, Query Registry, and Query/Tree/Language Lease source exists.
- [x] TshQueryCompiler and ATS-specific TshAtsCompiler contracts are separate; the Native S-expression Adapter no longer masquerades as an ATS Compiler.
- [x] ATS input explicitly returns unsupported; documentation consistently states that ATS Lexer/Parser/AST/Compiler/Lowering are not implemented yet.
- [x] strictNodeValidation defaults to disabled; explicit enablement returns unsupported rather than silently ignoring the request.
- [x] maxSteps defaults to null; explicit requests return unsupported before entering Native. stepsUsed=null means the Native API does not expose a count.
- [x] maxMatches configures both the Cursor pending-match limit and the total returned Match limit during draining.
- [x] Long ByteRange values are checked explicitly before entering the Int-limited Native Cursor.
- [ ] ATS Lexer/Parser/AST/Compiler/Lowering are not implemented; ATS must ultimately compile into the existing Tree-sitter Query pipeline.
- [ ] Grammar Node Schema Provider / strictNodeValidation is not implemented.
- [ ] Real Tree-sitter step budgets, interruptible Native cancellation, incremental Edit, and changed-range support are not implemented.
- [x] TSParser.parseInput(TSInput), JNI callbacks, TshSnapshotTreeSitterInput, and ParserOwner Snapshot overload provide chunked parsing. This entry independently retains a snapshot Lease and releases it after Native parsing returns (SOURCE_PARTIAL).
- [x] TshTextAccessProvider is integrated into the Engine Provider Registry. ProviderRegistry.acquireScoped reference-counts shared Provider lifetimes. TshTextAccessBinding manages Document Attachment, Snapshot Lease acquisition, Change/Disposal subscriptions, and lifecycle. text.snapshot/text.chunk and optional change/disposal notifications are exposed through capability negotiation.
- [x] OwnedSyntaxTree exclusive edit leases, synchronized snapshot/registry metadata revisions, and QueryExecutor stale-snapshot rejection have source implementations.
- [x] PieceTreeTextBuffer Snapshot/Chunk Provider source and test source exist (SOURCE_PARTIAL).

## Remaining Work and Verification

A checked item means source work exists, not that it has passed build, tests, or device verification. Continue to record evidence per module and commit. Preserve the separation between public contracts, host-owned objects, Native resource lifetimes, coordinate systems, ATS/Query, and the independent Regex pipeline.
