# TSH Native Architecture

```text
UI / Editor View (Kotlin)
  - renders the visible viewport
  - submits TextEdit events asynchronously
  - consumes compact flat spans and renders them
          |
          v
TreeSitterEngine (Kotlin mediator)
  - lightweight scheduler (CoroutineScope / Dispatchers.Default)
  - does not own or retain the full large document
          |
          v
JNI translation layer
  - coarse-grained calls
  - passes Native handles, pointers, and events
          |
          v
C/C++ high-performance core
  - Native Text Buffer (Rope / Piece Table)
  - Tree-sitter Core (Parser / Tree / Query)
  - Highlighting renderer (viewport clipping and compact result output)
```

The intended ownership boundary is that the actual large-text source resides in the Native text layer or is exposed through a stable TextSource provider. Kotlin coordinates work and renders only the visible viewport. JNI should remain a coarse-grained gateway rather than repeatedly copying large text or materializing large collections.

The diagram is an architectural target, not proof that every path currently uses zero-copy pointers or that every component is already implemented. Verify the concrete implementation and ownership contract before relying on those properties.
