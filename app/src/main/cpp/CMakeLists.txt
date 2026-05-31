cmake_minimum_required(VERSION 3.18)
project(waifu2x-jni)

set(CMAKE_CXX_STANDARD 17)

# Allow Gradle or the environment to provide the ncnn Android SDK path.
set(NCNN_SDK_DIR "${NCNN_SDK_DIR}" CACHE PATH "Path to the ncnn Android Vulkan SDK")

if(NOT NCNN_SDK_DIR AND DEFINED ENV{NCNN_SDK_DIR})
    set(NCNN_SDK_DIR "$ENV{NCNN_SDK_DIR}")
endif()

if(NOT NCNN_SDK_DIR)
    message(FATAL_ERROR
        "NCNN_SDK_DIR is not set. Define it via local.properties (ncnn.sdk.dir=...), "
        "a Gradle property (-PncnnSdkDir=...), the NCNN_SDK_DIR environment variable, "
        "or place the SDK under third_party/ncnn-20260526-android-vulkan."
    )
endif()

set(ncnn_DIR ${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn)

if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake" AND NOT EXISTS "${ncnn_DIR}/ncnn-config.cmake")
    message(FATAL_ERROR
        "Could not find ncnn CMake package for ABI ${ANDROID_ABI} under ${ncnn_DIR}. "
        "Check that NCNN_SDK_DIR points to the root of the ncnn Android Vulkan SDK."
    )
endif()

find_package(ncnn REQUIRED)

# Source files
set(WAIFU2X_SOURCES
    waifu2x.cpp
    anime4k.cpp
    waifu2x_jni.cpp
)

# Create shared library
add_library(waifu2x-jni SHARED ${WAIFU2X_SOURCES})

# Include directories
target_include_directories(waifu2x-jni PRIVATE
    ${CMAKE_SOURCE_DIR}
)

# Link libraries
target_link_libraries(waifu2x-jni
    ncnn
    android
    log
    jnigraphics
    GLESv3
    EGL
)
