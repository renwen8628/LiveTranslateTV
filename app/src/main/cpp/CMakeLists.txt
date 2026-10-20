# Native build for PlayStation Remote Play (chiaki-ng, AGPL-3.0).
# chiaki-ng lives in third_party/chiaki-ng as a git submodule; run tools/setup-native-build.sh once.
cmake_minimum_required(VERSION 3.22.1)
project(livetranslate-native C CXX)

set(CHIAKI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/chiaki-ng")

# Same options as chiaki-ng's own Android build, minus its app/JNI (we build our own below).
set(CHIAKI_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_CLI OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_GUI OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_SETSU OFF CACHE STRING "" FORCE)
# ON also tells chiaki-lib it is building for Android (e.g. no libevent).
set(CHIAKI_ENABLE_ANDROID ON CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_FFMPEG_DECODER OFF CACHE STRING "" FORCE)
set(CHIAKI_ENABLE_PI_DECODER OFF CACHE STRING "" FORCE)
set(CHIAKI_LIB_ENABLE_OPUS OFF CACHE BOOL "" FORCE)
set(CHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT ON CACHE BOOL "" FORCE)
set(CHIAKI_LIB_JSONC_EXTERNAL_PROJECT ON CACHE BOOL "" FORCE)
set(CHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT ON CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_STEAM_SHORTCUT OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_STEAMDECK_NATIVE OFF CACHE STRING "" FORCE)

# With CHIAKI_ENABLE_ANDROID, chiaki-ng also adds its own app's JNI library (android/app),
# which plays audio through Oboe. Skip that directory; we build our own JNI library below.
# A macro (not a function) keeps variables set by subdirectories in the caller's scope.
macro(add_subdirectory _ltv_dir)
    if(NOT "${_ltv_dir}" STREQUAL "android/app")
        _add_subdirectory(${ARGV})
    endif()
endmacro()

add_subdirectory("${CHIAKI_DIR}" chiaki-ng)

add_library(chiaki-jni SHARED
        chiaki-jni/chiaki-jni.c
        chiaki-jni/log.c
        chiaki-jni/video-decoder.c)
target_link_libraries(chiaki-jni chiaki-lib)

find_library(ANDROID_LIB_LOG log)
find_library(ANDROID_LIB_MEDIANDK mediandk)
find_library(ANDROID_LIB_ANDROID android)
target_link_libraries(chiaki-jni "${ANDROID_LIB_LOG}" "${ANDROID_LIB_MEDIANDK}" "${ANDROID_LIB_ANDROID}")
