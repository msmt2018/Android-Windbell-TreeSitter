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
#include <utility>

#include "utf16str/UTF16String.h"
#include "largefile/TextSource.h"
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

// Tree-sitter's public input callback uses uint32_t byte offsets. Keep the
// adapter explicit about that ABI limit, while the underlying TextSource uses
// uint64_t offsets and can serve independently-addressable large documents.
struct TSParserInput {
  const windbell::largefile::TextSource *source;
};

class CallbackTextSource final : public windbell::largefile::TextSource {
public:
  CallbackTextSource(JNIEnv *env, jobject callback, jmethodID readMethod,
                     uint64_t sizeBytes) noexcept
      : env_(env), callback_(callback), read_method_(readMethod), size_bytes_(sizeBytes) {}

  uint64_t sizeBytes() const noexcept override { return size_bytes_; }

  bool failed() const noexcept { return failed_; }

  bool read(uint64_t offset, uint32_t maxBytes,
            windbell::largefile::TextChunkView *out) const noexcept override {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;
    if (offset == size_bytes_) return true;
    if (offset > size_bytes_ || (offset & 1u) != 0 || maxBytes < 2u) {
      failed_ = true;
      return false;
    }

    jbyteArray bytes = reinterpret_cast<jbyteArray>(env_->CallObjectMethod(
        callback_, read_method_, static_cast<jlong>(offset), static_cast<jint>(maxBytes)));
    if (env_->ExceptionCheck() || bytes == nullptr) {
      if (bytes != nullptr) env_->DeleteLocalRef(bytes);
      failed_ = true;
      return false;
    }

    const jsize length = env_->GetArrayLength(bytes);
    if (length <= 0 || (length & 1) != 0 ||
        static_cast<uint32_t>(length) > maxBytes ||
        static_cast<uint64_t>(length) > size_bytes_ - offset) {
      env_->DeleteLocalRef(bytes);
      failed_ = true;
      return false;
    }

    try {
      buffer_.resize(static_cast<size_t>(length));
    } catch (...) {
      env_->DeleteLocalRef(bytes);
      failed_ = true;
      return false;
    }
    env_->GetByteArrayRegion(bytes, 0, length,
                             reinterpret_cast<jbyte *>(buffer_.data()));
    env_->DeleteLocalRef(bytes);
    if (env_->ExceptionCheck()) {
      failed_ = true;
      return false;
    }

    out->data = buffer_.data();
    out->size = static_cast<uint32_t>(length);
    return true;
  }

private:
  JNIEnv *env_;
  jobject callback_;
  jmethodID read_method_;
  uint64_t size_bytes_;
  mutable std::vector<char> buffer_;
  mutable bool failed_ = false;
};

static const char *TSParser_readInput(
    void *payload,
    uint32_t byte_index,
    TSPoint /*position*/,
    uint32_t *bytes_read) {
  auto *input = static_cast<TSParserInput *>(payload);
  if (input == nullptr || input->source == nullptr || bytes_read == nullptr) {
    if (bytes_read) *bytes_read = 0;
    return nullptr;
  }

  windbell::largefile::TextChunkView chunk{};
  if (!input->source->read(byte_index, windbell::largefile::kTreeSitterInputChunkBytes, &chunk) ||
      chunk.data == nullptr || chunk.size == 0) {
    *bytes_read = 0;
    return nullptr;
  }

  *bytes_read = chunk.size;
  return chunk.data;
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
  req_nnp(env, parser);
  int count = env->GetArrayLength(ranges);
  std::vector<TSRange> tsRanges(static_cast<size_t>(count));
  for (int i = 0; i < count; i++) {
    jobject range = env->GetObjectArrayElement(ranges, i);
    std::string msg = std::string("ranges[") + std::to_string(i) + "]";
    req_nnp(env, range, msg);
    tsRanges[i] = _unmarshalRange(env, range);

    // This binding always supplies TSInputEncodingUTF16LE. Tree-sitter byte
    // ranges therefore must begin and end on a two-byte UTF-16 code-unit
    // boundary. Do not let the runtime silently reinterpret an odd offset.
    if ((tsRanges[i].start_byte & 1u) != 0 ||
        (tsRanges[i].end_byte & 1u) != 0) {
      const std::string error = std::string("ranges[") + std::to_string(i) +
          "] has a byte offset in the middle of a UTF-16 code unit";
      throw_illegal_args(env, error.c_str());
      return JNI_FALSE;
    }
  }

  const TSRange *r = tsRanges.empty() ? nullptr : tsRanges.data();
  return (jboolean) ts_parser_set_included_ranges(((TSParserInternal *) parser)->getParser(
      env), r, static_cast<uint32_t>(count));
}

