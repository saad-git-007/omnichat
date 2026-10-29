# Host (Linux x86_64) toolchain for ggml's vulkan-shaders-gen, which runs at build time
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_COMPILER   gcc)
set(CMAKE_CXX_COMPILER g++)
get_filename_component(_cmake_bin "${CMAKE_COMMAND}" DIRECTORY)
set(CMAKE_MAKE_PROGRAM "${_cmake_bin}/ninja" CACHE FILEPATH "" FORCE)
