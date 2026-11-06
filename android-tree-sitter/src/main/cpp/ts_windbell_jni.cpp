#include <jni.h>
#include <vector>
#include <string>
#include "tsh/WindbellTshCore.h"
#include "tsh/WindbellTshCompiler.h"
#include "utils/ts_preconditions.h"

using namespace windbell::tsh;

extern "C" {

/**
 * 导出指定语法的高效多态调色板表
 * 返回 int 数组，每个 Style 占 3 个 int: [StyleID, LightColor(0xAARRGGBB), DarkColor(0xAARRGGBB)]
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
    std::vector<jint> data(count * 3);

    for (jsize i = 0; i < count; ++i) {
        data[i * 3 + 0] = grammar->styles[i].style_id;
        data[i * 3 + 1] = static_cast<jint>(grammar->styles[i].light_color);
        data[i * 3 + 2] = static_cast<jint>(grammar->styles[i].dark_color);
    }

    env->SetIntArrayRegion(result, 0, count * 3, data.data());
    return result;
}

/**
 * 导出符号大纲表
 * 每个 Tag 占 3 个 int: [start_byte, end_byte, kind] + 附带名称
 */
JNIEXPORT jobjectArray JNICALL
Java_com_zerostudio_idea_treesitter_tsh_WindbellTshEngine_nativeGetOutline(
    JNIEnv* env,
    jclass clazz,
    jlong tree_ptr,
    jstring text_jstr
) {
    req_nnp(env, tree_ptr, "tree");
    req_nnp(env, text_jstr, "text");

    TSTree* tree = reinterpret_cast<TSTree*>(tree_ptr);
    jsize len = env->GetStringLength(text_jstr);
    std::vector<uint16_t> buffer(len);
    env->GetStringRegion(text_jstr, 0, len, reinterpret_cast<jchar*>(buffer.data()));

    auto tags = WindbellEngine::getInstance().computeOutline(tree, buffer.data(), len);
    
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

} // extern "C"