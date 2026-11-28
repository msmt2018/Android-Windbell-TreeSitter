# Tree-sitter, ATS, and Regex: Two Independent Pipelines

## 1. Technical Direction

The TSH execution layer uses the following fixed architecture:

- **Tree-sitter:** syntax-tree parsing and incremental tree updates.
- **ATS:** Tree-sitter query extensions, selector expressiveness, and execution orchestration.
- **SCM-compatible simplified syntax:** an input-compatibility/simplified expression within the ATS system; it does not establish a separate execution pipeline.
- **Regex:** an independent regular-expression matching pipeline.
- **Oniguruma:** the Regex pipeline's execution backend.

Do not introduce Monarch or TextMate as a third execution pipeline. Their configuration organization, scopes, themes, and diagnostic concepts may be studied, but must not alter this execution architecture.

## 2. Unified Result Model

ATS and Regex retain their own native matching semantics and emit a unified MatchResult containing source, ruleId, capture, scope, documentVersion, coordinateSystem, range, priority, and diagnostic information. The Highlight Resolver handles arbitration; the two pipelines must not be disguised as one matcher.

## 3. Oniguruma and Non-Contiguous Text

Oniguruma requires a contiguous input view, while Piece Tree/Rope/sliding-window storage may be non-contiguous. Never pass a pointer to non-contiguous fragments directly to Oniguruma.

Define a TextSnapshot/Window Provider that supplies contiguous windows, boundary context, absolute-offset mapping, and window lifetime. Cross-window matching must account for maximum lookbehind/context, block-boundary state, and rule semantics. If correctness cannot be determined safely, report an explicit limitation or diagnostic; never silently miss matches.

## 4. Incrementality and Caching

Cache keys must include rule version, document version/change generation, language, and window boundaries. Edit events must invalidate affected ranges and dependent context. Every asynchronous result must validate its version before it is committed.
