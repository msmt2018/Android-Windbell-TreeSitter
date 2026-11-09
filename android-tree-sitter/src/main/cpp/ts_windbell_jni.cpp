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
#include <utility>

#include "tsh/WindbellTshCore.h"
#include "tsh/WindbellTshCompiler.h"
#include "utils/ts_exceptions.h"
#include "utils/ts_preconditions.h"
#include "utils/jni_string.h"
#include "ts__log.h"

using namespace windbell::tsh;

namespace {
/** Pins/accesses JVM UTF-16 storage without allocating a second native vector copy. */
class ScopedStringChars {
public:
    ScopedStringChars(JNIEnv* env, jstring value)
        : env_(env), value_(value), chars_(env && value ? env->GetStringChars(value, nullptr) : nullptr) {}
    ~ScopedStringChars() {
        if (chars_) env_->ReleaseStringChars(value_, chars_);
    }
    ScopedStringChars(const ScopedStringChars&) = delete;
    ScopedStringChars& operator=(const ScopedStringChars&) = delete;
    const jchar* get() const { return chars_; }
    explicit operator bool() const { return chars_ != nullptr; }
private:
    JNIEnv* env_;
    jstring value_;
    const jchar* chars_;
};
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

    std::string ext_str;
    if (!FNI_GetStandardUtf8(env, extension_jstr, ext_str)) {
        return JNI_FALSE;
    }

    std::string src_str;
    if (!FNI_GetStandardUtf8(env, tsh_source_jstr, src_str)) {
        return JNI_FALSE;
    }

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

    std::string ext_str;
    if (!FNI_GetStandardUtf8(env, extension_jstr, ext_str)) {
        return nullptr;
    }

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
    std::string ext_str;
    if (!FNI_GetStandardUtf8(env, extension_jstr, ext_str)) {
        return nullptr;
    }

    const jsize len = env->GetStringLength(text_jstr);
    if (len == 0) return env->NewIntArray(0);

    // Avoid an additional full-document std::vector<uint16_t> allocation.
    std::vector<HighlightSpan> spans;
    {
        ScopedStringChars chars(env, text_jstr);
        if (!chars) return nullptr;
        spans = WindbellEngine::getInstance().highlightHybrid(
            tree ? ts_tree_language(tree) : nullptr,
            tree,
            reinterpret_cast<const uint16_t*>(chars.get()),
            static_cast<uint32_t>(len),
            ext_str
        );
    }

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

/**
 * 4. 计算 AST 代码折叠区间
 * 签名: (JLjava/lang/String;Ljava/lang/String;)[I
 * 返回结构: [start_byte, end_byte, kind] * N
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeComputeFolds(
    JNIEnv* env,
    jobject /* thiz */,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr
) {
    if (!tree_ptr || !text_jstr) {
        throw_npe(env, "tree pointer or text cannot be null");
        return nullptr;
    }

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    std::string ext_str;
    if (extension_jstr != nullptr) {
        if (!FNI_GetStandardUtf8(env, extension_jstr, ext_str)) {
            return nullptr;
        }
    }

    const jsize len = env->GetStringLength(text_jstr);
    std::vector<FoldRange> folds;
    {
        ScopedStringChars chars(env, text_jstr);
        if (!chars) return nullptr;
        folds = WindbellEngine::getInstance().computeFoldRanges(
            tree, reinterpret_cast<const uint16_t*>(chars.get()),
            static_cast<uint32_t>(len), ext_str
        );
    }

    const jsize out_size = static_cast<jsize>(folds.size() * 3);
    jintArray result = env->NewIntArray(out_size);
    if (!result) {
        throw_npe(env, "Out of memory allocating folds int array");
        return nullptr;
    }

    std::vector<jint> flat_folds(out_size);
    for (size_t i = 0; i < folds.size(); ++i) {
        flat_folds[i * 3 + 0] = static_cast<jint>(folds[i].start_byte);
        flat_folds[i * 3 + 1] = static_cast<jint>(folds[i].end_byte);
        flat_folds[i * 3 + 2] = static_cast<jint>(folds[i].kind);
    }

    env->SetIntArrayRegion(result, 0, out_size, flat_folds.data());
    return result;
}

