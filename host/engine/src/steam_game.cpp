#include "steam_game.h"

#include <windows.h>

#include <cwctype>
#include <string>

#include "log.h"

namespace tcl {

namespace {

// Full path of the program that owns a window, lowercased ("" if it can't be read).
std::wstring window_program(HWND hwnd, DWORD* pid_out = nullptr) {
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid_out) *pid_out = pid;
    HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!p) return {};
    wchar_t path[MAX_PATH * 2];
    DWORD len = DWORD(std::size(path));
    std::wstring out;
    if (QueryFullProcessImageNameW(p, 0, path, &len)) out.assign(path, len);
    CloseHandle(p);
    for (auto& c : out) c = wchar_t(std::towlower(c));
    return out;
}

// Games Steam installs live under ...\steamapps\common\<game>\.
bool installed_by_steam(const std::wstring& program) {
    return program.find(L"\\steamapps\\common\\") != std::wstring::npos;
}

}  // namespace

uint32_t SteamGameWatch::running_app_id() {
    DWORD value = 0, size = sizeof(value);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Valve\\Steam", L"RunningAppID", RRF_RT_REG_DWORD, nullptr,
                     &value, &size) != ERROR_SUCCESS)
        return 0;
    return value;
}

bool SteamGameWatch::game_in_front() {
    HWND hwnd = GetForegroundWindow();
    if (!hwnd || !installed_by_steam(window_program(hwnd))) return false;
    // A launcher or loading box is a small window; the game itself fills (most of) the screen.
    RECT r{};
    MONITORINFO mi{sizeof(mi)};
    if (!GetWindowRect(hwnd, &r) || !GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &mi))
        return false;
    const double win = double(r.right - r.left) * (r.bottom - r.top);
    const double mon = double(mi.rcMonitor.right - mi.rcMonitor.left) * (mi.rcMonitor.bottom - mi.rcMonitor.top);
    return mon > 0 && win >= 0.8 * mon;
}

void* SteamGameWatch::prompt_in_front() {
    HWND hwnd = GetForegroundWindow();
    if (!hwnd || !IsWindowVisible(hwnd)) return nullptr;
    wchar_t title[256] = {};
    GetWindowTextW(hwnd, title, 256);
    if (wcsstr(title, L"Big Picture")) return nullptr;  // Steam's own console screen: fine
    const auto program = window_program(hwnd);
    const auto ends_with = [&](const wchar_t* name) {
        const size_t n = wcslen(name);
        return program.size() >= n && program.compare(program.size() - n, n, name) == 0;
    };
    if (program.empty() || ends_with(L"\\explorer.exe") || ends_with(L"\\jinky.exe") || ends_with(L"\\jinky-engine.exe"))
        return nullptr;
    return hwnd;
}

bool SteamGameWatch::close_foreground_game() {
    HWND hwnd = GetForegroundWindow();
    DWORD pid = 0;
    const auto program = hwnd ? window_program(hwnd, &pid) : std::wstring{};
    if (!installed_by_steam(program) || pid == 0) {
        TCL_WARN("game", "close requested, but the window in front is not a Steam game");
        return false;
    }
    // Like "quit game" on a console: no save prompt, the game just ends.
    HANDLE p = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
    const bool ok = p && TerminateProcess(p, 0);
    if (p) CloseHandle(p);
    TCL_INFO("game", "closing the game in front ({}): {}", pid, ok ? "done" : "failed");
    return ok;
}

void SteamGameWatch::reset() {
    started_ = ready_ = attention_ = false;
    prompt_ = nullptr;
    prompt_since_ = {};
    began_ = std::chrono::steady_clock::now();
    running_since_ = gone_since_ = {};
}

SteamGameWatch::Event SteamGameWatch::poll() {
    using namespace std::chrono;
    const auto now = steady_clock::now();
    // Something other than the game waiting in front (launcher, EULA, Steam prompt, another
    // store's app): after 3 s in front, show it on the TV so the player can deal with it.
    if (!ready_ && !attention_ && !game_in_front() && now - began_ > 4s) {
        void* prompt = prompt_in_front();
        if (prompt != prompt_) {
            prompt_ = prompt;
            prompt_since_ = now;
        } else if (prompt && now - prompt_since_ >= 3s) {
            attention_ = true;
            TCL_INFO("game", "a window is waiting for the player in front of the game: showing it on the TV");
            return Event::NeedsAttention;
        }
    }
    if (running_app_id() != 0) {
        if (!started_) {
            started_ = true;
            running_since_ = now;
            TCL_INFO("game", "Steam reports game {} running", running_app_id());
        }
        gone_since_ = {};
        // Reveal the picture once the game is in front; after 40 s show whatever is there (a
        // launcher waiting for a click, an update dialog...).
        if (!ready_ && (game_in_front() || now - running_since_ > 40s)) {
            ready_ = true;
            return Event::Ready;
        }
        return Event::None;
    }
    if (!started_) {
        // Never started (failed launch, Steam asking something): don't keep the TV waiting forever.
        if (!ready_ && now - began_ > 60s) {
            ready_ = true;
            return Event::Ready;
        }
        return Event::None;
    }
    if (gone_since_ == steady_clock::time_point{}) gone_since_ = now;
    // Launchers often exit and start the game as a new process; Steam may report "nothing
    // running" for a moment in between.
    if (now - gone_since_ < 10s) return Event::None;
    started_ = false;
    return Event::Closed;
}

}  // namespace tcl
