package com.zerostudio.idea.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TSHighlightSpanTest {

  @Test
  void preservesUnsignedTreeSitterByteOffsets() {
    TSHighlightSpan span = new TSHighlightSpan(
        0x8000_0001L, 0xFFFF_FFFFL, 2, 3, 4, 5L);

    assertEquals(0x8000_0001L, span.getStartByte());
    assertEquals(0xFFFF_FFFFL, span.getEndByte());
  }

  @Test
  void preservesUnsignedQueryMatchIds() {
    long matchId = 0xFFFF_FFFFL;
    TSHighlightSpan span = new TSHighlightSpan(0, 2, 0, 0, 1, matchId);

    assertEquals(matchId, span.getMatchId());
    assertEquals(matchId, TSHighlight.unpackSpans(
        new long[]{0, 2, 0, 0, 1, matchId}).get(0).getMatchId());
  }

  @Test
  void unpacksMultiplePackedRecordsWithoutNarrowingOffsets() {
    long[] packed = {
        0x8000_0000L, 0x8000_0002L, 1, 2, 3, 0xFFFF_FFFFL,
        0xFFFF_FFF0L, 0xFFFF_FFFFL, 4, 5, 6, 7
    };

    var spans = TSHighlight.unpackSpans(packed);

    assertEquals(2, spans.size());
    assertEquals(0x8000_0000L, spans.get(0).getStartByte());
    assertEquals(0xFFFF_FFF0L, spans.get(1).getStartByte());
    assertEquals(0xFFFF_FFFFL, spans.get(0).getMatchId());
  }

  @Test
  void rejectsMalformedPackedRecordStride() {
    assertThrows(IllegalArgumentException.class,
        () -> TSHighlight.unpackSpans(new long[]{0, 2, 0, 0, 1}));
  }

  @Test
  void rejectsInvalidByteRanges() {
    assertThrows(IllegalArgumentException.class,
        () -> new TSHighlightSpan(3, 2, 0, 0, 1, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new TSHighlightSpan(0, 0x1_0000_0000L, 0, 0, 1, 0));
  }
}
