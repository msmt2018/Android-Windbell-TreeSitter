/*
 * This file is part of android-tree-sitter.
 * Licensed under the GNU Lesser General Public License, version 2.1 or later.
 */
package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;

/**
 * Bounded native Tree-sitter highlight-capture extraction.
 *
 * <p>This API executes the compiled ATS/SCM query in native code and returns
 * the original query captures. Capture names, pattern indices, node ranges,
 * and capture indices are preserved by {@link TSQueryMatch}. TSH resolves
 * capture names to style identifiers; this class does not copy source text
 * or introduce another highlighting runtime.
 */
public final class TSHighlight {
  private TSHighlight() {}

  /**
   * Extract highlight query matches intersecting an absolute document byte range.
   * Limits are enforced natively and an over-budget query fails atomically.
   */
  public static TSQueryMatch[] extract(
      TSQuery query,
      TSNode root,
      long startByte,
      long endByte,
      int maxResults,
      int maxPendingMatches) {
    if (query == null || !query.canAccess()) {
      throw new IllegalArgumentException("Cannot highlight with an invalid query");
    }
    if (root == null || !root.canAccess() || root.getTree() == null
        || !root.getTree().canAccess() || root.hasChanges()) {
      throw new IllegalArgumentException("Cannot highlight an invalid or changed root");
    }
    if (startByte < 0 || endByte < startByte || endByte > 0xFFFF_FFFFL) {
      throw new IllegalArgumentException("Invalid absolute byte range");
    }
    if (maxResults < 1 || maxPendingMatches < 1) {
      throw new IllegalArgumentException("Highlight query limits must be positive");
    }
    return Native.extract(query.getNativeObject(), root, startByte, endByte,
        maxResults, maxPendingMatches);
  }

  @GenerateNativeHeaders(fileName = "highlight")
  private static final class Native {
    static native TSQueryMatch[] extract(
        long query,
        TSNode root,
        long startByte,
        long endByte,
        int maxResults,
        int maxPendingMatches);
  }
}
