/*
 * This file is part of android-tree-sitter.
 * Licensed under the GNU Lesser General Public License, version 2.1 or later.
 */
package com.zerostudio.idea.treesitter;

/**
 * Immutable syntax-highlight span in absolute document byte coordinates.
 * The span refers to source storage; it does not own or copy source text.
 */
public final class TSHighlightSpan {
  private final long startByte;
  private final long endByte;
  private final int captureId;
  private final int patternIndex;
  private final int styleId;
  private final long matchId;

  public TSHighlightSpan(long startByte, long endByte, int captureId,
      int patternIndex, int styleId, long matchId) {
    if (startByte < 0 || endByte < startByte || endByte > 0xFFFF_FFFFL) {
      throw new IllegalArgumentException("Invalid highlight byte range");
    }
    if (captureId < 0 || patternIndex < 0 || styleId < 0) {
      throw new IllegalArgumentException("Highlight identifiers must be non-negative");
    }
    this.startByte = startByte;
    this.endByte = endByte;
    this.captureId = captureId;
    this.patternIndex = patternIndex;
    this.styleId = styleId;
    this.matchId = matchId;
  }

  public long getStartByte() { return startByte; }
  public long getEndByte() { return endByte; }
  public int getCaptureId() { return captureId; }
  public int getPatternIndex() { return patternIndex; }
  public int getStyleId() { return styleId; }
  public long getMatchId() { return matchId; }
}
