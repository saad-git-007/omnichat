# Minimal package config for the header-only Khronos SPIRV-Headers checkout in third_party/
get_filename_component(_spirv_root "${CMAKE_CURRENT_LIST_DIR}/../../../../../../third_party/SPIRV-Headers" ABSOLUTE)
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${_spirv_root}/include")
endif()
set(SPIRV-Headers_INCLUDE_DIR "${_spirv_root}/include")
