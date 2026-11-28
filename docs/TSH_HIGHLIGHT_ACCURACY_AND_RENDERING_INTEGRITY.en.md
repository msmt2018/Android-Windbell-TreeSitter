# TSH Highlight Matching Accuracy, Result Integrity, and Actual Rendering Specification

> Purpose: consolidate previously confirmed highlighting/rendering failures into an implementable, diagnosable, and verifiable engineering specification.  
> Scope: `treesitter`, `android-tree-sitter` JNI/Native, TSH C++ Runtime, Kotlin API/Adapter, host Editor, Piece Tree/Rope, and Span/Canvas/View Renderer.  
> This document defines problems and end-to-end correctness requirements. It does not claim that every target is implemented or device-verified. Status must be backed by source, build, test, and real-device evidence.

## 1. Problem Statement

The problem is not merely “highlight colors are invisible.” It is a chain of correctness failures:

1. **Incorrect response:** requested document, range, version, or viewport differs from the data actually processed; a partial request returns the wrong region, stale data, or incomplete output.
2. **Incorrect matching:** Tree-sitter Query/ATS or Regex misses expected syntax objects or matches the wrong node, rule, or text range.
3. **Incorrect capture identity:** Query Match, Capture, Node, Pattern, Capture Name, Rule, and Style associations are lost or mismatched. Duplicate captures, nested nodes, or multiple patterns are merged incorrectly.
4. **Incorrect coordinates:** UTF-8 bytes, UTF-16 code units, Unicode code points, line/column, Piece Tree absolute offsets, and window-local offsets are mixed or converted incorrectly.
5. **Truncated results:** Native match/pending-match limits, memory budgets, pagination, batches, window boundaries, output-array capacity, or UI viewport limits omit results while upper layers report success.
6. **Incorrect filtering or overriding:** predicate semantics, priority, overlap conflicts, Scope/Style resolution, cache invalidation, or cross-pipeline merging removes valid matches or changes colors.
7. **Styles never reach drawing:** matches and styles exist, but .tsh colors are not resolved, Style IDs are mis-mapped, RenderPlans are not consumed, a Renderer lacks a property, or actual drawing never occurs.
8. **Large-document performance/stability:** full-text copies, contiguous-buffer assumptions, full Span allocation, unbounded temporary arrays, eager Query result materialization, blocking work, or memory growth make large files slow, incomplete, or unstable.
9. **Incorrect incremental updates:** old Trees, Matches, Captures, Spans, or RenderPlans remain valid after edits. Cache validity based only on Capture/viewport overlap misses ancestor, sibling, predicate, and context dependencies.
10. **Incorrect verification criteria:** a non-empty API result, Native output, or invalidate call is treated as proof that colors appeared, without checking final View/Canvas pixels.

These issues are connected. Diagnose them across the end-to-end data path; do not patch one layer with temporary colors or hidden failures.

## 2. Non-Negotiable Principles

### 2.1 Correctness Before Apparent Responsiveness

- Fast does not mean correct.
- Non-empty does not mean correctly matched.
- Partial output does not mean complete success.
- Creating/submitting/queuing a RenderPlan, updating Spans, or calling invalidate does not prove that pixels were drawn.
- If a stage cannot prove completeness, return an explicit state such as PARTIAL, TRUNCATED, STALE, CANCELLED, or FAILED. Never disguise it as COMPLETE.

### 2.2 No Silent Data Loss

No layer may silently discard valid data, including:

- Query Matches, Captures, Nodes, and Patterns.
- Regex full matches and capture groups.
- Results across partitions, windows, or batches.
- Spans/RenderInstructions that should appear in the viewport.
- Colors, fonts, backgrounds, and decoration properties declared in .tsh.
- Caches and partial results that remain valid after edits.

When an explicit rule conflict overrides an attribute, retain an explainable arbitration/provenance record.

### 2.3 Large-Document Support Must Exist at the Core

- Tree-sitter JNI bindings and TSH Native core must process large text directly; capability must not depend entirely on an application-layer Piece Tree.
- Piece Tree/Rope is a TextSource provider that can expose bounded reads, snapshots, versions, and incremental edits. It is not an external patch that replaces parsing, matching, and capture inside Tree-sitter/TSH.
- Native layers must correctly handle tokens, matches, captures, offsets, result budgets, pagination, and lifetimes.
- Do not use “flatten the entire file into a String and pass it to JNI/TSH” as the large-text architecture.
- Target scenarios include ordinary 200–300 MiB files and, where feasible, larger files. Capability must be measured by device memory and operation type.
- Dynamic-memory controls must be based on actual data-structure efficiency, lifecycle, and system pressure. Do not impose an artificially low fixed budget that silently limits normal parsing, captures, or editing.

