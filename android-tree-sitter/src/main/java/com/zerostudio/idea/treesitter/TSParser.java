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
import com.zerostudio.idea.treesitter.string.UTF16String;
import com.zerostudio.idea.treesitter.string.UTF16StringFactory;
import com.zerostudio.idea.treesitter.util.TSObjectFactoryProvider;
import java.nio.ByteBuffer;
import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of tree sitter's <code>TSParser</code> APIs. This implementation always converts
 * the input source code to {@link UTF16String UTF-16 string}. Native parsing requests source bytes
 * from a bounded 64 KiB callback window rather than exposing the entire remaining document to
 * Tree-sitter at once. Upstream Tree-sitter still uses 32-bit byte offsets; this binding's
 * UTF16String storage also currently has signed 32-bit byte lengths, so a single parse is limited
 * to fewer than 2 GiB of UTF-16LE bytes. Applications should use regional parsing for very large
 * documents instead of assuming that chunked input makes the syntax tree itself small.
 */
public class TSParser extends TSNativeObject {

  /**
   * Maximum even UTF-16LE byte length representable by this binding's current
   * signed-int UTF16String storage and Tree-sitter's 32-bit byte offsets.
   * This is an ABI ceiling, not a promise that a device has enough memory to
   * parse a document of this size.
   */
  public static final int MAX_SUPPORTED_INPUT_BYTES = Integer.MAX_VALUE - 1;

  /**
   * Maximum even byte length accepted by Tree-sitter's uint32 byte-offset API.
   * This is an address-space limit, not a device-memory recommendation.
   */
  public static final long MAX_SEGMENTED_INPUT_BYTES = 0xFFFF_FFFEL;

  protected final ReentrantLock parseLock = new ReentrantLock();
  protected final Condition parseCondition = parseLock.newCondition();
  protected final AtomicBoolean isParsing = new AtomicBoolean(false);
  protected final AtomicBoolean isCancellationRequested = new AtomicBoolean(false);

  /**
   * Supplies bounded UTF-16LE windows for a document without flattening or
   * allocating a direct buffer for the entire file. Returned bytes must be
   * even-length and remain valid only for the duration of the callback.
   */
  public interface ParseWindowCallback {
    byte[] read(long byteOffset, int maxBytes);
  }

  protected TSParser(long pointer) {
    super(pointer);
  }

  protected TSParser() {
    this(Native.newParser());
  }

  public static TSParser create() {
    return create(Native.newParser());
  }

  public static TSParser create(long parserPointer) {
    return TSObjectFactoryProvider.getFactory().createParser(parserPointer);
  }

  private TSTree createTree(long pointer) {
    if (pointer == 0) {
      return null;
    }
    return TSTree.create(pointer);
  }

  /**
   * Set the language of the given parser.
   *
   * @param language The language to set.
   * @see TSLanguage
   */
  public void setLanguage(TSLanguage language) {
    checkAccess();
    Native.setLanguage(getNativeObject(), language.getNativeObject());
  }

  /**
   * Get the language for this parser instance.
   *
   * @return The language instance.
   */
  public TSLanguage getLanguage() {
    checkAccess();
    final var langPtr = Native.getLanguage(this.getNativeObject());
    if (langPtr == 0) {
      return null;
    }

    return TSLanguageCache.get(langPtr);
  }

  /**
   * Parses the given String source. See {@link #parseString(TSTree, UTF16String)} for more
   * details.
   *
   * @param source The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseString(String source) {
    throwIfParseNotCancelled();
    try (final var str = UTF16StringFactory.newString(source)) {
      return parseString(str);
    }
  }

  /**
   * Parses the given {@link UTF16String} source. See {@link #parseString(TSTree, UTF16String)} for
   * more details.
   *
   * @param source The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseString(UTF16String source) {
    return parseString(null, source);
  }

  /**
   * Parses the given source code bytes. See {@link #parseString(TSTree, UTF16String)} for more
   * details.
   *
   * @param bytes The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   * @see #parseBytes(byte[], int, int)
   */
  public TSTree parseBytes(byte[] bytes) {
    return parseBytes(bytes, 0, bytes.length);
  }

