cmake_minimum_required(VERSION 3.22)
project(tclproto LANGUAGES CXX)

add_library(tclproto STATIC
    src/protocol.cpp
    src/framing.cpp
    src/udp.cpp
)
target_include_directories(tclproto PUBLIC include)
target_compile_features(tclproto PUBLIC cxx_std_20)
if(WIN32)
    target_link_libraries(tclproto PUBLIC ws2_32)
endif()
if(MSVC)
    target_compile_options(tclproto PRIVATE /W4 /permissive-)
else()
    target_compile_options(tclproto PRIVATE -Wall -Wextra)
endif()

# Tests only on the host build; the Android build just links the library.
if(NOT ANDROID AND PROJECT_IS_TOP_LEVEL)
    enable_testing()
    add_executable(tclproto_tests tests/test_framing.cpp)
    target_link_libraries(tclproto_tests PRIVATE tclproto)
    add_test(NAME tclproto_tests COMMAND tclproto_tests)
endif()
