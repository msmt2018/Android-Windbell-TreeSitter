# TSH API Foundation

Package: `com.zerostudio.idea.treesitter.tsh.api`

This reference documents the API types currently present in source. It is not a promise that every model has a complete parser, persistence layer or host integration.

## Engine and result model

### `TshEngine` and `TshEngineOptions`

`TshEngine.create(options)` constructs an engine. `initialize()` transitions it into its initialized state; `close()` shuts down owned registries/resources and exposes shutdown diagnostics. `state` reports `CREATED`, `INITIALIZED` or `CLOSED`.

The engine owns its memory budget, object/provider registries, attachment manager, compiled-query registry, native query lease store and native resource registry. Factory methods create Tree-sitter query compiler/executor, parser owner, match merge budget and render coordinator components. `attach`, `inspectAttachment`, `refreshAttachment`, `detach` and `resolveProviders` expose attachment/provider operations. Operations requiring an initialized engine return `TshResult` failures when lifecycle preconditions are not met.

`TshEngineOptions` controls diagnostic enablement, strict provider validation and memory-budget options.

### `TshResult<T>`, `TshDiagnostic` and source positions

* `TshResult.Success<T>` contains a value and diagnostics.
* `TshResult.Failure` contains diagnostics.
* `isSuccess()` and `diagnostics()` provide common inspection.
* `TshDiagnostic` contains code, severity, stage, message and optional source/range/object/cause/related information.
* `TshSourcePosition` records a long offset and coordinate system, with optional line/column.
* `TshSourceRange` contains start/end positions and optional document version.
* `TshDiagnosticSeverity`, `TshDiagnosticStage` and `TshCoordinateSystem` are enums used by these records.

### Capabilities and identity

* `TshCapability` describes a capability ID, version and description.
* `TshCapabilitySet` creates/holds capability sets and supports `contains`, `satisfies`, `get`, `all` and `isEmpty`.
* `TshObjectId`, `TshAttachmentId`, `TshDocumentId`, `TshMatchId`, `TshRuleId` and `TshRenderPlanId` are strongly named identifiers defined in `TshApiTypes.kt`.
* `TshObjectDescriptor` describes a registered object, its type, capabilities, metadata and registration timestamp.

## Object/provider lifecycle

### `TshObjectRegistry`

Registers object instances and maps them to descriptors/IDs. Supports registration, unique registration, lookup by instance/ID, descriptor lookup, unregister, enumeration, clear and close. Object identity is registry identity; it is not a persistent identifier across engine lifetimes.

### `TshProvider` / `TshProviderRegistry`

A provider declares `id`, `apiVersion`, capabilities, priority and `supports(instance, descriptor)`. The registry supports normal/scoped/unique registration, unregister, lookup, resolution and lifecycle cleanup.

`TshProviderApiRange` constrains compatible provider versions. `TshProviderResolution` reports selected/rejected providers, missing required/optional and incompatible capabilities, diagnostics and degradation level. `TshDegradationLevel` and `TshProviderRejection` describe resolution outcomes.

### `TshOwnerScope` and attachments

`TshOwnerScope` groups cleanup actions and closes them as a scope. `registerCleanup` returns a closeable registration when accepted; `closeWithReport` and `closeDiagnostics` expose cleanup failures.

`TshAttachmentRequest` declares an instance, object type, capabilities, metadata, required/optional capabilities and provider API range. `TshAttachmentManager.attach` returns a `TshResult<TshAttachmentReport>`; reports include handle, object descriptor, provider resolution, status, diagnostics and timestamps. `inspect`, `refresh`, `subscribeDetached` and `detach` manage the attachment. Status values are `READY`, `DEGRADED`, `INCOMPLETE` and `DETACHED`.

## Configuration and schema models

### `TshDslModel`

Defines typed values and declaration/document records: boolean, integer, decimal, string, identifier, color, measure, font-family, regex, query, reference, array and object values. It also defines DSL version, declaration kind, units, color spaces and attribute merge policies.

These are typed in-memory models. Their presence does not imply a text lexer/parser is implemented in this package.

### `TshSchemaRegistry`

Registers declaration schemas and validates declarations against property type, required/nullable/default/allowed-value/range/capability constraints. `TshValidationMode` selects strict/lenient behavior. `TshSchemaValidationResult` returns normalized properties, diagnostics, unapplied properties and DSL version.

### `TshConfigRepository`

Stores immutable snapshots of `TshConfigArtifact` values. `install`, revision-checked `replace`, `get`, `list`, revision-checked `unload` and `clear` manage installed artifacts. An artifact contains config ID, revision, source ID/text, typed document and compile timestamp; it is not a source-text compiler.

### `TshDslRuntimeDispatcher`

Dispatches validated declaration properties to registered `TshRuntimePropertyConsumer` implementations. `TshRuntimeConsumerRegistry` registers consumers by ID and declaration/property key. `TshRuntimeApplyReport` identifies applied and unapplied properties and diagnostics.

## Text/document access

### `TshTextDocument`, `TshTextSnapshot`, `TshTextSnapshotLease`

See [Text and large documents](text-and-large-documents.md). Snapshot offsets are UTF-16 code units; leases define snapshot retention.

### `TshTextAccessBinding`

Attaches a host document through a `TshTextAccessProvider`, acquires snapshot leases, subscribes to document changes and disposal, and cleans up subscriptions when detached/closed. `attach` returns a structured result and may register the provider with the engine. Provider threading/capability declarations determine whether the requested access is available.

