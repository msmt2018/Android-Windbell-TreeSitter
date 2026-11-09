package com.zerostudio.idea.treesitter;

/**
 * Random-access, bounded-chunk input for Tree-sitter.
 *
 * <p>Offsets and destination bytes use the parser's configured UTF-16LE encoding. Implementations
 * must fill at most destination.length bytes, return an even byte count, return zero only at EOF,
 * and preserve the requested document revision for the lifetime of one parse call.
 */
public interface TSInput {
  int read(long byteOffset, int row, int column, byte[] destination);
}
