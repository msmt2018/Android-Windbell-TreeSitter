# Diagnostics and Observability Specification

## 1. Unified Diagnostic Structure

A diagnostic must contain at least:

- `code`: stable machine-readable error code.
- `severity`: ERROR/WARNING/INFO/HINT.
- `stage`: CONFIG/LEX/PARSE/SCHEMA/SEMANTIC/COMPILE/SYNTAX/ATS/REGEX/HIGHLIGHT/COORDINATE/BINDING/RENDER/JNI/LIFECYCLE.
- `message`: user-readable explanation.
- `source`: file URI or object origin.
- `range`: optional source range; its coordinate system must be declared.
- `objectId/documentId`: when applicable.
- `cause/relatedInformation`: root cause and related locations.
- `actions`: optional remediation suggestions.
- `traceId/documentVersion`: correlation identifiers for one execution trace.

## 2. Layered Reports

- ConfigReport: file loading, parsing, Schema, and compilation results.
- ObjectInspectionReport: discovered objects and capabilities.
- IntegrationReport: Providers, missing capabilities, and compatibility.
- PipelineTrace: ATS/Regex execution, match counts, and elapsed time.
- HighlightReport: rule hits, scopes, and style provenance.
- RenderReport: instruction counts, Provider, submission, and refresh results.
- PerformanceReport: stage timings, cache hits, and task cancellation.
- NativeReport: JNI errors, resources, and Native call status.

## 3. Prohibited Behavior

- Do not collapse every failure into Boolean false.
- Do not swallow exceptions or silently return empty lists to simulate success.
- Do not describe “instructions generated” as “pixels rendered.”
- Do not mark a Provider capability as supported when it was not executed.
- Preserve accessible base logs even when diagnostics themselves fail.

## 4. Example Diagnostic Chain

CONFIG_LOADED -> ATS_QUERY_EXECUTED -> REGEX_MATCHED -> RANGES_MAPPED -> STYLES_RESOLVED -> INSTRUCTIONS_CREATED -> PROVIDER_SELECTED -> SUBMITTED -> INVALIDATED -> DRAW_CALLBACK_CONFIRMED.

The final step requires a verifiable completion signal from the Provider. Calling invalidate alone does not prove that drawing occurred.
