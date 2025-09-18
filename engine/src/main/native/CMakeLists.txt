cmake_minimum_required(VERSION 3.22.1)

project(globespeak_whispercpp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(WHISPERCPP_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../extern/whisper.cpp)
if (NOT EXISTS ${WHISPERCPP_ROOT})
    message(FATAL_ERROR "whisper.cpp submodule not found at ${WHISPERCPP_ROOT}")
endif()

# Disable unused whisper.cpp artifacts
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libs" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Disable whisper examples" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Disable whisper tests" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "Disable whisper server" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "Disable curl" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "Disable SDL2" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_RPC OFF CACHE BOOL "Disable RPC" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan" FORCE)
set(GGML_CUDA OFF CACHE BOOL "Disable CUDA" FORCE)
set(GGML_METAL OFF CACHE BOOL "Disable Metal" FORCE)
set(GGML_KOMPUTE OFF CACHE BOOL "Disable Kompute" FORCE)
set(GGML_SYCL OFF CACHE BOOL "Disable SYCL" FORCE)
set(GGML_HIP OFF CACHE BOOL "Disable HIP" FORCE)

add_subdirectory(${WHISPERCPP_ROOT} whispercpp EXCLUDE_FROM_ALL)

add_library(globespeak_whispercpp SHARED
    jni/WhisperBridge.cpp
)

target_include_directories(globespeak_whispercpp
    PRIVATE
        ${WHISPERCPP_ROOT}/include
        ${WHISPERCPP_ROOT}/ggml/include
        ${WHISPERCPP_ROOT}/src
)

target_link_libraries(globespeak_whispercpp
    PRIVATE
        whisper
        log
        atomic
)

if (ANDROID)
    target_compile_options(globespeak_whispercpp PRIVATE
        -O3
        -ffast-math
        -fvisibility=hidden
        -fexceptions
    )
    target_link_options(globespeak_whispercpp PRIVATE
        -Wl,--exclude-libs,ALL
    )
endif()

set_target_properties(globespeak_whispercpp PROPERTIES
    OUTPUT_NAME "whisper_jni"
)
