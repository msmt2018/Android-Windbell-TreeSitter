/*
 * Copyright © 2026 com.zerostudio.idea.treesitter
 *
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * 工业级生产环境 JNI 边界通信实现：全量动态装配、零硬编码分词、内存安全与局部引用拦截
 */

#include <jni.h>
#include <vector>
#include <array>
#include <algorithm>
#include <string>
#include <cstdint>
#include <cstring>
#include <limits>
#include <cstdlib>

#include "tsh/WindbellTshCore.h"
#include "tsh/WindbellTshCompiler.h"
#include "tsh/NativeHighlightCache.h"
#include "largefile/FileWindowSource.h"
#include "largefile/PieceTreeSource.h"
#include "largefile/TextPartitionIndex.h"
#include <cerrno>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>
#include <sys/types.h>
#include <unistd.h>
#include "utils/ts_exceptions.h"
#include "utils/ts_preconditions.h"
#include "ts__log.h"


using namespace windbell::tsh;

namespace {

static std::shared_ptr<SharedNativeMemoryBudget> nativeHighlightBudget() {
    static const auto budget = []() noexcept {
        try {
            // One process-wide capture-cache budget shared by all open documents.
            return std::make_shared<SharedNativeMemoryBudget>(32u * 1024u * 1024u);
        } catch (...) {
            return std::shared_ptr<SharedNativeMemoryBudget>{};
        }
    }();
    return budget;
}

struct NativeTshDocument {
    std::unique_ptr<windbell::largefile::PieceTreeSource> source;
    TSParser *parser = nullptr;
    TSTree *tree = nullptr;
    std::string extension;
    uint32_t length_chars = 0;
    uint64_t version = 0;
    NativeHighlightCache highlight_cache{4u * 1024u * 1024u, nativeHighlightBudget()};
    windbell::largefile::TextPartitionIndex partition_index{256};
    std::vector<RegexStateCheckpoint> regex_state_checkpoints;
    // Half-open line intervals invalidated by the latest committed edit.
    // Kept in new-document coordinates; consumed by incremental capture work.
    std::vector<std::pair<uint64_t, uint64_t>> dirty_line_intervals;
    std::mutex mutex;

    ~NativeTshDocument() {
        if (tree) ts_tree_delete(tree);
        if (parser) ts_parser_delete(parser);
    }
};

struct NativeDocumentInput {
    const windbell::largefile::TextSource *source = nullptr;
    // TSInput uses a null/zero-length result to signal EOF. Preserve the
    // distinction between a real EOF and an I/O/source failure so a failed
    // read can never be accepted as a successfully parsed truncated document.
    bool read_failed = false;
};

using DirtyLineInterval = std::pair<uint64_t, uint64_t>;

static bool appendDirtyLineInterval(
    const windbell::largefile::TextSource &source,
    windbell::largefile::TextPartitionIndex &index,
    uint64_t startByte, uint64_t endByte,
    std::vector<DirtyLineInterval> *intervals
) noexcept {
    if (!intervals || startByte > endByte || endByte > source.sizeBytes() ||
        (startByte & 1u) || (endByte & 1u)) return false;
    uint64_t firstLine = 0, firstLineStart = 0;
    uint64_t lastLine = 0, lastLineStart = 0;
    if (!index.pointForByte(source, startByte, &firstLine, &firstLineStart) ||
        !index.pointForByte(source, endByte, &lastLine, &lastLineStart)) return false;
    // The end is exclusive. If it is exactly at the next line start, that
    // next line is not part of the changed byte range.
    uint64_t endExclusive = lastLine + 1u;
    if (endByte > startByte && endByte == lastLineStart) endExclusive = lastLine;
    if (endExclusive <= firstLine) endExclusive = firstLine + 1u;
    try { intervals->emplace_back(firstLine, endExclusive); }
    catch (...) { return false; }
    return true;
}

static bool normalizeDirtyLineIntervals(
    std::vector<DirtyLineInterval> *intervals
) noexcept {
    if (!intervals) return false;
    try {
        std::sort(intervals->begin(), intervals->end(),
            [](const DirtyLineInterval &a, const DirtyLineInterval &b) {
                return a.first < b.first || (a.first == b.first && a.second < b.second);
            });
        size_t output = 0;
        for (const auto &range : *intervals) {
            if (range.first >= range.second) continue;
            if (output && range.first <= (*intervals)[output - 1].second) {
                (*intervals)[output - 1].second = std::max(
                    (*intervals)[output - 1].second, range.second);
            } else {
                (*intervals)[output++] = range;
            }
        }
        intervals->resize(output);
    } catch (...) { return false; }
    return true;
}

// Pins or exposes the Java edit array without allocating a second native
// vector. The native Piece Tree copies only the inserted bytes into its
// append-only storage; the old document contents are never materialized.
class ScopedByteArrayElements final {
public:
    ScopedByteArrayElements(JNIEnv *env, jbyteArray array, jsize length) noexcept
        : env_(env), array_(array) {
        if (env_ && array_ && length > 0) {
            data_ = env_->GetByteArrayElements(array_, nullptr);
        }
    }
    ~ScopedByteArrayElements() { release(); }
    ScopedByteArrayElements(const ScopedByteArrayElements &) = delete;
    ScopedByteArrayElements &operator=(const ScopedByteArrayElements &) = delete;

