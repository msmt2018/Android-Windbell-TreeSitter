cmake_minimum_required(VERSION 3.22.1)
project(windbell_largefile_tests LANGUAGES CXX)

enable_testing()

add_executable(windbell_largefile_tests
    TextSourceTest.cpp
    ../TextSource.cpp
    ../ChunkedTextStore.cpp
    ../MappedFileSource.cpp
    ../FileWindowSource.cpp
    ../PieceTreeSource.cpp
    ../TextPartitionIndex.cpp
)

target_include_directories(windbell_largefile_tests PRIVATE ..)
target_compile_features(windbell_largefile_tests PRIVATE cxx_std_20)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(windbell_largefile_tests PRIVATE
        -Wall -Wextra -Wpedantic
        -fsanitize=address,undefined
        -fno-omit-frame-pointer
    )
    target_link_options(windbell_largefile_tests PRIVATE
        -fsanitize=address,undefined
    )
endif()

add_test(NAME windbell_largefile_tests COMMAND windbell_largefile_tests)
