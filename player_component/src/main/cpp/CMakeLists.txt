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

set(COMMON_LINK_LIBS "")

if (cpp_shared)
    list(APPEND COMMON_LINK_LIBS ${cpp_shared})
endif()

if (android-lib)
    list(APPEND COMMON_LINK_LIBS ${android-lib})
endif()

if (log-lib)
    list(APPEND COMMON_LINK_LIBS ${log-lib})
endif()

if (jnigraphics-lib)
    list(APPEND COMMON_LINK_LIBS ${jnigraphics-lib})
endif()

if (glesv3-lib)
    list(APPEND COMMON_LINK_LIBS ${glesv3-lib})
endif()

if (egl-lib)
    list(APPEND COMMON_LINK_LIBS ${egl-lib})
endif()

set(LIBASS_LINK_LIBS ass_prebuilt)
list(APPEND LIBASS_LINK_LIBS ${COMMON_LINK_LIBS})

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

set(MPV_PREBUILT "${PREBUILT_LIBS_DIR}/${ANDROID_ABI}/libmpv.so")

if (EXISTS "${MPV_PREBUILT}")
    add_library(mpv_prebuilt SHARED IMPORTED)
    set_target_properties(mpv_prebuilt PROPERTIES IMPORTED_LOCATION "${MPV_PREBUILT}")
    set(MPV_IMPORTED_LIBS mpv_prebuilt)
else()
    message(WARNING "libmpv.so not found at ${MPV_PREBUILT}, building mpv_bridge without mpv linkage")
    set(MPV_IMPORTED_LIBS "")
endif()

add_library(mpv_bridge SHARED
    mpv_bridge.cpp
)

target_include_directories(mpv_bridge
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

set(MPV_BRIDGE_LINK_LIBS ${COMMON_LINK_LIBS})

if (MPV_IMPORTED_LIBS)
    list(APPEND MPV_BRIDGE_LINK_LIBS ${MPV_IMPORTED_LIBS})
endif()

target_link_libraries(mpv_bridge
    PRIVATE
        ${MPV_BRIDGE_LINK_LIBS}
)

target_compile_definitions(mpv_bridge
    PRIVATE
        MPV_PREBUILT_AVAILABLE=$<BOOL:${MPV_IMPORTED_LIBS}>
)