    const uint8_t *data() const noexcept {
        return reinterpret_cast<const uint8_t *>(data_);
    }
    bool valid(jsize length) const noexcept {
        return length == 0 || data_ != nullptr;
    }
    void release() noexcept {
        if (data_) {
            env_->ReleaseByteArrayElements(array_, data_, JNI_ABORT);
            data_ = nullptr;
        }
    }

private:
    JNIEnv *env_ = nullptr;
    jbyteArray array_ = nullptr;
    jbyte *data_ = nullptr;
};

static const char *nativeDocumentRead(
    void *payload, uint32_t byte_index, TSPoint, uint32_t *bytes_read
) {
    auto *input = static_cast<NativeDocumentInput *>(payload);
    if (!input || !input->source || !bytes_read) {
        if (bytes_read) *bytes_read = 0;
        if (input) input->read_failed = true;
        return nullptr;
    }
    *bytes_read = 0;
    const uint64_t document_size = input->source->sizeBytes();
    if (byte_index == document_size) return nullptr; // genuine EOF
    if (byte_index > document_size) {
        input->read_failed = true;
        return nullptr;
    }

    windbell::largefile::TextChunkView chunk{};
    if (!input->source->read(
            byte_index, windbell::largefile::kTreeSitterInputChunkBytes, &chunk) ||
        !chunk.data || chunk.size == 0 ||
        (chunk.size & 1u) != 0 ||
        chunk.size > document_size - byte_index ||
        chunk.size > windbell::largefile::kTreeSitterInputChunkBytes) {
        input->read_failed = true;
        return nullptr;
    }
    *bytes_read = chunk.size;
    return chunk.data;
}

static bool writeAllAt(int fd, const uint8_t *data, size_t length, uint64_t offset) {
    size_t written = 0;
    while (written < length) {
        const ssize_t count = ::pwrite(
            fd, data + written, length - written,
            static_cast<off_t>(offset + written));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        written += static_cast<size_t>(count);
    }
    return true;
}

// Translate a UTF-16LE byte offset to Tree-sitter coordinates using the
// document's sparse native line index. Never rescan the entire document from
// byte zero for every edit endpoint.
static bool nativePointAt(
    const windbell::largefile::TextSource &source,
    windbell::largefile::TextPartitionIndex &line_index,
    uint64_t byte_offset,
    TSPoint *point
) noexcept {
    if (!point || (byte_offset & 1u) || byte_offset > source.sizeBytes()) return false;
    uint64_t row = 0;
    uint64_t line_start = 0;
    if (!line_index.pointForByte(source, byte_offset, &row, &line_start) ||
        row > std::numeric_limits<uint32_t>::max() ||
        line_start > byte_offset ||
        byte_offset - line_start > std::numeric_limits<uint32_t>::max()) return false;
    point->row = static_cast<uint32_t>(row);
    point->column = static_cast<uint32_t>(byte_offset - line_start);
    return true;
}


static jintArray encodeNativeHighlightResult(
    JNIEnv *env,
    const std::vector<HighlightSpan> &spans,
    const uint32_t *match_counts,
    bool diagnostics
) {
    constexpr size_t kFields = 6;
    const jsize header = diagnostics ? 4 : 0;
    const size_t maximum = (static_cast<size_t>(std::numeric_limits<jsize>::max()) -
        static_cast<size_t>(header)) / kFields;
    if (spans.size() > maximum) {
        throw_illegal_args(env, "Native document highlight result exceeds JNI array limits");
        return nullptr;
    }
    const jsize output_size = header + static_cast<jsize>(spans.size() * kFields);
    jintArray output = env->NewIntArray(output_size);
    if (!output) {
        throw_npe(env, "Unable to allocate native document highlight result");
        return nullptr;
    }
    if (diagnostics) {
        const jint values[4] = {
            static_cast<jint>(match_counts[0]), static_cast<jint>(match_counts[1]),
            static_cast<jint>(match_counts[2]), static_cast<jint>(match_counts[3])
        };
        env->SetIntArrayRegion(output, 0, 4, values);
        if (env->ExceptionCheck()) return nullptr;
    }
    constexpr size_t kBatchSpans = 4096;
    std::vector<jint> batch;
    batch.reserve(kBatchSpans * kFields);
    for (size_t base = 0; base < spans.size();) {
        const size_t count = std::min(kBatchSpans, spans.size() - base);
        batch.clear();
        for (size_t i = 0; i < count; ++i) {
            const auto &span = spans[base + i];
            batch.push_back(static_cast<jint>(span.start_byte));
            batch.push_back(static_cast<jint>(span.end_byte));
            batch.push_back(static_cast<jint>(
                span.style_id | (static_cast<uint32_t>(span.flags) << 16)));
            batch.push_back(static_cast<jint>(span.priority));
            batch.push_back(static_cast<jint>(span.declaration_order));
            batch.push_back(static_cast<jint>(span.source));
        }
        env->SetIntArrayRegion(
            output, header + static_cast<jsize>(base * kFields),
            static_cast<jsize>(batch.size()), batch.data());
        if (env->ExceptionCheck()) return nullptr;
        base += count;
    }
    return output;
}

} // namespace

static size_t utf16LengthModifiedUtf8(const std::string& value) {
    size_t count = 0;
    for (size_t i = 0; i < value.size();) {
        const uint8_t first = static_cast<uint8_t>(value[i]);
        if (first <= 0x7F) {
            ++i;
            ++count;
        } else if ((first & 0xE0) == 0xC0 && i + 1 < value.size()) {
            i += 2;
            ++count;
        } else if ((first & 0xF0) == 0xE0 && i + 2 < value.size()) {
            i += 3;
            ++count;
        } else if ((first & 0xF8) == 0xF0 && i + 3 < value.size()) {
            i += 4;
            count += 2;
        } else {
            ++i;
            ++count;
        }
    }
    return count;
}

extern "C" {

/**
 * 1. 动态加载并编译 .tsh DSL 语法规则源文本
 * 签名: (Ljava/lang/String;Ljava/lang/String;J)Z
 */
JNIEXPORT jboolean JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeLoadTshSource(
    JNIEnv* env,
    jobject /* thiz */,
    jstring extension_jstr,
    jstring tsh_source_jstr,
    jlong language_ptr
) {
    if (!extension_jstr || !tsh_source_jstr) {
        throw_npe(env, "extension or tshSource cannot be null");
        return JNI_FALSE;
    }

    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) {
        throw_npe(env, "Failed to extract extension string");
        return JNI_FALSE;
    }
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    const char* src = env->GetStringUTFChars(tsh_source_jstr, nullptr);
    if (!src) {
        throw_npe(env, "Failed to extract TSH source string");
        return JNI_FALSE;
    }
    std::string src_str(src);
    env->ReleaseStringUTFChars(tsh_source_jstr, src);

    const TSLanguage* ts_lang = reinterpret_cast<const TSLanguage*>(language_ptr);
    
    // Keep diagnostics attached to this exact invocation. Reading the compiler's
    // compatibility "last diagnostics" slot here would race with other documents
    // compiling different language grammars on worker threads.
    auto compilation = TshCompiler::getInstance().compileDetailed(src_str, ts_lang);
    if (!compilation.grammar) {
        for (const auto& diagnostic : compilation.diagnostics) {
            LOGE("WindbellTSH",
                 "[%s] %s (offset=%u, line=%u, column=%u)",
                 diagnostic.code.c_str(),
                 diagnostic.message.c_str(),
                 diagnostic.offset,
                 diagnostic.line,
                 diagnostic.column);
        }
        LOGE("WindbellTSH", "Failed to compile TSH grammar rules for: %s", ext_str.c_str());
        return JNI_FALSE;
    }

    WindbellEngine::getInstance().registerGrammar(ext_str, compilation.grammar);
    return JNI_TRUE;
}

/**
 * Export language identity metadata as key/value pairs.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetLanguageMetadata(
    JNIEnv* env, jobject, jstring extension_jstr
) {
    if (!extension_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return nullptr;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    std::vector<std::pair<std::string, std::string>> values;
    if (grammar) {
        auto join = [](const std::vector<std::string>& items) {
            std::string out;
            for (size_t i = 0; i < items.size(); ++i) {
                if (i) out.push_back('\x1F');
                out += items[i];
            }
            return out;
        };
        values.emplace_back("id", grammar->id);
        values.emplace_back("name", grammar->name);
        values.emplace_back("extensions", join(grammar->file_extensions));
        values.emplace_back("aliases", join(grammar->aliases));
        values.emplace_back("mimeTypes", join(grammar->mime_types));
        values.emplace_back("firstLine", grammar->first_line_pattern);
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size() * 2), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < values.size(); ++i) {
        jstring key = env->NewStringUTF(values[i].first.c_str());
        jstring value = env->NewStringUTF(values[i].second.c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i * 2), key);
        env->SetObjectArrayElement(result, static_cast<jsize>(i * 2 + 1), value);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

/**
 * Test a first source line against the language's DSL-declared detector.
 */
