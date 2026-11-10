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

#include <atomic>
#include <chrono>
#include <iostream>
#include <vector>
#include <new>
#include <limits>
#include <memory>

#include "utf16str/UTF16String.h"
#include "utils/ts_obj_utils.h"
#include "utils/ts_exceptions.h"
#include "utils/ts_preconditions.h"
#include "ts__log.h"

#include "ts_parser.h"

/**
 * `TSParserInternal` stores the actual tree sitter parser instance along
 * with the cancellation flag and the cancellation flag mutex.
 */
class TSParserInternal {
 public:
  TSParserInternal() : parser(ts_parser_new()) {}

  ~TSParserInternal() {
    ts_parser_delete(parser);
    parser = nullptr;
  }

  TSParser *getParser(JNIEnv *env) {
    if (check_destroyed(env)) {
      return nullptr;
    }
    return parser;
  }

  bool beginRound(JNIEnv *env) {
    bool expected = false;
    if (!parsing.compare_exchange_strong(expected, true)) {
      throw_illegal_state(env, "Parser is already parsing another syntax tree!");
      return false;
    }
    cancellationRequested.store(false);
    return true;
  }

  void endRound() {
    parsing.store(false);
    cancellationRequested.store(false);
  }

  bool requestCancellation() {
    if (!parsing.load()) {
      return false;
    }
    cancellationRequested.store(true);
    return true;
  }

  void setTimeoutMicros(uint64_t timeout) {
    timeoutMicros.store(timeout);
  }

  uint64_t getTimeoutMicros() const {
    return timeoutMicros.load();
  }

  bool shouldStop() const {
    if (cancellationRequested.load()) {
      return true;
    }
    const auto timeout = timeoutMicros.load();
    return timeout != 0 &&
      std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - parseStart).count() >= timeout;
  }

  void markParseStart() {
    parseStart = std::chrono::steady_clock::now();
  }

 private:
  TSParser *parser;
  std::atomic_bool parsing{false};
  std::atomic_bool cancellationRequested{false};
  std::atomic_uint64_t timeoutMicros{0};
  std::chrono::steady_clock::time_point parseStart{};

  bool check_destroyed(JNIEnv *env) const {
    if (parser == nullptr) {
      throw_illegal_state(env, "TSParserInternal has already been destroyed");
      return true;
    }
    return false;
  }
};

static bool TSParser_progressCallback(TSParseState *state) {
  auto *parser = static_cast<TSParserInternal *>(state->payload);
  return parser == nullptr || parser->shouldStop();
}

struct TSParserInput {
  const char *bytes;
  uint32_t length;
};

static const char *TSParser_readInput(
    void *payload,
    uint32_t byte_index,
    TSPoint position,
    uint32_t *bytes_read) {
  auto *input = static_cast<TSParserInput *>(payload);
  if (byte_index >= input->length) {
    *bytes_read = 0;
    return nullptr;
  }

  *bytes_read = input->length - byte_index;
  return input->bytes + byte_index;
}

static jlong
TSParser_newParser(JNIEnv *env,
                   jclass self) {
  auto parser = new TSParserInternal;
  return (jlong) parser;
}

static void
TSParser_delete(JNIEnv *env,
                jclass self,
                jlong parser_ptr) {
  req_nnp(env, parser_ptr);

  auto parser = (TSParserInternal *) parser_ptr;
  delete parser;
}

static void
TSParser_setLanguage(JNIEnv *env,
                     jclass self,
                     jlong parser,
                     jlong language) {
  req_nnp(env, parser, "parser");
  req_nnp(env, language, "language");
  ts_parser_set_language(((TSParserInternal *) parser)->getParser(env),
                         (TSLanguage *) language);
}

static jlong
TSParser_getLanguage(JNIEnv *env,
                     jclass self,
                     jlong parser) {
  req_nnp(env, parser);
  return (jlong) ts_parser_language(((TSParserInternal *) parser)->getParser(env));
}

static void
TSParser_reset(JNIEnv *env,
               jclass self,
               jlong parser) {
  req_nnp(env, parser);
  TSParser *pParser = ((TSParserInternal *) (parser))->getParser(env);
  LOGD("ts_parser.cc", "Reset parser: %p, language: %p", pParser,
       ts_parser_language(pParser));
  ts_parser_reset(pParser);
}

static void
TSParser_setTimeout(JNIEnv *env,
                    jclass self,
                    jlong parser,
                    jlong macros) {
  req_nnp(env, parser);
  ((TSParserInternal *) parser)->setTimeoutMicros(static_cast<uint64_t>(macros));
}