static jobjectArray
TSParser_getIncludedRanges(
    JNIEnv *env,
    jclass self,
    jlong parser) {
  req_nnp(env, parser);
  jint count;
  const TSRange *ranges =
      ts_parser_included_ranges(((TSParserInternal *) parser)->getParser(env),
                                reinterpret_cast<uint32_t *>(&count));
  jobjectArray result = createRangeArr(env, count);
  req_nnp(env, result, "TSRange[] from factory");

  for (uint32_t i = 0; i < count; i++) {
    const TSRange *r = (ranges + i);
    env->SetObjectArrayElement(result, (jint) i, _marshalRange(env, *r));
  }
  return result;
}

static jlong TSParser_parse(JNIEnv *env,
                            jclass clazz,
                            jlong parser,
                            jlong tree_pointer,
                            jlong str_pointer) {
  req_nnp(env, parser);
  req_nnp(env, str_pointer, "string");
  auto *ts_parser_internal = (TSParserInternal *) parser;
  TSParser *ts_parser = ts_parser_internal->getParser(env);
  TSTree *old_tree = tree_pointer == 0 ? nullptr : (TSTree *) tree_pointer;
  auto *source = as_str(env, str_pointer);

  if (!ts_parser_internal->beginRound(env)) {
    return 0;
  }

  // Adapt the existing UTF-16LE storage as a bounded, chunk-readable source.
  // Tree-sitter receives at most 64 KiB per callback instead of a pointer to the
  // entire remaining document. The source owns the bytes for the whole parse.
  const auto source_bytes = static_cast<uint64_t>(source->byte_length());
  if (source_bytes > UINT32_MAX || (source_bytes & 1u) != 0) {
    ts_parser_internal->endRound();
    throw_illegal_args(env,
        "Tree-sitter UTF-16LE input must have an even byte length within its 32-bit offset limit");
    return 0;
  }
  windbell::largefile::ContiguousTextSource textSource(source->data(), source_bytes);
  TSParserInput inputPayload{&textSource};
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

  // Tree-sitter 0.26 replaces timeout and cancellation-flag APIs with a
  // progress callback. It returns nullptr when that callback cancels parsing.
  struct ParseRoundGuard {
    TSParserInternal *parser;
    ~ParseRoundGuard() { if (parser) parser->endRound(); }
  } roundGuard{ts_parser_internal};

  ts_parser_internal->markParseStart();
  auto tree = ts_parser_parse_with_options(ts_parser, old_tree, input, options);
  return (jlong) tree;
}