JNIEXPORT jboolean JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeMatchesFirstLine(
    JNIEnv* env, jobject, jstring extension_jstr, jstring line_jstr
) {
    if (!extension_jstr || !line_jstr) return JNI_FALSE;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return JNI_FALSE;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    if (!grammar || !grammar->first_line_regex) return JNI_FALSE;

    const jchar* chars = env->GetStringChars(line_jstr, nullptr);
    if (!chars) return JNI_FALSE;
    const jsize length = env->GetStringLength(line_jstr);
    const UChar* start = reinterpret_cast<const UChar*>(chars);
    const UChar* end = start + static_cast<size_t>(length) * sizeof(jchar);
    OnigRegion* region = onig_region_new();
    if (!region) {
        env->ReleaseStringChars(line_jstr, chars);
        return JNI_FALSE;
    }
    const int matched = onig_match(grammar->first_line_regex, start, end, start, region, ONIG_OPTION_NONE);
    onig_region_free(region, 1);
    env->ReleaseStringChars(line_jstr, chars);
    return matched >= 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Return structured compiler diagnostics as [code, message, offset, line, column] tuples.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetCompileDiagnostics(
    JNIEnv* env, jobject
) {
    const auto diagnostics = TshCompiler::getInstance().getLastDiagnostics();
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(diagnostics.size() * 5), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < diagnostics.size(); ++i) {
        const auto& d = diagnostics[i];
        const std::array<std::string, 5> values = {
            d.code, d.message, std::to_string(d.offset),
            std::to_string(d.line), std::to_string(d.column)
        };
        for (size_t k = 0; k < values.size(); ++k) {
            jstring value = env->NewStringUTF(values[k].c_str());
            env->SetObjectArrayElement(result, static_cast<jsize>(i * 5 + k), value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(string_class);
    return result;
}

/**
 * Enumerate DSL-declared color schemes for an extension.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetSchemeNames(
    JNIEnv* env, jobject, jstring extension_jstr
) {
    if (!extension_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return nullptr;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    if (!grammar) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);

    std::vector<std::string> names;
    names.reserve(grammar->schemes.size());
    for (const auto& entry : grammar->schemes) names.push_back(entry.first);
    std::sort(names.begin(), names.end());

    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(names.size()), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < names.size(); ++i) {
        jstring value = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

/**
 * Return the first declared scheme, or null when the DSL declares none.
 */
JNIEXPORT jstring JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetDefaultScheme(
    JNIEnv* env, jobject, jstring extension_jstr
) {
    if (!extension_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return nullptr;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    if (!grammar || grammar->default_scheme_name.empty()) return nullptr;
    return env->NewStringUTF(grammar->default_scheme_name.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetDefaultIsDark(
    JNIEnv* env, jobject, jstring extension_jstr
) {
    if (!extension_jstr) return JNI_FALSE;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return JNI_FALSE;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    return grammar && grammar->default_is_dark ? JNI_TRUE : JNI_FALSE;
}

/**
 * Export editor metadata as triples: [kind, value1, value2] * N.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetEditorMetadata(
    JNIEnv* env, jobject, jstring extension_jstr
) {
    if (!extension_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return nullptr;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);

    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    if (!grammar) {
        jobjectArray empty = env->NewObjectArray(0, string_class, nullptr);
        env->DeleteLocalRef(string_class);
        return empty;
    }

    std::vector<std::array<std::string, 3>> records;
    const auto& metadata = grammar->editor_metadata;
    for (const auto& value : metadata.line_comments) records.push_back({"lineComment", value, ""});
    for (const auto& pair : metadata.block_comments) records.push_back({"blockComment", pair.first, pair.second});
    for (const auto& pair : metadata.brackets) records.push_back({"bracket", pair.first, pair.second});
    for (const auto& scope : metadata.bracket_not_in) records.push_back({"bracketNotIn", scope, ""});
    for (const auto& pair : metadata.surrounding_pairs) records.push_back({"surrounding", pair.first, pair.second});
    for (const auto& pair : metadata.auto_closing_pairs) {
        std::string not_in;
        for (size_t i = 0; i < pair.not_in.size(); ++i) {
            if (i) not_in.push_back('\x1F');
            not_in += pair.not_in[i];
        }
        records.push_back({"autoClosing", pair.open, pair.close + std::string(1, '\x1F') + not_in});
    }
    if (!metadata.word_pattern.empty()) records.push_back({"wordPattern", metadata.word_pattern, ""});
    if (!metadata.increase_indent_pattern.empty()) records.push_back({"indentIncrease", metadata.increase_indent_pattern, ""});
    if (!metadata.decrease_indent_pattern.empty()) records.push_back({"indentDecrease", metadata.decrease_indent_pattern, ""});
    if (!metadata.indent_next_line_pattern.empty()) records.push_back({"indentNextLine", metadata.indent_next_line_pattern, ""});
    if (!metadata.unindented_line_pattern.empty()) records.push_back({"unIndentedLine", metadata.unindented_line_pattern, ""});
    for (const auto& value : metadata.completion_trigger_characters) records.push_back({"completionTrigger", value, ""});
    for (const auto& value : metadata.electric_characters) records.push_back({"electricCharacter", value, ""});
    if (!metadata.auto_close_before_pattern.empty()) records.push_back({"autoCloseBefore", metadata.auto_close_before_pattern, ""});
    for (size_t i = 0; i < metadata.on_enter_rules.size(); ++i) {
        const auto& rule = metadata.on_enter_rules[i];
        const std::string index = std::to_string(i);
        records.push_back({"onEnterBefore", index, rule.before_text});
        if (!rule.after_text.empty()) records.push_back({"onEnterAfter", index, rule.after_text});
        if (!rule.previous_line_text.empty()) records.push_back({"onEnterPrevious", index, rule.previous_line_text});
        records.push_back({"onEnterIndent", index, rule.indent_action});
        if (!rule.append_text.empty()) records.push_back({"onEnterAppend", index, rule.append_text});
        if (rule.remove_text != 0) records.push_back({"onEnterRemove", index, std::to_string(rule.remove_text)});
    }
    for (const auto& option : metadata.options) records.push_back({"editorOption", option.first, option.second});

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(records.size() * 3), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < records.size(); ++i) {
        for (size_t field = 0; field < 3; ++field) {
            jstring value = env->NewStringUTF(records[i][field].c_str());
            env->SetObjectArrayElement(result, static_cast<jsize>(i * 3 + field), value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(string_class);
    return result;
}

/**
 * Export semantic style catalog entries as eight strings each:
 * id, name, scope, description, parent, tags, deprecated, metadata.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetStyleCatalog(
    JNIEnv* env, jobject, jstring extension_jstr
) {
    if (!extension_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) return nullptr;
    const std::string extension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    if (!grammar) {
        jobjectArray empty = env->NewObjectArray(0, string_class, nullptr);
        env->DeleteLocalRef(string_class);
        return empty;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(grammar->styles.size() * 8), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < grammar->styles.size(); ++i) {
        const auto& style = grammar->styles[i];
        std::string tags;
        for (size_t tag_index = 0; tag_index < style.tags.size(); ++tag_index) {
            if (tag_index) tags.push_back('\x1F');
            tags += style.tags[tag_index];
        }
        std::string metadata;
        for (const auto& entry : style.metadata) {
            metadata += std::to_string(utf16LengthModifiedUtf8(entry.first));
            metadata.push_back(':');
            metadata += entry.first;
            metadata += std::to_string(utf16LengthModifiedUtf8(entry.second));
            metadata.push_back(':');
            metadata += entry.second;
        }
        const std::string values[] = {
            std::to_string(style.style_id), style.name, style.scope,
            style.description, style.parent, tags,
            style.deprecated ? "true" : "false", metadata
        };
        for (size_t field = 0; field < 8; ++field) {
            jstring value = env->NewStringUTF(values[field].c_str());
            env->SetObjectArrayElement(result, static_cast<jsize>(i * 8 + field), value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(string_class);
    return result;
}

/**
 * Return arbitrary UI theme token pairs as [name, decimalARGB, ...].
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetThemeTokens(
    JNIEnv* env, jobject, jstring extension_jstr, jstring scheme_jstr, jboolean is_dark
) {
    if (!extension_jstr || !scheme_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    const char* scheme_name = env->GetStringUTFChars(scheme_jstr, nullptr);
    if (!ext || !scheme_name) {
        if (ext) env->ReleaseStringUTFChars(extension_jstr, ext);
        if (scheme_name) env->ReleaseStringUTFChars(scheme_jstr, scheme_name);
        return nullptr;
    }
    const std::string extension(ext);
    const std::string selected_scheme(scheme_name);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    env->ReleaseStringUTFChars(scheme_jstr, scheme_name);

    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    if (!grammar) return nullptr;
    auto scheme_it = grammar->schemes.find(selected_scheme);
    if (scheme_it == grammar->schemes.end()) return nullptr;

    auto effective_tokens = scheme_it->second.ui_colors;
    const auto& appearance_tokens = is_dark ?
        scheme_it->second.ui_dark_colors : scheme_it->second.ui_light_colors;
    for (const auto& entry : appearance_tokens) effective_tokens[entry.first] = entry.second;

    std::vector<std::string> keys;
    keys.reserve(effective_tokens.size());
    for (const auto& entry : effective_tokens) keys.push_back(entry.first);
    std::sort(keys.begin(), keys.end());

    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(keys.size() * 2), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < keys.size(); ++i) {
        const auto color = effective_tokens.at(keys[i]);
        jstring key = env->NewStringUTF(keys[i].c_str());
        jstring value = env->NewStringUTF(std::to_string(color).c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i * 2), key);
        env->SetObjectArrayElement(result, static_cast<jsize>(i * 2 + 1), value);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

/**
 * Export palette for the explicitly selected scheme.
 * Layout: [styleId, lightColor, darkColor, lightFlags, darkFlags, hasLight, hasDark] * N.
 * Presence flags distinguish an absent value from an explicit transparent color.
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetPalette(
    JNIEnv* env, jobject, jstring extension_jstr, jstring scheme_jstr
) {
    if (!extension_jstr || !scheme_jstr) return nullptr;
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    const char* scheme_name = env->GetStringUTFChars(scheme_jstr, nullptr);
    if (!ext || !scheme_name) {
        if (ext) env->ReleaseStringUTFChars(extension_jstr, ext);
        if (scheme_name) env->ReleaseStringUTFChars(scheme_jstr, scheme_name);
        return nullptr;
    }
    const std::string extension(ext);
    const std::string selected_scheme(scheme_name);
    env->ReleaseStringUTFChars(extension_jstr, ext);
    env->ReleaseStringUTFChars(scheme_jstr, scheme_name);

    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(extension);
    if (!grammar) return env->NewIntArray(0);
    auto scheme_it = grammar->schemes.find(selected_scheme);
    if (scheme_it == grammar->schemes.end()) return env->NewIntArray(0);

    const jsize count = static_cast<jsize>(grammar->styles.size());
    jintArray result = env->NewIntArray(count * 7);
    if (!result) return nullptr;
    std::vector<jint> values(static_cast<size_t>(count) * 7, 0);
    for (jsize i = 0; i < count; ++i) {
        const auto& style = grammar->styles[static_cast<size_t>(i)];
        values[i * 7] = static_cast<jint>(style.style_id);
        auto color_it = scheme_it->second.colors.find(style.style_id);
        if (color_it != scheme_it->second.colors.end()) {
            const SchemeColor& color = color_it->second;
            values[i * 7 + 1] = static_cast<jint>(color.light_color);
            values[i * 7 + 2] = static_cast<jint>(color.dark_color);
            values[i * 7 + 3] = static_cast<jint>(color.light_font_flags);
            values[i * 7 + 4] = static_cast<jint>(color.dark_font_flags);
            values[i * 7 + 5] = color.has_light_color ? 1 : 0;
            values[i * 7 + 6] = color.has_dark_color ? 1 : 0;
        }
    }
    env->SetIntArrayRegion(result, 0, count * 7, values.data());
    return result;
}

/**
 * 3. 双通道混合高亮计算 (微观正则 + 宏观 AST 语法树剪枝)
 * 签名: (JLjava/lang/String;Ljava/lang/String;)[I
 * 返回结构: [start_byte, end_byte, packed_style_flags, priority, declaration_order, source] * N
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeHighlight(
    JNIEnv* env,
    jobject /* thiz */,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr,
    jboolean diagnostics,
    jlong range_start_byte,
    jlong range_end_byte
) {
    if (!text_jstr || !extension_jstr) {
        throw_npe(env, "text or extension cannot be null");
        return nullptr;
    }
    if (range_start_byte < 0 || range_end_byte < 0 ||
        static_cast<uint64_t>(range_start_byte) > UINT32_MAX ||
        static_cast<uint64_t>(range_end_byte) > UINT32_MAX ||
        (range_start_byte & 1LL) != 0 || (range_end_byte & 1LL) != 0 ||
        range_start_byte > range_end_byte) {
        throw_illegal_args(env, "TSH byte range must be ordered, non-negative UTF-16LE byte offsets");
        return nullptr;
    }

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) {
        throw_npe(env, "Failed to extract extension string");
        return nullptr;
    }
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    const jsize len = env->GetStringLength(text_jstr);
    if (len == 0) {
        return env->NewIntArray(0);
    }

    // Keep the Java UTF-16 source pinned/readable for the duration of the
    // synchronous native pass. Avoid allocating a second full-document vector:
    // this matters for multi-megabyte and very large editor buffers.
    const jchar* source_chars = env->GetStringChars(text_jstr, nullptr);
    if (!source_chars) return nullptr;
    struct SourceCharsGuard {
        JNIEnv* env;
        jstring source;
        const jchar* chars;
        ~SourceCharsGuard() {
            if (chars) env->ReleaseStringChars(source, chars);
        }
    } source_guard{env, text_jstr, source_chars};

    std::vector<HighlightSpan> raw_candidates;
    uint32_t pipeline_match_counts[4] = {};
    std::vector<HighlightSpan> spans = WindbellEngine::getInstance().highlightHybrid(
        tree ? ts_tree_language(tree) : nullptr,
        tree,
        reinterpret_cast<const uint16_t*>(source_chars),
        static_cast<uint32_t>(len),
        ext_str,
        diagnostics == JNI_TRUE ? &raw_candidates : nullptr,
        diagnostics == JNI_TRUE ? pipeline_match_counts : nullptr,
        static_cast<uint32_t>(range_start_byte),
        static_cast<uint32_t>(range_end_byte)
    );
    if (diagnostics == JNI_TRUE) spans = std::move(raw_candidates);

    const jsize header_size = diagnostics == JNI_TRUE ? 4 : 0;
    constexpr size_t kSpanFields = 6;
    const size_t max_span_count =
        (static_cast<size_t>(std::numeric_limits<jsize>::max()) -
         static_cast<size_t>(header_size)) / kSpanFields;
    if (spans.size() > max_span_count) {
        throw_illegal_args(env, "Highlight result exceeds the maximum JNI int-array size");
        return nullptr;
    }
    const jsize out_size = header_size +
        static_cast<jsize>(spans.size() * kSpanFields);
    jintArray result = env->NewIntArray(out_size);
    if (!result) {
        throw_npe(env, "Out of memory allocating spans int array");
        return nullptr;
    }

    if (diagnostics == JNI_TRUE) {
        const jint header[4] = {
            static_cast<jint>(pipeline_match_counts[0]),
            static_cast<jint>(pipeline_match_counts[1]),
            static_cast<jint>(pipeline_match_counts[2]),
            static_cast<jint>(pipeline_match_counts[3])
        };
        env->SetIntArrayRegion(result, 0, header_size, header);
        if (env->ExceptionCheck()) return nullptr;
    }

    // Do not allocate a second int array as large as the entire highlight result.
    // Convert a bounded number of spans at a time and copy each batch directly
    // into the Java result array. Peak temporary conversion memory stays bounded.
    constexpr size_t kSpansPerBatch = 4096;
    std::vector<jint> batch;
    batch.reserve(kSpansPerBatch * kSpanFields);
    size_t span_index = 0;
    while (span_index < spans.size()) {
        const size_t count = std::min(kSpansPerBatch, spans.size() - span_index);
        batch.clear();
        for (size_t i = 0; i < count; ++i) {
            const auto& span = spans[span_index + i];
            batch.push_back(static_cast<jint>(span.start_byte));
            batch.push_back(static_cast<jint>(span.end_byte));
            batch.push_back(static_cast<jint>(
                span.style_id | (static_cast<uint32_t>(span.flags) << 16)));
            batch.push_back(static_cast<jint>(span.priority));
            batch.push_back(static_cast<jint>(span.declaration_order));
            batch.push_back(static_cast<jint>(span.source));
        }
        const jsize destination_offset = header_size +
            static_cast<jsize>(span_index * kSpanFields);
        env->SetIntArrayRegion(
            result, destination_offset, static_cast<jsize>(batch.size()), batch.data());
        if (env->ExceptionCheck()) return nullptr;
        span_index += count;
    }
    return result;
}



/**
 * Native-owned file-backed UTF-16LE document session. Source acquisition,
 * parsing and TSH execution are performed by this JNI engine; the callback
 * only returns bounded byte windows.
 */
JNIEXPORT jlong JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeCreateDocument(
    JNIEnv *env, jobject, jobject reader, jlong byte_length,
    jstring temporary_directory, jlong language_pointer, jstring extension_jstr
) {
    if (!reader || !temporary_directory || !extension_jstr || language_pointer == 0) {
        throw_npe(env, "Native document source, directory, extension and language are required");
        return 0;
    }
    if (byte_length < 0 || static_cast<uint64_t>(byte_length) > UINT32_MAX ||
        (byte_length & 1LL) != 0) {
        throw_illegal_args(env, "Native TSH input must be even UTF-16LE bytes within Tree-sitter's 32-bit offset range");
        return 0;
    }
    const char *dir_chars = env->GetStringUTFChars(temporary_directory, nullptr);
    const char *ext_chars = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!dir_chars || !ext_chars) {
        if (dir_chars) env->ReleaseStringUTFChars(temporary_directory, dir_chars);
        if (ext_chars) env->ReleaseStringUTFChars(extension_jstr, ext_chars);
        return 0;
    }
    const std::string directory(dir_chars), extension(ext_chars);
    env->ReleaseStringUTFChars(temporary_directory, dir_chars);
    env->ReleaseStringUTFChars(extension_jstr, ext_chars);
    if (directory.empty()) {
        throw_illegal_args(env, "A writable application cache directory is required");
        return 0;
    }
    jclass reader_class = env->GetObjectClass(reader);
    if (!reader_class) return 0;
    jmethodID read_method = env->GetMethodID(reader_class, "read", "(JI)[B");
    env->DeleteLocalRef(reader_class);
    if (!read_method || env->ExceptionCheck()) return 0;

    std::string pattern = directory;
    if (pattern.back() != '/') pattern.push_back('/');
    pattern += ".windbell-tsh-XXXXXX";
    std::vector<char> path(pattern.begin(), pattern.end());
    path.push_back('\0');
    const int fd = ::mkstemp(path.data());
    if (fd < 0) {
        throw_illegal_state(env, "Cannot create native document backing file");
        return 0;
    }
    const std::string backing_path(path.data());
    bool ok = ::ftruncate(fd, static_cast<off_t>(byte_length)) == 0;
    std::vector<jbyte> window;
    if (ok && byte_length > 0) {
        try {
            window.resize(static_cast<size_t>(std::min<uint64_t>(
                windbell::largefile::kTreeSitterInputChunkBytes,
                static_cast<uint64_t>(byte_length))));
        } catch (...) {
            ::close(fd);
            ::unlink(backing_path.c_str());
            throw_illegal_state(env, "Unable to allocate bounded JNI document transfer window");
            return 0;
        }
    }
    uint64_t offset = 0;
    while (ok && offset < static_cast<uint64_t>(byte_length)) {
        const jint requested = static_cast<jint>(std::min<uint64_t>(
            windbell::largefile::kTreeSitterInputChunkBytes,
            static_cast<uint64_t>(byte_length) - offset));
        auto bytes = reinterpret_cast<jbyteArray>(env->CallObjectMethod(
            reader, read_method, static_cast<jlong>(offset), requested));
        if (env->ExceptionCheck() || !bytes) {
            if (bytes) env->DeleteLocalRef(bytes);
            ok = false;
            break;
        }
        const jsize count = env->GetArrayLength(bytes);
        if (count <= 0 || (count & 1) || count > requested ||
            static_cast<uint64_t>(count) > static_cast<uint64_t>(byte_length) - offset) {
            env->DeleteLocalRef(bytes);
            ok = false;
            break;
        }
        env->GetByteArrayRegion(bytes, 0, count, window.data());
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck() ||
            !writeAllAt(fd, reinterpret_cast<const uint8_t *>(window.data()),
                        static_cast<size_t>(count), offset)) {
            ok = false;
            break;
        }
        offset += static_cast<uint64_t>(count);
    }
    ::close(fd);
    if (!ok || offset != static_cast<uint64_t>(byte_length)) {
        ::unlink(backing_path.c_str());
        if (!env->ExceptionCheck()) {
            throw_illegal_state(env, "Native source callback returned an invalid or incomplete UTF-16LE window");
        }
        return 0;
    }

    auto document = std::make_unique<NativeTshDocument>();
    // Use a fixed 64 KiB pread window instead of mapping the complete
    // document. This keeps native virtual-address usage bounded on 32-bit and
    // memory-constrained devices; the document mutex serializes window reads.
    auto window_source = std::make_shared<windbell::largefile::FileWindowSource>(
        backing_path, windbell::largefile::kTreeSitterInputChunkBytes);
    if (!window_source->isOpen()) {
        ::unlink(backing_path.c_str());
        throw_illegal_state(env, "Unable to open bounded native document source");
        return 0;
    }
    // The open descriptor remains valid after unlink, so no temporary file is
    // left behind if the process exits before the document handle is closed.
    ::unlink(backing_path.c_str());
    document->source = std::make_unique<windbell::largefile::PieceTreeSource>(
        std::static_pointer_cast<const windbell::largefile::TextSource>(window_source));
    if (!document->source->isValid()) {
        throw_illegal_state(env, "Unable to initialize native Piece Tree document source");
        return 0;
    }
    document->extension = extension;
    document->length_chars = static_cast<uint32_t>(static_cast<uint64_t>(byte_length) / 2u);
    document->parser = ts_parser_new();
    if (!document->parser || !ts_parser_set_language(
            document->parser, reinterpret_cast<const TSLanguage *>(language_pointer))) {
        throw_illegal_state(env, "Unable to initialize Tree-sitter for native document");
        return 0;
    }
    NativeDocumentInput payload{document->source.get(), false};
    TSInput input{&payload, nativeDocumentRead, TSInputEncodingUTF16LE, nullptr};
    document->tree = ts_parser_parse(document->parser, nullptr, input);
    if (!document->tree || payload.read_failed ||
        ts_node_end_byte(ts_tree_root_node(document->tree)) !=
            document->source->sizeBytes()) {
        if (document->tree) {
            ts_tree_delete(document->tree);
            document->tree = nullptr;
        }
        throw_illegal_state(env,
            "Tree-sitter input failed or did not cover the complete native document");
        return 0;
    }
    return reinterpret_cast<jlong>(document.release());
}

/**
 * Applies one UTF-16LE edit to the native document and incrementally reparses
 * from the edited Tree-sitter tree. The old file-window source and persistent
 * Piece Tree remain valid until the candidate source and syntax tree commit.
 */
JNIEXPORT jboolean JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeApplyDocumentEdit(
    JNIEnv *env, jobject, jlong document_pointer,
    jlong start_byte, jlong old_end_byte, jbyteArray inserted_bytes
) {
    auto *document = reinterpret_cast<NativeTshDocument *>(document_pointer);
    if (!document || !inserted_bytes) {
        throw_npe(env, "Native document and inserted UTF-16LE bytes are required");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(document->mutex);
    const uint64_t old_size = document->source->sizeBytes();
    const jsize inserted_size = env->GetArrayLength(inserted_bytes);
    if (start_byte < 0 || old_end_byte < start_byte ||
        static_cast<uint64_t>(old_end_byte) > old_size ||
        (start_byte & 1LL) || (old_end_byte & 1LL) || (inserted_size & 1) != 0) {
        throw_illegal_args(env, "Native edit offsets and inserted data must be valid UTF-16LE byte ranges");
        return JNI_FALSE;
    }
    const uint64_t new_size = old_size -
        static_cast<uint64_t>(old_end_byte - start_byte) +
        static_cast<uint64_t>(inserted_size);
    if (new_size > UINT32_MAX || (new_size & 1u) != 0) {
        throw_illegal_args(env, "Edited document exceeds Tree-sitter's 32-bit byte-offset limit");
        return JNI_FALSE;
    }

    // Build an isolated Piece Tree candidate. The original file-window source and
    // immutable append chunks are shared; unchanged document bytes are never copied.
    // Only the tree nodes and inserted UTF-16LE bytes are newly allocated.
    auto next_source = document->source->clone();
    if (!next_source) {
        throw_illegal_state(env, "Unable to clone native Piece Tree transaction");
        return JNI_FALSE;
    }
    TSPoint start_point{};
    TSPoint old_end_point{};
    if (!nativePointAt(*document->source, document->partition_index,
                       static_cast<uint64_t>(start_byte), &start_point) ||
        !nativePointAt(*document->source, document->partition_index,
                       static_cast<uint64_t>(old_end_byte), &old_end_point)) {
        throw_illegal_state(env, "Native line index could not resolve Tree-sitter edit coordinates");
        return JNI_FALSE;
    }

    ScopedByteArrayElements inserted(env, inserted_bytes, inserted_size);
    if (!inserted.valid(inserted_size)) return JNI_FALSE;
    const uint8_t *inserted_data = inserted.data();

    TSPoint new_end_point = start_point;
    for (jsize i = 0; i + 1 < inserted_size; i += 2) {
        const uint16_t unit = static_cast<uint16_t>(inserted_data[i]) |
            (static_cast<uint16_t>(inserted_data[i + 1]) << 8);
        if (unit == '\n') {
            ++new_end_point.row;
            new_end_point.column = 0;
        } else {
            new_end_point.column += 2;
        }
    }
    if (!next_source->replace(
            static_cast<uint64_t>(start_byte), static_cast<uint64_t>(old_end_byte),
            inserted_data, static_cast<uint64_t>(inserted_size))) {
        inserted.release();
        throw_illegal_state(env, "Native Piece Tree rejected the edit (memory budget or range)");
        return JNI_FALSE;
    }

    // Prepare the sparse line index transactionally against the old source.
    // If parsing fails, the live document keeps its original index unchanged.
    std::unique_ptr<windbell::largefile::TextPartitionIndex> next_partition_index;
    try {
        next_partition_index =
            std::make_unique<windbell::largefile::TextPartitionIndex>(document->partition_index);
    } catch (...) {
        inserted.release();
        throw_illegal_state(env, "Unable to allocate transactional native line-index snapshot");
        return JNI_FALSE;
    }
    if (!next_partition_index->applyEdit(
            *document->source, static_cast<uint64_t>(start_byte),
            static_cast<uint64_t>(old_end_byte), inserted_data,
            static_cast<uint64_t>(inserted_size))) {
        next_partition_index->reset();
    }
    inserted.release();

    TSTree *edited_tree = ts_tree_copy(document->tree);
    if (!edited_tree) {
        throw_illegal_state(env, "Unable to clone native syntax tree for transactional edit");
        return JNI_FALSE;
    }
    TSInputEdit edit{};
    edit.start_byte = static_cast<uint32_t>(start_byte);
    edit.old_end_byte = static_cast<uint32_t>(old_end_byte);
    edit.new_end_byte = static_cast<uint32_t>(
        static_cast<uint64_t>(start_byte) + static_cast<uint64_t>(inserted_size));
    edit.start_point = start_point;
    edit.old_end_point = old_end_point;
    edit.new_end_point = new_end_point;
    ts_tree_edit(edited_tree, &edit);

    NativeDocumentInput payload{next_source.get(), false};
    TSInput input{&payload, nativeDocumentRead, TSInputEncodingUTF16LE, nullptr};
    TSTree *next_tree = ts_parser_parse(document->parser, edited_tree, input);
    if (!next_tree || payload.read_failed ||
        ts_node_end_byte(ts_tree_root_node(next_tree)) != next_source->sizeBytes()) {
        if (next_tree) ts_tree_delete(next_tree);
        ts_tree_delete(edited_tree);
        ts_parser_reset(document->parser);
        throw_illegal_state(env,
            "Tree-sitter input failed or incremental parse did not cover the complete document; original document retained");
        return JNI_FALSE;
    }

    // Prepare syntax invalidation before committing any candidate document state.
    // Compare the edited old tree (already in new-document coordinates) with the
    // newly parsed tree. Also include the literal edit range: leaf text can change
    // without producing a structural changed range.
    std::vector<DirtyLineInterval> next_dirty_intervals;
    std::vector<std::pair<uint32_t, uint32_t>> next_dirty_byte_ranges;
    TSRange *changed_ranges = nullptr;
    uint32_t changed_count = 0;
    try {
        next_dirty_intervals.reserve(2u);
        next_dirty_byte_ranges.reserve(2u);
    } catch (...) {
        ts_tree_delete(next_tree);
        ts_tree_delete(edited_tree);
        throw_illegal_state(env, "Unable to allocate native dirty-range transaction");
        return JNI_FALSE;
    }
    changed_ranges = ts_tree_get_changed_ranges(edited_tree, next_tree, &changed_count);
    bool dirty_ranges_ok = changed_ranges != nullptr || changed_count == 0;
    for (uint32_t i = 0; dirty_ranges_ok && i < changed_count; ++i) {
        dirty_ranges_ok = appendDirtyLineInterval(
            *next_source, *next_partition_index,
            changed_ranges[i].start_byte, changed_ranges[i].end_byte,
            &next_dirty_intervals);
        if (dirty_ranges_ok) {
            try { next_dirty_byte_ranges.emplace_back(
                changed_ranges[i].start_byte, changed_ranges[i].end_byte); }
            catch (...) { dirty_ranges_ok = false; }
        }
    }
    std::free(changed_ranges); // Tree-sitter 0.28 documents malloc/free ownership.
    const uint64_t insertedEnd = static_cast<uint64_t>(start_byte) +
        static_cast<uint64_t>(inserted_size);
    if (dirty_ranges_ok) {
        dirty_ranges_ok = appendDirtyLineInterval(
            *next_source, *next_partition_index,
            static_cast<uint64_t>(start_byte), insertedEnd,
            &next_dirty_intervals);
        if (dirty_ranges_ok) {
            try { next_dirty_byte_ranges.emplace_back(
                static_cast<uint32_t>(start_byte), static_cast<uint32_t>(insertedEnd)); }
            catch (...) { dirty_ranges_ok = false; }
        }
    }
    if (dirty_ranges_ok) dirty_ranges_ok = normalizeDirtyLineIntervals(&next_dirty_intervals);
    if (dirty_ranges_ok) {
        try {
            std::sort(next_dirty_byte_ranges.begin(), next_dirty_byte_ranges.end());
            size_t out = 0;
            for (const auto &range : next_dirty_byte_ranges) {
                if (out && range.first <= next_dirty_byte_ranges[out - 1].second) {
                    next_dirty_byte_ranges[out - 1].second = std::max(
                        next_dirty_byte_ranges[out - 1].second, range.second);
                } else next_dirty_byte_ranges[out++] = range;
            }
            next_dirty_byte_ranges.resize(out);
        } catch (...) { dirty_ranges_ok = false; }
    }
    ts_tree_delete(edited_tree);
    if (!dirty_ranges_ok) {
        ts_tree_delete(next_tree);
        throw_illegal_state(env, "Unable to map Tree-sitter changed ranges into native line intervals");
        return JNI_FALSE;
    }

    const uint64_t old_source_version = document->source->version();
    ts_tree_delete(document->tree);
    document->tree = next_tree;
    document->source = std::move(next_source);
    document->partition_index = std::move(*next_partition_index);
    document->length_chars = static_cast<uint32_t>(new_size / 2u);
    ++document->version;
    document->dirty_line_intervals.swap(next_dirty_intervals);
    // Until NativeHighlightCache supports interval-level eviction, clear its
    // entries conservatively. Dirty intervals are retained for the next
    // incremental ATS capture implementation instead of being discarded.
    document->highlight_cache.rebaseAfterEdit(
        document->version, document->source->version(), document->partition_index.version(),
        static_cast<uint32_t>(start_byte), static_cast<uint32_t>(old_end_byte),
        static_cast<uint32_t>(inserted_size), next_dirty_byte_ranges);

    // Keep tokenizer checkpoints before the edit as immediately reusable. Keep
    // checkpoints after the replaced range as *candidates* at their shifted byte
    // offsets: they become reusable only after a fresh scan reaches the same
    // offset with an identical Regex state stack (state convergence).
    const uint64_t new_source_version = document->source->version();
    const uint64_t new_partition_version = document->partition_index.version();
    const int64_t byte_delta = static_cast<int64_t>(inserted_size) -
        static_cast<int64_t>(old_end_byte - start_byte);
    std::vector<RegexStateCheckpoint> retained;
    bool checkpoints_copied = true;
    try {
        retained.reserve(document->regex_state_checkpoints.size());
        for (auto checkpoint : document->regex_state_checkpoints) {
            if (checkpoint.source_version != old_source_version) continue;
            if (checkpoint.byte_offset <= static_cast<uint64_t>(start_byte)) {
                checkpoint.source_version = new_source_version;
                checkpoint.partition_version = new_partition_version;
                retained.push_back(std::move(checkpoint));
                continue;
            }
            if (checkpoint.byte_offset >= static_cast<uint64_t>(old_end_byte)) {
                const int64_t shifted = static_cast<int64_t>(checkpoint.byte_offset) + byte_delta;
                if (shifted < 0 || shifted > static_cast<int64_t>(new_size)) continue;
                checkpoint.byte_offset = static_cast<uint64_t>(shifted);
                // Keep the old source version until a new scan proves convergence.
                retained.push_back(std::move(checkpoint));
            }
        }
    } catch (...) {
        checkpoints_copied = false;
    }
    if (checkpoints_copied) document->regex_state_checkpoints.swap(retained);
    else document->regex_state_checkpoints.clear();
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeHighlightDocument(
    JNIEnv *env, jobject, jlong document_pointer, jboolean diagnostics,
    jlong range_start_byte, jlong range_end_byte
) {
    auto *document = reinterpret_cast<NativeTshDocument *>(document_pointer);
    if (!document) {
        throw_npe(env, "Native document handle cannot be null");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(document->mutex);
    const uint64_t total_bytes = document->source->sizeBytes();
    if (range_start_byte < 0 || range_end_byte < 0 ||
        static_cast<uint64_t>(range_start_byte) > total_bytes ||
        static_cast<uint64_t>(range_end_byte) > total_bytes ||
        (range_start_byte & 1LL) || (range_end_byte & 1LL) ||
        range_start_byte > range_end_byte) {
        throw_illegal_args(env, "Native TSH range must be ordered UTF-16LE byte offsets");
        return nullptr;
    }
    NativeHighlightCache::Key cache_key{
        document->version,
        document->source->version(),
        document->partition_index.version(),
        static_cast<uint32_t>(range_start_byte),
        static_cast<uint32_t>(range_end_byte),
        diagnostics == JNI_TRUE
    };
    if (const auto *cached = document->highlight_cache.find(cache_key)) {
        return encodeNativeHighlightResult(
            env, cached->spans, cached->match_counts,
            diagnostics == JNI_TRUE);
    }
    windbell::largefile::TextChunkView view{};
    const uint16_t *input = nullptr;
    std::vector<uint16_t> compatibility_window;
    const auto grammar = WindbellEngine::getInstance().getGrammarByExtension(document->extension);
    const bool source_native_ast_only = grammar &&
        grammar->regex_rules.empty() && grammar->regex_states.empty();
    if (total_bytes != 0 && !source_native_ast_only) {
        if (!document->source->read(0, UINT32_MAX, &view) ||
            !view.data || view.size == 0) {
            throw_illegal_state(env, "Native Piece Tree cannot provide its first input window");
            return nullptr;
        }
        if (view.size == total_bytes) {
            // A source that fits in the first bounded window remains zero-copy.
            input = reinterpret_cast<const uint16_t *>(view.data);
        } else {
            // The legacy contiguous Regex entry point requires one contiguous UTF-16
            // buffer. Rebuilding it after edits defeats Piece Tree storage and can
            // consume hundreds of MiB, preventing large-file viewport rendering.
            // Retain this bridge only for modest documents. For larger edited Piece
            // Trees, leave input null so TextSource-backed Tree-sitter queries still
            // produce absolute captures for the renderer. Regex state rules need their
            // own source-windowed scanners before they can run in this mode.
            constexpr uint64_t kMaxLegacyCompatibilityBytes = 32ULL * 1024ULL * 1024ULL;
            if (total_bytes <= kMaxLegacyCompatibilityBytes) {
                try {
                    compatibility_window.resize(static_cast<size_t>(total_bytes / 2u));
                } catch (...) {
                    compatibility_window.clear();
                }
                if (!compatibility_window.empty()) {
                    uint64_t copied = 0;
                    while (copied < total_bytes) {
                        windbell::largefile::TextChunkView chunk{};
                        const uint32_t request = static_cast<uint32_t>(std::min<uint64_t>(
                            windbell::largefile::kTreeSitterInputChunkBytes, total_bytes - copied));
                        if (!document->source->read(copied, request, &chunk) ||
                            !chunk.data || chunk.size == 0 || chunk.size > total_bytes - copied) {
                            throw_illegal_state(env, "Failed to stream edited Piece Tree into legacy TSH input");
                            return nullptr;
                        }
                        std::memcpy(reinterpret_cast<uint8_t *>(compatibility_window.data()) + copied,
                            chunk.data, chunk.size);
                        copied += chunk.size;
                    }
                    input = compatibility_window.data();
                }
            } else {
                // No whole-document compatibility allocation: source-backed
                // Tree-sitter captures remain available to the viewport renderer.
            }
        }
    }
    std::vector<HighlightSpan> candidates;
    uint32_t counts[4] = {};
    auto spans = WindbellEngine::getInstance().highlightHybrid(
        ts_tree_language(document->tree), document->tree, input,
        document->length_chars, document->extension,
        diagnostics == JNI_TRUE ? &candidates : nullptr,
        diagnostics == JNI_TRUE ? counts : nullptr,
        static_cast<uint32_t>(range_start_byte),
        static_cast<uint32_t>(range_end_byte), document->source.get(),
        &document->regex_state_checkpoints, document->source->version(),
        document->partition_index.version());
    if (diagnostics == JNI_TRUE) spans = std::move(candidates);
    // Materialize the JNI result before transferring span ownership to the
    // cache. This avoids a second full copy of potentially hundreds of
    // thousands of HighlightSpan records on every cache miss.
    jintArray encoded = encodeNativeHighlightResult(
        env, spans, counts, diagnostics == JNI_TRUE);
    if (!encoded || env->ExceptionCheck()) return nullptr;
    NativeHighlightCache::Value cache_value;
    cache_value.spans = std::move(spans);
    std::copy(std::begin(counts), std::end(counts), std::begin(cache_value.match_counts));
    document->highlight_cache.put(cache_key, std::move(cache_value));
    return encoded;
}

JNIEXPORT void JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeCloseDocument(
    JNIEnv *, jobject, jlong document_pointer
) {
    delete reinterpret_cast<NativeTshDocument *>(document_pointer);
}

} // extern "C"