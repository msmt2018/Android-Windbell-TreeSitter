# TSH / Tree-sitter 0.28.0 migration (no largefile implementation)

## Baseline

This migration is based on `back_main_0e9051c`, preserving the backup branch as the ancestry and keeping the work isolated from `main`.

## Engine version

The `tree-sitter-lib` git submodule is pinned to upstream Tree-sitter commit `62816e1c0d0a4ca9553174f3cbe5b9631047f7f4`. Its upstream `Cargo.toml` declares version `0.28.0`. This is the full upstream source revision, not a partial JNI-only compatibility patch.

## Target architecture

- Migrate the current Windbell TSH compiler, runtime, style resolution, native query execution, independent Oniguruma regex execution, JNI bridge, Android renderer and application Activity integration.
- Keep Tree-sitter/ATS query execution and Oniguruma regex execution as the two independent matching pipelines.
- Do not migrate `android-tree-sitter/src/main/cpp/largefile/`, the Kotlin `largefile` package, PieceTree/window/partition execution, or segmented/windowed Token processing.
- Replace those dependencies with a straightforward contiguous-document input contract. Tree-sitter must parse one complete document into one coherent syntax tree; do not parse independent viewports and stitch syntax trees together.
- Preserve the rendering path through Android spans and verify that configured `.tsh` styles reach the actual editor drawing layer.

## Migration gate

The current TSH native implementation directly depends on `largefile::TextSource`, `TextChunkView`, partition versions and windowed regex/query helpers. Copying those files without refactoring would leave forbidden dependencies or produce a non-building module. Therefore the JNI input, TSH core, and Android application adapter must be migrated as one coherent change, followed by native and Android build/test validation.

## Status

- [x] Create an isolated migration branch from the backup branch.
- [x] Pin the upstream Tree-sitter submodule to a verified 0.28.0 source commit.
- [ ] Migrate TSH native compiler/core and JNI without largefile/window/partition dependencies.
- [ ] Migrate Java/Kotlin Tree-sitter bindings and TSH APIs without largefile/window APIs.
- [ ] Migrate Activity, renderer, assets and tests.
- [ ] Build and run native/JVM/Android rendering tests.