  /**
   * Parses the given source code bytes. See {@link #parseString(TSTree, UTF16String)} for more
   * details. See {@link #parseString(TSTree, UTF16String)} for more details.
   *
   * @param bytes  The source code to parse.
   * @param offset The start offset in <code>bytes</code>.
   * @param len    The number of bytes to from <code>offset</code> to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseBytes(byte[] bytes, int offset, int len) {
    throwIfParseNotCancelled();
    try (final var source = UTF16StringFactory.newString(bytes, offset, len)) {
      return parseString(source);
    }
  }

  /**
   * Parse the given edited source code using the previously parsed syntax tree. The given
   * {@link TSTree} must have been edited using {@link TSTree#edit(TSInputEdit)} before calling this
   * method. See {@link #parseString(TSTree, UTF16String)} for more details.
   *
   * @param oldTree The previously parsed syntax tree.
   * @param source  The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   */
  public TSTree parseString(TSTree oldTree, String source) {
    throwIfParseNotCancelled();
    try (final var str = UTF16StringFactory.newString(source)) {
      return parseString(oldTree, str);
    }
  }

  /**
   * Parse the given edited source code using the previously parsed syntax tree. The given
   * {@link TSTree} must have been edited using {@link TSTree#edit(TSInputEdit)} before calling this
   * method.
   * <p>
   * Throws {@link ParseInProgressException} if the parser is currently parsing a syntax tree and
   * the cancellation was NOT requested using {@link #requestCancellationAsync()}. This method
   * blocks the current thread if the previous parse was requested to be cancelled but the parse
   * operation has not been cancelled yet.
   *
   * @param oldTree The previously parsed syntax tree.
   * @param source  The source code to parse.
   * @return The parsed tree, or <code>null</code> if the parse failed or was cancelled.
   * @throws IllegalStateException    If the parser is not accessible. See
   *                                  {@link TSNativeObject#canAccess()} for more details.
   * @throws ParseInProgressException If the parser is currently parsing another syntax tree.
   */
  public TSTree parseString(TSTree oldTree, UTF16String source) {
    checkAccess();
    final int sourceByteLength = source.byteLength();
    if (sourceByteLength < 0 || sourceByteLength > MAX_SUPPORTED_INPUT_BYTES) {
      throw new IllegalArgumentException(
          "UTF-16 source exceeds this Tree-sitter binding's supported byte length");
    }
    if ((sourceByteLength & 1) != 0) {
      throw new IllegalArgumentException("UTF-16LE source byte length must be even");
    }

    // Check for reentrancy (same thread calling this method again, before the previous call returned)
    if (parseLock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Reentrancy detected");
    }

    // if the parser is currently parsing a syntax tree and the cancellation
    // was not requested, throw an error
    throwIfParseNotCancelled();

    // acquire the lock
    // this will wait until the cancelled parse call returns
    parseLock.lock();
    setCancellationRequested(false);
    setParsingFlag();
    try {
      final var strPointer = source.getNativeObject();
      final var oldTreePointer = oldTree != null ? oldTree.getNativeObject() : 0;
      final var tree = Native.parse(this.getNativeObject(), oldTreePointer, strPointer);
      return createTree(tree);
    } finally {
      unsetParsingFlag();
      parseCondition.signalAll();
      parseLock.unlock();
    }
  }

  /**
   * Parses UTF-16LE text supplied as direct ByteBuffer segments without joining
   * the segments into a single Java String or native contiguous byte array.
   *
   * <p>Each buffer's remaining range is parsed. Buffers must be direct, have an
   * even remaining byte count, and remain unmodified for the duration of this
   * synchronous call. The parser retains no references after it returns.
   *
   * <p>This avoids an additional full-document input copy, but Tree-sitter still
   * constructs a syntax tree for the supplied input. For the editor's complete ATS
   * symbol tree, parse the full source and bound query/render work separately.
   *
   * @param buffers UTF-16LE direct buffers in document order.
   * @return parsed tree, or null if parsing failed or was cancelled.
   */
  public TSTree parseBuffers(ByteBuffer[] buffers) {
    return parseBuffers(null, buffers);
  }

