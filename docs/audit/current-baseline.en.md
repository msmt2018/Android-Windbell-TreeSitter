# Current Implementation Audit Baseline (Round 1)

> Scope: `WindbellTshEngine.kt`, `ts_windbell_jni.cpp`, `WindbellTshCore.h`, `WindbellTshCompiler.h`, existing TSH Kotlin tests, and module Gradle configuration were inspected. This is a static source audit, not build or device-runtime verification.

## 1. Existing Capabilities

| Capability | Current entry/implementation | Static audit finding |
|---|---|---|
| .tsh loading | `WindbellTshEngine.loadTshSource` -> JNI -> `TshCompiler.compile` | Compiler entry exists; returns Boolean and lacks structured diagnostics |
| Theme palette | `nativeGetPalette` | Exports styleId/light/dark through a flat IntArray |
| Hybrid highlighting | `nativeHighlight` -> `WindbellEngine.highlightHybrid` | Native core exposes a hybrid ATS/AST and Regex highlighting entry |
| Folding | `nativeComputeFolds` | Folding-range output exists |
| Outline | `nativeGetOutline` | Numbers and names are transferred through String arrays |
| Hover | `nativeGetHoverInfo` | Ranges, node types, and details are transferred through String arrays |
| Android Span | `applyHighlightsToSpannable` | A direct Spannable-writing path exists |
| Tests | `WindbellTshEngineTest`, `JavaTshFullTest` | Test sources cover configuration loading, selected highlighting, Unicode boundaries, folding/outline, and concurrency |

## 2. Key Gaps

1. **No unified public Engine facade:** the current entry is the singleton `WindbellTshEngine.instance`; standard Engine state, Session, service registration, and close protocols are absent.
2. **No generic object registration/discovery:** the engine cannot uniformly identify concrete host Editor, Document, Buffer, Layout, and Renderer instances.
3. **No Capability/Provider contract:** editor drawing and text-access capabilities cannot be declared, negotiated, or diagnosed.
4. **Application-layer rendering coupling:** `WindbellTshEngine.kt` directly depends on Android `SpannableStringBuilder`, `CharacterStyle`, `ForegroundColorSpan`, and `StyleSpan`; independent Render Instructions/Providers are absent.
5. **Over-broad Span cleanup:** the current implementation removes all `CharacterStyle` instances from the target Spannable, potentially deleting host or third-party styles. It must remove only TSH-owned Spans or use identifiable TSH Span markers.
6. **Coordinate model is not public:** returned ranges are internally shifted to UTF-16 code units, but there is no public Position/Range/CoordinateSystem or versioned conversion contract.
7. **Incomplete configuration diagnostics:** Native compilation returns only Boolean; lexer/parser/schema/semantic diagnostic lists and source ranges are absent.
8. **Inconsistent JNI transport:** some results use IntArray and others String[]; no unified result structure, error-code, or version protocol exists.
9. **Incomplete lifecycle:** the singleton and Native Grammar Registry lack a unified attach/detach/close contract for host objects.
10. **No end-to-end integration diagnostics:** the pipeline cannot be traced from configuration loading to completion of a real Renderer callback.

## 3. First Public API Foundation Slice (Build Verification Pending)

New package: `com.zerostudio.idea.treesitter.tsh.api`

- `TshEngine`: platform-independent lifecycle facade.
- `TshResult` / `TshDiagnostic`: structured result and diagnostic foundations.
- `TshCoordinateSystem` / `TshSourcePosition` / `TshSourceRange`: explicit coordinate models.
- `TshObjectRegistry` / `TshObjectDescriptor`: registration by external instance identity.
- `TshCapability` / `TshCapabilitySet`: object capability descriptions.
- `TshProvider` / `TshProviderRegistry`: Provider registration and object matching.
- `TshApiFoundationTest`: corresponding unit-test source.

This slice does not replace the existing Native execution chain and does not claim that automatic editor adaptation or real rendering is complete.

## 4. Existing Risks to Fix or Verify First

- Verify whether Native Span ranges are UTF-16 code units or byte offsets in two-byte units. Use explicit naming and conversion; do not rely on implicit shifts.
- Verify that Tree-sitter node ranges and Native UTF-16 text-scan ranges align exactly around non-ASCII characters.
- Fix Span cleanup so external styles are never removed.
- Migrate Outline/Hover String[] numeric protocols to typed structures while retaining old JNI compatibility temporarily.
- Replace Boolean configuration compilation with a structured CompilationResult, retaining the old Boolean API as a compatibility wrapper.
- Add real editor Renderer Provider tests; SpannableStringBuilder tests are not substitutes for actual widget drawing verification.

## 5. Next Audit Round

- Fully inspect `WindbellTshCompiler.cpp` lexical/syntax rules, error recovery, and source ranges.
- Inspect `WindbellTshCore.cpp` ATS/Regex execution, priority, conflicts, incremental behavior, and locks.
- Inspect `CMakeLists.txt` and the JNI registration/annotation-generation chain.
- Inspect `android-tree-sitter` API compatibility and run an actual Gradle build.
