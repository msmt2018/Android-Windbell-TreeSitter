/*
 * JNI Native Transport Layer for Windbell-TSH
 *
 * 内存与 GC 约束：
 * 1. 严格使用 env->GetStringRegion 与扁平数组传输，严防局部引用溢出。
 * 2. 批量将调色板、Span、折叠数据在 Native 栈写入，消除 Java 对象装箱。
 */

#include <jni.h>
#include <vector>
#include <string>
#include "tsh/WindbellTshCore.h"
#include "tsh/WindbellTshCompiler.h"
#include "utils/ts_preconditions.h"
#include "ts__log.h"

using namespace windbell::tsh;

extern "C" {

/**
 * 动态加载并编译 .tsh 源码，绑定目标语言符号
 */
JNIEXPORT jboolean JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeLoadTshSource(
    JNIEnv* env,
    jclass clazz,
    jstring extension_jstr,
    jstring tsh_source_jstr,
    jlong language_ptr
) {
    req_nnp(env, extension_jstr, "extension");
    req_nnp(env, tsh_source_jstr, "tshSourceContent");

    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    const char* src = env->GetStringUTFChars(tsh_source_jstr, nullptr);
    std::string src_str(src);
    env->ReleaseStringUTFChars(tsh_source_jstr, src);

    const TSLanguage* ts_lang = reinterpret_cast<const TSLanguage*>(language_ptr);
    auto grammar = TshCompiler::getInstance().compile(src_str, ts_lang);
    if (!grammar) {
        LOGE("WindbellTSH", "Failed to parse TSH configuration for: %s", ext_str.c_str());
        return JNI_FALSE;
    }

    WindbellEngine::getInstance().registerGrammar(ext_str, grammar);
    return JNI_TRUE;
}

/**
 * 批量传输调色板色彩映射
 * 格式：[StyleID, LightColor(0xAARRGGBB), DarkColor(0xAARRGGBB)]
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetPalette(
    JNIEnv* env,
    jclass clazz,
    jstring extension_jstr
) {
    req_nnp(env, extension_jstr, "extension");
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    auto grammar = WindbellEngine::getInstance().getGrammarByExtension(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    if (!grammar) return env->NewIntArray(0);

    jsize count = static_cast<jsize>(grammar->styles.size());
    jintArray result = env->NewIntArray(count * 3);
    std::vector<jint> flat_palette(count * 3);

    for (jsize i = 0; i < count; ++i) {
        flat_palette[i * 3 + 0] = grammar->styles[i].style_id;
        flat_palette[i * 3 + 1] = static_cast<jint>(grammar->styles[i].light_color);
        flat_palette[i * 3 + 2] = static_cast<jint>(grammar->styles[i].dark_color);
    }

    env->SetIntArrayRegion(result, 0, count * 3, flat_palette.data());
    return result;
}

/**
 * 执行混合分词计算并返回扁平数组
 * 格式：[start_byte, end_byte, style_id | (flags << 16)]
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeHighlight(
    JNIEnv* env,
    jclass clazz,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr
) {
    req_nnp(env, text_jstr, "text");
    req_nnp(env, extension_jstr, "extension");

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    jsize len = env->GetStringLength(text_jstr);
    if (len == 0) return env->NewIntArray(0);

    std::vector<uint16_t> buffer(len);
    env->GetStringRegion(text_jstr, 0, len, reinterpret_cast<jchar*>(buffer.data()));

    std::vector<HighlightSpan> spans = WindbellEngine::getInstance().highlightHybrid(
        tree ? ts_tree_language(tree) : nullptr,
        tree,
        buffer.data(),
        static_cast<uint32_t>(len),
        ext_str
    );

    jsize out_size = static_cast<jsize>(spans.size() * 3);
    jintArray result = env->NewIntArray(out_size);
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
 * 提取代码折叠范围
 * 格式：[start_byte, end_byte, kind]
 */
JNIEXPORT jintArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeComputeFolds(
    JNIEnv* env,
    jclass clazz,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr
) {
    req_nnp(env, tree_ptr, "tree");
    req_nnp(env, text_jstr, "text");
    req_nnp(env, extension_jstr, "extension");

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    jsize len = env->GetStringLength(text_jstr);
    std::vector<uint16_t> buffer(len);
    env->GetStringRegion(text_jstr, 0, len, reinterpret_cast<jchar*>(buffer.data()));

    std::vector<FoldRange> folds = WindbellEngine::getInstance().computeFoldRanges(
        tree, buffer.data(), static_cast<uint32_t>(len), ext_str
    );

    jsize out_size = static_cast<jsize>(folds.size() * 3);
    jintArray result = env->NewIntArray(out_size);
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
 * 提取符号大纲列表
 * 格式：[start_byte, end_byte, kind, name]
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetOutline(
    JNIEnv* env,
    jclass clazz,
    jlong tree_ptr,
    jstring text_jstr,
    jstring extension_jstr
) {
    req_nnp(env, tree_ptr, "tree");
    req_nnp(env, text_jstr, "text");
    req_nnp(env, extension_jstr, "extension");

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    const char* ext = env->GetStringUTFChars(extension_jstr, nullptr);
    std::string ext_str(ext);
    env->ReleaseStringUTFChars(extension_jstr, ext);

    jsize len = env->GetStringLength(text_jstr);
    std::vector<uint16_t> buffer(len);
    env->GetStringRegion(text_jstr, 0, len, reinterpret_cast<jchar*>(buffer.data()));

    auto tags = WindbellEngine::getInstance().computeOutline(tree, buffer.data(), len, ext_str);

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray strArray = env->NewObjectArray(static_cast<jsize>(tags.size() * 4), strClass, nullptr);

    for (size_t i = 0; i < tags.size(); ++i) {
        jstring s_byte = env->NewStringUTF(std::to_string(tags[i].start_byte).c_str());
        jstring e_byte = env->NewStringUTF(std::to_string(tags[i].end_byte).c_str());
        jstring kind = env->NewStringUTF(std::to_string(tags[i].kind).c_str());
        jstring name = env->NewStringUTF(tags[i].name);

        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 0), s_byte);
        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 1), e_byte);
        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 2), kind);
        env->SetObjectArrayElement(strArray, static_cast<jsize>(i * 4 + 3), name);

        env->DeleteLocalRef(s_byte);
        env->DeleteLocalRef(e_byte);
        env->DeleteLocalRef(kind);
        env->DeleteLocalRef(name);
    }

    env->DeleteLocalRef(strClass);
    return strArray;
}

/**
 * 悬停信息查询接口
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetHoverInfo(
    JNIEnv* env,
    jclass clazz,
    jlong tree_ptr,
    jint byte_offset
) {
    req_nnp(env, tree_ptr, "tree");
    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);

    HoverInfo info;
    bool found = WindbellEngine::getInstance().getHoverInfo(tree, static_cast<uint32_t>(byte_offset), info);
    if (!found) return nullptr;

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(4, strClass, nullptr);

    jstring s_byte = env->NewStringUTF(std::to_string(info.start_byte).c_str());
    jstring e_byte = env->NewStringUTF(std::to_string(info.end_byte).c_str());
    jstring type = env->NewStringUTF(info.node_type.c_str());
    jstring detail = env->NewStringUTF(info.detail.c_str());

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