### 2.4 Exactly Two Matching Pipelines

1. **ATS / Tree-sitter Query:** structural syntax-tree queries and captures.
2. **Independent Regex / Oniguruma:** text-pattern matching and supplementary local rules.

Both converge on a unified Match model. Do not introduce a third execution pipeline or conflate their internal semantics. TextMate/Monarch may inform configuration organization and semantics but are not additional runtimes.

### 2.5 The Renderer Owns Final Drawing

TSH handles parsing, matching, Scope/Style resolution, and RenderPlans. The host Renderer owns actual display updates and drawing. The core must not assume that every host supports every style property, and must not treat invalidate as Draw.

## 3. End-to-End Data Path and Correctness Boundaries

Every stage must preserve or expose input identity, version, coordinates, output counts, status, and diagnostics.

| Stage | Input | Output | Required proof |
|---|---|---|---|
| Text Access | Document/Snapshot/Piece Tree | Bounded text reads | Correct document identity, version, length, encoding, and range |
| Parse | TextSource + Grammar | TSTree | Complete input; consistent byte/point coordinates; no silent truncation |
| Query Compile | Grammar + Query Source | Compiled Query | Correct Pattern/Capture/Predicate definitions |
| Query Execute | Tree + Cursor + Range | Match/Capture | Correct matched object, Pattern, Capture, and Node identity |
| Regex Execute | TextSource + Regex + Window | Match/Capture Group | Equivalent window semantics and correct absolute offsets |
| Normalize | Native results | TshMatchSet | Correct coordinate conversion, version binding, source, and completeness |
| Resolve | Match + Scope + Style/Theme | ResolvedStyle | Correct rule hit, color/property, and priority |
| Compose | Multi-source styles | RenderInstruction/Plan | Deterministic and explainable overlap/containment resolution |
| Apply/Draw | RenderPlan + Host Renderer | Receipt | Viewport instructions actually applied/drawn; off-screen work correctly deferred |
| Verify | Receipt + Host/Pixel | Observable result | Real widget colors/decorations match expected output |

Each stage must carry or make traceable documentId, documentVersion, coordinate system, range, source pipeline, rule/pattern/capture identity, completeness state, and trace/diagnostic identifiers.

## 4. Result Completeness and Truncation

A limit is not proof of completeness. Every API that can stop early must expose whether output is COMPLETE, PARTIAL, TRUNCATED, CANCELLED, STALE, or FAILED, along with the stopping reason and continuation information where applicable.

Never silently treat a bounded Cursor drain, output-array capacity, viewport query, or Regex window as the entire document result. Pagination and incremental continuation must preserve ordering and document version.

## 5. Coordinate Integrity

Use explicit coordinate-system types. Tree-sitter ranges are UTF-8 byte offsets; Android text APIs commonly use UTF-16 code-unit offsets. Convert through a versioned TextSnapshot/coordinate mapper. Include tests for supplementary-plane characters, Emoji sequences, combining marks, CRLF, and edits around encoding boundaries. Reject malformed surrogate sequences instead of silently shifting offsets.

## 6. Style and Color Integrity

- Preserve the exact color configured in .tsh through Native palette decoding, Kotlin fallback, Android Span, and final Canvas drawing.
- Missing foreground/background means “no override,” not a theme-derived substitute.
- Do not replace an explicitly configured color with a night-mode fallback or inferred semantic color.
- Preserve alpha and modifiers through every conversion.
- Record style provenance and arbitration decisions so a color mismatch can be localized.

## 7. Incremental Correctness

Every result is bound to a document version and rule/theme revision. On edits, invalidate all dependent syntax/query/Regex/style/render data, including structural dependencies such as ancestors, siblings, predicates, and surrounding context. A late result from an older version must never overwrite a newer result.

## 8. Large-Document Execution

Avoid whole-document flattening, eager full-result materialization, and per-token JVM object creation. Prefer bounded TextSource access, Native compact batches, stable handles/Leases, visible-range execution, and version-aware caches. A memory-pressure response may evict rebuildable caches or reduce speculative work, but must not silently truncate required output.

## 9. Rendering Verification

Distinguish these milestones:

1. Match generated.
2. Style resolved.
3. RenderInstruction generated.
4. Provider selected.
5. Instruction submitted.
6. Host invalidated.
7. Draw callback confirmed.
8. Final pixels/content verified.

Only the last stages prove actual rendering. Tests must inspect the real host widget or rendered output, not only the existence of a Span or RenderPlan.

## 10. Acceptance Criteria

A fix is complete only when the original failure is reproducible, the responsible stage is identified, the source-level fix preserves the contracts above, relevant regression tests pass, large-document behavior is measured, and real rendering is verified on the target host/device. Documentation or a successful API call alone is not completion evidence.