### `TshCoordinateConverter` and `TshSparseCoordinateIndex`

Convert offsets between supported coordinate systems using the supplied text/snapshot. Conversion failures are represented as `TshResult`; long-offset conversion is available through `convertLong`. Do not assume grapheme clusters, code points, UTF-16 code units and encoded bytes are interchangeable.

## Query and matching

### Query models

* `TshGrammarDescriptor`: grammar ID/version/ABI/fingerprint/capabilities.
* `TshAtsSource`: query text, source language (`ATS` or `TREE_SITTER`) and optional source ID.
* `TshQueryCompileOptions`: predicate enablement, strict node validation and pattern-count limit.
* `TshCompiledQuery`: compiled query identity/revision, grammar, source hash, compiler version, options and optional native object ID.
* `TshSyntaxTreeSnapshot`: document/version/tree revision, grammar, root byte range, native object ID and input encoding.
* `TshQueryExecutionBudget`: max steps, matches, captures and result bytes.
* `TshQueryCancellation` / `TshQueryProgressCancellation`: cancellation and progress callback contracts.
* `TshCapture` / `TshQueryResult`: capture and execution result records.
* `TshQueryCompiler`, `TshAtsCompiler` and `TshQueryExecutor`: compile/execute interfaces.
* `TshCompiledQueryRegistry`: register, retrieve, compatibility-check, unregister and close compiled queries.

### Tree-sitter adapters

`TshTreeSitterQueryCompiler` compiles query text against a registered grammar language and tracks native query ownership. `TshTreeSitterQueryExecutor` executes a compiled query against a syntax-tree snapshot with range, budget, cancellation and predicate handling. `TshTreeSitterMatchAdapter` converts native query captures to unified `TshMatch` records. `TshTreeSitterParserOwner` owns parser instances and syntax trees; `TshOwnedSyntaxTree` represents a registered tree with snapshot/revision and memory ownership. `TshTreeSitterEditCompiler` and `TshTreeSitterIncrementalUpdate` translate/apply document edits.

### Unified match model

`TshMatch` records match identity, document/version, source pipeline, rule, capture, canonical/raw ranges, priority, captures, scope assignments and provenance. `TshMatchSet` groups results with document and pipeline revisions plus completeness/diagnostics. `TshMatchMergeBudget` merges results under match/estimated-byte/memory limits. Completeness values are `COMPLETE`, `PARTIAL` and `TRUNCATED`.

`TshRegexMatchAdapter` converts regex records into unified matches. Regex types include `TshRegexCaptureGroup`, `TshRegexMatchRecord` and `TshRegexResult`.

### Regex execution and windows

`TshOnigurumaRegex` compiles and executes Oniguruma patterns against UTF-16 windows. `TshRegexWindowConstraints` describes core/max window size, optional maximum match length and lookbehind/lookahead requirements. `TshRegexWindowLease` represents a retained text window with core and context boundaries. `TshRegexWindowSequence` produces successive window leases. The executor returns match records, completeness/truncation information and diagnostics; unsupported context-dependent constructs are handled according to the compile/validation path rather than silently assumed safe.

## Native ownership and memory

* `TshMemoryBudget`, `TshMemoryBudgetOptions`, `TshMemoryBudgetSnapshot`, `TshMemoryReservation`: reserve, resize, release and inspect memory by category.
* `TshNativeResourceRegistry` and `TshNativeResourceLeaseStore`: register native resources and acquire shared/exclusive leases; retired resources close after active leases are released.
* `TshNativeQueryLeaseStore`: tracks compiled native query leases and deferred retirement.
* `TshObjectRegistry`: associates runtime objects with object IDs/descriptors.
* `TshNativeTreeMemoryEstimator`: estimates native tree memory from a `TshNativeTreeMemoryRequest`; the estimate is supplied by the configured estimator and is not an exact allocator measurement.
* `TshParseProgressCallback`: asks whether parsing should be cancelled at a UTF-16 offset/error state.

## Style and rendering contracts

### `TshStyleResolver`

Resolves `TshStyleRule` entries against `TshScopePath` assignments. `TshScopeSelector` supports exact-path and suffix-path matching using exact and wildcard selector segments. The resolver returns render instructions according to selector specificity, priority and z-order implemented by the class.

### `TshRenderApi`

* `TshResolvedStyle`: optional foreground/background ARGB, font families/size/weight, italic, underline, strike-through and decorations.
* `TshRenderInstruction`: source range, resolved style, z-index, priority and source match IDs.
* `TshRenderRevisions`: document/config/theme/viewport/renderer and optional Tree-sitter/Regex revisions.
* `TshRenderPlan`: plan identity, revisions, instructions and required capabilities.
* `TshRenderReceipt`: provider receipt/status, applied/deferred/dropped counts and diagnostics. `confirmsActualDraw` and `confirmsHostMutation` distinguish what the receipt actually confirms.
* `TshRenderProvider`: submits/cancels plans and participates in provider capability resolution.
* `TshRenderCoordinator`: validates current revisions, reserves plan memory, submits plans, acknowledges receipts, cancels pending work and closes outstanding state.
* Receipt status values are `QUEUED`, `DRAWN`, `PARTIAL`, `APPLIED`, `PARTIALLY_APPLIED`, `REJECTED` and `FAILED`. Do not interpret `APPLIED` as proof of actual pixels unless `confirmsActualDraw` says so.

## Sources

[tsh/api package](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh/api)
