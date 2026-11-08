/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <utility>
#include <vector>

#include "tree_sitter/api.h"
#include "utils/ts_obj_utils.h"
#include "utils/ts_preconditions.h"
#include "ts_tags.h"
#include "ts_highlight.h"

namespace {

void throwTagsIllegalArgument(JNIEnv *env, const char *message) {
  jclass type = env->FindClass("java/lang/IllegalArgumentException");
  if (type) {
    env->ThrowNew(type, message);
    env->DeleteLocalRef(type);
  }
}

void throwTagsIllegalState(JNIEnv *env, const char *message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type) {
    env->ThrowNew(type, message);
    env->DeleteLocalRef(type);
  }
}

void throwTagsOutOfMemory(JNIEnv *env) {
  jclass type = env->FindClass("java/lang/OutOfMemoryError");
  if (type) {
    env->ThrowNew(type, "Unable to allocate native Tree-sitter query results");
    env->DeleteLocalRef(type);
  }
}

struct OwnedQueryMatch {
  uint32_t id = 0;
  uint16_t pattern_index = 0;
  std::vector<TSQueryCapture> captures;
};

#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
struct TagsProgressBudget {
  uint32_t callbacks = 0;
  static constexpr uint32_t kMaxCallbacks = 100000;
  bool cancelled = false;
};

bool tagsProgressCallback(TSQueryCursorState *state) {
  if (!state || !state->payload) return true;
  auto *budget = static_cast<TagsProgressBudget *>(state->payload);
  if (++budget->callbacks >= TagsProgressBudget::kMaxCallbacks) {
    budget->cancelled = true;
    return true;
  }
  return false;
}
#endif

} // namespace

static jobjectArray TSQuery_extractBounded(
    JNIEnv *env, jclass,
    jlong query_pointer, jobject root_object,
    jlong start_byte, jlong end_byte,
    jint max_results, jint max_pending_matches) {
  if (query_pointer == 0 || root_object == nullptr) {
    throwTagsIllegalArgument(env, "query and root must be non-null");
    return nullptr;
  }
  constexpr jlong kMaxTreeSitterByteOffset =
      static_cast<jlong>(std::numeric_limits<uint32_t>::max());
  if (start_byte < 0 || end_byte < start_byte ||
      end_byte > kMaxTreeSitterByteOffset) {
    throwTagsIllegalArgument(
        env, "Invalid absolute byte range: Tree-sitter byte offsets are uint32");
    return nullptr;
  }
  if (max_results < 1 || max_results > 1000000 ||
      max_pending_matches < 1 || max_pending_matches > 1000000) {
    throwTagsIllegalArgument(env, "Tree-sitter query limits must be between 1 and 1000000");
    return nullptr;
  }

  const auto start = static_cast<uint32_t>(start_byte);
  const auto end = static_cast<uint32_t>(end_byte);
  const TSNode root = _unmarshalNode(env, root_object);
  if (env->ExceptionCheck()) return nullptr;
  if (ts_node_is_null(root) || ts_node_has_changes(root)) {
    throwTagsIllegalArgument(env, "Cannot query a null or changed syntax node");
    return nullptr;
  }
  if (start < ts_node_start_byte(root) || end > ts_node_end_byte(root)) {
    throwTagsIllegalArgument(env, "Tree-sitter query range must be inside the root node");
    return nullptr;
  }

#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  // The options payload must outlive the cursor because Tree-sitter retains
  // the options pointer until the cursor is destroyed or executed again.
  TagsProgressBudget progress_budget;
  TSQueryCursorOptions query_options{};
  query_options.payload = &progress_budget;
  query_options.progress_callback = tagsProgressCallback;
#endif
  std::unique_ptr<TSQueryCursor, decltype(&ts_query_cursor_delete)> cursor(
      ts_query_cursor_new(), &ts_query_cursor_delete);
  if (!cursor) {
    throwTagsOutOfMemory(env);
    return nullptr;
  }

  ts_query_cursor_set_byte_range(cursor.get(), start, end);
  ts_query_cursor_set_match_limit(
      cursor.get(), static_cast<uint32_t>(max_pending_matches));
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  ts_query_cursor_exec_with_options(
      cursor.get(), reinterpret_cast<const TSQuery *>(query_pointer), root,
      &query_options);
#else
  ts_query_cursor_exec(
      cursor.get(), reinterpret_cast<const TSQuery *>(query_pointer), root);
#endif

  std::vector<OwnedQueryMatch> matches;
  try {
    matches.reserve(static_cast<size_t>(std::min(max_results, 4096)));
    TSQueryMatch match{};
    while (ts_query_cursor_next_match(cursor.get(), &match)) {
      if (matches.size() >= static_cast<size_t>(max_results)) {
        throwTagsIllegalState(env, "Tree-sitter query exceeded maxResults; no partial result was returned");
        return nullptr;
      }
      OwnedQueryMatch owned;
      owned.id = match.id;
      owned.pattern_index = match.pattern_index;
      if (match.capture_count > 0) {
        owned.captures.assign(
            match.captures, match.captures + match.capture_count);
      }
      matches.push_back(std::move(owned));
    }
  } catch (const std::bad_alloc &) {
    throwTagsOutOfMemory(env);
    return nullptr;
  }

#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  if (progress_budget.cancelled) {
    throwTagsIllegalState(env,
        "Tree-sitter query exceeded its native work budget; no partial result was returned");
    return nullptr;
  }
