#
#  This file is part of android-tree-sitter.
#
#  android-tree-sitter library is free software; you can redistribute it and/or
#  modify it under the terms of the GNU Lesser General Public
#  License as published by the Free Software Foundation; either
#  version 2.1 of the License, or (at your option) any later version.
#
#  android-tree-sitter library is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
#  Lesser General Public License for more details.
#
#   You should have received a copy of the GNU General Public License
#  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
#

#
#  Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
#

cmake_minimum_required(VERSION 3.22.1)
project("android-tree-sitter")

set(PROJECT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# 引入项目通用配置
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../cmake/common-config.cmake)

# Tree-sitter 核心头文件路径
include_directories("${TS_DIR}/lib/src")
include_directories("${TS_DIR}/lib/include")

# Detect the Tree-sitter 0.28 Query Cursor controls without forcing a
# submodule update on consumers that still build the previous API. Once the
# vendored tree-sitter-lib is upgraded, the same TSH source automatically
# enables bounded query execution and cancellation.
include(CheckCXXSourceCompiles)
set(CMAKE_REQUIRED_INCLUDES "${TS_DIR}/lib/include")
check_cxx_source_compiles("
    #include <tree_sitter/api.h>
    using ProgressCallback = decltype(((TSQueryCursorOptions*)0)->progress_callback);
    using ExecWithOptions = decltype(&ts_query_cursor_exec_with_options);
    using SetMatchLimit = decltype(&ts_query_cursor_set_match_limit);
    using DidExceedMatchLimit = decltype(&ts_query_cursor_did_exceed_match_limit);
    static_assert(sizeof(TSQueryCursorOptions) > 0, \"Query cursor options are available\");
    int main() { return 0; }
" TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
unset(CMAKE_REQUIRED_INCLUDES)
if(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
    add_compile_definitions(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS=1)
endif()

# ==============================================================================
# Oniguruma (鬼车) 路径配置：位于当前 cpp/oniguruma 目录
# ==============================================================================
set(ONIG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/oniguruma")
set(ONIG_SRC_DIR "${ONIG_DIR}/src")

# project-sora/oniguruma's regenc.h includes config.h directly. The upstream
# CMake project generates it from config.h.cmake.in; this project compiles the
# Oniguruma sources in its own target, so reproduce the required feature checks
# and generate the same header into our binary directory.
include(CheckIncludeFiles)
include(CheckSymbolExists)
include(CheckTypeSize)

set(PACKAGE onig)
set(PACKAGE_VERSION 6.9.10)
set(VERSION ${PACKAGE_VERSION})
set(USE_CRNL_AS_LINE_TERMINATOR 0)

check_include_files(alloca.h HAVE_ALLOCA_H)
check_include_files(stdint.h HAVE_STDINT_H)
check_include_files(sys/times.h HAVE_SYS_TIMES_H)
check_include_files(sys/time.h HAVE_SYS_TIME_H)
check_include_files(time.h HAVE_TIME_H)
check_include_files(sys/types.h HAVE_SYS_TYPES_H)
check_include_files(unistd.h HAVE_UNISTD_H)
check_include_files(inttypes.h HAVE_INTTYPES_H)
check_type_size(int SIZEOF_INT)
check_type_size(intptr_t SIZEOF_INTPTR_T)
check_type_size(long SIZEOF_LONG)
check_type_size("long long" SIZEOF_LONG_LONG)
check_type_size("void*" SIZEOF_VOIDP)
if(HAVE_TIME_H)
    check_type_size(time_t SIZEOF_TIME_T)
    check_symbol_exists(clock_gettime time.h HAVE_CLOCK_GETTIME)
endif()
if(HAVE_ALLOCA_H)
    check_symbol_exists(alloca "alloca.h" HAVE_ALLOCA)
else()
    check_symbol_exists(alloca "stdlib.h;malloc.h" HAVE_ALLOCA)
endif()

set(ONIG_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/oniguruma_config")
file(MAKE_DIRECTORY "${ONIG_CONFIG_DIR}")
configure_file(
    "${ONIG_SRC_DIR}/config.h.cmake.in"
    "${ONIG_CONFIG_DIR}/config.h"
)

# Search generated configuration before the vendored Oniguruma source tree.
include_directories(BEFORE "${ONIG_CONFIG_DIR}")
include_directories("${ONIG_SRC_DIR}")

# 引入 Windbell-TSH 自身头文件
include_directories("${CMAKE_CURRENT_SOURCE_DIR}")
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/tsh")

# 编译参数调优：保证 C++20，隐藏缺省符号，开启 -O3 优化并定义 HAVE_CONFIG_H
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden -DHAVE_CONFIG_H")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden -DHAVE_CONFIG_H")

# ==============================================================================
# 构建主库 android-tree-sitter
# ==============================================================================
add_library(android-tree-sitter SHARED
    # Tree-sitter 核心内核
    "${TS_DIR}/lib/src/lib.c"

    # Oniguruma 完整核心与编码表实现 (完全闭合依赖符号，避免 NDK 静态断言失败)
    "${ONIG_SRC_DIR}/regerror.c"
    "${ONIG_SRC_DIR}/regparse.c"
    "${ONIG_SRC_DIR}/regext.c"
    "${ONIG_SRC_DIR}/regcomp.c"
    "${ONIG_SRC_DIR}/regexec.c"
    "${ONIG_SRC_DIR}/reggnu.c"
    "${ONIG_SRC_DIR}/regenc.c"
    "${ONIG_SRC_DIR}/regsyntax.c"
    "${ONIG_SRC_DIR}/regtrav.c"
    "${ONIG_SRC_DIR}/regversion.c"
    "${ONIG_SRC_DIR}/st.c"
    "${ONIG_SRC_DIR}/onig_init.c"
    "${ONIG_SRC_DIR}/unicode.c"
    "${ONIG_SRC_DIR}/ascii.c"
    "${ONIG_SRC_DIR}/utf8.c"
    "${ONIG_SRC_DIR}/utf16_le.c"
    "${ONIG_SRC_DIR}/utf16_be.c"
    "${ONIG_SRC_DIR}/utf32_le.c"
    "${ONIG_SRC_DIR}/utf32_be.c"
    "${ONIG_SRC_DIR}/sjis.c"
    "${ONIG_SRC_DIR}/sjis_prop.c"
    "${ONIG_SRC_DIR}/euc_jp.c"
    "${ONIG_SRC_DIR}/euc_jp_prop.c"
    "${ONIG_SRC_DIR}/unicode_unfold_key.c"
    "${ONIG_SRC_DIR}/unicode_fold1_key.c"
    "${ONIG_SRC_DIR}/unicode_fold2_key.c"
    "${ONIG_SRC_DIR}/unicode_fold3_key.c"

    # Native large-text source adapters and bounded storage
    largefile/TextSource.cpp
    largefile/FileWindowSource.cpp
    largefile/PieceTreeSource.cpp
    largefile/TextPartitionIndex.cpp

    # 原生 JNI 适配层
    main.cc
    ts.cc
    ts_cursor.cc
    ts_language.cc
    ts_lookahead_iterator.cc
    ts_node.cc
    ts_parser.cc
    ts_query.cc
    ts_query_cursor.cc
    ts_tree.cc
    utf16str/JavaUTF16String.cpp
    utf16str/JavaUTF16StringFactory.cpp
    utf16str/UTF16String.cpp
    utils/jni_string.cpp
    utils/ts_exceptions.cpp
    utils/ts_preconditions.cpp
    utils/ts_obj_utils.cpp

    # Windbell-TSH 核心实现
    tsh/WindbellTshCompiler.cpp
    tsh/WindbellTshCore.cpp
    ts_windbell_jni.cpp
)

# Drop unreferenced code/data from the monolithic JNI library.
#
# Oniguruma is built from its upstream source files, but Windbell-TSH currently
# initializes only UTF-16 LE. Section-level garbage collection allows the linker
# to discard unused encoding implementations and other unreachable routines
# without maintaining a fragile hand-picked list of Oniguruma translation units.
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU" AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(android-tree-sitter PRIVATE
        -ffunction-sections
        -fdata-sections
    )
    target_link_options(android-tree-sitter PRIVATE
        -Wl,--gc-sections
    )
endif()

if (${CMAKE_SYSTEM_NAME} STREQUAL Android)
    find_library(log log)
    target_link_libraries(android-tree-sitter PRIVATE ${log})
endif()
# Host-side tests for storage and source adapters. Android builds only compile
# the production library; these tests run in a normal desktop CMake build.
include(CTest)
if(BUILD_TESTING AND NOT ANDROID)
    add_executable(windbell_largefile_tests
        largefile/tests/TextSourceTest.cpp
        largefile/TextSource.cpp
        largefile/ChunkedTextStore.cpp
        largefile/MappedFileSource.cpp
        largefile/FileWindowSource.cpp
        largefile/PieceTreeSource.cpp
        largefile/TextPartitionIndex.cpp
    )
    target_include_directories(windbell_largefile_tests PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/largefile"
    )
    target_compile_features(windbell_largefile_tests PRIVATE cxx_std_20)
    add_test(NAME windbell_largefile_tests COMMAND windbell_largefile_tests)
endif()
