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

#include <iostream>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>

#include "utils/ts_obj_utils.h"
#include "utils/ts_preconditions.h"

#include "ts_query_cursor.h"

namespace {

#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
struct QueryProgressCallbackState {
  jobject callback = nullptr;
  jmethodID method = nullptr;
  JNIEnv *active_env = nullptr;
  TSQueryCursorOptions options{};
};

std::mutex g_query_progress_lock;
// The map owns each state pointer. Raw pointers avoid losing the JNI global
// reference if unordered_map allocation throws while moving a unique_ptr.
std::unordered_map<TSQueryCursor *, QueryProgressCallbackState *>
    g_query_progress_callbacks;

bool queryProgressCallback(TSQueryCursorState *state) {
  if (!state || !state->payload) return true;
  auto *callback_state = static_cast<QueryProgressCallbackState *>(state->payload);
  JNIEnv *env = callback_state->active_env;
  if (!env || !callback_state->callback || !callback_state->method) return true;
  const jboolean cancel = env->CallBooleanMethod(
      callback_state->callback, callback_state->method,
      static_cast<jlong>(state->current_byte_offset));
  // Preserve a Java exception and stop native work. JNI will propagate the
  // pending exception when nextMatch returns to Java.
  return env->ExceptionCheck() || cancel == JNI_TRUE;
}

void releaseQueryProgressCallback(JNIEnv *env, TSQueryCursor *cursor) {
  QueryProgressCallbackState *state = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_query_progress_lock);
    auto found = g_query_progress_callbacks.find(cursor);
    if (found == g_query_progress_callbacks.end()) return;
    state = found->second;
    g_query_progress_callbacks.erase(found);
  }
  if (state->callback) env->DeleteGlobalRef(state->callback);
  delete state;
}
#endif

void throwUnsupportedOperation(JNIEnv *env, const char *message) {
  jclass exception_class = env->FindClass("java/lang/UnsupportedOperationException");
  if (exception_class) {
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
  }
}

void throwOutOfMemory(JNIEnv *env, const char *message) {
  jclass exception_class = env->FindClass("java/lang/OutOfMemoryError");
  if (exception_class) {
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
  }
}

void throwIllegalState(JNIEnv *env, const char *message) {
  jclass exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class) {
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
  }
}

} // namespace

static jlong TSQueryCursor_newCursor(JNIEnv *env, jclass self) {
  return (jlong) ts_query_cursor_new();
}

static void TSQueryCursor_delete(JNIEnv *env, jclass self, jlong cursor) {
  req_nnp(env, cursor);
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  releaseQueryProgressCallback(env, (TSQueryCursor *) cursor);
#endif
  ts_query_cursor_delete((TSQueryCursor *) cursor);
}

static void TSQueryCursor_exec(JNIEnv *env,
                               jclass self,
                               jlong cursor,
                               jlong query,
                               jobject node) {
  req_nnp(env, cursor);
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  // A regular exec resets Tree-sitter's retained options pointer.
  releaseQueryProgressCallback(env, (TSQueryCursor *) cursor);
#endif
  ts_query_cursor_exec((TSQueryCursor *) cursor,
                       (TSQuery *) query,
                       _unmarshalNode(env, node));
}

static void TSQueryCursor_execWithOptions(JNIEnv *env,
                                          jclass self,
                                          jlong cursor,
                                          jlong query,
                                          jobject node,
                                          jobject callback) {
  req_nnp(env, cursor);
  req_nnp(env, query);
  req_nnp(env, callback);
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  TSQueryCursor *native_cursor = (TSQueryCursor *) cursor;
  // Resolve the Java node before installing callback state. If node conversion
  // raises, the cursor must not retain an options pointer for a query that
  // was never started.
  TSNode native_node = _unmarshalNode(env, node);
  if (env->ExceptionCheck()) return;

  releaseQueryProgressCallback(env, native_cursor);

  std::unique_ptr<QueryProgressCallbackState> state(
      new (std::nothrow) QueryProgressCallbackState());
  if (!state) {
    throwOutOfMemory(env, "Unable to allocate query progress callback state");
    return;
  }
  state->callback = env->NewGlobalRef(callback);
  if (!state->callback) return;
  jclass callback_class = env->GetObjectClass(callback);
  if (!callback_class) {
    env->DeleteGlobalRef(state->callback);
    return;
  }
  state->method = env->GetMethodID(callback_class, "onProgress", "(J)Z");
  env->DeleteLocalRef(callback_class);
  if (!state->method) {
    env->DeleteGlobalRef(state->callback);
    return;
  }

  state->options.payload = state.get();
  state->options.progress_callback = queryProgressCallback;
  QueryProgressCallbackState *callback_state = state.get();
  try {
    std::lock_guard<std::mutex> lock(g_query_progress_lock);
    const auto inserted = g_query_progress_callbacks.emplace(native_cursor, callback_state);
    if (!inserted.second) {
      env->DeleteGlobalRef(callback_state->callback);
      throwIllegalState(env, "A query is already active on this cursor");
      return;
    }
    state.release();
  } catch (const std::bad_alloc &) {
    // Ownership is released only after successful map insertion.
    env->DeleteGlobalRef(callback_state->callback);
    throwOutOfMemory(env, "Unable to allocate query progress callback state");
    return;
  }
  ts_query_cursor_exec_with_options(
      native_cursor, (TSQuery *) query, native_node, &callback_state->options);
#else
  (void) self;
  (void) node;
  throwUnsupportedOperation(
      env, "Query progress callbacks require Tree-sitter 0.28 or newer");
#endif
}

