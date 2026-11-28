# Highlighting and Actual Rendering Pipeline

## 1. Required End-to-End Closure

`.tsh` -> Config AST/Validated Model -> ATS/Tree-sitter and Regex -> Match Result -> Text Range Mapping -> Highlight Scope -> Attribute/Theme Resolution -> Render Instruction -> Render Provider -> Editor Invalidation -> Actual Drawing.

Generating a HighlightSpan or RenderInstruction does not mean rendering is complete. End-to-end success requires the target editor to display the result and update it after configuration changes.

## 2. Unified Intermediate Results

A match result must retain its source (ATS/Regex), rule ID, capture/scope, document version, coordinate system, and range. A resolved style must include foreground, background, font, decoration, priority, and provenance.

RenderInstruction should be an immutable value object containing target object ID, document ID/version, range, drawing attributes, layer, and invalidation strategy.

## 3. Provider Contract

RenderProvider declares supported drawing capabilities, such as text foreground/background, underline, line decoration, gutter, inlay, and overlay. It maps unified instructions to the editor's native Canvas, Span, Decoration, or Renderer. Provider capabilities differ and must be negotiated, not guessed.

## 4. Coordinates and Visible Viewport

Convert document ranges to target-editor coordinates before rendering. Work outside the visible viewport may be deferred. Before drawing, validate document version, range boundaries, layout version, and target-object lifecycle. Partial refresh must invalidate the union of the old-style and new-style affected ranges.

## 5. Theme Updates

A theme/color change should re-resolve styles and refresh affected ranges without needlessly rebuilding the syntax tree. Rule changes invalidate syntax-match caches according to their dependencies.

## 6. Acceptance Scenarios

- ATS keywords and Regex strings/comments in the same document.
- UTF-16/UTF-8 range mapping, including Emoji, supplementary-plane characters, and combining characters.
- Refresh only the visible viewport while scrolling.
- Incremental updates after edits; stale results must not overwrite newer document versions.
- Light/Dark and custom theme switching.
- Span, Canvas, and editor-native Renderer Providers.
- Cancel rendering on editor destruction/document switching.
- Update actual rendering after .tsh color changes without requiring the host application to rewrite its adapter.