static jlong TSParser_parseBuffers(
    JNIEnv *env,
    jclass clazz,
    jlong parser,
    jlong tree_pointer,
    jobjectArray buffers,
    jlong byte_length) {
  req_nnp(env, parser, "parser");
  req_nnp(env, buffers, "buffers");

  if (byte_length < 0 ||
      static_cast<uint64_t>(byte_length) > UINT32_MAX ||
      (byte_length & 1LL) != 0) {
    throw_illegal_args(env,
        "Segmented UTF-16LE input length must be even and fit Tree-sitter's 32-bit byte offsets");
    return 0;
  }

  auto *parser_internal = reinterpret_cast<TSParserInternal *>(parser);
  TSParser *ts_parser = parser_internal->getParser(env);
  if (ts_parser == nullptr || env->ExceptionCheck()) return 0;

  const jsize count = env->GetArrayLength(buffers);
  std::vector<windbell::largefile::TextSegment> segments;
  try {
    segments.reserve(static_cast<size_t>(count));
  } catch (...) {
    throw_illegal_state(env, "Unable to allocate segmented Tree-sitter input metadata");
    return 0;
  }

  uint64_t total = 0;
  for (jsize i = 0; i < count; ++i) {
    jobject buffer = env->GetObjectArrayElement(buffers, i);
    if (buffer == nullptr) {
      throw_illegal_args(env, "Segmented Tree-sitter input contains a null ByteBuffer");
      return 0;
    }

    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    void *address = env->GetDirectBufferAddress(buffer);
    env->DeleteLocalRef(buffer);

    if (capacity < 0 || (capacity > 0 && address == nullptr) ||
        (capacity & 1LL) != 0 ||
        static_cast<uint64_t>(capacity) > UINT32_MAX - total) {
      throw_illegal_args(env,
          "Every input segment must be a direct ByteBuffer with an even capacity");
      return 0;
    }

    segments.push_back({
        static_cast<const char *>(address),
        static_cast<uint64_t>(capacity)
    });
    total += static_cast<uint64_t>(capacity);
  }

  if (total != static_cast<uint64_t>(byte_length)) {
    throw_illegal_args(env, "Segment capacities do not match the declared input length");
    return 0;
  }

  if (!parser_internal->beginRound(env)) return 0;
  struct ParseRoundGuard {
    TSParserInternal *parser;
    ~ParseRoundGuard() { if (parser) parser->endRound(); }
  } roundGuard{parser_internal};

  windbell::largefile::SegmentedTextSource source(std::move(segments), total);
  if (!source.isValid()) {
    throw_illegal_args(env, "Invalid segmented UTF-16LE text source");
    return 0;
  }

  TSParserInput inputPayload{&source};
  TSInput input{
      &inputPayload,
      TSParser_readInput,
      TSInputEncodingUTF16LE,
      nullptr,
  };
  TSParseOptions options{
      parser_internal,
      TSParser_progressCallback,
  };

  TSTree *old_tree = tree_pointer == 0 ? nullptr : reinterpret_cast<TSTree *>(tree_pointer);
  parser_internal->markParseStart();
  return reinterpret_cast<jlong>(
      ts_parser_parse_with_options(ts_parser, old_tree, input, options));
}

static jlong TSParser_parseWindowed(
    JNIEnv *env,
    jclass clazz,
    jlong parser,
    jlong tree_pointer,
    jobject callback,
    jlong byte_length) {
  req_nnp(env, parser, "parser");
  req_nnp(env, callback, "window callback");
  if (byte_length < 0 ||
      static_cast<uint64_t>(byte_length) > UINT32_MAX ||
      (byte_length & 1LL) != 0) {
    throw_illegal_args(env,
        "Windowed UTF-16LE input length must be even and fit Tree-sitter's 32-bit byte offsets");
    return 0;
  }

  auto *parser_internal = reinterpret_cast<TSParserInternal *>(parser);
  TSParser *ts_parser = parser_internal->getParser(env);
  if (ts_parser == nullptr || env->ExceptionCheck()) return 0;

  jclass callback_class = env->GetObjectClass(callback);
  if (callback_class == nullptr) return 0;
  jmethodID read_method = env->GetMethodID(callback_class, "read", "(JI)[B");
  env->DeleteLocalRef(callback_class);
  if (read_method == nullptr || env->ExceptionCheck()) return 0;

  if (!parser_internal->beginRound(env)) return 0;
  struct ParseRoundGuard {
    TSParserInternal *parser;
    ~ParseRoundGuard() { if (parser) parser->endRound(); }
  } roundGuard{parser_internal};

  CallbackTextSource source(
      env, callback, read_method, static_cast<uint64_t>(byte_length));
  TSParserInput inputPayload{&source};
  TSInput input{
      &inputPayload,
      TSParser_readInput,
      TSInputEncodingUTF16LE,
      nullptr,
  };
  TSParseOptions options{
      parser_internal,
      TSParser_progressCallback,
  };

  TSTree *old_tree = tree_pointer == 0 ? nullptr : reinterpret_cast<TSTree *>(tree_pointer);
  parser_internal->markParseStart();
  TSTree *tree = ts_parser_parse_with_options(ts_parser, old_tree, input, options);
  if (env->ExceptionCheck() || source.failed()) {
    if (tree != nullptr) ts_tree_delete(tree);
    if (!env->ExceptionCheck()) {
      throw_illegal_state(env,
          "Window callback returned an invalid or incomplete UTF-16LE chunk");
    }
    return 0;
  }
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
  SET_JNI_METHOD(methods, TSParser_Native_parseBuffers, TSParser_parseBuffers);
  SET_JNI_METHOD(methods, TSParser_Native_parseWindowed, TSParser_parseWindowed);
  SET_JNI_METHOD(methods, TSParser_Native_requestCancellation,
                 TSParser_requestCancellation);
}