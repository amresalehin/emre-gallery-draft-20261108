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

# llama.cpp is embedded as a subproject, so its standalone-only release
# metadata is not populated by the upstream top-level CMakeLists.txt.
# mtmd uses this version for its shared-library metadata.
set(LLAMA_BUILD_NUMBER 9049)
set(LLAMA_INSTALL_VERSION 0.0.9049)

# Build llama + GGML only. The upstream tools target is deliberately disabled:
# this Android integration needs the mtmd library, not its desktop CLIs.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# Build mtmd as a library-only target. Upstream tools/mtmd/CMakeLists.txt also
# declares CLI/debug executables unconditionally, and those are not Android
# runtime dependencies (they also require the tools-only arg.h header).
find_package(Threads REQUIRED)
set(MTMD_SOURCE_DIR "${llama_cpp_SOURCE_DIR}/tools/mtmd")
add_library(mtmd SHARED
    "${MTMD_SOURCE_DIR}/mtmd.cpp"
    "${MTMD_SOURCE_DIR}/mtmd-audio.cpp"
    "${MTMD_SOURCE_DIR}/mtmd-image.cpp"
    "${MTMD_SOURCE_DIR}/mtmd-helper.cpp"
    "${MTMD_SOURCE_DIR}/clip.cpp"
    "${MTMD_SOURCE_DIR}/models/cogvlm.cpp"
    "${MTMD_SOURCE_DIR}/models/conformer.cpp"
    "${MTMD_SOURCE_DIR}/models/dotsocr.cpp"
    "${MTMD_SOURCE_DIR}/models/gemma4a.cpp"
    "${MTMD_SOURCE_DIR}/models/gemma4v.cpp"
    "${MTMD_SOURCE_DIR}/models/glm4v.cpp"
    "${MTMD_SOURCE_DIR}/models/granite-speech.cpp"
    "${MTMD_SOURCE_DIR}/models/hunyuanocr.cpp"
    "${MTMD_SOURCE_DIR}/models/internvl.cpp"
    "${MTMD_SOURCE_DIR}/models/kimivl.cpp"
    "${MTMD_SOURCE_DIR}/models/kimik25.cpp"
    "${MTMD_SOURCE_DIR}/models/nemotron-v2-vl.cpp"
    "${MTMD_SOURCE_DIR}/models/llama4.cpp"
    "${MTMD_SOURCE_DIR}/models/llava.cpp"
    "${MTMD_SOURCE_DIR}/models/minicpmv.cpp"
    "${MTMD_SOURCE_DIR}/models/paddleocr.cpp"
    "${MTMD_SOURCE_DIR}/models/pixtral.cpp"
    "${MTMD_SOURCE_DIR}/models/qwen2vl.cpp"
    "${MTMD_SOURCE_DIR}/models/qwen3vl.cpp"
    "${MTMD_SOURCE_DIR}/models/qwen3a.cpp"
    "${MTMD_SOURCE_DIR}/models/step3vl.cpp"
    "${MTMD_SOURCE_DIR}/models/siglip.cpp"
    "${MTMD_SOURCE_DIR}/models/whisper-enc.cpp"
    "${MTMD_SOURCE_DIR}/models/deepseekocr.cpp"
    "${MTMD_SOURCE_DIR}/models/mobilenetv5.cpp"
    "${MTMD_SOURCE_DIR}/models/youtuvl.cpp"
    "${MTMD_SOURCE_DIR}/models/yasa2.cpp"
)
set_target_properties(mtmd PROPERTIES
    VERSION "${LLAMA_INSTALL_VERSION}"
    SOVERSION 0
    POSITION_INDEPENDENT_CODE ON
)
target_link_libraries(mtmd PUBLIC ggml llama PRIVATE Threads::Threads)
target_include_directories(mtmd PUBLIC
    "${MTMD_SOURCE_DIR}"
    "${llama_cpp_SOURCE_DIR}/include"
    "${llama_cpp_SOURCE_DIR}/ggml/include"
)
target_include_directories(mtmd PRIVATE
    "${llama_cpp_SOURCE_DIR}"
    "${llama_cpp_SOURCE_DIR}/vendor"
)
target_compile_features(mtmd PRIVATE cxx_std_17)
target_compile_definitions(mtmd PRIVATE LLAMA_BUILD PUBLIC LLAMA_SHARED)
target_compile_options(mtmd PRIVATE -Wno-cast-qual)
if(ANDROID)
    target_compile_options(mtmd PRIVATE -Wno-missing-prototypes)
endif()

find_library(log-lib log)
find_library(android-lib android)

add_library(emre_minicpm SHARED
    minicpm_v46_jni.cpp
    lfm2_5_vl_jni.cpp
)
target_include_directories(emre_minicpm PRIVATE
    "${llama_cpp_SOURCE_DIR}/include"
    "${llama_cpp_SOURCE_DIR}/ggml/include"
    "${MTMD_SOURCE_DIR}"
)
target_link_libraries(emre_minicpm PRIVATE llama mtmd ${log-lib} ${android-lib})
target_compile_options(emre_minicpm PRIVATE -Wall -Wextra)