  /**
   * Incrementally parses segmented UTF-16LE input using a previously edited tree.
   *
   * @param oldTree previously parsed and edited tree, or null.
   * @param buffers UTF-16LE direct buffers in document order.
   * @return parsed tree, or null if parsing failed or was cancelled.
   */
  public TSTree parseBuffers(TSTree oldTree, ByteBuffer[] buffers) {
    return parseBuffers(oldTree, buffers, null);
  }

  /**
   * Parses segmented UTF-16LE input while retaining only the supplied Tree-sitter
   * included ranges in the resulting syntax tree. Ranges use absolute document
   * byte offsets and UTF-16LE TSPoint coordinates. A non-empty included-range set
   * creates a partial syntax tree, not a complete document AST. Never publish that
   * tree as the editor's ATS symbol tree or pass it to the TSH full-document path.
   *
   * <p>The included ranges are reset to the full-document default after parsing,
   * including when parsing fails, so a later ordinary parse is not accidentally
   * restricted to a stale viewport.
   */
  public TSTree parseBuffers(
      TSTree oldTree, ByteBuffer[] buffers, TSRange[] includedRanges) {
    checkAccess();
    if (buffers == null) {
      throw new NullPointerException("buffers");
    }

    final ByteBuffer[] slices = new ByteBuffer[buffers.length];
    long totalBytes = 0;
    for (int i = 0; i < buffers.length; i++) {
      final ByteBuffer buffer = buffers[i];
      if (buffer == null) {
        throw new IllegalArgumentException("buffers[" + i + "] must not be null");
      }
      if (!buffer.isDirect()) {
        throw new IllegalArgumentException("buffers[" + i + "] must be a direct ByteBuffer");
      }
      final ByteBuffer slice = buffer.slice();
      final int remaining = slice.remaining();
      if ((remaining & 1) != 0) {
        throw new IllegalArgumentException("buffers[" + i + "] splits a UTF-16 code unit");
      }
      totalBytes += remaining;
      if (totalBytes > MAX_SEGMENTED_INPUT_BYTES) {
        throw new IllegalArgumentException("Segmented input exceeds Tree-sitter's 32-bit byte-offset limit");
      }
      slices[i] = slice;
    }

    if (includedRanges != null) {
      long previousEnd = 0L;
      for (int i = 0; i < includedRanges.length; i++) {
        final TSRange range = includedRanges[i];
        if (range == null) {
          throw new IllegalArgumentException("includedRanges[" + i + "] must not be null");
        }
        final long start = Integer.toUnsignedLong(range.getStartByte());
        final long end = Integer.toUnsignedLong(range.getEndByte());
        if ((start & 1L) != 0L || (end & 1L) != 0L ||
            start > end || end > totalBytes || (i > 0 && start < previousEnd)) {
          throw new IllegalArgumentException("includedRanges must be ordered, non-overlapping UTF-16LE ranges within the input");
        }
        previousEnd = end;
      }
    }

    if (parseLock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Reentrancy detected");
    }
    throwIfParseNotCancelled();
    parseLock.lock();
    setCancellationRequested(false);
    setParsingFlag();
    boolean rangesInstalled = false;
    try {
      if (includedRanges != null) {
        if (!setIncludedRanges(includedRanges)) {
          throw new IllegalArgumentException("Tree-sitter rejected the included ranges");
        }
        rangesInstalled = true;
      }
      final long oldTreePointer = oldTree != null ? oldTree.getNativeObject() : 0;
      return createTree(Native.parseBuffers(
          getNativeObject(), oldTreePointer, slices, totalBytes));
    } finally {
      try {
        if (rangesInstalled && !setIncludedRanges(new TSRange[0])) {
          throw new IllegalStateException("Failed to reset Tree-sitter included ranges");
        }
      } finally {
        unsetParsingFlag();
        parseCondition.signalAll();
        parseLock.unlock();
      }
    }
  }