#endif
  if (ts_query_cursor_did_exceed_match_limit(cursor.get())) {
    throwTagsIllegalState(env,
        "Tree-sitter query exceeded maxPendingMatches; no partial result was returned");
    return nullptr;
  }

  jclass match_class = env->FindClass(
      "com/zerostudio/idea/treesitter/TSQueryMatch");
  if (!match_class) return nullptr;
  jobjectArray result = env->NewObjectArray(
      static_cast<jsize>(matches.size()), match_class, nullptr);
  env->DeleteLocalRef(match_class);
  if (!result) return nullptr;

  for (jsize i = 0; i < static_cast<jsize>(matches.size()); ++i) {
    if (env->PushLocalFrame(32) != JNI_OK) return nullptr;
    const auto &owned = matches[static_cast<size_t>(i)];
    TSQueryMatch native_match{};
    native_match.id = owned.id;
    native_match.pattern_index = owned.pattern_index;
    native_match.capture_count = static_cast<uint32_t>(owned.captures.size());
    native_match.captures = owned.captures.empty()
        ? nullptr : const_cast<TSQueryCapture *>(owned.captures.data());
    jobject java_match = _marshalMatch(env, native_match);
    if (java_match && !env->ExceptionCheck()) {
      env->SetObjectArrayElement(result, i, java_match);
    }
    env->PopLocalFrame(nullptr);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

static jobjectArray TSTags_extract(
    JNIEnv *env, jclass,
    jlong query_pointer, jobject root_object,
    jlong start_byte, jlong end_byte,
    jint max_results, jint max_pending_matches) {
  return TSQuery_extractBounded(env, nullptr, query_pointer, root_object,
                                start_byte, end_byte, max_results,
                                max_pending_matches);
}

static jobjectArray TSHighlight_extract(
    JNIEnv *env, jclass,
    jlong query_pointer, jobject root_object,
    jlong start_byte, jlong end_byte,
    jint max_results, jint max_pending_matches) {
  // Highlight queries use the exact same bounded Tree-sitter cursor executor
  // as tags. The returned captures retain their original capture and pattern
  // indices; style resolution remains a TSH-layer concern.
  return TSQuery_extractBounded(env, nullptr, query_pointer, root_object,
                        start_byte, end_byte, max_results,
                        max_pending_matches);
}

void TSTags_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, TSTags_Native_extract, TSTags_extract);
}

void TSHighlight_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, TSHighlight_Native_extract, TSHighlight_extract);
}