/**
 * 5. 导出符号大纲表（支持严密的局部引用管理，杜绝 512 LocalRef 溢出崩溃）
 * 签名: (JLjava/lang/String;Ljava/lang/String;)[Ljava/lang/String;
 * 返回结构: [start_byte, end_byte, kind, name] * N
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetOutline(
    JNIEnv* env,
    jobject /* thiz */,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr
) {
    if (!tree_ptr || !text_jstr) {
        throw_npe(env, "tree pointer or text cannot be null");
        return nullptr;
    }

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    std::string ext_str;
    if (extension_jstr != nullptr) {
        if (!FNI_GetStandardUtf8(env, extension_jstr, ext_str)) {
            return nullptr;
        }
    }

    const jsize len = env->GetStringLength(text_jstr);
    std::vector<OutlineTag> tags;
    {
        ScopedStringChars chars(env, text_jstr);
        if (!chars) return nullptr;
        tags = WindbellEngine::getInstance().computeOutline(
            tree, reinterpret_cast<const uint16_t*>(chars.get()),
            static_cast<uint32_t>(len), ext_str
        );
    }

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) {
        return nullptr;
    }

    const jsize total_elements = static_cast<jsize>(tags.size() * 4);
    jobjectArray strArray = env->NewObjectArray(total_elements, strClass, nullptr);
    if (!strArray) {
        env->DeleteLocalRef(strClass);
        throw_npe(env, "Out of memory allocating outline string array");
        return nullptr;
    }

    for (size_t i = 0; i < tags.size(); ++i) {
        jstring s_byte = env->NewStringUTF(std::to_string(tags[i].start_byte).c_str());
        jstring e_byte = env->NewStringUTF(std::to_string(tags[i].end_byte).c_str());
        jstring kind   = env->NewStringUTF(std::to_string(tags[i].kind).c_str());
        jstring name   = FNI_NewStringFromStandardUtf8(env, tags[i].name);

        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 0), s_byte);
        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 1), e_byte);
        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 2), kind);
        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 3), name);

        // 必须在每一次迭代后显式释放，严防 Android 512 LocalRef 溢出
        env->DeleteLocalRef(s_byte);
        env->DeleteLocalRef(e_byte);
        env->DeleteLocalRef(kind);
        env->DeleteLocalRef(name);
    }

    env->DeleteLocalRef(strClass);
    return strArray;
}

/**
 * 6. AST 节点悬停诊断与类型反查
 * 签名: (JI)[Ljava/lang/String;
 * 返回结构: [start_byte, end_byte, node_type, detail]
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetHoverInfo(
    JNIEnv* env,
    jobject /* thiz */,
    jlong tree_ptr,
    jint byte_offset
) {
    if (!tree_ptr) {
        throw_npe(env, "tree pointer cannot be null");
        return nullptr;
    }
    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);

    HoverInfo info;
    const bool found = WindbellEngine::getInstance().getHoverInfo(
        tree,
        static_cast<uint32_t>(byte_offset),
        info
    );
    if (!found) {
        return nullptr;
    }

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) {
        return nullptr;
    }

    jobjectArray arr = env->NewObjectArray(4, strClass, nullptr);
    if (!arr) {
        env->DeleteLocalRef(strClass);
        return nullptr;
    }

    jstring s_byte = env->NewStringUTF(std::to_string(info.start_byte).c_str());
    jstring e_byte = env->NewStringUTF(std::to_string(info.end_byte).c_str());
    jstring type   = FNI_NewStringFromStandardUtf8(env, info.node_type);
    jstring detail = FNI_NewStringFromStandardUtf8(env, info.detail);

    env->SetObjectArrayElement(arr, 0, s_byte);
    env->SetObjectArrayElement(arr, 1, e_byte);
    env->SetObjectArrayElement(arr, 2, type);
    env->SetObjectArrayElement(arr, 3, detail);

    env->DeleteLocalRef(s_byte);
    env->DeleteLocalRef(e_byte);
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(detail);
    env->DeleteLocalRef(strClass);

    return arr;
}

} // extern "C"