# TSH Android Rendering API

Package: `com.zerostudio.idea.treesitter.tsh.android`

## `TshAndroidSpannableBinding`

Binds a TSH document/render result to Android `Spannable` text and coordinates span application/cleanup with the host target. The binding is the Android integration boundary; it is not the grammar parser or query executor.

The binding source defines the target/document association and operations for applying or clearing rendered spans. Callers must use the document identity and revision information expected by the binding and release the binding when the host document is detached or disposed.

## `TshAndroidSpannableRenderer`

Applies resolved TSH style information to Android text spans.

The implementation maps supported foreground/background and text-decoration/font flags to Android span objects, maintains the association between rendered spans and document ranges, and removes/replaces prior spans as render updates arrive. The exact span classes and precedence are implementation-defined by this source.

**Rendering boundary:** this adapter produces Android text-span mutations. It is not evidence that a non-Android renderer supports the same attributes, nor that every declared font family/size property is painted by the editor's final Canvas path. The host editor remains responsible for its actual drawing behavior.

## Usage and lifecycle

1. Obtain capture/style results from the TSH runtime.
2. Pass the current document and revision-consistent result to the Android binding/renderer.
3. Apply updates only while the host text target remains valid.
4. Clear/detach the binding when the document is replaced or disposed.

The renderer and binding must not outlive the host text object they mutate.

## Sources

[TshAndroidSpannableBinding.kt](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh/android/TshAndroidSpannableBinding.kt) · [TshAndroidSpannableRenderer.kt](../../android-tree-sitter/src/main/java/com/zerostudio/idea/treesitter/tsh/android/TshAndroidSpannableRenderer.kt)
