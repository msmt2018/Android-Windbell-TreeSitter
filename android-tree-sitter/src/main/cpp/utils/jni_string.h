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

#ifndef ANDROIDTREESITTER_JNI_STRING_H
#define ANDROIDTREESITTER_JNI_STRING_H

#include <jni.h>
#include <assert.h>
#include <stdlib.h>
#include <string>
#include <cstring>
#include <cstdint>

jsize FNI_GetStringLength(JNIEnv *env, jstring string);

const jchar *FNI_GetStringChars(JNIEnv *env, jstring string, int32_t *length);

void FNI_ReleaseStringChars(const jchar *chars);

jstring FNI_NewString(JNIEnv *env, const jbyte *bytes, int len);

/** Convert Java UTF-16 to standard UTF-8 (not JNI Modified UTF-8). */
bool FNI_GetStandardUtf8(JNIEnv *env, jstring source, std::string &out);

/** Convert standard UTF-8 bytes returned by Tree-sitter to a Java UTF-16 String. */
jstring FNI_NewStringFromStandardUtf8(JNIEnv *env, const char *bytes, uint32_t length);
inline jstring FNI_NewStringFromStandardUtf8(JNIEnv *env, const std::string &value) {
    return FNI_NewStringFromStandardUtf8(
        env, value.data(), static_cast<uint32_t>(value.size()));
}

#endif //ANDROIDTREESITTER_JNI_STRING_H