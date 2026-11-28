# TSH Architecture Overview

## 1. Product Positioning

The authoritative top-level public API contract is [TSH Public API Design Principles and Host Integration Contract](../api/public-api-design-principles-and-integration-contract.md). This architecture document describes the system view only; it must not override host ownership, capability negotiation, coordinate, or rendering contracts.

TSH is more than a syntax-highlighting function collection. It is a platform for language configuration, syntax analysis, text-object adaptation, style resolution, rendering integration, and diagnostics. The engine absorbs editor-architecture complexity and exposes a stable contract to hosts.

“Zero-friction integration” does not promise automatic compatibility with every unknown private widget. It means each capability has a standard API, Provider extension point, explicit missing-capability report, and actionable adaptation path.

## 2. Layers

1. **Application integration:** Android View, Editor, Document, TextBuffer, Layout, Renderer.
2. **Object and capability:** Object Registry, Descriptor, Capability, Resolver, Adapter, Binding.
3. **Language and configuration:** TSH Lexer/Parser/AST/Schema/Validator/Formatter/Language Tooling.
4. **Syntax execution:** Tree-sitter Parser/Tree/Query, ATS, SCM-compatible simplified syntax, independent Regex/Oniguruma.
5. **Style and rendering:** Highlight, Scope, Attribute, Theme, Style Resolver, Render Instruction, Provider.
6. **Infrastructure:** JNI, Native lifecycle, threads, caches, events, diagnostics, tests.

## 3. End-to-End Data Flow

`.tsh` source -> lexical/syntax analysis -> AST -> Schema/type/semantic validation -> compiled configuration -> Tree-sitter/ATS and Regex execution -> normalized match results -> document-coordinate ranges -> Highlight Scope/Style Resolver -> Render Instructions -> target Renderer Provider -> partial editor refresh -> runtime diagnostics.

Every failure must retain source location, object identity, stage, error code, and optional remediation guidance.

## 4. Core Architecture Principles

- Separate public APIs from implementation; applications depend only on public contracts.
- Integrate external objects through instance registration, descriptors, or Providers; do not require inheritance from TSH base classes.
- Declare capabilities explicitly; do not assume all editors expose identical Layout, Selection, or drawing hooks.
- Define object identity, document version, text coordinate system, and resource ownership.
- Use explicit coordinate systems for text ranges; never mix Tree-sitter UTF-8 byte offsets with Android UTF-16 offsets.
- Version incremental computations and their invalidation ranges.
- Separate configuration, syntax, style, and rendering state. A theme switch should not needlessly reparse syntax.
- Providers must support registration, discovery, API/Capability version negotiation, deterministic selection, diagnostics, replacement, and unregistration. Invoke third-party callbacks outside locks.
- Define ownership, threading, exception, and encoding rules at JNI boundaries.
- Distinguish design, implementation, compilation, and runtime verification states.

## 5. Module Boundaries

- `api`: stable public contracts and value objects.
- `core`: engine implementation, service registration, sessions, and lifecycle.
- `config`: TSH loading, AST, Schema, and compilation.
- `object`: external-object registration, descriptors, and capability discovery.
- `text`: Document, Buffer, Position, Range, and coordinate conversion.
- `editor`: editor adapters and editor sessions.
- `syntax`: Tree-sitter/ATS query objects.
- `regex`: independent Regex/Oniguruma pipeline.
- `highlight`: match-result-to-style resolution.
- `render`: render instructions, scheduling, and Providers.
- `attribute`: attributes, themes, and style mappings.
- `binding`: object, attribute, event, and lifecycle bindings.
- `diagnostics`: diagnostics, tracing, performance, and compatibility reports.
- `integration`: Android and third-party editor integration.
- `extension`: extensions, Provider discovery, and version negotiation.

The existing `WindbellTshEngine` and Native core remain as compatibility implementations during migration. Do not remove them or change their ABI without testing.
