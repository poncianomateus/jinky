cmake_minimum_required(VERSION 3.22)
project(tclstream_host LANGUAGES CXX)

add_subdirectory(../../proto proto)

add_executable(tclstream-host
    src/main.cpp
    src/audio_capture.cpp
    src/capture.cpp
    src/converter.cpp
    src/nvenc_encoder.cpp
    src/test_pattern.cpp
    src/gamepad.cpp
    ../../third_party/vigemclient/src/ViGEmClient.cpp
)
target_compile_features(tclstream-host PRIVATE cxx_std_20)
target_compile_definitions(tclstream-host PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)
target_include_directories(tclstream-host PRIVATE ../../third_party/nvenc ../../third_party/vigemclient/include)
target_link_libraries(tclstream-host PRIVATE tclproto d3d11 dxgi winmm avrt ole32 setupapi)
if(MSVC)
    target_compile_options(tclstream-host PRIVATE /W4 /permissive- /utf-8)
    # Third-party code is compiled as-is; don't hold it to our warning level.
    set_source_files_properties(../../third_party/vigemclient/src/ViGEmClient.cpp PROPERTIES COMPILE_OPTIONS "/W0")
endif()

# Loopback test receiver: validates the host's stream without a TV.
add_executable(tclstream-probe ../../tools/probe/probe.cpp)
target_link_libraries(tclstream-probe PRIVATE tclproto xinput)
