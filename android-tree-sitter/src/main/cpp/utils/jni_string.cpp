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

#include "jni_string.h"
#include "ts_exceptions.h"

#include <limits>
#include <vector>

jstring FNI_NewAndroidString(JNIEnv *pEnv, const jbyte *bytes, int len);
jstring FNI_NewJVMString(JNIEnv *env, const jbyte *bytes, jsize len);

static const char *charset_name = "UTF_16LE";

/** Constructs a new java.lang.String object from an array of Unicode
 * characters.
 *
 * Returns a Java string object, or NULL if the string cannot be constructed.
 */
jstring FNI_NewJVMString(JNIEnv *env, const jbyte *bytes, jsize len) {
    jstring result;
    jstring charset = env->NewStringUTF(charset_name);
    jclass strcls = env->FindClass("java/lang/String");
    jmethodID cid = env->GetMethodID(strcls, "<init>", "([BLjava/lang/String;)V");
    jbyteArray ba = env->NewByteArray(len);
    env->SetByteArrayRegion(ba, 0, len, bytes);
    result = (jstring) env->NewObject(strcls, cid, ba, charset);
    env->DeleteLocalRef(ba);
    env->DeleteLocalRef(strcls);
    env->DeleteLocalRef(charset);
    return result;
}

/** Returns the length (the count of Unicode characters) of a Java string.
 */
jsize FNI_GetStringLength(JNIEnv *env, jstring string) {
    jclass strcls = env->FindClass("java/lang/String");
    jmethodID mid = env->GetMethodID(strcls, "length", "()I");
    env->DeleteLocalRef(strcls);
    return env->CallIntMethod(string, mid);
}

/** Returns a pointer to the array of Unicode characters of the string.
 * This pointer is valid until ReleaseStringchars() is called.
 *
 * If isCopy is not NULL, then *isCopy is set to JNI_TRUE if a copy is made;
 * or it is set to JNI_FALSE if no copy is made.
 *
 * Returns a pointer to a Unicode string, or NULL if the operation fails.
 */
const jchar *FNI_GetStringChars(JNIEnv *env, jstring string, jint *length) {
    jclass strcls = env->FindClass("java/lang/String");
    jmethodID mid = env->GetMethodID(strcls, "toCharArray", "()[C");
    auto ca = (jcharArray) env->CallObjectMethod(string, mid);
    jsize len = env->GetArrayLength(ca);
    if (length != nullptr) *length = len;
    auto *result = new jchar [len];
    env->GetCharArrayRegion(ca, 0, len, result);
    env->DeleteLocalRef(strcls);
    env->DeleteLocalRef(ca);
    return result;
}

/** Informs the VM that the native code no longer needs access to chars.
 * The chars argument is a pointer obtained from string using GetStringChars().
 */
void FNI_ReleaseStringChars(const jchar *chars) {
    delete [] chars;
}

jstring FNI_NewString(JNIEnv *env, const jbyte *bytes, int len) {
#ifdef __ANDROID__
    return FNI_NewAndroidString(env, bytes, len);
#else
    return FNI_NewJVMString(env, bytes, len);
#endif
}

jstring FNI_NewAndroidString(JNIEnv *env, const jbyte *bytes, int len) {
    jstring result;
    jclass strcls = env->FindClass("com/zerostudio/idea/treesitter/string/StringDecoder");
    jmethodID newStr = env->GetStaticMethodID(strcls, "fromBytes", "([B)Ljava/lang/String;");
    jbyteArray ba = env->NewByteArray(len);
    env->SetByteArrayRegion(ba, 0, len, bytes);
    result = (jstring) env->CallStaticObjectMethod(strcls, newStr, ba);
    env->DeleteLocalRef(ba);
    env->DeleteLocalRef(strcls);
    return result;
}

