# TSH / Tree-sitter Large-Text Token and Native Chunk-Processing Architecture

> Type: technical theory, industry research, core-framework and architecture design.  
> Repository: `msmt2018/Android-Windbell-TreeSitter`.  
> Scope: `treesitter`, `android-tree-sitter` JNI/Native, TSH C++ Runtime, ATS/Tree-sitter Query, and independent Regex/Oniguruma.  
> Focus: establish a Native processing system for very large token/node/match volumes and chunked input inside the Tree-sitter JNI bindings and TSH engine.  
> Non-goal: moving large-text responsibility to application-layer file objects, requiring the application to read the entire file into a String, flattening a Piece Tree into one contiguous buffer, or replacing Native engine capabilities with application-side chunk parsing.  
> Status: architecture and technical design; not proof that every mechanism is implemented, built, or device-verified.

> **Memory-strategy update:** Earlier fixed 100 MiB normal budgets and a unified 512 MiB hard cap are not performance targets or normal-operation quotas. Follow the current overall roadmap: first optimize Piece Tree/multi-way nodes/buffers/encoding indexes/object lifetimes. Memory budgets are for observability, system-pressure protection, and exceptional recovery; they must not restrict complete parsing, captures, or editing.

## 0. Core Conclusions

The large-text direction is not “the application splits a file into blocks and sends each block to Tree-sitter.” Instead:

1. **Native input supports bounded, on-demand, repeatable text access.** JNI connects Native TextSource to host storage without requiring a full-text copy.
2. **Token production, token staging, Subtree construction, and Query Match/Capture output use layered memory structures.** Do not make every object depend on one huge vector, one contiguous allocation, or eager full materialization.
3. **Chunk boundaries are data-access/resource-scheduling boundaries, not syntax boundaries.** Parser state, external-scanner state, parse-stack branches, and Tree structure must evolve continuously across read chunks.
4. **Text-storage trees, syntax Subtrees, and parser-stack branches are distinct structures.** They solve different problems and connect through stable absolute coordinates and revisions.
5. **Caches are multi-level and purpose-specific, with budgets.** Each cache needs a key, version, ownership, eviction strategy, and invalidation reason.
6. **Do not convert every large token set into JVM objects.** Prefer compact, segmented, batched Native representations; convert only data crossing JNI or entering host UI into stable public results.
7. **Incremental parsing should reuse Tree-sitter's existing old-tree edit and Subtree-reuse mechanisms.** Do not invent a third parser pipeline competing with Tree-sitter.
8. **Memory management must optimize real structures, lifetimes, and system pressure.** Do not use artificially low fixed budgets to restrict normal syntax trees, tokens, captures, or edit data. Reduce redundancy through persistent sharing, node pooling, batch allocation, cache eviction, and pressure feedback. Exceptional system-protection thresholds must be based on real-device measurements and must not replace performance work.

The goal is controlled memory growth through chunking, pooling, reuse, pagination, and windows while preserving full-document parsing and matching semantics. Cross-chunk tokens must not be lost, shifted, miscaptured, or silently truncated.

## 1. The Actual Problem Is the Native Token Engine

### 1.1 More Than Input Size

| Area | Typical pressure | Required Native capability |
|---|---|---|
| Input access | Oversized contiguous buffers and repeated copies | On-demand reads, stable windows, repeatable seeking |
| Lexer/Token | Huge token counts and frequent small allocations | Compact token representation, segmented output, batch allocation |
| Parser | State across chunks and multiple GLR branches | Continuous state, shared branches, controlled stack versions |
| Subtree | Huge node counts and frequent allocation/free | Inline optimization, pooling, reference counting, structural reuse |
| Query | Huge Match/Capture counts | Range queries, cursor pagination, budgets, explicit completeness |
| Cache | Repeated hot-region reads and lexing | Multi-level caches, window reuse, version-aware eviction |
| JNI | Large arrays/object conversion and boundary copies | Native batches, handles/Leases, limited boundary conversion |
| Regex | Oniguruma needs a stable contiguous pointer | Bounded contiguous WindowLease and independent window pool |
| Memory | Temporary objects, caches, trees, and results coexist | Global accounting, pressure feedback, reclamation, controlled degradation |

Avoid merely splitting a file into chunks, converting each chunk to a String in the application, and repeatedly invoking JNI. Also avoid copying the entire Piece Tree into one Native contiguous buffer and calling that large-text support. Both approaches relocate full-text copying without solving Native token, node, cache, and lifecycle costs.

### 1.2 Target Scale and Constraints

Design for:

- Normal target: 200–300 MiB text files.
- Extended target: 200–500 MiB and larger, tiered by device memory, grammar complexity, token density, and operation.
- Token density: dense short tokens, long tokens, extremely long lines, deep nesting, and many error-recovery nodes.
- Workloads: first parse, incremental edits, viewport queries, whole-document Query, and Regex window scanning.
- Environments: low-memory Android devices, foreground/background transitions, rapid scrolling, and continuous editing.

Memory/performance constraints:

