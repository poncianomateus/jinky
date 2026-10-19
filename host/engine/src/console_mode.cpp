#include "console_mode.h"

#include <windows.h>
#include <shellapi.h>

#include <cstdlib>
#include <fstream>

#include "audio_capture.h"
#include "log.h"

namespace tcl {

namespace {

std::string narrow(const std::wstring& w) {
    std::string s;
    for (wchar_t c : w) s.push_back(c < 128 ? char(c) : '?');
    return s;
}

// %LOCALAPPDATA%\Jinky\console.flag holds the display name while console mode is on.
std::wstring marker_path() {
    wchar_t base[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    std::wstring dir = std::wstring(base) + L"\\Jinky";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir + L"\\console.flag";
}

void open_url(const wchar_t* url) {
    const auto r = reinterpret_cast<intptr_t>(ShellExecuteW(nullptr, L"open", url, nullptr, nullptr, SW_SHOWNORMAL));
    if (r <= 32) TCL_WARN("console", "could not open {} (is Steam installed?)", narrow(url));
}

}  // namespace

bool ConsoleMode::set_refresh_60(const std::wstring& display, int& previous_hz) {
    DEVMODEW current{};
    current.dmSize = sizeof current;
    if (!EnumDisplaySettingsW(display.c_str(), ENUM_CURRENT_SETTINGS, &current)) return false;
    previous_hz = int(current.dmDisplayFrequency);
    if (previous_hz == 60) return true;

    // Same resolution and colour depth, 60 Hz (some monitors list 59 Hz for 59.94).
    DEVMODEW best{};
    bool found = false;
    DEVMODEW m{};
    m.dmSize = sizeof m;
    for (DWORD i = 0; EnumDisplaySettingsW(display.c_str(), i, &m); ++i) {
        if (m.dmPelsWidth != current.dmPelsWidth || m.dmPelsHeight != current.dmPelsHeight ||
            m.dmBitsPerPel != current.dmBitsPerPel)
            continue;
        if (m.dmDisplayFrequency == 60 || (!found && m.dmDisplayFrequency == 59)) {
            best = m;
            found = true;
            if (m.dmDisplayFrequency == 60) break;
        }
    }
    if (!found) return false;
    best.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY;
    // Not CDS_UPDATEREGISTRY: a temporary change; the saved (registry) mode stays 240 Hz.
    return ChangeDisplaySettingsExW(display.c_str(), &best, nullptr, 0, nullptr) == DISP_CHANGE_SUCCESSFUL;
}

void ConsoleMode::restore_display(const std::wstring& display) {
    // Null mode = go back to the mode saved in the registry (the user's normal refresh rate).
    ChangeDisplaySettingsExW(display.c_str(), nullptr, nullptr, 0, nullptr);
}

void ConsoleMode::enter(const std::wstring& display) {
    if (active_) return;
    active_ = true;
    display_ = display;
    if (const auto marker = marker_path(); !marker.empty()) std::wofstream(marker) << display;

    int previous_hz = 0;
    if (set_refresh_60(display, previous_hz))
        TCL_INFO("console", "display {} set to 60 Hz (was {} Hz)", narrow(display), previous_hz);
    else
        TCL_WARN("console", "could not switch {} to 60 Hz; games may tear or judder", narrow(display));

    if (!AudioCapture::set_pc_muted(true)) TCL_WARN("console", "could not mute the PC");
    open_url(L"steam://open/bigpicture");
    TCL_INFO("console", "console mode on: Steam Big Picture, 60 Hz, PC muted");
    emit_event("console", "\"on\":true");
}

void ConsoleMode::leave() {
    if (!active_) return;
    active_ = false;
    open_url(L"steam://close/bigpicture");
    restore_display(display_);
    AudioCapture::set_pc_muted(false);
    if (const auto marker = marker_path(); !marker.empty()) DeleteFileW(marker.c_str());
    TCL_INFO("console", "console mode off: display and sound restored");
    emit_event("console", "\"on\":false");
}

bool ConsoleMode::restore_if_left() {
    const auto marker = marker_path();
    if (marker.empty() || GetFileAttributesW(marker.c_str()) == INVALID_FILE_ATTRIBUTES) return false;
    std::wstring display;
    std::getline(std::wifstream(marker), display);
    if (!display.empty()) restore_display(display);
    AudioCapture::set_pc_muted(false);
    DeleteFileW(marker.c_str());
    return true;
}

}  // namespace tcl
