# TSH Documentation Center

[简体中文](README.md) | [English](README_EN.md)

This directory contains architecture, implementation, language-configuration, engine-behavior, and integration documentation for Windbell Tree-sitter Highlighting (TSH).

## Documentation Index

| Document | Description |
|---|---|
| [Architecture Overview](architecture/overview.en.md) | Module boundaries, layers, data flow, and architecture principles |
| [Java/Kotlin API Reference](api/README.md) | API reference based on the current Java/Kotlin source |
| [Editor Integration Guide](guides/editor-integration.en.md) | Integrating TSH with a host editor |
| [Highlighting and Rendering Pipeline](guides/highlight-render-pipeline.en.md) | Configuration, matching, styles, and Android rendering |
| [TSH DSL v2 Specification](TSH_DSL_V2_SPEC.en.md) | File structure, architecture boundaries, two pipelines, and implementation constraints |
| [TSH DSL v2 Syntax Reference](TSH_DSL_V2_SYNTAX.en.md) | Root fields, ATS, Query, Regex, predicates, styles, and themes |
| [SCM-to-TSH Migration Guide](TSH_SCM_TO_TSH_MIGRATION.en.md) | Migrating highlights.scm Query, captures, and predicates |
| [TSH Language and Configuration Toolchain](language/tsh-language.en.md) | Responsibilities and boundaries of Lexer/Parser/Schema/Validator/Compiler |
| [ATS and Regex](engine/ats-regex.en.md) | Tree-sitter/ATS and the independent Regex pipeline |
| [Diagnostics Specification](diagnostics/diagnostics.en.md) | Diagnostic model and error localization |
| [Development Roadmap](roadmap.en.md) | Phased work and verification status |
| [Current Implementation Audit Baseline](audit/current-baseline.en.md) | Static source audit and risks requiring verification |
| [Highlight Accuracy and Actual Rendering](TSH_HIGHLIGHT_ACCURACY_AND_RENDERING_INTEGRITY.en.md) | Match completeness, coordinates, colors, and final drawing acceptance |
| [Large-Text Native Token Architecture](TSH_LARGE_TEXT_NATIVE_TOKEN_ARCHITECTURE.en.md) | Native chunked input, tokens, Query, caches, and memory strategy |
| [TSH Language Specification and Core Architecture](TSH_LANGUAGE_ARCHITECTURE.en.md) | Single TSH configuration entry, no SCM intermediates, ATS/native Query paths |
| [Native Architecture Diagram](Architecture.en.md) | Kotlin, JNI, and C/C++ core boundaries |

## API Documentation Policy

The API reference documents only types, signatures, lifetimes, and observable behavior present in the current Java/Kotlin source. Planned APIs, unimplemented syntax capabilities, host-editor features, and future architecture must not be described as current public API contracts.

The corresponding source is authoritative. If documentation and source disagree, source takes precedence and the documentation must be corrected.

## Status Vocabulary

- **Design:** specification or planning only; not a callable API.
- **In progress:** source exists, but implementation or integration is ongoing.
- **Implemented:** corresponding code exists; this does not imply build or device verification.
- **Build verified:** the corresponding module builds successfully.
- **Runtime verified:** automated tests or real-device verification passed.

Documentation, class names, and design descriptions are not substitutes for build, test, or runtime evidence.
