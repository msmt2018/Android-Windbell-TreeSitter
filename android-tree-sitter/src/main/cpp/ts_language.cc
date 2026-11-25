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

#include <dlfcn.h>
#include <memory>
#include <new>

#include "tree_sitter/api.h"
#include "utils/ts_obj_utils.h"
#include "ts__log.h"
#include "utils/ts_preconditions.h"

#include "ts_language.h"

typedef const TSLanguage *(*TsLangFunc)();

static jint TSLanguage_symCount(JNIEnv *env, jclass self, jlong ptr) {
  req_nnp(env, ptr);
  return (jint) ts_language_symbol_count((TSLanguage *) ptr);
}

static jint TSLanguage_fldCount(JNIEnv *env, jclass self, jlong ptr) {
  req_nnp(env, ptr);
  return (jint) ts_language_field_count((TSLanguage *) ptr);
}


static jint TSLanguage_symForName(JNIEnv *env,
                                  jclass self,
                                  jlong ptr,
                                  jbyteArray name,
                                  jint length,
                                  jboolean isNamed) {
  req_nnp(env, ptr);
  jbyte *nm = env->GetByteArrayElements(name, NULL);
  uint32_t count = ts_language_symbol_for_name((TSLanguage *) ptr,
                                               reinterpret_cast<const char *>(nm),
                                               length,
                                               isNamed);
  env->ReleaseByteArrayElements(name, nm, JNI_ABORT);
  return (jint) count;
}


static jstring
TSLanguage_symName(JNIEnv *env, jclass self, jlong lngPtr, jint sym) {
  req_nnp(env, lngPtr);
  return env->NewStringUTF(ts_language_symbol_name((TSLanguage *) lngPtr, sym));
}


static jstring
TSLanguage_fldNameForId(JNIEnv *env, jclass self, jlong ptr, jint id) {
  req_nnp(env, ptr);
  return env->NewStringUTF(ts_language_field_name_for_id((TSLanguage *) ptr,
                                                         id));
}


static jint TSLanguage_fldIdForName(JNIEnv *env,
                                    jclass self,
                                    jlong ptr,
                                    jbyteArray name,
                                    jint length) {
  req_nnp(env, ptr);
  req_nnp(env, name, "field name bytes");
  if (length < 0 || length > env->GetArrayLength(name)) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) {
      env->ThrowNew(exception, "Invalid Tree-sitter field-name byte length");
      env->DeleteLocalRef(exception);
    }
    return 0;
  }
  jbyte *nm = env->GetByteArrayElements(name, nullptr);
  if (nm == nullptr) return 0;
  const uint32_t id = ts_language_field_id_for_name(
      reinterpret_cast<const TSLanguage *>(ptr),
      reinterpret_cast<const char *>(nm),
      static_cast<uint32_t>(length));
  env->ReleaseByteArrayElements(name, nm, JNI_ABORT);
  return static_cast<jint>(id);
}


static jint TSLanguage_symType(JNIEnv *env, jclass self, jlong ptr, jint sym) {
  req_nnp(env, ptr);
  return (jint) ts_language_symbol_type((TSLanguage *) ptr, sym);
}


static jint TSLanguage_langVer(JNIEnv *env, jclass self, jlong ptr) {
  req_nnp(env, ptr);
  return (jint) ts_language_abi_version((TSLanguage *) ptr);
}

