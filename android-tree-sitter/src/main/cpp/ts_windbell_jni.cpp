/*
 * Copyright © 2026 com.zerostudio.idea.treesitter
 *
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * 工业级生产环境 JNI 边界通信实现：全量动态装配、零硬编码分词、内存安全与局部引用拦截
 */

#include <jni.h>
#include <vector>
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
 * Export semantic style catalog entries as six strings each:
 * id, name, scope, description, parent, tags joined with U+001F.
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

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(grammar->styles.size() * 6), string_class, nullptr);
    if (!result) { env->DeleteLocalRef(string_class); return nullptr; }
    for (size_t i = 0; i < grammar->styles.size(); ++i) {
        const auto& style = grammar->styles[i];
        std::string tags;
        for (size_t tag_index = 0; tag_index < style.tags.size(); ++tag_index) {
            if (tag_index) tags.push_back('\x1F');
            tags += style.tags[tag_index];
        }
        const std::string values[] = {
            std::to_string(style.style_id), style.name, style.scope,
            style.description, style.parent, tags
        };
        for (size_t field = 0; field < 6; ++field) {
            jstring value = env->NewStringUTF(values[field].c_str());
            env->SetObjectArrayElement(result, static_cast<jsize>(i * 6 + field), value);
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
 * A zero color means that the DSL did not define a value for that appearance.
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