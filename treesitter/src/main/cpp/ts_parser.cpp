/*
 * Copyright © 2023 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Derived from kotlin-tree-sitter (https://github.com/tree-sitter/kotlin-tree-sitter),
 * Copyright (c) 2024 tree-sitter contributors, under the MIT License: see
 * LICENSE-kotlin-tree-sitter at the root of this module. Its JNI layer, written in C there,
 * rewritten in C++, and changed since.
 */

#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <climits>

#include "ts_utils.h"

#ifdef __cplusplus
extern "C" {
#endif

// What one parse needs between the calls of its read callback: the Kotlin callback
// and the chunk it answered last, which tree-sitter reads until it asks again. One
// per parse, on the stack of parser_parse_function: parses may run on several
// threads at once (a reparse in the background, an injected part on the main
// thread), so nothing of this may be shared between them.
struct ReadState {
    JNIEnv *env;
    jobject callback;
    jbyteArray bytes;
    jbyte *chunk;
};

// lets go of the chunk answered last: its elements, and the local reference to it
// (a parse that reads thousands of chunks would otherwise fill the local reference
// table of the JNI frame)
static void release_chunk(ReadState *state) {
    if (state->bytes != nullptr) {
        if (state->chunk != nullptr) {
            state->env->ReleaseByteArrayElements(state->bytes, state->chunk, JNI_ABORT);
        }
        state->env->DeleteLocalRef(state->bytes);
    }
    state->bytes = nullptr;
    state->chunk = nullptr;
}

jlong JNICALL parser_init() {
    return reinterpret_cast<jlong>(ts_parser_new()); 
}

void JNICALL parser_delete(JNIEnv *env, jclass clazz, jlong parser) {
    TSParser *self = reinterpret_cast<TSParser*>(parser);
    TSLogger logger = ts_parser_logger(self);
    if (logger.payload != nullptr)
        env->DeleteGlobalRef((jobject)logger.payload);
    ts_parser_delete(self);
}

void JNICALL parser_reset(JNIEnv *env, jobject thiz) {
    TSParser *self = GET_POINTER(TSParser, thiz);
    ts_parser_reset(self);
}

void JNICALL parser_set_language(JNIEnv *env, jobject thiz, jobject value) {    
    TSParser *self = GET_POINTER(TSParser, thiz);
    TSLanguage *language = GET_POINTER(TSLanguage, value);
    ts_parser_set_language(self, language);
    env->SetObjectField(thiz, global_field_cache.TSParser_language, value);
}

void JNICALL parser_set_included_ranges(JNIEnv *env, jobject thiz, jobject value) {
    // get the parser
    TSParser *self = GET_POINTER(TSParser, thiz);
    // get the list size    
    uint32_t size = (uint32_t)CALL_METHOD_NO_ARGS(Int, value, List_size);
    
    // calloc native TSRange array
    TSRange *ranges = (TSRange *)calloc(size, sizeof(TSRange));
    for (uint32_t i = 0; i < size; ++i) {
        jobject range_object = CALL_METHOD(Object, value, List_get, (jint)i);
        *(ranges + i) = unmarshal_range(env, range_object);
        env->DeleteLocalRef(range_object);
    }
    
    if (ts_parser_set_included_ranges(self, ranges, size)) {
        env->SetObjectField(thiz, global_field_cache.TSParser_includedRanges, value);
    } else {
        THROW(IllegalArgumentException, "Included ranges must be in ascending order and not overlap");
    }
    // here requires free memory
    free((void*)ranges);
}

void JNICALL parser_dot_graphs(JNIEnv* env, jobject thiz, jstring pathname) {
    TSParser *self = GET_POINTER(TSParser, thiz);
    const char *path =env->GetStringUTFChars(pathname, nullptr); 
    // here may requires MANAGE_EXTERNAL_STORAGE permission
    int fp = open(path, O_CREAT|O_TRUNC|O_RDWR, 0666);
    if(fp < 0) 
        LOGE("Error: %s\n", strerror(errno));
    else 
        ts_parser_print_dot_graphs(self, fp);
    
    env->ReleaseStringUTFChars(pathname, path);
}

// the callback param is kotlin lambda
void JNICALL parser_set_logger(JNIEnv *env, jobject thiz, jobject value) {    
    // convert lambda to C-Style function pointer
    auto callback = [](void *payload, TSLogType type, const char *buffer) {
        // local JNIEnv pointer
        JNIEnv *env = ::getEnv();
        // java enum TSLogType object
        jobject log_type = nullptr;       
        switch(type) {
            case TSLogTypeParse:
                log_type = GET_STATIC_FIELD(Object, TSParser$LogType, TSParser$LogType_PARSE);
                break;
            case TSLogTypeLex:
                log_type = GET_STATIC_FIELD(Object, TSParser$LogType, TSParser$LogType_LEX);
                break;
            default:
                UNREACHABLE();
        }
        
        // the message from native TSLogType
        jstring message = env->NewStringUTF(buffer);        
        // call the kotlin TSParser logger lambda     
        CALL_METHOD(Object, (jobject)payload, Function2_invoke, log_type, message);
    };
    
    TSParser *self = GET_POINTER(TSParser, thiz);
    TSLogger logger = ts_parser_logger(self);
    if (logger.payload != nullptr) {
        // release previous global logger reference
        env->DeleteGlobalRef(reinterpret_cast<jobject>(logger.payload));
    }
    // check the kotlin lambda object
    if (value != nullptr) {        
        logger.payload = reinterpret_cast<void*>(
            // new a global reference
            env->NewGlobalRef(value)
        );
        logger.log = callback;
    } else {
        logger.payload = nullptr;
        logger.log = nullptr;
    }
    // native set logger
    ts_parser_set_logger(self, logger);
    env->SetObjectField(thiz, global_field_cache.TSParser_logger, value);
}

static inline TSInputEncoding get_encoding(JNIEnv *env, jobject encoding) {
    jobject UTF_8 = GET_STATIC_FIELD(Object, TSInputEncoding, TSInputEncoding_UTF_8);
    if (encoding == nullptr || env->IsSameObject(encoding, UTF_8)) {
        return TSInputEncodingUTF8;
    }
    
    jobject UTF_16LE = GET_STATIC_FIELD(Object, TSInputEncoding, TSInputEncoding_UTF_16LE);
    if (env->IsSameObject(encoding, UTF_16LE)) {
        return TSInputEncodingUTF16LE;
    }
    
    jobject UTF_16BE = GET_STATIC_FIELD(Object, TSInputEncoding, TSInputEncoding_UTF_16BE);
    if (env->IsSameObject(encoding, UTF_16BE)) {
        return TSInputEncodingUTF16BE;
    }
    UNREACHABLE();
}

jobject JNICALL parser_parse_string(
    JNIEnv *env, jobject thiz, jobject oldTree, jobject charset, jbyteArray byte_array
) { 
    // get the java TSLanguage object from java TSParser
    // note here language is jobect not native type
    jobject language = GET_FIELD(Object, thiz, TSParser_language);
    if (language == nullptr) {
        THROW(IllegalStateException, "The parser has no language assigned");
    }
    // native TSParser pointer
    TSParser *self = GET_POINTER(TSParser, thiz);  
    // text encoding, UTF-8 or UTF-16   
    TSInputEncoding input_encoding = get_encoding(env, charset);
    jbyte *byte_chars = env->GetByteArrayElements(byte_array, nullptr);
    size_t length = env->GetArrayLength(byte_array);
    
    // old native TSTree
    TSTree *old_tree = oldTree ? GET_POINTER(TSTree, oldTree) : nullptr;
    // new native TSTree    
    TSTree *new_tree = ts_parser_parse_string_encoding(
        self, old_tree, reinterpret_cast<const char*>(byte_chars), length, input_encoding
    );
    
    jstring source = nullptr;
    // note here requires to check the encoding
    if(input_encoding == TSInputEncodingUTF8) {
        source = env->NewStringUTF(reinterpret_cast<const char*>(byte_chars));
    } else {
        source = env->NewString(reinterpret_cast<const jchar*>(byte_chars), length / 2);
    }
    
    env->ReleaseByteArrayElements(byte_array, byte_chars, JNI_ABORT);
    // return the new java TSTree object
    return NEW_OBJECT(TSTree, reinterpret_cast<jlong>(new_tree), source, language);
}


// read callback
static const char *parse_read_callback(
    void *payload, uint32_t byte_index, TSPoint point, uint32_t *bytes_read
) {
    ReadState *state = static_cast<ReadState *>(payload);
    JNIEnv *env = state->env;
    // tree-sitter is done with the previous chunk once it asks for another one
    release_chunk(state);
    // the callback threw before: no more Java calls with that exception pending
    if (env->ExceptionCheck()) {
        *bytes_read = 0;
        return "";
    }

    // local java TSPoint object
    jobject position = NEW_OBJECT(TSPoint, point.row, point.column);
    // convert kotlin Int to UInt
    jobject uint_index = env->AllocObject(global_class_cache.UInt);
    env->SetIntField(uint_index, global_field_cache.UInt_data, static_cast<jint>(byte_index));

    // call kotlin ParseCallback lambda
    jbyteArray chunk_bytes = reinterpret_cast<jbyteArray>(
        CALL_METHOD(Object, state->callback, Function2_invoke, uint_index, position)
    );
    env->DeleteLocalRef(uint_index);
    env->DeleteLocalRef(position);

    if (env->ExceptionCheck() || chunk_bytes == nullptr) {
        // the callback failed: the end of the input, the exception goes up afterwards
        *bytes_read = 0;
        return "";
    }
    state->bytes = chunk_bytes;
    state->chunk = env->GetByteArrayElements(chunk_bytes, nullptr);
    *bytes_read = static_cast<uint32_t>(env->GetArrayLength(chunk_bytes));
    // return the native string
    return reinterpret_cast<const char *>(state->chunk);
}

// progress callback
static bool parse_progress_callback(TSParseState *state) {    
    // local JNIEnv pointer
    JNIEnv *env = ::getEnv();    
    jobject offset = env->AllocObject(global_class_cache.UInt);
    env->SetIntField(offset, global_field_cache.UInt_data, (jint)state->current_byte_offset);
    jobject error = NEW_OBJECT(Boolean, (jboolean)state->has_error);
    jobject result = CALL_METHOD(Object, (jobject)state->payload, Function2_invoke, offset, error);
    env->DeleteLocalRef(offset);
    env->DeleteLocalRef(error);
    return static_cast<bool>(
        env->GetBooleanField(result, global_field_cache.Boolean_value)
    );
}

jobject JNICALL parser_parse_function(
    JNIEnv *env, jobject thiz, jobject oldTree, 
    jobject charset, jobject progress_callback, jobject read_callback
) {   
    // get the java TSLanguage object from java TSParser
    // note here language is jobect not native type
    jobject language = GET_FIELD(Object, thiz, TSParser_language);
    if (language == nullptr) {
        THROW(IllegalStateException, "The parser has no language assigned");
        return nullptr;
    }    
    
    // native TSParser pointer
    TSParser *self = GET_POINTER(TSParser, thiz);
    // text encoding, UTF-8 or UTF-16
    TSInputEncoding input_encoding = get_encoding(env, charset);
    
    ReadState state = { env, read_callback, nullptr, nullptr };
    TSInput input = {
        .payload = (void *)&state,
        .read = parse_read_callback,
        .encoding = input_encoding,
    };
    
    // old native TSTree
    TSTree *old_tree = oldTree ? GET_POINTER(TSTree, oldTree) : nullptr;
    // new native TSTree
    TSTree *new_tree = nullptr;
    // ...
    if (progress_callback != nullptr) {
        TSParseOptions options = {
            .payload = (void *)progress_callback,
            .progress_callback = parse_progress_callback,
        };
        new_tree = ts_parser_parse_with_options(self, old_tree, input, options);
    } else {
        new_tree = ts_parser_parse(self, old_tree, input);
    }
    // the last chunk read
    release_chunk(&state);
    // the read callback threw: that goes up to Kotlin, not a tree of half the text
    if (env->ExceptionCheck()) {
        if (new_tree != nullptr) ts_tree_delete(new_tree);
        return nullptr;
    }
        
    // return the new java TSTree object
    return NEW_OBJECT(TSTree, reinterpret_cast<jlong>(new_tree), nullptr, language);
}


/*
 * Bounded TSInput bridge. One Java byte[] is reused for every read; its pinned/native view is
 * released only when Tree-sitter asks for the next chunk, because the previous pointer remains
 * valid until that point. This avoids allocating one Java array per chunk and never flattens the
 * complete document in JNI.
 */
struct InputReadState {
    JNIEnv *env;
    jobject input;
    jmethodID read_method;
    jbyteArray destination;
    jbyte *chunk;
    jint capacity;
};

static void release_input_chunk(InputReadState *state) {
    if (state->chunk != nullptr) {
        state->env->ReleaseByteArrayElements(state->destination, state->chunk, JNI_ABORT);
        state->chunk = nullptr;
    }
}

static const char *parse_input_read_callback(
    void *payload, uint32_t byte_index, TSPoint point, uint32_t *bytes_read
) {
    InputReadState *state = static_cast<InputReadState *>(payload);
    JNIEnv *env = state->env;
    release_input_chunk(state);
    *bytes_read = 0;

    if (env->ExceptionCheck()) return "";

    if (point.row > static_cast<uint32_t>(INT_MAX) ||
        point.column > static_cast<uint32_t>(INT_MAX)) {
        THROW(IllegalArgumentException, "Tree-sitter input point exceeds Java signed coordinate capacity");
        return "";
    }

    const jint count = env->CallIntMethod(
        state->input,
        state->read_method,
        static_cast<jlong>(byte_index),
        static_cast<jint>(point.row),
        static_cast<jint>(point.column),
        state->destination
    );
    if (env->ExceptionCheck()) return "";

    if (count < 0 || count > state->capacity || (count & 1) != 0) {
        THROW(IllegalArgumentException, "TSInput.read must return an even byte count within destination capacity");
        return "";
    }
    if (count == 0) return "";

    state->chunk = env->GetByteArrayElements(state->destination, nullptr);
    if (state->chunk == nullptr) {
        if (!env->ExceptionCheck()) {
            THROW(OutOfMemoryError, "Cannot pin the bounded Tree-sitter input chunk");
        }
        return "";
    }
    *bytes_read = static_cast<uint32_t>(count);
    return reinterpret_cast<const char *>(state->chunk);
}

struct InputProgressState {
    JNIEnv *env;
    jobject callback;
    jmethodID method;
};

static bool parse_input_progress_callback(TSParseState *parse_state) {
    InputProgressState *state = static_cast<InputProgressState *>(parse_state->payload);
    if (state->env->ExceptionCheck()) return true;
    const jboolean should_cancel = state->env->CallBooleanMethod(
        state->callback,
        state->method,
        static_cast<jlong>(parse_state->current_byte_offset),
        static_cast<jboolean>(parse_state->has_error)
    );
    return state->env->ExceptionCheck() || should_cancel == JNI_TRUE;
}

static jlong JNICALL parser_parse_input(
    JNIEnv *env,
    jclass,
    jlong parser_pointer,
    jlong old_tree_pointer,
    jobject input,
    jint chunk_size_bytes,
    jobject progress_callback
) {
    if (parser_pointer == 0) {
        THROW(IllegalStateException, "Native parser pointer is null");
        return 0;
    }
    if (input == nullptr) {
        THROW(NullPointerException, "TSInput cannot be null");
        return 0;
    }
    if (chunk_size_bytes < 2 || chunk_size_bytes > 1024 * 1024 ||
        (chunk_size_bytes & 1) != 0) {
        THROW(IllegalArgumentException, "chunkSizeBytes must be even and between 2 and 1 MiB");
        return 0;
    }

    TSParser *parser = reinterpret_cast<TSParser *>(parser_pointer);
    if (ts_parser_language(parser) == nullptr) {
        THROW(IllegalStateException, "The parser has no language assigned");
        return 0;
    }

    jclass input_class = env->GetObjectClass(input);
    if (input_class == nullptr) return 0;
    const jmethodID read_method = env->GetMethodID(input_class, "read", "(JII[B)I");
    env->DeleteLocalRef(input_class);
    if (read_method == nullptr || env->ExceptionCheck()) return 0;

    jbyteArray destination = env->NewByteArray(chunk_size_bytes);
    if (destination == nullptr) return 0;

    InputReadState read_state = {
        env, input, read_method, destination, nullptr, chunk_size_bytes
    };
    TSInput ts_input = {
        .payload = &read_state,
        .read = parse_input_read_callback,
        .encoding = TSInputEncodingUTF16LE,
    };

    TSTree *old_tree = old_tree_pointer == 0
        ? nullptr
        : reinterpret_cast<TSTree *>(old_tree_pointer);
    TSTree *new_tree = nullptr;

    InputProgressState progress_state = { env, nullptr, nullptr };
    if (progress_callback != nullptr) {
        jclass progress_class = env->GetObjectClass(progress_callback);
        if (progress_class == nullptr) {
            env->DeleteLocalRef(destination);
            return 0;
        }
        progress_state.callback = progress_callback;
        progress_state.method = env->GetMethodID(progress_class, "onProgress", "(JZ)Z");
        env->DeleteLocalRef(progress_class);
        if (progress_state.method == nullptr || env->ExceptionCheck()) {
            env->DeleteLocalRef(destination);
            return 0;
        }

        TSParseOptions options = {
            .payload = &progress_state,
            .progress_callback = parse_input_progress_callback,
        };
        new_tree = ts_parser_parse_with_options(parser, old_tree, ts_input, options);
    } else {
        new_tree = ts_parser_parse(parser, old_tree, ts_input);
    }

    release_input_chunk(&read_state);
    env->DeleteLocalRef(destination);

    if (env->ExceptionCheck()) {
        if (new_tree != nullptr) ts_tree_delete(new_tree);
        return 0;
    }
    return reinterpret_cast<jlong>(new_tree);
}

extern const JNINativeMethod TSParser_methods[] = {
    {"init", "()J", (void *)&parser_init},
    {"delete", "(J)V", (void *)&parser_delete},
    {"reset", "()V", (void *)&parser_reset},
    {"dotGraphs", "(Ljava/lang/String;)V", (void *)&parser_dot_graphs},
    {"setLanguage", "(L" PACKAGE "TSLanguage;)V", (void *)&parser_set_language},
    {"setIncludedRanges", "(Ljava/util/List;)V", (void *)&parser_set_included_ranges},
    {"setLogger", "(Lkotlin/jvm/functions/Function2;)V", (void *)&parser_set_logger},
    {"parse", "(L" PACKAGE "TSTree;L" PACKAGE "TSInputEncoding;[B)L" PACKAGE "TSTree;",
      (void *)&parser_parse_string},
    {"parse", "(L" PACKAGE "TSTree;L" PACKAGE "TSInputEncoding;"
        "Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;)L" PACKAGE "TSTree;",
      (void *)&parser_parse_function}
,    {"parseInput", "(JJL" PACKAGE "TSInput;IL" PACKAGE "TSParser$ProgressCallback;)J",
      (void *)&parser_parse_input}
};

extern const size_t TSParser_methods_size = sizeof TSParser_methods / sizeof(JNINativeMethod);


#ifdef __cplusplus
}
#endif // __cplusplus