static jintArray TSLanguage_subtypes(JNIEnv *env,
                                     jclass self,
                                     jlong ptr,
                                     jint supertype) {
  req_nnp(env, ptr);
  if (supertype < 0 || supertype > 0xFFFF) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) {
      env->ThrowNew(exception, "Supertype symbol must be in the unsigned 16-bit range");
    }
    return nullptr;
  }

  uint32_t length = 0;
  const TSSymbol *subtypes = ts_language_subtypes(
      reinterpret_cast<const TSLanguage *>(ptr),
      static_cast<TSSymbol>(supertype),
      &length);

  jintArray result = env->NewIntArray(static_cast<jsize>(length));
  if (result == nullptr || length == 0 || subtypes == nullptr) return result;

  std::unique_ptr<jint[]> values(new (std::nothrow) jint[length]);
  if (!values) {
    jclass exception = env->FindClass("java/lang/OutOfMemoryError");
    if (exception != nullptr) {
      env->ThrowNew(exception, "Unable to allocate Tree-sitter subtype result");
    }
    return nullptr;
  }

  for (uint32_t index = 0; index < length; ++index) {
    values[index] = static_cast<jint>(subtypes[index]);
  }
  env->SetIntArrayRegion(result, 0, static_cast<jsize>(length), values.get());
  return result;
}

static jintArray TSLanguage_supertypes(JNIEnv *env,
                                       jclass self,
                                       jlong ptr) {
  req_nnp(env, ptr);

  uint32_t length = 0;
  const TSSymbol *supertypes = ts_language_supertypes(
      reinterpret_cast<const TSLanguage *>(ptr),
      &length);

  jintArray result = env->NewIntArray(static_cast<jsize>(length));
  if (result == nullptr || length == 0 || supertypes == nullptr) return result;

  std::unique_ptr<jint[]> values(new (std::nothrow) jint[length]);
  if (!values) {
    jclass exception = env->FindClass("java/lang/OutOfMemoryError");
    if (exception != nullptr) {
      env->ThrowNew(exception, "Unable to allocate Tree-sitter supertype result");
    }
    return nullptr;
  }

  for (uint32_t index = 0; index < length; ++index) {
    values[index] = static_cast<jint>(supertypes[index]);
  }
  env->SetIntArrayRegion(result, 0, static_cast<jsize>(length), values.get());
  return result;
}

static jlongArray TSLanguage_loadLanguage(JNIEnv *env,
                                          jclass clazz,
                                          jstring libpath,
                                          jstring func) {
  if (libpath == nullptr || func == nullptr) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) env->ThrowNew(exception, "Grammar library path and entry symbol are required");
    return nullptr;
  }

  // JNI string pins and the dlopen handle are independently RAII-managed.
  // Every failure path (including dlsym, grammar entry, and Java array
  // allocation) now releases all resources acquired before that point.
  class ScopedUtfChars final {
  public:
    ScopedUtfChars(JNIEnv *jni, jstring value) noexcept
        : env(jni), string(value), chars(jni->GetStringUTFChars(value, nullptr)) {}
    ~ScopedUtfChars() {
      if (chars != nullptr) env->ReleaseStringUTFChars(string, chars);
    }
    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;
    const char *get() const noexcept { return chars; }
    explicit operator bool() const noexcept { return chars != nullptr; }
  private:
    JNIEnv *env;
    jstring string;
    const char *chars;
  };

  struct DlCloser {
    void operator()(void *handle) const noexcept {
      if (handle != nullptr) dlclose(handle);
    }
  };

  ScopedUtfChars library_path(env, libpath);
  ScopedUtfChars function_name(env, func);
  if (!library_path || !function_name) return nullptr;

  std::unique_ptr<void, DlCloser> handle(dlopen(library_path.get(), RTLD_NOW | RTLD_LOCAL));
  if (!handle) {
    const char *error = dlerror();
    LOGE(LOG_TAG, "Failed to dlopen library '%s': %s",
         library_path.get(), error != nullptr ? error : "unknown dynamic-loader error");
    return nullptr;
  }

  dlerror();
  void *func_addr = dlsym(handle.get(), function_name.get());
  const char *symbol_error = dlerror();
  if (symbol_error != nullptr || func_addr == nullptr) {
    LOGE(LOG_TAG, "Cannot find grammar entry '%s' in '%s': %s",
         function_name.get(), library_path.get(),
         symbol_error != nullptr ? symbol_error : "symbol resolved to null");
    return nullptr;
  }

  const auto lang_func = reinterpret_cast<TsLangFunc>(func_addr);
  const TSLanguage *language = lang_func();
  if (language == nullptr) {
    LOGE(LOG_TAG, "Grammar entry '%s' in '%s' returned nullptr",
         function_name.get(), library_path.get());
    return nullptr;
  }

  jlongArray result = env->NewLongArray(2);
  if (result == nullptr) return nullptr;
  const jlong ptrs[2] = {
      reinterpret_cast<jlong>(const_cast<TSLanguage *>(language)),
      reinterpret_cast<jlong>(handle.get())
  };
  env->SetLongArrayRegion(result, 0, 2, ptrs);
  if (env->ExceptionCheck()) {
    env->DeleteLocalRef(result);
    return nullptr;
  }

  // Transfer dlopen ownership only after the Java result is fully populated.
  handle.release();
  LOGD(LOG_TAG, "Loaded tree-sitter grammar entry '%s' from '%s'",
       function_name.get(), library_path.get());
  return result;
}

