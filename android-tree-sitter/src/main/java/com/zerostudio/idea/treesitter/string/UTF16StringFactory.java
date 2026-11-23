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

package com.zerostudio.idea.treesitter.string;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;
import com.zerostudio.idea.treesitter.util.TSObjectFactoryProvider;
import dalvik.annotation.optimization.FastNative;

/**
 * Provides APIs to create instances of {@link UTF16String}.
 *
 * @author Akash Yadav
 */
public class UTF16StringFactory {

  private UTF16StringFactory() {
    throw new UnsupportedOperationException();
  }

  public static UTF16String newString() {
    return newString("");
  }

  public static UTF16String newString(String source) {
    return newString(source, false);
  }

  public static UTF16String newString(String source, boolean isSynchronized) {
    return createString(Native.newString(source), isSynchronized);
  }

  public static UTF16String newString(byte[] bytes) {
    return newString(bytes, false);
  }

  public static UTF16String newString(byte[] bytes, boolean isSynchronized) {
    return newString(bytes, 0, bytes.length, isSynchronized);
  }

  public static UTF16String newString(byte[] bytes, int offset, int len) {
    return newString(bytes, offset, len, false);
  }

  public static UTF16String newString(byte[] bytes, int offset, int len, boolean isSynchronized) {
    return createString(newString0(bytes, offset, len), isSynchronized);
  }

  private static long newString0(byte[] bytes, int offset, int len) {
    if (bytes == null) {
      throw new NullPointerException("bytes");
    }
    // UTF16String stores UTF-16LE code units. A byte slice must start and end
    // on a two-byte code-unit boundary, and its bounds must be checked without
    // offset + len overflow before entering JNI.
    if (offset < 0 || len < 0 || offset > bytes.length - len) {
      throw new IllegalArgumentException(
          "Invalid UTF-16 byte slice: offset=" + offset + ", length=" + len
              + ", byteLength=" + bytes.length);
    }
    if (((offset | len) & 1) != 0) {
      throw new IllegalArgumentException(
          "UTF-16LE byte slices must be aligned to two-byte code units");
    }
    return Native.newStringBytes(bytes, offset, len);
  }

  static UTF16String createString(long pointer) {
    return createString(pointer, false);
  }

  static UTF16String createString(long pointer, boolean isSynchronized) {
    return TSObjectFactoryProvider.getFactory().createString(pointer, isSynchronized);
  }

  @GenerateNativeHeaders(fileName = "utf16string_factory")
  private static class Native {

    @FastNative
    static native long newString(String source);

    @FastNative
    static native long newStringBytes(byte[] bytes, int off, int len);
  }
}
