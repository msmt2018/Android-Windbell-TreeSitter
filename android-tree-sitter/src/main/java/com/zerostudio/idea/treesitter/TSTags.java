/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;

/**
 * Bounded native tag extraction built on Tree-sitter query matches.
 *
 * <p>This API deliberately returns ordinary query matches. Language-specific
 * tag semantics (definition/reference classification and metadata) remain
 * the responsibility of the query and its capture names; this layer owns
 * bounded execution and safe JNI transfer only.
 */
public final class TSTags {

  private TSTags() {
  }

  /**
   * Extract query matches from an absolute byte range within {@code root}.
   *
   * <p>No partial result is returned when either result or pending-match
   * limits are exceeded. The query and root must remain valid for this call.
   *
   * @param query compiled query
   * @param root syntax node used as the query root
   * @param startByte absolute inclusive start byte, in document coordinates
   * @param endByte absolute exclusive end byte, in document coordinates
   * @param maxResults maximum number of completed matches to materialize
   * @param maxPendingMatches Tree-sitter in-progress match limit
   * @return query matches, including their original pattern and capture data
   */
  public static TSQueryMatch[] extract(
      TSQuery query,
      TSNode root,
      int startByte,
      int endByte,
      int maxResults,
      int maxPendingMatches) {
    if (query == null || !query.canAccess()) {
      throw new IllegalArgumentException("Cannot extract tags with an invalid query");
    }
    if (root == null || !root.canAccess() || root.getTree() == null
        || !root.getTree().canAccess() || root.hasChanges()) {
      throw new IllegalArgumentException("Cannot extract tags from an invalid or changed root");
    }
    if (startByte < 0 || endByte < startByte) {
      throw new IllegalArgumentException("Invalid absolute byte range");
    }
    if (maxResults < 1 || maxPendingMatches < 1) {
      throw new IllegalArgumentException("Tag query limits must be positive");
    }
    return Native.extract(
        query.getNativeObject(), root, startByte, endByte,
        maxResults, maxPendingMatches);
  }

  @GenerateNativeHeaders(fileName = "tags")
  private static final class Native {
    static native TSQueryMatch[] extract(
        long query,
        TSNode root,
        int startByte,
        int endByte,
        int maxResults,
        int maxPendingMatches);
  }
}
