cmake_minimum_required(VERSION 3.22.1)
project(emre_minicpm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MiniCPM-V 4.6 support first shipped in the upstream llama.cpp b9049 release.
# Pin the source commit so the GGUF model and multimodal projector stay compatible.
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/2496f9c14965c39589f53eea31bdb6d762b1d360.tar.gz
)
FetchContent_MakeAvailable(llama_cpp)

# llama.cpp's mtmd library is intentionally not included when its full tools
# build is disabled. Add only the multimodal library needed by this app.
if(NOT TARGET mtmd)
    add_subdirectory(
        "${llama_cpp_SOURCE_DIR}/tools/mtmd"
        "${CMAKE_CURRENT_BINARY_DIR}/llama-mtmd"
    )
endif()

find_library(log-lib log)
find_library(android-lib android)

add_library(emre_minicpm SHARED minicpm_v46_jni.cpp)
target_include_directories(emre_minicpm PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/tools/mtmd
)
target_link_libraries(emre_minicpm PRIVATE llama mtmd ${log-lib} ${android-lib})
target_compile_options(emre_minicpm PRIVATE -Wall -Wextra)