  /**
   * Parses a versioned/random-access UTF-16 document through bounded windows.
   * Only bytes requested by Tree-sitter are materialized. Pass an empty included
   * range set for a complete document AST; non-empty ranges intentionally produce
   * a partial tree and must not replace the complete ATS symbol tree.
   */
  public TSTree parseWindowed(
      TSTree oldTree,
      long byteLength,
      TSRange[] includedRanges,
      ParseWindowCallback callback) {
    checkAccess();
    if (callback == null) throw new NullPointerException("callback");
    if (byteLength < 0 || byteLength > MAX_SEGMENTED_INPUT_BYTES || (byteLength & 1L) != 0) {
      throw new IllegalArgumentException(
          "Windowed UTF-16LE input length must be even and fit Tree-sitter's 32-bit byte offsets");
    }

    final TSRange[] ranges = includedRanges == null ? new TSRange[0] : includedRanges;
    long previousEnd = 0L;
    for (int i = 0; i < ranges.length; i++) {
      final TSRange range = ranges[i];
      if (range == null) throw new IllegalArgumentException("includedRanges[" + i + "] must not be null");
      final long start = Integer.toUnsignedLong(range.getStartByte());
      final long end = Integer.toUnsignedLong(range.getEndByte());
      if ((start & 1L) != 0L || (end & 1L) != 0L ||
          start > end || end > byteLength || (i > 0 && start < previousEnd)) {
        throw new IllegalArgumentException(
            "includedRanges must be ordered, non-overlapping UTF-16LE ranges within the input");
      }
      previousEnd = end;
    }

    if (parseLock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Reentrancy detected");
    }
    throwIfParseNotCancelled();
    parseLock.lock();
    setCancellationRequested(false);
    setParsingFlag();
    boolean rangesInstalled = false;
    try {
      if (!setIncludedRanges(ranges)) {
        throw new IllegalArgumentException("Tree-sitter rejected the included ranges");
      }
      rangesInstalled = true;
      final long oldTreePointer = oldTree != null ? oldTree.getNativeObject() : 0;
      return createTree(Native.parseWindowed(
          getNativeObject(), oldTreePointer, callback, byteLength));
    } finally {
      try {
        if (rangesInstalled && !setIncludedRanges(new TSRange[0])) {
          throw new IllegalStateException("Failed to reset Tree-sitter included ranges");
        }
      } finally {
        unsetParsingFlag();
        parseCondition.signalAll();
        parseLock.unlock();
      }
    }
  }

  /**
   * Set the maximum duration in microseconds that parsing should be allowed to take before
   * halting.
   *
   * <p>If parsing takes longer than this, it will halt early, returning <code>null</code>.
   */
  public void setTimeout(long microseconds) {
    checkAccess();
    Native.setTimeout(getNativeObject(), microseconds);
  }

  /**
   * Get the duration in microseconds that parsing is allowed to take.
   *
   * @return The timeout in microseconds.
   */
  public long getTimeout() {
    checkAccess();
    return Native.getTimeout(getNativeObject());
  }

  /**
   * Check whether the parser is in the process of parsing a syntax tree.
   *
   * @return <code>true</code> if the parser is parsing a syntax tree, <code>false</code> otherwise.
   */
  public boolean isParsing() {
    return isParsing.get();
  }

  /**
   * Sets the 'parsing' flag to indicate that the parser is in the process of parsing a syntax
   * tree.
   */
  protected boolean setParsingFlag() {
    return this.isParsing.compareAndSet(false, true);
  }

  /**
   * Sets the 'parsing' flag to indicate that the parsing operation is NOT in progress.
   */
  protected boolean unsetParsingFlag() {
    return this.isParsing.compareAndSet(true, false);
  }

  /**
   * Request the parsing operation to be cancelled if the parser is in the process of parsing a
   * syntax tree.
   * <p>
   * This is an asynchronous operation and the previous parse call may NOT be cancelled immediately.
   * Use {@link #requestCancellationAndWait()} for a blocking cancellation request.
   *
   * @return <code>true</code> if the cancellation was requested successfully, <code>false</code>
   * otherwise.
   */
  public boolean requestCancellationAsync() {
    final var requested = Native.requestCancellation(getNativeObject());
    setCancellationRequested(requested);
    return requested;
  }