static jboolean
TSQueryCursor_exceededMatchLimit(JNIEnv *env, jclass self, jlong cursor) {
  req_nnp(env, cursor);
  return (jboolean) ts_query_cursor_did_exceed_match_limit((TSQueryCursor *) cursor);
}

static void TSQueryCursor_setMatchLimit(JNIEnv *env,
                                        jclass self,
                                        jlong cursor,
                                        jint newLimit) {
  req_nnp(env, cursor);
  ts_query_cursor_set_match_limit((TSQueryCursor *) cursor, newLimit);
}

static jint
TSQueryCursor_getMatchLimit(JNIEnv *env, jclass self, jlong cursor) {
  req_nnp(env, cursor);
  return (jint) ts_query_cursor_match_limit((TSQueryCursor *) cursor);
}

static void TSQueryCursor_setByteRange(JNIEnv *env,
                                       jclass self,
                                       jlong cursor,
                                       jint start,
                                       jint end) {
  req_nnp(env, cursor);
  ts_query_cursor_set_byte_range((TSQueryCursor *) cursor, start, end);
}

static void TSQueryCursor_setPointRange(JNIEnv *env,
                                        jclass self,
                                        jlong cursor,
                                        jobject start,
                                        jobject end) {
  req_nnp(env, cursor);
  ts_query_cursor_set_point_range((TSQueryCursor *) cursor,
                                  _unmarshalPoint(env, start),
                                  _unmarshalPoint(env, end));
}

static jobject TSQueryCursor_nextMatch(JNIEnv *env, jclass self, jlong cursor) {
  req_nnp(env, cursor);
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  QueryProgressCallbackState *callback_state = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_query_progress_lock);
    auto found = g_query_progress_callbacks.find((TSQueryCursor *) cursor);
    if (found != g_query_progress_callbacks.end()) {
      callback_state = found->second;
      callback_state->active_env = env;
    }
  }
#endif
  TSQueryMatch m;
  bool b = ts_query_cursor_next_match((TSQueryCursor *) cursor, &m);
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
  if (callback_state) callback_state->active_env = nullptr;
#endif
  if (env->ExceptionCheck() || !b) return nullptr;
  return _marshalMatch(env, m);
}

static void
TSQueryCursor_removeMatch(JNIEnv *env, jclass self, jlong cursor, jint id) {
  req_nnp(env, cursor);
  ts_query_cursor_remove_match((TSQueryCursor *) cursor, id);
}

void TSQueryCursor_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, TSQueryCursor_Native_newCursor, TSQueryCursor_newCursor);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_delete, TSQueryCursor_delete);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_exec, TSQueryCursor_exec);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_execWithOptions,
                 TSQueryCursor_execWithOptions);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_exceededMatchLimit,
                 TSQueryCursor_exceededMatchLimit);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_setMatchLimit,
                 TSQueryCursor_setMatchLimit);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_getMatchLimit,
                 TSQueryCursor_getMatchLimit);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_setByteRange, TSQueryCursor_setByteRange);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_setPointRange,
                 TSQueryCursor_setPointRange);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_nextMatch, TSQueryCursor_nextMatch);
  SET_JNI_METHOD(methods, TSQueryCursor_Native_removeMatch, TSQueryCursor_removeMatch);
}