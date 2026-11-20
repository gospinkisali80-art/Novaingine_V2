cmake_minimum_required(VERSION 3.22.1)

project(NovaingineV2 LANGUAGES CXX)

add_library(
    nova_engine
    SHARED
    src/jni/native_engine.cpp
)

target_compile_features(nova_engine PRIVATE cxx_std_17)

target_compile_options(
    nova_engine PRIVATE
    -O2
    -Wall
    -Wextra
)

find_library(log-lib log)
find_library(gles-lib GLESv3)

target_link_libraries(
    nova_engine
    PRIVATE
    ${log-lib}
    ${gles-lib}
)
