cmake_minimum_required(VERSION 3.21)

project(aescrypt_android
        VERSION 4.7.1.0
        DESCRIPTION "AES Crypt for Android"
        HOMEPAGE_URL "https://www.aescrypt.com"
        LANGUAGES CXX)

set(COPYRIGHT_TEXT "Copyright (C) 2026")
set(AUTHOR_TEXT    "Terrapane Corporation")

# Build the project version and other data into the version.h file
configure_file(version.h.in version.h @ONLY)

add_subdirectory(dependencies)

# Android's logging library
find_library(log-lib log)

# JNI bridge library
add_library(aescrypt_android SHARED
    aescrypt_jni.cpp
    encrypt_stream.cpp
    decrypt_stream.cpp)

target_link_libraries(aescrypt_android
    PRIVATE
        aescrypt_engine
        secutil
        logger
        ${log-lib})

set_target_properties(aescrypt_android
    PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF)

target_include_directories(aescrypt_android
    PRIVATE
        $<BUILD_INTERFACE:${PROJECT_BINARY_DIR}>
        $<BUILD_INTERFACE:${PROJECT_SOURCE_DIR}/include>)

target_compile_options(aescrypt_android
    PRIVATE
        $<$<OR:$<CXX_COMPILER_ID:Clang>,$<CXX_COMPILER_ID:AppleClang>>:-Wpedantic -Wextra -Wall -Werror -Wconversion -Wshadow>
        $<$<CXX_COMPILER_ID:GNU>:-Wpedantic -Wextra -Wall -Werror -Wconversion -Wshadow=local>
        $<$<CXX_COMPILER_ID:MSVC>:/W4 /WX>)