- Do not use the old fixed 100 MiB normal budget or 512 MiB normal hard cap to restrict ordinary operation.
- First optimize text/syntax-node structures, buffer sharing, encoding indexes, object allocation, result batches, and lifetimes.
- Observe Native Heap, JVM Heap, PSS, mmap resident pages, temporary files, and peak working set.
- Under system pressure, reclaim rebuildable caches, stop unnecessary prefetch, and reduce background concurrency. Do not silently truncate complete parsing or captures.
- Exceptional protection and OOM handling must be based on real-device measurements and emit structured diagnostics.

## 2. Technical Foundation: Use Tree-sitter's Existing Mechanisms

Distinguish existing Tree-sitter capabilities from proposed project extensions. Do not document a proposed design as an upstream API.

### 2.1 TSInput: On-Demand Native Input

The Tree-sitter C API's `TSInput` supplies text through a read callback addressed by byte offset and point. The callback returns the readable slice and its length; `bytes_read == 0` signals end of document. The Parser borrows callback memory rather than taking ownership, so the returned buffer must remain valid while Parser reads it.

This is the input foundation JNI should use:

- Native Parser requests an absolute position through TextSource/Reader.
- TextSource locates it in Piece Tree, Rope, memory-mapped storage, or another host source.
- Only the current read window is supplied to Parser.
- A bounded buffer pool manages windows instead of allocating a new object for every callback.
- Callback buffer lifetime must cover Tree-sitter's read of that slice.
- Length, encoding boundaries, and EOF must be exact; “temporarily no data” must not be reported as EOF.

TSInput is a chunked input interface. It does not automatically parallelize a document into independent files, nor does it automatically paginate all internal tokens, Subtrees, or Query results. It solves input delivery; Native token/cache/result capacity still requires separate review.

### 2.2 Incremental Parsing and Old-Tree Reuse

Tree-sitter supports describing document edits with `TSInputEdit` and passing the edited old Tree into the next parse. Unaffected Subtrees that meet reuse conditions can be reused instead of rebuilding the entire Tree after every edit.

The project must preserve this mechanism:

- Apply edits to the old Tree using exact byte and point coordinates.
- Bind each Tree and parse result to a document revision.
- Retire stale Trees safely when an edit batch partially fails.
- Keep old Tree reservations/resources alive while active Leases still reference them.
- Never publish a partially edited Tree as a valid Query snapshot.

### 2.3 Separate Storage, Syntax, and Parser Structures

Piece Tree/Rope manages text storage and edits. Tree-sitter Subtrees represent grammar structure. Parser stacks represent active parse states/branches. Do not merge these concepts into one “chunk tree.” Use absolute offsets, point coordinates, and revisions to connect them.

## 3. Native Data Structures and Allocation

Prefer segmented pools and compact Native records over per-token heap objects or one unbounded vector. Keep hot fields compact and separate cold diagnostic/provenance data. Batch allocate and release by ownership generation where safe. Avoid pointer invalidation when buffers grow; use stable handles or segmented storage for objects referenced across stages.

Token, node, match, capture, and render-result lifetimes must be explicit. A result batch must not outlive the Tree/Snapshot it references unless it owns a stable copied representation or retained Lease.

## 4. Query and Capture Output

A Cursor is a streaming source, not permission to materialize every result at once. Provide bounded batch draining, stable continuation state, range filtering, and explicit completeness status. Limits must not silently turn a partial batch into a complete document result. Preserve Match, Pattern, Capture, Node, and rule identity through JNI.

## 5. Regex Windowing

Oniguruma requires contiguous input. Supply bounded contiguous windows through WindowLease, including enough boundary context for the configured rules. Map local offsets back to absolute document coordinates. Cross-window semantics must account for lookbehind/context and block state; if a rule cannot be evaluated safely at a boundary, emit a diagnostic rather than silently missing it.

## 6. Cache Design

Cache keys include grammar/language identity, rule revision, document revision, coordinate range, window boundaries, and relevant parser/query options. Separate input windows, parse artifacts, Query programs, Match batches, and resolved styles. Invalidate dependent structural context after edits; do not assume only directly overlapping captures are affected.

## 7. JNI Boundary

Keep calls coarse-grained. Prefer Native batches and stable handles/Leases over per-token JNI calls and huge Java arrays. Define ownership, thread affinity, cancellation, error mapping, and coordinate encoding for every boundary object. Do not expose raw Native pointers as application-owned resources.

## 8. Memory and Pressure Management

Measure actual allocations and lifetimes before introducing limits. Prefer structural sharing, pooling, reuse, bounded temporary windows, cache eviction, and asynchronous cleanup. Pressure handling may reclaim rebuildable caches and stop speculative work, but it must preserve correctness and report any operation that cannot complete. A memory ledger is observability/admission support, not a substitute for optimizing the data structures.

## 9. Validation Matrix

Validate first parse, incremental edits, large Query result sets, dense captures, long lines, UTF-8/UTF-16 boundaries, Emoji/supplementary characters, CRLF, Regex across windows, stale-result rejection, Tree/Lease lifetime, scrolling, backgrounding, and low-memory pressure. Record input size, token/node/match counts, peak Native/JVM/PSS, latency by stage, and completeness status.

A successful chunked read alone does not prove large-document support. The full parse, query, capture, rendering, and lifecycle path must be measured and verified.
