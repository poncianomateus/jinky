cmake_minimum_required(VERSION 3.22)
project(tclreceiver LANGUAGES CXX)

# Same protocol library the Windows host uses.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../proto ${CMAKE_CURRENT_BINARY_DIR}/proto)

add_library(tclreceiver SHARED
    receiver.cpp
    jni_bridge.cpp
)
target_compile_features(tclreceiver PRIVATE cxx_std_20)
target_compile_options(tclreceiver PRIVATE -Wall -Wextra)
target_link_libraries(tclreceiver PRIVATE tclproto log)
