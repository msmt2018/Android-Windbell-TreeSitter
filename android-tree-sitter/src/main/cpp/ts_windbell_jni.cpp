/*
 * Copyright © 2026 com.zerostudio.idea.treesitter
 *
 * Windbell Treesitter Highlighting Configuration Engine (Windbell-TSH)
 * 工业级生产环境 JNI 边界通信实现：全量动态装配、零硬编码分词、内存安全与局部引用拦截
 */

#include <jni.h>
#include <vector>
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
 * 2. 导出指定语法的高效多态调色板表
 * 签名: (Ljava/lang/String;)[I
 * 返回结构: [StyleID, LightColor(0xAARRGGBB), DarkColor(0xAARRGGBB)] * N
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetPalette(
    JNIEnv* env,
    jobject /* thiz */,
    jstring extension_jstr
) {
    if (!extension_jstr) {
        throw_npe(env, "extension cannot be null");
        return nullptr;
    }

    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    if (!ext) {
        throw_npe(env, "Failed to extract extension string");
        return nullptr;
    }
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(ext_str);
    if (!grammar) {
        return env->NewIntArray(0);
    }

    const jsize count = static_cast<jsize>(grammar->styles.size());
    jintArray result = env->NewIntArray(count * 3);
    if (!result) {
        throw_npe(env, "Out of memory allocating palette int array");
        return nullptr;
    }

    std::vector<jint> flat_palette(count * 3);
    for (jsize i = 0; i < count; ++i) {
        flat_palette[i * 3 + 0] = static_cast<jint>(grammar->styles[i].style_id);
        flat_palette[i * 3 + 1] = static_cast<jint>(grammar->styles[i].light_color);
        flat_palette[i * 3 + 2] = static_cast<jint>(grammar->styles[i].dark_color);
    }

    env->SetIntArrayRegion(result, 0, count * 3, flat_palette.data());
    return result;
}

/**
 * 3. 双通道混合高亮计算 (微观正则 + 宏观 AST 语法树剪枝)
 * 签名: (JLjava/lang/String;Ljava/lang/String;)[I
 * 返回结构: [start_byte, end_byte, (style_id | (font_flags << 16))] * N
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

    const jsize out_size = static_cast<jsize>(spans.size() * 3);
    jintArray result = env->NewIntArray(out_size);
    if (!result) {
        throw_npe(env, "Out of memory allocating spans int array");
        return nullptr;
    }

    std::vector<jint> flat_spans(out_size);
    for (size_t i = 0; i < spans.size(); ++i) {
        flat_spans[i * 3 + 0] = static_cast<jint>(spans[i].start_byte);
        flat_spans[i * 3 + 1] = static_cast<jint>(spans[i].end_byte);
        flat_spans[i * 3 + 2] = static_cast<jint>(spans[i].style_id | (static_cast<uint32_t>(spans[i].flags) << 16));
    }

    env->SetIntArrayRegion(result, 0, out_size, flat_spans.data());
    return result;
}

} // extern "C"