static jlong
TSParser_getTimeout(JNIEnv *env,
                    jclass self,
                    jlong parser) {
  req_nnp(env, parser);
  return static_cast<jlong>(
      ((TSParserInternal *) parser)->getTimeoutMicros());
}

static jboolean
TSParser_setIncludedRanges(
    JNIEnv *env,
    jclass self,
    jlong parser,
    jobjectArray ranges) {
  if (parser == 0) {
    throw_npe(env, "parser == nullptr");
    return JNI_FALSE;
  }
  if (ranges == nullptr) {
    throw_npe(env, "ranges == nullptr");
    return JNI_FALSE;
  }

  auto *internal = reinterpret_cast<TSParserInternal *>(parser);
  TSParser *native_parser = internal->getParser(env);
  if (native_parser == nullptr || env->ExceptionCheck()) {
    return JNI_FALSE;
  }

  const jsize count = env->GetArrayLength(ranges);
  if (env->ExceptionCheck()) {
    return JNI_FALSE;
  }

  // The number of ranges is controlled by the Java caller. Do not allocate a
  // variable-length array on the native stack: large arrays can exhaust the
  // thread stack, and VLAs are not part of standard C++.
  std::vector<TSRange> native_ranges;
  try {
    native_ranges.resize(static_cast<size_t>(count));
  } catch (const std::bad_alloc &) {
    throw_exception(env, "java/lang/OutOfMemoryError",
                    "Unable to allocate Tree-sitter included ranges");
    return JNI_FALSE;
  }

  for (jsize i = 0; i < count; ++i) {
    jobject range = env->GetObjectArrayElement(ranges, i);
    if (env->ExceptionCheck()) {
      if (range != nullptr) env->DeleteLocalRef(range);
      return JNI_FALSE;
    }
    if (range == nullptr) {
      const std::string error = "ranges[" + std::to_string(i) + "] == nullptr";
      throw_npe(env, error.c_str());
      return JNI_FALSE;
    }

    native_ranges[static_cast<size_t>(i)] = _unmarshalRange(env, range);
    env->DeleteLocalRef(range);
    if (env->ExceptionCheck()) {
      return JNI_FALSE;
    }

    // This binding always supplies TSInputEncodingUTF16LE. Tree-sitter byte
    // ranges therefore must begin and end on a two-byte UTF-16 code-unit
    // boundary. Do not let the runtime silently reinterpret an odd offset.
    const TSRange &native_range = native_ranges[static_cast<size_t>(i)];
    if ((native_range.start_byte & 1u) != 0 ||
        (native_range.end_byte & 1u) != 0) {
      const std::string error = "ranges[" + std::to_string(i) +
          "] has a byte offset in the middle of a UTF-16 code unit";
      throw_illegal_args(env, error.c_str());
      return JNI_FALSE;
    }
  }

  const TSRange *range_data = native_ranges.empty() ? nullptr : native_ranges.data();
  return static_cast<jboolean>(ts_parser_set_included_ranges(
      native_parser, range_data, static_cast<uint32_t>(native_ranges.size())));
}

static jobjectArray
TSParser_getIncludedRanges(
    JNIEnv *env,
    jclass self,
    jlong parser) {
  if (parser == 0) {
    throw_npe(env, "parser == nullptr");
    return nullptr;
  }

  auto *internal = reinterpret_cast<TSParserInternal *>(parser);
  TSParser *native_parser = internal->getParser(env);
  if (native_parser == nullptr || env->ExceptionCheck()) {
    return nullptr;
  }

  uint32_t count = 0;
  const TSRange *ranges = ts_parser_included_ranges(native_parser, &count);
  if (env->ExceptionCheck()) {
    return nullptr;
  }
  if (count > static_cast<uint32_t>(std::numeric_limits<jint>::max())) {
    throw_illegal_state(env, "Tree-sitter included range count exceeds Java array limits");
    return nullptr;
  }

  jobjectArray result = createRangeArr(env, static_cast<jint>(count));
  if (result == nullptr || env->ExceptionCheck()) {
    return nullptr;
  }

  for (uint32_t i = 0; i < count; ++i) {
    jobject range = _marshalRange(env, ranges[i]);
    if (range == nullptr || env->ExceptionCheck()) {
      if (range != nullptr) env->DeleteLocalRef(range);
      return nullptr;
    }
    env->SetObjectArrayElement(result, static_cast<jsize>(i), range);
    env->DeleteLocalRef(range);
    if (env->ExceptionCheck()) {
      return nullptr;
    }
  }
  return result;
}

