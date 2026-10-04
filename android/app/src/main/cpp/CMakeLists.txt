cmake_minimum_required(VERSION 3.22.1)

project("offlinestudy" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Исходники llama.cpp (тег b11380) кладутся в эту папку перед сборкой:
#   git clone --depth 1 --branch b11380 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp sources not found in ${LLAMA_SRC}. Run: git clone --depth 1 --branch b11380 https://github.com/ggml-org/llama.cpp ${LLAMA_SRC}")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(offlinestudy SHARED offline_ai.cpp)

target_include_directories(offlinestudy PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(offlinestudy
        llama
        ggml
        android
        log)
