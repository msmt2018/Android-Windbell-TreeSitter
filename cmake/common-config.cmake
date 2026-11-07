# If not building for Android, we need to include header files from JDK
if (NOT ${CMAKE_SYSTEM_NAME} STREQUAL Android)

    find_package(JNI REQUIRED)

    # Path for jni.h
    include_directories(${JAVA_INCLUDE_PATH})

    # Path for jni_md.h
    include_directories(${JAVA_INCLUDE_PATH2})
endif ()

# Termux host build only:
# Use the Android system liblog when compiling directly with Termux Clang.
#
# Do not apply this workaround to AGP/NDK cross-compilation. During an NDK
# build, ANDROID_ABI/CMAKE_ANDROID_ARCH_ABI is defined and find_library()
# must resolve liblog from the NDK sysroot for the selected ABI.
if (
    CMAKE_SYSTEM_NAME STREQUAL "Android"
    AND DEFINED ENV{PREFIX}
    AND "$ENV{PREFIX}" MATCHES "^/data/data/com\\.termux/files/usr"
    AND NOT DEFINED ANDROID_ABI
    AND NOT DEFINED CMAKE_ANDROID_ARCH_ABI
)
    if (
        CMAKE_SIZEOF_VOID_P EQUAL 8
        AND EXISTS "/system/lib64/liblog.so"
    )
        set(
            log
            "/system/lib64/liblog.so"
            CACHE FILEPATH
            "Android system log library for Termux host build"
            FORCE
        )
    elseif (EXISTS "/system/lib/liblog.so")
        set(
            log
            "/system/lib/liblog.so"
            CACHE FILEPATH
            "Android system log library for Termux host build"
            FORCE
        )
    else ()
        message(
            FATAL_ERROR
            "Termux host build: Android system liblog.so was not found"
        )
    endif ()

    message(
        STATUS
        "Termux host build: Android log library = ${log}"
    )
endif ()

if (NOT DEFINED PROJECT_CMAKE_DIR)
    set(PROJECT_CMAKE_DIR "${PROJECT_DIR}/cmake")
endif ()

if (NOT DEFINED TS_DIR)
    set(TS_DIR "${PROJECT_DIR}/tree-sitter-lib")
endif ()

# Include paths from tree-sitter
set(TS_INCLUDES ${TS_DIR}/lib/include ${TS_DIR}/lib/src)

# tree-sitter header files
include_directories(${TS_INCLUDES})

# Auto-generated headers
include_directories(${AUTOGEN_HEADERS})