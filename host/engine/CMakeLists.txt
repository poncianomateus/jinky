cmake_minimum_required(VERSION 3.22)
project(jinky_engine LANGUAGES CXX)

# Static C++ runtime: the engine runs on PCs without the Visual C++ Redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
file(READ ../../VERSION JINKY_VERSION)
string(STRIP "${JINKY_VERSION}" JINKY_VERSION)

add_subdirectory(../../proto proto)

add_executable(jinky-engine
    src/main.cpp
    src/net_tuning.cpp
    src/audio_capture.cpp
    src/capture.cpp
    src/console_mode.cpp
    src/frame_cap.cpp
    src/screenshot.cpp
    src/pad_mouse.cpp
    src/steam_game.cpp
    src/cursor.cpp
    src/converter.cpp
    src/nvenc_encoder.cpp
    src/test_pattern.cpp
    src/pairing.cpp
    src/netinfo.cpp
    src/gamepad.cpp
    ../../third_party/vigemclient/src/ViGEmClient.cpp
)
target_compile_features(jinky-engine PRIVATE cxx_std_20)
target_compile_definitions(jinky-engine PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE JINKY_VERSION="${JINKY_VERSION}")
target_include_directories(jinky-engine PRIVATE ../../third_party/nvenc ../../third_party/vigemclient/include)
# NVIDIA NVAPI (driver settings: the frame cap for game sessions); a no-op without an NVIDIA driver.
target_include_directories(jinky-engine SYSTEM PRIVATE ../../third_party/nvapi)
target_link_libraries(jinky-engine PRIVATE tclproto d3d11 dxgi winmm avrt ole32 windowscodecs shell32 setupapi d3dcompiler bcrypt iphlpapi qwave wlanapi
                      ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/nvapi/amd64/nvapi64.lib)
if(MSVC)
    target_compile_options(jinky-engine PRIVATE /W4 /permissive- /utf-8 /external:W0)
    # Third-party code is compiled as-is; don't hold it to our warning level.
    set_source_files_properties(../../third_party/vigemclient/src/ViGEmClient.cpp PROPERTIES COMPILE_OPTIONS "/W0")
endif()

# Loopback test receiver: validates the host's stream without a TV.
add_executable(jinky-probe ../../tools/probe/probe.cpp)
target_link_libraries(jinky-probe PRIVATE tclproto xinput)

# Development aid: a DualSense driving the Android TV emulator (scripts/tv-emulator.ps1).
add_executable(jinky-padbridge ../../tools/padbridge/padbridge.cpp)
target_link_libraries(jinky-padbridge PRIVATE hid setupapi)
target_compile_features(jinky-padbridge PRIVATE cxx_std_20)
