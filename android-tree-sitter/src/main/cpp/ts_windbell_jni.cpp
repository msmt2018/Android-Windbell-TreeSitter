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

#include "tsh/WindbellTshCore.h"
#include "tsh/WindbellTshCompiler.h"
#include "tsh/NativeHighlightCache.h"
#include "largefile/MappedFileSource.h"
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

struct NativeTshDocument {
    std::unique_ptr<windbell::largefile::PieceTreeSource> source;
    TSParser *parser = nullptr;
    TSTree *tree = nullptr;
    std::string extension;
    std::string backing_directory;
    uint32_t length_chars = 0;
    uint64_t version = 0;
    NativeHighlightCache highlight_cache{16u * 1024u * 1024u};
    windbell::largefile::TextPartitionIndex partition_index{256};
    std::vector<MonarchStateCheckpoint> monarch_checkpoints;
    std::mutex mutex;

    ~NativeTshDocument() {
        if (tree) ts_tree_delete(tree);
        if (parser) ts_parser_delete(parser);
    }
};

struct NativeDocumentInput {
    const windbell::largefile::TextSource *source = nullptr;
};

static const char *nativeDocumentRead(
    void *payload, uint32_t byte_index, TSPoint, uint32_t *bytes_read
) {
    auto *input = static_cast<NativeDocumentInput *>(payload);
    if (!input || !input->source || !bytes_read) {
        if (bytes_read) *bytes_read = 0;
        return nullptr;
    }
    windbell::largefile::TextChunkView chunk{};
    if (!input->source->read(
            byte_index, windbell::largefile::kTreeSitterInputChunkBytes, &chunk) ||
        !chunk.data || chunk.size == 0) {
        *bytes_read = 0;
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
static bool copySourceRange(
    const windbell::largefile::TextSource &source,
    int destination_fd,
    uint64_t source_offset,
    uint64_t length,
    uint64_t destination_offset
) {
    uint64_t copied = 0;
    while (copied < length) {
        windbell::largefile::TextChunkView chunk{};
        const uint32_t request = static_cast<uint32_t>(std::min<uint64_t>(
            windbell::largefile::kTreeSitterInputChunkBytes, length - copied));
        if (!source.read(source_offset + copied, request, &chunk) ||
            !chunk.data || chunk.size == 0 || chunk.size > length - copied) return false;
        if (!writeAllAt(destination_fd,
                reinterpret_cast<const uint8_t *>(chunk.data), chunk.size,
                destination_offset + copied)) return false;
        copied += chunk.size;
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
    if (!line_index.lineForByte(source, byte_offset, &row) ||
        !line_index.byteForLine(source, row, &line_start) ||
        line_start > byte_offset ||
        byte_offset - line_start > std::numeric_limits<uint32_t>::max()) return false;
    point->row = static_cast<uint32_t>(std::min<uint64_t>(
        row, std::numeric_limits<uint32_t>::max()));
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
        std::vector<jbyte> window(static_cast<size_t>(count));
        env->GetByteArrayRegion(bytes, 0, count, window.data());
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck() ||
            !writeAllAt(fd, reinterpret_cast<const uint8_t *>(window.data()),
                        window.size(), offset)) {
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
    auto mapped_source = std::make_shared<windbell::largefile::MappedFileSource>();
    if (!mapped_source->open(backing_path)) {
        ::unlink(backing_path.c_str());
        throw_illegal_state(env, "Unable to memory-map native document source");
        return 0;
    }
    ::unlink(backing_path.c_str());
    document->source = std::make_unique<windbell::largefile::PieceTreeSource>(
        std::static_pointer_cast<const windbell::largefile::TextSource>(mapped_source));
    if (!document->source->isValid()) {
        throw_illegal_state(env, "Unable to initialize native Piece Tree document source");
        return 0;
    }
    document->extension = extension;
    document->backing_directory = directory;
    document->length_chars = static_cast<uint32_t>(static_cast<uint64_t>(byte_length) / 2u);
    document->parser = ts_parser_new();
    if (!document->parser || !ts_parser_set_language(
            document->parser, reinterpret_cast<const TSLanguage *>(language_pointer))) {
        throw_illegal_state(env, "Unable to initialize Tree-sitter for native document");
        return 0;
    }
    NativeDocumentInput payload{document->source.get()};
    TSInput input{&payload, nativeDocumentRead, TSInputEncodingUTF16LE, nullptr};
    document->tree = ts_parser_parse(document->parser, nullptr, input);
    if (!document->tree) {
        throw_illegal_state(env, "Tree-sitter failed to parse native document");
        return 0;
    }
    return reinterpret_cast<jlong>(document.release());
}

/**
 * Applies one UTF-16LE edit to the native document and incrementally reparses
 * from the edited Tree-sitter tree. The immutable old mapping remains alive
 * until the new mapping and tree have both been created successfully.
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

    // Build an isolated Piece Tree candidate. The immutable mmap and append
    // chunks are shared; unchanged document bytes are never copied to a new file.
    // Only the tree nodes and inserted UTF-16LE bytes are newly allocated.
    auto next_source = document->source->clone();
    if (!next_source) {
        throw_illegal_state(env, "Unable to clone native Piece Tree transaction");
        return JNI_FALSE;
    }
    std::vector<jbyte> inserted_for_points(static_cast<size_t>(inserted_size));
    if (inserted_size > 0) {
        env->GetByteArrayRegion(inserted_bytes, 0, inserted_size, inserted_for_points.data());
        if (env->ExceptionCheck()) return JNI_FALSE;
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
    TSPoint new_end_point = start_point;
    for (jsize i = 0; i + 1 < inserted_size; i += 2) {
        const uint16_t unit = static_cast<uint16_t>(
            static_cast<uint8_t>(inserted_for_points[static_cast<size_t>(i)])) |
            (static_cast<uint16_t>(static_cast<uint8_t>(
                inserted_for_points[static_cast<size_t>(i + 1)])) << 8);
        if (unit == '\n') {
            ++new_end_point.row;
            new_end_point.column = 0;
        } else {
            new_end_point.column += 2;
        }
    }
    if (!next_source->replace(
            static_cast<uint64_t>(start_byte), static_cast<uint64_t>(old_end_byte),
            inserted_for_points.empty() ? nullptr : inserted_for_points.data(),
            static_cast<uint64_t>(inserted_size))) {
        throw_illegal_state(env, "Native Piece Tree rejected the edit (memory budget or range)");
        return JNI_FALSE;
    }

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

    NativeDocumentInput payload{next_source.get()};
    TSInput input{&payload, nativeDocumentRead, TSInputEncodingUTF16LE, nullptr};
    TSTree *next_tree = ts_parser_parse(document->parser, edited_tree, input);
    ts_tree_delete(edited_tree);
    if (!next_tree) {
        throw_illegal_state(env, "Tree-sitter incremental reparse failed; original document retained");
        return JNI_FALSE;
    }

    // Preserve unaffected native line checkpoints and shift the suffix by
    // the edit's byte/line deltas. If the sparse index cannot update, reset it;
    // correctness never depends on the optional index.
    if (!document->partition_index.applyEdit(
            *document->source, static_cast<uint64_t>(start_byte),
            static_cast<uint64_t>(old_end_byte),
            inserted_for_points.data(), static_cast<uint64_t>(inserted_size))) {
        document->partition_index.reset();
    }
    const uint64_t old_source_version = document->source->version();
    ts_tree_delete(document->tree);
    document->tree = next_tree;
    document->source = std::move(next_source);
    document->length_chars = static_cast<uint32_t>(new_size / 2u);
    ++document->version;
    document->highlight_cache.clear();

    // Keep tokenizer checkpoints before the edit as immediately reusable. Keep
    // checkpoints after the replaced range as *candidates* at their shifted byte
    // offsets: they become reusable only after a fresh scan reaches the same
    // offset with an identical Monarch state stack (state convergence).
    const uint64_t new_source_version = document->source->version();
    const uint64_t new_partition_version = document->partition_index.version();
    const int64_t byte_delta = static_cast<int64_t>(inserted_size) -
        static_cast<int64_t>(old_end_byte - start_byte);
    std::vector<MonarchStateCheckpoint> retained;
    retained.reserve(document->monarch_checkpoints.size());
    for (auto checkpoint : document->monarch_checkpoints) {
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
    document->monarch_checkpoints.swap(retained);
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
        grammar->regex_rules.empty() && grammar->monarch_states.empty();
    if (total_bytes != 0 && !source_native_ast_only) {
        if (!document->source->read(0, UINT32_MAX, &view) ||
            !view.data || view.size == 0) {
            throw_illegal_state(env, "Native Piece Tree cannot provide its first input window");
            return nullptr;
        }
        if (view.size == total_bytes) {
            // Unedited mmap-backed documents remain zero-copy.
            input = reinterpret_cast<const uint16_t *>(view.data);
        } else {
            // The legacy Regex/Monarch entry point requires one contiguous UTF-16
            // buffer. Rebuilding it after edits defeats Piece Tree storage and can
            // consume hundreds of MiB, preventing large-file viewport rendering.
            // Retain this bridge only for modest documents. For larger edited Piece
            // Trees, leave input null so TextSource-backed Tree-sitter queries still
            // produce absolute captures for the renderer. Regex/Monarch need their
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
        &document->monarch_checkpoints, document->source->version(),
        document->partition_index.version());
    if (diagnostics == JNI_TRUE) spans = std::move(candidates);
    NativeHighlightCache::Value cache_value;
    cache_value.spans = spans;
    std::copy(std::begin(counts), std::end(counts), std::begin(cache_value.match_counts));
    document->highlight_cache.put(cache_key, std::move(cache_value));
    return encodeNativeHighlightResult(env, spans, counts, diagnostics == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeCloseDocument(
    JNIEnv *, jobject, jlong document_pointer
) {
    delete reinterpret_cast<NativeTshDocument *>(document_pointer);
}

} // extern "C"