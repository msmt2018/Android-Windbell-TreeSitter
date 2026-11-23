/*
 * This file is part of android-tree-sitter.
 * Licensed under the GNU Lesser General Public License, version 2.1 or later.
 */
package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;
import java.util.ArrayList;
import java.util.Arrays;
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
  private TSHighlight() {}

  /**
   * Extract highlight query matches intersecting an absolute document byte range.
   * Limits are enforced natively and an over-budget query fails atomically.
   */
  @Deprecated
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
    if (maxSpans < 1) {
      throw new IllegalArgumentException("maxSpans must be positive");
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
  @Deprecated
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
    if (maxResults < 1 || maxPendingMatches < 1 || maxSpans < 1) {
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
  @Deprecated
  public static List<TSHighlightSpan> extractSpans(
      TSQuery query, TSNode root, long startByte, long endByte,
      int maxResults, int maxPendingMatches, int[] styleIds, int maxSpans) {
    return unpackSpans(extractPackedSpans(query, root, startByte, endByte,
        maxResults, maxPendingMatches, styleIds, maxSpans));
  }


  /**
   * Opens a resumable, batch-oriented highlight capture cursor.
   *
   * <p>Unlike {@link #extractPackedSpans}, this API never accumulates the complete query result.
   * Each call to {@link Cursor#nextBatch(int)} returns at most the requested number of styled
   * captures. A single Tree-sitter match may be continued across multiple batches without losing
   * its match id, pattern index, or capture index. A zero pending-match limit leaves Tree-sitter's
   * native cursor at its default dynamically-growing policy; a positive value is an explicit
   * caller-selected resource policy, not a total result count.
   */
  public static Cursor openCursor(
      TSQuery query, TSNode root, long startByte, long endByte, int[] styleIds) {
    return openCursor(query, root, startByte, endByte, styleIds, 0, null);
  }

  public static Cursor openCursor(
      TSQuery query, TSNode root, long startByte, long endByte,
      int[] styleIds, int maxPendingMatches) {
    return openCursor(query, root, startByte, endByte, styleIds, maxPendingMatches, null);
  }

  public static Cursor openCursor(
      TSQuery query, TSNode root, long startByte, long endByte,
      int[] styleIds, int maxPendingMatches,
      TSQueryCursor.ProgressCallback progressCallback) {
    Objects.requireNonNull(styleIds, "styleIds");
    if (query == null || !query.canAccess()) {
      throw new IllegalArgumentException("Cannot highlight with an invalid query");
    }
    if (root == null || !root.canAccess() || root.getTree() == null
        || !root.getTree().canAccess() || root.hasChanges()) {
      throw new IllegalArgumentException("Cannot highlight an invalid or changed root");
    }
    if (startByte < 0L || endByte < startByte || endByte > 0xFFFF_FFFFL ||
        startByte < Integer.toUnsignedLong(root.getStartByte()) ||
        endByte > Integer.toUnsignedLong(root.getEndByte())) {
      throw new IllegalArgumentException("Highlight byte range must be inside the root and fit uint32");
    }
    if (styleIds.length != query.getCaptureCount()) {
      throw new IllegalArgumentException("styleIds must contain one entry per query capture");
    }
    if (maxPendingMatches < 0) {
      throw new IllegalArgumentException("maxPendingMatches must be zero (unlimited) or positive");
    }

    final TSQueryCursor nativeCursor = TSQueryCursor.create();
    final boolean[] cancelled = new boolean[1];
    try {
      nativeCursor.setByteRange(startByte, endByte);
      if (maxPendingMatches > 0) nativeCursor.setMatchLimit(maxPendingMatches);
      if (progressCallback == null) {
        nativeCursor.exec(query, root);
      } else {
        nativeCursor.exec(query, root, currentOffset -> {
          final boolean stop = progressCallback.onProgress(currentOffset);
          if (stop) cancelled[0] = true;
          return stop;
        });
      }
      return new Cursor(nativeCursor, styleIds, cancelled);
    } catch (RuntimeException | Error error) {
      nativeCursor.close();
      throw error;
    }
  }

  /** Stateful highlight cursor. Close it when the scan is complete or abandoned. */
  public static final class Cursor implements AutoCloseable {
    private final TSQueryCursor cursor;
    private final int[] styleIds;
    private final boolean[] cancellationState;
    private TSQueryMatch currentMatch;
    private int nextCaptureIndex;
    private boolean exhausted;
    private boolean closed;

    private Cursor(TSQueryCursor cursor, int[] styleIds, boolean[] cancellationState) {
      this.cursor = cursor;
      this.styleIds = styleIds.clone();
      this.cancellationState = cancellationState;
    }

    /**
     * Returns a compact six-long-per-capture batch:
     * startByte, endByte, captureId, patternIndex, styleId, matchId.
     *
     * <p>The limit is per delivery batch only. It does not truncate the total query result.
     * A match with more captures than the batch size is continued by the next call.
     */
    public synchronized Batch nextBatch(int maxSpans) {
      checkOpen();
      if (maxSpans <= 0 || maxSpans > Integer.MAX_VALUE / 6) {
        throw new IllegalArgumentException("maxSpans must fit a positive six-column JNI/Java batch");
      }
      final long[] buffer = new long[maxSpans * 6];
      int emitted = 0;
      while (emitted < maxSpans && !exhausted) {
        if (currentMatch == null) {
          currentMatch = cursor.nextMatch();
          nextCaptureIndex = 0;
          if (currentMatch == null) {
            exhausted = true;
            break;
          }
        }
        final TSQueryCapture[] captures = currentMatch.getCaptures();
        if (captures == null || nextCaptureIndex >= captures.length) {
          currentMatch = null;
          continue;
        }
        final TSQueryCapture capture = captures[nextCaptureIndex++];
        if (capture == null || capture.getNode() == null || !capture.getNode().canAccess()) {
          throw new IllegalStateException("Highlight query returned an invalid capture node");
        }
        final int captureId = capture.getIndex();
        if (captureId < 0 || captureId >= styleIds.length) {
          throw new IllegalStateException("Highlight query returned a capture id outside the style table");
        }
        final int styleId = styleIds[captureId];
        if (styleId < 0) continue;

        final TSNode node = capture.getNode();
        final long start = Integer.toUnsignedLong(node.getStartByte());
        final long end = Integer.toUnsignedLong(node.getEndByte());
        if (end < start) throw new IllegalStateException("Highlight capture has an invalid byte range");
        final int offset = emitted * 6;
        buffer[offset] = start;
        buffer[offset + 1] = end;
        buffer[offset + 2] = captureId;
        buffer[offset + 3] = currentMatch.getPatternIndex();
        buffer[offset + 4] = styleId;
        buffer[offset + 5] = Integer.toUnsignedLong(currentMatch.getId());
        emitted++;
        if (nextCaptureIndex >= captures.length) currentMatch = null;
      }

      final boolean exceeded = exhausted && cursor.didExceedMatchLimit();
      return new Batch(
          emitted == 0 ? new long[0] : Arrays.copyOf(buffer, emitted * 6),
          exhausted && !exceeded && !cancelled[0],
          exceeded,
          cancelled[0]);
    }

    public synchronized boolean didExceedMatchLimit() {
      checkOpen();
      return cursor.didExceedMatchLimit();
    }

    private void checkOpen() {
      if (closed) throw new IllegalStateException("Highlight cursor is closed");
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      currentMatch = null;
      cursor.close();
    }
  }

  /** One resumable output batch. An exceeded pending-match limit means results are incomplete. */
  public static final class Batch {
    private final long[] packedSpans;
    private final boolean complete;
    private final boolean exceededMatchLimit;
    private final boolean cancelled;

    private Batch(long[] packedSpans, boolean complete, boolean exceededMatchLimit, boolean cancelled) {
      this.packedSpans = packedSpans;
      this.complete = complete;
      this.exceededMatchLimit = exceededMatchLimit;
      this.cancelled = cancelled;
    }

    public long[] getPackedSpans() { return packedSpans; }
    public boolean isComplete() { return complete; }
    public boolean didExceedMatchLimit() { return exceededMatchLimit; }
    public boolean isCancelled() { return cancelled; }
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