static jlong TSParser_parse(JNIEnv *env,
                            jclass clazz,
                            jlong parser,
                            jlong tree_pointer,
                            jlong str_pointer) {
  if (parser == 0) {
    throw_npe(env, "parser == nullptr");
    return 0;
  }
  if (str_pointer == 0) {
    throw_npe(env, "string == nullptr");
    return 0;
  }

  auto *ts_parser_internal = reinterpret_cast<TSParserInternal *>(parser);
  TSParser *ts_parser = ts_parser_internal->getParser(env);
  if (ts_parser == nullptr || env->ExceptionCheck()) {
    return 0;
  }

  TSTree *old_tree = tree_pointer == 0
      ? nullptr
      : reinterpret_cast<TSTree *>(tree_pointer);
  auto *source = as_str(env, str_pointer);
  if (source == nullptr || env->ExceptionCheck()) {
    return 0;
  }

  // UTF16String exposes a signed jint byte length. Reject an overflowed or
  // malformed length before narrowing it to Tree-sitter's uint32 byte ABI.
  const jint source_byte_length = source->byte_length();
  if (source_byte_length < 0 || (source_byte_length & 1) != 0) {
    throw_illegal_args(env,
        "UTF-16LE source byte length must be non-negative and even");
    return 0;
  }
  if (static_cast<uint64_t>(source_byte_length) >
      static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())) {
    throw_illegal_args(env, "Source exceeds Tree-sitter's uint32 byte-offset limit");
    return 0;
  }

  // Keep the existing owned snapshot copy. UTF16String is mutable and does not
  // expose a read lease, so borrowing its vector storage here would permit a
  // concurrent mutation to invalidate the pointer during parsing.
  std::unique_ptr<const char[]> source_bytes;
  try {
    source_bytes.reset(source->to_cstring());
  } catch (const std::length_error &error) {
    throw_illegal_args(env, error.what());
    return 0;
  } catch (const std::bad_alloc &) {
    throw_exception(env, "java/lang/OutOfMemoryError",
                    "Unable to copy UTF-16LE parser input");
    return 0;
  }

  if (!ts_parser_internal->beginRound(env)) {
    return 0;
  }

  struct ParseRoundGuard {
    TSParserInternal *parser;
    ~ParseRoundGuard() {
      if (parser != nullptr) parser->endRound();
    }
  } round_guard{ts_parser_internal};

  TSParserInput inputPayload{
      source_bytes.get(),
      static_cast<uint32_t>(source_byte_length),
  };
  TSInput input{
      &inputPayload,
      TSParser_readInput,
      TSInputEncodingUTF16LE,
      nullptr,
  };
  TSParseOptions options{
      ts_parser_internal,
      TSParser_progressCallback,
  };

  ts_parser_internal->markParseStart();
  TSTree *tree = ts_parser_parse_with_options(ts_parser, old_tree, input, options);
  return reinterpret_cast<jlong>(tree);
}

static jboolean
TSParser_requestCancellation(
    JNIEnv *env,
    jclass clazz,
    jlong parser) {

  req_nnp(env, parser);
  const bool requested = ((TSParserInternal *) parser)->requestCancellation();
  if (requested) {
    LOGD("TSParser", "Cancellation has been requested");
  }
  return static_cast<jboolean>(requested);
}

void TSParser_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, TSParser_Native_newParser, TSParser_newParser);
  SET_JNI_METHOD(methods, TSParser_Native_delete, TSParser_delete);
  SET_JNI_METHOD(methods, TSParser_Native_setLanguage, TSParser_setLanguage);
  SET_JNI_METHOD(methods, TSParser_Native_getLanguage, TSParser_getLanguage);
  SET_JNI_METHOD(methods, TSParser_Native_reset, TSParser_reset);
  SET_JNI_METHOD(methods, TSParser_Native_setTimeout, TSParser_setTimeout);
  SET_JNI_METHOD(methods, TSParser_Native_getTimeout, TSParser_getTimeout);
  SET_JNI_METHOD(methods, TSParser_Native_setIncludedRanges, TSParser_setIncludedRanges);
  SET_JNI_METHOD(methods, TSParser_Native_getIncludedRanges, TSParser_getIncludedRanges);
  SET_JNI_METHOD(methods, TSParser_Native_parse, TSParser_parse);
  SET_JNI_METHOD(methods, TSParser_Native_requestCancellation,
                 TSParser_requestCancellation);
}