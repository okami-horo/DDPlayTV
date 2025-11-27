cmake_minimum_required(VERSION 3.22.1)
project(libass_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PLAYER_COMPONENT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../..")
set(PREBUILT_LIBS_DIR "${PLAYER_COMPONENT_DIR}/libs")
set(LIBASS_PREBUILT "${PREBUILT_LIBS_DIR}/${ANDROID_ABI}/libass.so")

add_library(ass_prebuilt SHARED IMPORTED)
set_target_properties(ass_prebuilt PROPERTIES IMPORTED_LOCATION "${LIBASS_PREBUILT}")

set(GPU_SOURCES
    ass_gpu_bridge.cpp
)

add_library(libass_bridge SHARED
    libass_bridge.cpp
    ${GPU_SOURCES}
)

target_include_directories(libass_bridge
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

find_library(log-lib log)
find_library(android-lib android)
find_library(cpp_shared c++_shared)
find_library(jnigraphics-lib jnigraphics)
find_library(glesv3-lib GLESv3)
find_library(egl-lib EGL)

set(LIBASS_LINK_LIBS ass_prebuilt)

if (cpp_shared)
    list(APPEND LIBASS_LINK_LIBS ${cpp_shared})
endif()

if (android-lib)
    list(APPEND LIBASS_LINK_LIBS ${android-lib})
endif()

if (log-lib)
    list(APPEND LIBASS_LINK_LIBS ${log-lib})
endif()

if (jnigraphics-lib)
    list(APPEND LIBASS_LINK_LIBS ${jnigraphics-lib})
endif()

if (glesv3-lib)
    list(APPEND LIBASS_LINK_LIBS ${glesv3-lib})
endif()

if (egl-lib)
    list(APPEND LIBASS_LINK_LIBS ${egl-lib})
endif()

set_target_properties(libass_bridge PROPERTIES
    OUTPUT_NAME "libass_bridge"
)

target_link_libraries(libass_bridge
    PRIVATE
        ${LIBASS_LINK_LIBS}
)

target_compile_definitions(libass_bridge
    PRIVATE
        ASS_GPU_RENDER=1
)