bool FNI_GetStandardUtf8(JNIEnv *env, jstring source, std::string &out) {
    if (source == nullptr) {
        throw_npe(env, "String source cannot be null");
        return false;
    }

    const jsize length = env->GetStringLength(source);
    const jchar *chars = env->GetStringChars(source, nullptr);
    if (chars == nullptr) return false;

    out.clear();
    out.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        uint32_t code_point = chars[i];
        if (code_point >= 0xD800 && code_point <= 0xDBFF) {
            if (i + 1 >= length || chars[i + 1] < 0xDC00 || chars[i + 1] > 0xDFFF) {
                env->ReleaseStringChars(source, chars);
                throw_illegal_args(env, "String contains an unpaired UTF-16 high surrogate");
                return false;
            }
            code_point = 0x10000 + ((code_point - 0xD800) << 10) +
                         (static_cast<uint32_t>(chars[++i]) - 0xDC00);
        } else if (code_point >= 0xDC00 && code_point <= 0xDFFF) {
            env->ReleaseStringChars(source, chars);
            throw_illegal_args(env, "String contains an unpaired UTF-16 low surrogate");
            return false;
        }

        if (code_point <= 0x7F) {
            out.push_back(static_cast<char>(code_point));
        } else if (code_point <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
            out.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        } else if (code_point <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
            out.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
            out.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        }
    }

    env->ReleaseStringChars(source, chars);
    return true;
}

jstring FNI_NewStringFromStandardUtf8(JNIEnv *env,
                                      const char *bytes,
                                      uint32_t length) {
    if (bytes == nullptr && length != 0) {
        throw_npe(env, "UTF-8 byte buffer cannot be null when length is non-zero");
        return nullptr;
    }
    std::vector<jchar> utf16;
    utf16.reserve(length);
    uint32_t i = 0;
    while (i < length) {
        const uint8_t first = static_cast<uint8_t>(bytes[i]);
        uint32_t code_point = 0xFFFD;
        uint32_t count = 1;
        if (first <= 0x7F) {
            code_point = first;
        } else if (first >= 0xC2 && first <= 0xDF && i + 1 < length) {
            const uint8_t b1 = static_cast<uint8_t>(bytes[i + 1]);
            if ((b1 & 0xC0) == 0x80) {
                code_point = ((first & 0x1F) << 6) | (b1 & 0x3F);
                count = 2;
            }
        } else if (first >= 0xE0 && first <= 0xEF && i + 2 < length) {
            const uint8_t b1 = static_cast<uint8_t>(bytes[i + 1]);
            const uint8_t b2 = static_cast<uint8_t>(bytes[i + 2]);
            const uint32_t candidate = ((first & 0x0F) << 12) |
                                       ((b1 & 0x3F) << 6) | (b2 & 0x3F);
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 &&
                candidate >= 0x800 && !(candidate >= 0xD800 && candidate <= 0xDFFF)) {
                code_point = candidate;
                count = 3;
            }
        } else if (first >= 0xF0 && first <= 0xF4 && i + 3 < length) {
            const uint8_t b1 = static_cast<uint8_t>(bytes[i + 1]);
            const uint8_t b2 = static_cast<uint8_t>(bytes[i + 2]);
            const uint8_t b3 = static_cast<uint8_t>(bytes[i + 3]);
            const uint32_t candidate = ((first & 0x07) << 18) |
                                       ((b1 & 0x3F) << 12) |
                                       ((b2 & 0x3F) << 6) | (b3 & 0x3F);
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 &&
                (b3 & 0xC0) == 0x80 && candidate >= 0x10000 &&
                candidate <= 0x10FFFF) {
                code_point = candidate;
                count = 4;
            }
        }

        i += count;
        if (code_point <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (code_point >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (code_point & 0x3FF)));
        }
    }

    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw_illegal_args(env, "UTF-8 string exceeds the Java String length limit");
        return nullptr;
    }
    static const jchar empty_string_storage = 0;
    const jchar *string_data = utf16.empty() ? &empty_string_storage : utf16.data();
    return env->NewString(string_data, static_cast<jsize>(utf16.size()));
}

