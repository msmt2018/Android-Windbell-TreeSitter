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

#include "utils/ts_exceptions.h"
#include "utils/ts_obj_utils.h"
#include "utils/ts_preconditions.h"

#include "ts_query.h"

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

void fillQuery(JNIEnv *, jobject, uint32_t, TSQueryError);
int query_quantifier_id(JNIEnv *env, TSQuantifier quantifier);
jint getErrorType(TSQueryError);

/** Converts Java UTF-16 code units to standard UTF-8 (not JNI Modified UTF-8). */
static bool append_standard_utf8(JNIEnv *env, std::string &out, jstring source) {
  const jsize length = env->GetStringLength(source);
  const jchar *chars = env->GetStringChars(source, nullptr);
  if (chars == nullptr) {
    return false; // JVM exception is pending.
  }

  out.reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t code_point = chars[i];
    if (code_point >= 0xD800 && code_point <= 0xDBFF) {
      if (i + 1 >= length || chars[i + 1] < 0xDC00 || chars[i + 1] > 0xDFFF) {
        env->ReleaseStringChars(source, chars);
        throw_illegal_args(env, "Query source contains an unpaired UTF-16 high surrogate");
        return false;
      }
      code_point = 0x10000 + ((code_point - 0xD800) << 10) +
                   (static_cast<uint32_t>(chars[++i]) - 0xDC00);
    } else if (code_point >= 0xDC00 && code_point <= 0xDFFF) {
      env->ReleaseStringChars(source, chars);
      throw_illegal_args(env, "Query source contains an unpaired UTF-16 low surrogate");
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

/** Creates a Java String from standard UTF-8 bytes returned by Tree-sitter. */
static jstring new_string_from_standard_utf8(JNIEnv *env,
                                             const char *bytes,
                                             uint32_t length) {
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
    throw_illegal_args(env, "Tree-sitter UTF-8 string exceeds the Java String length limit");
    return nullptr;
  }
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

static jlong TSQuery_newQuery(JNIEnv *env,
                              jclass self,
                              jobject queryObject,
                              jlong language,
                              jstring source) {
  req_nnp(env, language);
  req_nnp(env, source, "Query source");

  std::string source_utf8;
  if (!append_standard_utf8(env, source_utf8, source)) {
    return 0;
  }
  if (source_utf8.size() > std::numeric_limits<uint32_t>::max()) {
    throw_illegal_args(env, "Query source exceeds Tree-sitter's byte length limit");
    return 0;
  }

  uint32_t error_offset = 0;
  TSQueryError error_type = TSQueryErrorNone;
  TSQuery *query = ts_query_new((TSLanguage *) language,
                                source_utf8.data(),
                                static_cast<uint32_t>(source_utf8.size()),
                                &error_offset,
                                &error_type);
  fillQuery(env, queryObject, error_offset, error_type);
  return (jlong) query;
}

static void TSQuery_delete(JNIEnv *env, jclass self, jlong query) {
  req_nnp(env, query);
  ts_query_delete((TSQuery *) query);
}

static jint TSQuery_captureCount(JNIEnv *env, jclass self, jlong query) {
  req_nnp(env, query);
  return (jint) ts_query_capture_count((TSQuery *) query);
}

static jint TSQuery_patternCount(JNIEnv *env, jclass self, jlong query) {
  req_nnp(env, query);
  return (jint) ts_query_pattern_count((TSQuery *) query);
}

static jint TSQuery_stringCount(JNIEnv *env, jclass self, jlong query) {
  req_nnp(env, query);
  return (jint) ts_query_string_count((TSQuery *) query);
}

static jint TSQuery_startByteForPattern(JNIEnv *env,
                                        jclass self,
                                        jlong query,
                                        jint pattern) {
  req_nnp(env, query);
  return (jint) ts_query_start_byte_for_pattern((TSQuery *) query, pattern);
}

static jobjectArray TSQuery_predicatesForPattern(JNIEnv *env,
                                                 jclass self,
                                                 jlong query,
                                                 jint pattern) {
  req_nnp(env, query);

  uint32_t count;
  const TSQueryPredicateStep *predicates =
      ts_query_predicates_for_pattern((TSQuery *) query, pattern, &count);
  jobjectArray result = createQueryPredicateStepArr(env, (jint) count);
  req_nnp(env, result, "TSQueryPredicateStep[] from factory");

  for (uint32_t i = 0; i < count; i++) {
    const TSQueryPredicateStep *predicate = (predicates + i);
    jobject obj = _marshalQueryPredicateStep(env, predicate);
    env->SetObjectArrayElement(result, i, obj);
  }

  return result;
}

static jboolean
TSQuery_patternRooted(JNIEnv *env, jclass self, jlong query, jint pattern) {
  req_nnp(env, query);
  return (jboolean) ts_query_is_pattern_rooted((TSQuery *) query, pattern);
}

static jboolean TSQuery_patternNonLocal(JNIEnv *env,
                                        jclass self,
                                        jlong query,
                                        jint pattern_index) {
  req_nnp(env, query);
  return (jboolean) ts_query_is_pattern_non_local((TSQuery *) query,
                                                  pattern_index);
}

static jboolean TSQuery_patternGuaranteedAtStep(JNIEnv *env,
                                                jclass self,
                                                jlong query,
                                                jint offset) {
  req_nnp(env, query);
  return (jboolean) ts_query_is_pattern_guaranteed_at_step((TSQuery *) query,
                                                           offset);
}

static jstring
TSQuery_captureNameForId(JNIEnv *env, jclass self, jlong query, jint id) {
  req_nnp(env, query);
  uint32_t count;
  const char
      *name = ts_query_capture_name_for_id((TSQuery *) query, id, &count);
  return new_string_from_standard_utf8(env, name, count);
}

static jstring
TSQuery_stringValueForId(JNIEnv *env, jclass self, jlong query, jint id) {
  req_nnp(env, query);
  uint32_t count;
  const char *str = ts_query_string_value_for_id((TSQuery *) query, id, &count);
  return new_string_from_standard_utf8(env, str, count);
}

void fillQuery(JNIEnv *env,
               jobject query,
               uint32_t error_offset,
               TSQueryError error_type) {
  req_nnp(env, query);
  jclass klass = env->GetObjectClass(query);
  jfieldID offsetField = env->GetFieldID(klass, "errorOffset", "I");
  jfieldID errorTypeField = env->GetFieldID(klass, "errorType", "I");
  env->SetIntField(query, offsetField, (jint) error_offset);
  env->SetIntField(query, errorTypeField, getErrorType(error_type));
}

jint getErrorType(TSQueryError error) {
  switch (error) {
    default:
    case TSQueryErrorNone:
      return 0;
    case TSQueryErrorSyntax:
      return 1;
    case TSQueryErrorNodeType:
      return 2;
    case TSQueryErrorField:
      return 3;
    case TSQueryErrorCapture:
      return 4;
    case TSQueryErrorStructure:
      return 5;
    case TSQueryErrorLanguage:
      return 6;
  }
}
static jint TSQuery_captureQuantifierForId(JNIEnv *env,
                                           jclass clazz,
                                           jlong query,
                                           jint pattern,
                                           jint capture) {
  req_nnp(env, query);
  auto ts_query = (TSQuery *) query;
  auto quantifier =
      ts_query_capture_quantifier_for_id(ts_query, pattern, capture);
  return query_quantifier_id(env, quantifier);
}

int query_quantifier_id(JNIEnv *env, TSQuantifier quantifier) {
  switch (quantifier) {
    case TSQuantifierZero:
      return 0;
    case TSQuantifierZeroOrOne:
      return 1;
    case TSQuantifierZeroOrMore:
      return 2;
    case TSQuantifierOne:
      return 3;
    case TSQuantifierOneOrMore:
      return 4;
  }

  throw_illegal_args(env, "Unknown quantifier");
  return -1;
}

void TSQuery_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, TSQuery_Native_newQuery, TSQuery_newQuery);
  SET_JNI_METHOD(methods, TSQuery_Native_delete, TSQuery_delete);
  SET_JNI_METHOD(methods, TSQuery_Native_captureCount, TSQuery_captureCount);
  SET_JNI_METHOD(methods, TSQuery_Native_patternCount, TSQuery_patternCount);
  SET_JNI_METHOD(methods, TSQuery_Native_stringCount, TSQuery_stringCount);
  SET_JNI_METHOD(methods, TSQuery_Native_startByteForPattern,
                 TSQuery_startByteForPattern);
  SET_JNI_METHOD(methods, TSQuery_Native_predicatesForPattern,
                 TSQuery_predicatesForPattern);
  SET_JNI_METHOD(methods, TSQuery_Native_patternRooted, TSQuery_patternRooted);
  SET_JNI_METHOD(methods, TSQuery_Native_patternNonLocal, TSQuery_patternNonLocal);
  SET_JNI_METHOD(methods, TSQuery_Native_patternGuaranteedAtStep,
                 TSQuery_patternGuaranteedAtStep);
  SET_JNI_METHOD(methods, TSQuery_Native_captureNameForId, TSQuery_captureNameForId);
  SET_JNI_METHOD(methods, TSQuery_Native_stringValueForId, TSQuery_stringValueForId);
  SET_JNI_METHOD(methods, TSQuery_Native_captureQuantifierForId,
                 TSQuery_captureQuantifierForId);
}