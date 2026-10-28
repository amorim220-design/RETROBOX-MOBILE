cmake_minimum_required(VERSION 3.22.1)

project(retrobox CXX)

add_library(retrobox SHARED
        retrobox.h
        libretro.h
        emulator.cpp
        jni_bridge.cpp)

target_compile_features(retrobox PRIVATE cxx_std_17)
target_compile_options(retrobox PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
find_library(aaudio-lib aaudio)
find_library(dl-lib dl)

# The NDK ships libc++ for the *target* inside the sysroot; find_library() would
# resolve the host copies from toolchains/llvm/prebuilt/<host>/lib, so point at
# the target archives explicitly.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CXX_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(CXX_TRIPLE "x86_64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CXX_TRIPLE "arm-linux-androideabi")
else()
    set(CXX_TRIPLE "i686-linux-android")
endif()
set(CXX_STATIC "${CMAKE_SYSROOT}/usr/lib/${CXX_TRIPLE}/libc++_static.a")
set(CXX_ABI "${CMAKE_SYSROOT}/usr/lib/${CXX_TRIPLE}/libc++abi.a")
if(NOT EXISTS "${CXX_STATIC}")
    message(FATAL_ERROR "libc++_static.a not found at ${CXX_STATIC}")
endif()

target_link_libraries(retrobox
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${gles-lib}
        ${aaudio-lib}
        ${dl-lib}
        ${CXX_STATIC}
        ${CXX_ABI})