static void TSLanguage_dlclose(JNIEnv *env, jclass clazz, jlong libhandle) {
  if (libhandle == 0) return;
  dlclose((void *) libhandle);
}

static jint TSLanguage_stateCount(JNIEnv *env, jclass clazz, jlong pointer) {
  req_nnp(env, pointer);
  return (jint) ts_language_state_count((TSLanguage *) pointer);
}

static jshort TSLanguage_nextState(JNIEnv *env,
                                   jclass clazz,
                                   jlong pointer,
                                   jshort state_id,
                                   jshort symbol) {
  req_nnp(env, pointer);
  return (jshort) ts_language_next_state((TSLanguage *) pointer,
                                         state_id,
                                         symbol);
}

void TSLanguage_Native__SetJniMethods(JNINativeMethod *methods, int count) {
  SET_JNI_METHOD(methods, TSLanguage_Native_symCount, TSLanguage_symCount);
  SET_JNI_METHOD(methods, TSLanguage_Native_fldCount, TSLanguage_fldCount);
  SET_JNI_METHOD(methods, TSLanguage_Native_symForName, TSLanguage_symForName);
  SET_JNI_METHOD(methods, TSLanguage_Native_symName, TSLanguage_symName);
  SET_JNI_METHOD(methods, TSLanguage_Native_fldNameForId, TSLanguage_fldNameForId);
  SET_JNI_METHOD(methods, TSLanguage_Native_fldIdForName, TSLanguage_fldIdForName);
  SET_JNI_METHOD(methods, TSLanguage_Native_symType, TSLanguage_symType);
  SET_JNI_METHOD(methods, TSLanguage_Native_langVer, TSLanguage_langVer);
  SET_JNI_METHOD(methods, TSLanguage_Native_subtypes, TSLanguage_subtypes);
  SET_JNI_METHOD(methods, TSLanguage_Native_supertypes, TSLanguage_supertypes);
  SET_JNI_METHOD(methods, TSLanguage_Native_loadLanguage, TSLanguage_loadLanguage);
  SET_JNI_METHOD(methods, TSLanguage_Native_dlclose, TSLanguage_dlclose);
  SET_JNI_METHOD(methods, TSLanguage_Native_stateCount, TSLanguage_stateCount);
  SET_JNI_METHOD(methods, TSLanguage_Native_nextState, TSLanguage_nextState);
}

// Independent loader reference for the image containing a generated language.
// This outlives Java TSLanguage.close() while TSH grammars/documents still use it.
std::shared_ptr<void> retainTreeSitterLanguageLibrary(const TSLanguage *language) noexcept {
  if (language == nullptr) return {};
  Dl_info image{};
  if (dladdr(reinterpret_cast<const void *>(language), &image) == 0 ||
      image.dli_fname == nullptr || image.dli_fname[0] == '\0') return {};
  void *handle = dlopen(image.dli_fname, RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr) return {};
  try {
    return std::shared_ptr<void>(handle, [](void *value) noexcept {
      if (value != nullptr) (void)dlclose(value);
    });
  } catch (...) {
    (void)dlclose(handle);
    return {};
  }
}
