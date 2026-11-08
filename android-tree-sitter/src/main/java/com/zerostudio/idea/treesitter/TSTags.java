/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  android-tree-sitter library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;
import java.util.Objects;

/**
 * Native query-backed tag extraction.
 *
 * <p>Tag semantics are defined by the supplied Tree-sitter query. A common
 * convention is to use captures such as {@code @definition}, {@code @reference},
 * {@code @name}, and {@code @scope}; this class deliberately does not impose
 * language-specific capture names.
 *
 * <p>Returned matches own Java-side node values and remain usable independently
 * of the temporary native query cursor. Byte ranges use the source document's
 * absolute Tree-sitter byte coordinates.
 */
public final class TSTags {

  private static final int DEFAULT_MAX_RESULTS = 100_000;
  private static final int DEFAULT_MAX_PENDING_MATCHES = 4_096;

  private TSTags() {
  }

  /**
   * Extract query matches from a syntax subtree.
   *
   * @param query compiled tag query
   * @param root root node to search
   * @param startByte inclusive absolute start byte
   * @param endByte exclusive absolute end byte
   * @return all query matches in traversal order
   * @throws IllegalArgumentException for invalid arguments or ranges
   * @throws IllegalStateException if either safety limit is exceeded
   */
  public static TSQueryMatch[] extract(
      TSQuery query, TSNode root, int startByte, int endByte) {
    return extract(query, root, startByte, endByte,
        DEFAULT_MAX_RESULTS, DEFAULT_MAX_PENDING_MATCHES);
  }

  /**
   * Extract query matches with explicit memory/work safety limits.
   *
   * @param maxResults maximum number of returned matches (1..1,000,000)
   * @param maxPendingMatches Tree-sitter in-progress match limit (1..1,000,000)
   */
  public static TSQueryMatch[] extract(
      TSQuery query, TSNode root, int startByte, int endByte,
      int maxResults, int maxPendingMatches) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(root, "root");
    if (startByte < 0 || endByte < startByte) {
      throw new IllegalArgumentException("Invalid byte range");
    }
    if (maxResults < 1 || maxResults > 1_000_000) {
      throw new IllegalArgumentException("maxResults must be between 1 and 1000000");
    }
    if (maxPendingMatches < 1 || maxPendingMatches > 1_000_000) {
      throw new IllegalArgumentException(
          "maxPendingMatches must be between 1 and 1000000");
    }
    query.checkAccess();
    root.checkAccess();
    if (!query.canAccess() || !root.canAccess() ||
        !root.getTree().canAccess() || root.hasChanges()) {
      throw new IllegalArgumentException("Cannot extract tags from invalid or changed nodes");
    }
    return Native.extract(query.getNativeObject(), root, startByte, endByte,
        maxResults, maxPendingMatches);
  }

  @GenerateNativeHeaders(fileName = "tags")
  private static final class Native {
    static native TSQueryMatch[] extract(
        long query, TSNode root, int startByte, int endByte,
        int maxResults, int maxPendingMatches);
  }
}
