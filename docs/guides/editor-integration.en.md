# Editor Integration Guide

## 1. Goal

The host may be an Android View, custom View, third-party editor widget, or standalone text component. TSH does not require inheritance from a particular base class. Prefer existing standard Providers and register extensions only for capabilities that need them.

## 2. Three Levels of Adaptation

1. **Automatic adaptation:** recognize explicitly supported standard Android objects and known Providers.
2. **Generic adaptation:** inspect public APIs through explicit interfaces, public capability descriptions, or safe reflection.
3. **Custom adaptation:** implement a Provider only for an uncovered capability; do not rewrite the entire editor-adaptation layer.

Automatic recognition does not mean guessing the editor semantics of arbitrary Views. Unknown widgets must produce capability diagnostics.

## 3. Integration Flow

1. Create a TshEngine/context.
2. Register or discover Editor, Document, TextBuffer, Layout, and Renderer instances.
3. Inspect the CapabilitySet.
4. Register Providers for missing capabilities.
5. Load and validate the .tsh configuration.
6. Create an EditorSession and bind the document.
7. Observe text changes, selection changes, scrolling, and lifecycle events.
8. Start incremental parsing/highlighting.
9. Submit RenderInstructions to the target Renderer Provider.
10. On teardown, cancel tasks, detach objects, and release resources.

## 4. Provider Principles

A Provider must declare:

- Supported capability IDs and versions.
- Target object types/matching strategy.
- Threading requirements.
- Incremental, partial-refresh, and asynchronous support.
- Lifecycle and resource ownership.
- Diagnostic codes and fallback behavior on failure.

A Provider must not assume ownership of host editor objects. Unless ownership is explicitly transferred by contract, retain only weak or invalidatable references.

## 5. Rendering Integration

Possible target mechanisms include Canvas/Paint, Span, Decoration, line-level drawing hooks, and custom Renderers. Spannable is not the only supported strategy. Do not call unknown private drawing methods on host widgets.

## 6. Integration Acceptance Criteria

- Standard objects can be integrated through existing Providers.
- A custom object needs to implement only missing capabilities.
- Unsupported capabilities are reported accurately.
- Editing, scrolling, folding, theme changes, and teardown do not commit stale results.
- The real editor displays the configured .tsh styles.