  /**
   * If the parser is parsing syntax tree, sets the cancellation flag and blocks the current thread
   * until the parse operation returns. Does nothing if {@link #requestCancellationAsync()} returns
   * false.
   */
  public void requestCancellationAndWait() {
    if (requestCancellationAsync()) {
      parseLock.lock();
      try {
        while (isParsing()) {
          parseCondition.await();
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      } finally {
        parseLock.unlock();
      }
    }
  }

  protected synchronized void setCancellationRequested(boolean isRequested) {
    this.isCancellationRequested.set(isRequested);
  }

  protected synchronized boolean isCancellationRequested() {
    return this.isCancellationRequested.get();
  }

  /**
   * Set the ranges of text that the parser should include when parsing.
   *
   * <p>By default, the parser will always include entire documents. This function allows you to
   * parse only a *portion* of a document but still return a syntax tree whose ranges match up with
   * the document as a whole. You can also pass multiple disjoint ranges.
   *
   * <p>If the ranges parameter is an empty array, then the entire document will be parsed.
   * Otherwise, the given ranges must be ordered from earliest to latest in the document, and they
   * must not overlap. As this binding supplies UTF-16LE input, every range byte offset must also be
   * divisible by two; an offset inside a UTF-16 code unit is rejected with
   * {@link IllegalArgumentException}. That is, the following must hold for all `i` < `length - 1`:
   * ranges[i].end_byte <= ranges[i + 1].start_byte where `length` is the length of the ranges
   * array.
   *
   * <p>If this requirement is not satisfied, the operation will fail, the ranges will not be
   * assigned, and this function will return `false`. On success, this function returns `true`
   */
  public boolean setIncludedRanges(TSRange[] ranges) {
    checkAccess();
    return Native.setIncludedRanges(getNativeObject(), ranges);
  }

  public TSRange[] getIncludedRanges() {
    checkAccess();
    return Native.getIncludedRanges(getNativeObject());
  }

  /**
   * Instruct the parser to start the next parse from the beginning.
   *
   * <p>If the parser previously failed because of a timeout or a cancellation, then by default, it
   * will resume where it left off on the next call to any of the parsing functions. If you don't
   * want to resume, and instead intend to use this parser to parse some other document, you must
   * call this function first.
   */
  public void reset() {
    checkAccess();
    Native.reset(getNativeObject());
  }

  @Override
  protected void closeNativeObj() {
    Native.delete(getNativeObject());
  }

  private void throwIfParseNotCancelled() {
    if (isParsing() && !isCancellationRequested()) {
      throw new ParseInProgressException(
        "Parser is already parsing another syntax tree! Cancel the previous parse before starting another.");
    }
  }

  /**
   * Base class the {@link TSParser} exceptions.
   */
  protected static class TSParserException extends RuntimeException {

    public TSParserException(String message) {
      super(message);
    }

    public TSParserException(String message, Throwable cause) {
      super(message, cause);
    }

    public TSParserException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Thrown when a parse is requested while another parse is already in progress.
   */
  protected static final class ParseInProgressException extends TSParserException {

    public ParseInProgressException(String message) {
      super(message);
    }
  }

  @GenerateNativeHeaders(fileName = "parser")
  private static class Native {

    @FastNative
    static native long newParser();

    @FastNative
    static native void delete(long parser);

    @FastNative
    static native void setLanguage(long parser, long language);

    @FastNative
    static native long getLanguage(long parser);

    @FastNative
    static native void reset(long parser);

    @FastNative
    static native void setTimeout(long parser, long timeout);

    @FastNative
    static native long getTimeout(long parser);

    @FastNative
    static native boolean setIncludedRanges(long parser, TSRange[] ranges);

    @FastNative
    static native TSRange[] getIncludedRanges(long parser);

    @FastNative
    static native long parse(long parser, long treePointer, long strPointer);

    @FastNative
    static native long parseBuffers(
        long parser, long treePointer, ByteBuffer[] buffers, long byteLength);

    @FastNative
    static native long parseWindowed(
        long parser, long treePointer, ParseWindowCallback callback, long byteLength);

    @FastNative
    static native boolean requestCancellation(long parser);
  }
}