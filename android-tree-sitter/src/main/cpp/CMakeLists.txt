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

# ==============================================================================
# Oniguruma (鬼车) 路径配置：位于当前 cpp/oniguruma 目录
# ==============================================================================
set(ONIG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/oniguruma")
set(ONIG_SRC_DIR "${ONIG_DIR}/src")

# 引入 Oniguruma 源码及配置头文件路径
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
    tsh/TshRegexNative.cpp
    ts_windbell_jni.cpp
)

if (${CMAKE_SYSTEM_NAME} STREQUAL Android)
    find_library(log log)
    target_link_libraries(android-tree-sitter PRIVATE ${log})
endif()