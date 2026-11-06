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

cmake_minimum_required(VERSION 3.22.1)
project("android-tree-sitter")

set(PROJECT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../cmake/common-config.cmake)

# Tree-sitter 内核内部依赖头文件
include_directories("${TS_DIR}/lib/src")
include_directories("${TS_DIR}/lib/include")

# Oniguruma 正则库头文件配置
set(ONIG_DIR "${PROJECT_DIR}/project-sora-oniguruma")
include_directories("${ONIG_DIR}/src")

# TSH 自研高亮引擎头文件
include_directories("${CMAKE_CURRENT_SOURCE_DIR}")
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/tsh")

# 编译参数调优：保证 C++20，隐藏缺省符号，开启 ARM64 激进优化
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden -DHAVE_CONFIG_H")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden -DHAVE_CONFIG_H")

# 添加核心复合动态库
add_library(android-tree-sitter SHARED
    # Tree-sitter 核心合集
    "${TS_DIR}/lib/src/lib.c"

    # Oniguruma 核心合集 (用于微观实时分词流水线)
    "${ONIG_DIR}/src/regerror.c"
    "${ONIG_DIR}/src/regparse.c"
    "${ONIG_DIR}/src/regext.c"
    "${ONIG_DIR}/src/regcomp.c"
    "${ONIG_DIR}/src/regexec.c"
    "${ONIG_DIR}/src/reggnu.c"
    "${ONIG_DIR}/src/regenc.c"
    "${ONIG_DIR}/src/regsyntax.c"
    "${ONIG_DIR}/src/regtrav.c"
    "${ONIG_DIR}/src/regversion.c"
    "${ONIG_DIR}/src/st.c"
    "${ONIG_DIR}/src/onig_init.c"
    "${ONIG_DIR}/src/unicode.c"
    "${ONIG_DIR}/src/ascii.c"
    "${ONIG_DIR}/src/utf16_le.c"
    "${ONIG_DIR}/src/utf16_be.c"
    "${ONIG_DIR}/src/utf8.c"

    # 原生 JNI 与封装
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
    tsh/WindbellTshCore.cpp
    ts_windbell_jni.cpp
)

if (${CMAKE_SYSTEM_NAME} STREQUAL Android)
    find_library(log log)
    target_link_libraries(android-tree-sitter ${log})
endif()