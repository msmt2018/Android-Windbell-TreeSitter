/*
 * This file is part of android-tree-sitter.
 * Licensed under the GNU Lesser General Public License, version 2.1 or later.
 */
package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
  public static final int MAX_RESULTS = 250_000;
  public static final int MAX_PENDING_MATCHES = 1_000_000;
  public static final int MAX_SPANS = 500_000;

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
    if (maxResults < 1 || maxResults > MAX_RESULTS
        || maxPendingMatches < 1 || maxPendingMatches > MAX_PENDING_MATCHES) {
      throw new IllegalArgumentException("Highlight query limits exceed the supported native budget");
    }
    return Native.extract(query.getNativeObject(), root, startByte, endByte,
        maxResults, maxPendingMatches);
  }


  /**
   * Convert query captures into render-ready spans using a capture-indexed
   * style table. A negative style ID suppresses that capture. Byte offsets
   * are widened as unsigned uint32 values and are never interpreted as
   * signed Java ints. Overlapping captures are preserved.
   *
   * <p>This conversion does not copy source text. The returned list is sorted
   * by start byte, then end byte, pattern index, and capture index.
   */
  public static List<TSHighlightSpan> toSpans(
      TSQuery query, TSQueryMatch[] matches, int[] styleIds, int maxSpans) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(matches, "matches");
    Objects.requireNonNull(styleIds, "styleIds");
    if (!query.canAccess()) {
      throw new IllegalArgumentException("Cannot map highlights with an invalid query");
    }
    if (maxSpans < 1 || maxSpans > MAX_SPANS) {
      throw new IllegalArgumentException("maxSpans must be between 1 and MAX_SPANS");
    }
    final int captureCount = query.getCaptureCount();
    if (styleIds.length != captureCount) {
      throw new IllegalArgumentException("styleIds must contain one entry per query capture");
    }

    final List<TSHighlightSpan> spans = new ArrayList<>();
    for (TSQueryMatch match : matches) {
      if (match == null) {
        throw new IllegalArgumentException("Highlight matches cannot contain null");
      }
      final TSQueryCapture[] captures = match.getCaptures();
      if (captures == null) continue;
      for (TSQueryCapture capture : captures) {
        if (capture == null || capture.getNode() == null
            || !capture.getNode().canAccess()) {
          throw new IllegalStateException("Highlight query contains an invalid capture node");
        }
        final int captureId = capture.getIndex();
        if (captureId < 0 || captureId >= captureCount) {
          throw new IllegalStateException("Highlight capture id is outside the query table");
        }
        final int styleId = styleIds[captureId];
        if (styleId < 0) continue;
        final TSNode node = capture.getNode();
        final long start = Integer.toUnsignedLong(node.getStartByte());
        final long end = Integer.toUnsignedLong(node.getEndByte());
        if (end < start) {
          throw new IllegalStateException("Highlight capture has an invalid byte range");
        }
        if (spans.size() >= maxSpans) {
          throw new IllegalStateException("Highlight query exceeded maxSpans; no partial result was returned");
        }
        spans.add(new TSHighlightSpan(start, end, captureId, match.getPatternIndex(),
            styleId, match.getId()));
      }
    }
    spans.sort(Comparator.comparingLong(TSHighlightSpan::getStartByte)
        .thenComparingLong(TSHighlightSpan::getEndByte)
        .thenComparingInt(TSHighlightSpan::getPatternIndex)
        .thenComparingInt(TSHighlightSpan::getCaptureId));
    return Collections.unmodifiableList(spans);
  }

  /**
   * Execute the query and map captures directly into a compact native-produced
   * span buffer. Each six-long record is startByte, endByte, captureId,
   * patternIndex, styleId, and matchId. No TSQueryMatch/TSQueryCapture Java
   * object graph is created by this path.
   */
  public static long[] extractPackedSpans(
      TSQuery query, TSNode root, long startByte, long endByte,
      int maxResults, int maxPendingMatches, int[] styleIds, int maxSpans) {
    Objects.requireNonNull(styleIds, "styleIds");
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
    if (maxResults < 1 || maxResults > MAX_RESULTS
        || maxPendingMatches < 1 || maxPendingMatches > MAX_PENDING_MATCHES
        || maxSpans < 1 || maxSpans > MAX_SPANS) {
      throw new IllegalArgumentException("Highlight query limits must be positive");
    }
    return Native.extractSpans(query.getNativeObject(), root, startByte, endByte,
        maxResults, maxPendingMatches, styleIds, maxSpans);
  }

  /** Materialize the compact native span buffer as immutable Java span objects. */
  public static List<TSHighlightSpan> unpackSpans(long[] packed) {
    Objects.requireNonNull(packed, "packed");
    if (packed.length % 6 != 0) {
      throw new IllegalArgumentException("Packed highlight spans must contain six values per record");
    }
    final List<TSHighlightSpan> spans = new ArrayList<>(packed.length / 6);
    for (int i = 0; i < packed.length; i += 6) {
      spans.add(new TSHighlightSpan(
          packed[i], packed[i + 1], Math.toIntExact(packed[i + 2]),
          Math.toIntExact(packed[i + 3]), Math.toIntExact(packed[i + 4]),
          packed[i + 5]));
    }
    return Collections.unmodifiableList(spans);
  }

  /** Convenience API for callers that need objects instead of the packed buffer. */
  public static List<TSHighlightSpan> extractSpans(
      TSQuery query, TSNode root, long startByte, long endByte,
      int maxResults, int maxPendingMatches, int[] styleIds, int maxSpans) {
    return unpackSpans(extractPackedSpans(query, root, startByte, endByte,
        maxResults, maxPendingMatches, styleIds, maxSpans));
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

    static native long[] extractSpans(
        long query, TSNode root, long startByte, long endByte,
        int maxResults, int maxPendingMatches, int[] styleIds, int maxSpans);
  }
}
