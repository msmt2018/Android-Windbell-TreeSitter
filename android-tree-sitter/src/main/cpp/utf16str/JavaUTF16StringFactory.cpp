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

#include <jni.h>
#include <vector>
#include <new>
#include <utility>

#include "../utils/ts_exceptions.h"

#include "UTF16String.h"

#include "ts_utf16string_factory.h"

static jlong
UTF16StringFactory_newString(
    JNIEnv *env,
    jclass clazz,
    jstring source) {
  auto *str = new UTF16String;
  str->append(env, source);
  return (jlong) str;
}

static jlong
UTF16StringFactory_newStringBytes(
    JNIEnv *env,
    jclass clazz,
    jbyteArray bytes,
    jint off,
    jint len) {
  if (bytes == nullptr) {
    throw_npe(env, "bytes");
    return 0;
  }

  const jsize array_length = env->GetArrayLength(bytes);
  if (env->ExceptionCheck()) return 0;
  if (off < 0 || len < 0 || (len & 1) != 0 ||
      off > array_length || len > array_length - off) {
    throw_illegal_args(env,
        "UTF-16LE byte slice must be even-length and within the source array");
    return 0;
  }

  try {
    std::vector<jbyte> storage(static_cast<size_t>(len));
    if (len != 0) {
      env->GetByteArrayRegion(bytes, off, len, storage.data());
      if (env->ExceptionCheck()) return 0;
    }
    auto *ustr = new (std::nothrow) UTF16String(std::move(storage));
    if (ustr == nullptr) {
      throw_exception(env, "java/lang/OutOfMemoryError",
                      "Unable to allocate native UTF16String");
      return 0;
    }
    return reinterpret_cast<jlong>(ustr);
  } catch (const std::bad_alloc&) {
    throw_exception(env, "java/lang/OutOfMemoryError",
                    "Unable to copy UTF-16LE byte slice");
    return 0;
  }
}

void UTF16StringFactory_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, UTF16StringFactory_Native_newString,
                 UTF16StringFactory_newString);
  SET_JNI_METHOD(methods, UTF16StringFactory_Native_newStringBytes,
                 UTF16StringFactory_newStringBytes);
}