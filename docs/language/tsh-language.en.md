# TSH Language and .tsh Configuration Toolchain

For exact file syntax, use:

- [TSH DSL v2 Specification](../TSH_DSL_V2_SPEC.en.md)
- [TSH DSL v2 Syntax Reference](../TSH_DSL_V2_SYNTAX.en.md)
- [SCM-to-TSH Migration Guide](../TSH_SCM_TO_TSH_MIGRATION.en.md)

## 1. Toolchain Responsibilities

The target toolchain may include Source Manager, Lexer, Parser, immutable AST, Schema/type validation, semantic validation, Compiler, diagnostics, formatter, and Language Service.

The current Native WindbellTshCompiler compiles .tsh text into a runtime TshGrammar. Do not describe planned IDE language-service features as completed.

## 2. Current Native Configuration Entry

Current root entries include language, compatibility fields name/ignoreCase, matching, editor, styles, schemes, defaultScheme, isDark, defines, repository, treeSitter.queries, ast, and contains.

There are exactly two matching pipelines: ATS and Oniguruma Regex. Tree-sitter Query is the native Query execution mechanism on the ATS/Tree-sitter side, not a third pipeline. The Native compiler does not accept Monarch or the not-yet-implemented unified `rules { ats/regex }` facade.

## 3. Compilation Stages

1. **Lexer:** reads identifiers, strings, raw backtick strings, Regex, colors, comments, and structural punctuation.
2. **Parser:** recognizes root blocks, rule objects, Query source, and nested Regex rules.
3. **Grammar binding:** binds ATS nodes/fields and Queries to the supplied TSLanguage.
4. **Regex/Query compilation:** compiles Oniguruma expressions and native Tree-sitter Queries.
5. **Semantic binding:** resolves style names, capture groups, Query captures, repository/defines references, and scheme inheritance.
6. **Runtime materialization:** creates executable grammar, selector index, Query programs, and Regex rules.
7. **Capture arbitration/rendering:** normalizes both pipelines to spans and passes them to theme and Android rendering.

## 4. DSL Model/Schema API vs. File Syntax

TshDslModel.kt, TshSchemaRegistry.kt, and TshDslRuntimeDispatcher.kt define parser-neutral declaration models, Schema validation, and property-consumer dispatch. They form the DSL/API foundation type system; they do not mean every TshDslDeclarationKind, unit, color space, or generic value type is mapped to Native .tsh file syntax.

Keep these states distinct:

- **Accepted by file Parser:** fields and structures actually recognized by Native WindbellTshCompiler.
- **Representable by Schema model:** generic declarations/values expressible by the Kotlin DSL model.
- **Runtime consumer registered:** a property has an actual consumer capable of applying it.
- **Consumed and rendered by host:** the Android/editor adapter uses the result in final output.

These states cannot be inferred from one another.

## 5. Diagnostics and Verification

Native compiler compileDetailed returns the compilation result and offset/line/column diagnostics. TshDslRuntimeDispatcher preserves diagnostics for unapplied properties, missing consumers, unmet capabilities, and consumer exceptions. Passing Schema validation does not prove that a property was applied.

The full language service still requires continued work on AST/source ranges, field Schema, semantic reference resolution, formatting, completion, hover, definition/references, and migration tests. Do not claim unimplemented capabilities as complete.
