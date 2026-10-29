cmake_minimum_required(VERSION 3.31)

project("omni" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party)
set(LLAMA_SRC   ${THIRD_PARTY}/llama.cpp)

# llama.cpp: shared libs + dynamically loaded ggml backends (CPU variants, Vulkan, OpenCL)
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP       ON CACHE BOOL "" FORCE)
else()
    # x86_64 is only for UI testing on the emulator: CPU backend only
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
    set(OMNI_VULKAN OFF)
    set(OMNI_OPENCL OFF)
    set(OMNI_HEXAGON OFF)
endif()

# Vulkan: NDK libvulkan stub + glslc, C++ headers (vulkan.hpp) from Khronos Vulkan-Headers
if(OMNI_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
    find_library(OMNI_NDK_VULKAN vulkan REQUIRED)
    set(Vulkan_LIBRARY ${OMNI_NDK_VULKAN} CACHE FILEPATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE ${ANDROID_NDK}/shader-tools/linux-x86_64/glslc CACHE FILEPATH "" FORCE)
    set(SPIRV-Headers_DIR ${CMAKE_CURRENT_LIST_DIR}/cmake CACHE PATH "" FORCE)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${CMAKE_CURRENT_LIST_DIR}/cmake/host-toolchain.cmake CACHE FILEPATH "" FORCE)
endif()

# OpenCL: link against an ICD loader at build time; the vendor libOpenCL.so is used at run time
if(OMNI_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(OpenCL_INCLUDE_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY ${THIRD_PARTY}/opencl-android/lib/libOpenCL.so CACHE FILEPATH "" FORCE)
endif()

# Hexagon NPU: host backend + DSP-side skel (libggml-htp-v73.so for the S23's Snapdragon 8 Gen 2), built with the
# Hexagon SDK from github.com/snapdragon-toolchain/hexagon-sdk unpacked in third_party/hexagon
if(OMNI_HEXAGON)
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(HEXAGON_SDK_ROOT   ${THIRD_PARTY}/hexagon/6.7.0.0 CACHE PATH "" FORCE)
    set(HEXAGON_TOOLS_ROOT ${THIRD_PARTY}/hexagon/6.7.0.0/tools/HEXAGON_Tools/21.0.04 CACHE PATH "" FORCE)
    set(PREBUILT_LIB_DIR android_aarch64 CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

if(OMNI_HEXAGON)
    # the skel is an ExternalProject output, not a target: copy it next to the other libs so it lands in the APK
    add_custom_target(omni-htp-skel ALL
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                ${CMAKE_BINARY_DIR}/build-llama/ggml/src/ggml-hexagon/libggml-htp-v73.so
                ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libggml-htp-v73.so
        DEPENDS htp-v73)
endif()

if(OMNI_VULKAN)
    target_include_directories(ggml-vulkan PRIVATE ${THIRD_PARTY}/SPIRV-Headers/include)
endif()

add_library(omni SHARED omni_jni.cpp)

target_include_directories(omni PRIVATE
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(omni llama llama-common mtmd android log)

if(OMNI_HEXAGON)
    # Gradle only builds the targets it packages, so hang the skel build off libomni
    add_dependencies(omni omni-htp-skel)
endif()
