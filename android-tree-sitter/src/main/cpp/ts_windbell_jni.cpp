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

#include "tsh/WindbellTshCore.h"
#include "tsh/WindbellTshCompiler.h"
#include "utils/ts_exceptions.h"
#include "utils/ts_preconditions.h"
#include "ts__log.h"

using namespace windbell::tsh;

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
    
    // 动态编译 DSL 规则并对齐 Tree-sitter AST 符号
    auto grammar = TshCompiler::getInstance().compile(src_str, ts_lang);
    if (!grammar) {
        LOGE("WindbellTSH", "Failed to compile TSH grammar rules for: %s", ext_str.c_str());
        return JNI_FALSE;
    }

    WindbellEngine::getInstance().registerGrammar(ext_str, grammar);
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
            if (!metadata.empty()) metadata.push_back('\x1E');
            metadata += entry.first;
            metadata.push_back('\x1F');
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
 * 返回结构: [start_byte, end_byte, packed_style_flags, priority, declaration_order] * N
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeHighlight(
    JNIEnv* env,
    jobject /* thiz */,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr
) {
    if (!text_jstr || !extension_jstr) {
        throw_npe(env, "text or extension cannot be null");
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

    // 利用 UTF-16LE 零重编码特征：无损获取 Java 内存原始双字节字符流
    std::vector<uint16_t> buffer(len);
    env->GetStringRegion(text_jstr, 0, len, reinterpret_cast<jchar*>(buffer.data()));

    std::vector<HighlightSpan> spans = WindbellEngine::getInstance().highlightHybrid(
        tree ? ts_tree_language(tree) : nullptr,
        tree,
        buffer.data(),
        static_cast<uint32_t>(len),
        ext_str
    );

    const jsize out_size = static_cast<jsize>(spans.size() * 5);
    jintArray result = env->NewIntArray(out_size);
    if (!result) {
        throw_npe(env, "Out of memory allocating spans int array");
        return nullptr;
    }

    std::vector<jint> flat_spans(out_size);
    for (size_t i = 0; i < spans.size(); ++i) {
        flat_spans[i * 5 + 0] = static_cast<jint>(spans[i].start_byte);
        flat_spans[i * 5 + 1] = static_cast<jint>(spans[i].end_byte);
        flat_spans[i * 5 + 2] = static_cast<jint>(spans[i].style_id | (static_cast<uint32_t>(spans[i].flags) << 16));
        flat_spans[i * 5 + 3] = static_cast<jint>(spans[i].priority);
        flat_spans[i * 5 + 4] = static_cast<jint>(spans[i].declaration_order);
    }

    env->SetIntArrayRegion(result, 0, out_size, flat_spans.data());
    return result;
}

} // extern "C"