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

// Programs that are never "the game": Windows' shell, Steam itself (Big Picture, overlay) and Jinky.
bool shell_program(const std::wstring& program) {
    static const wchar_t* const names[] = {L"\\explorer.exe", L"\\steam.exe", L"\\steamwebhelper.exe",
                                           L"\\gameoverlayui.exe", L"\\gameoverlayui64.exe", L"\\jinky.exe",
                                           L"\\jinky-engine.exe", L"\\dwm.exe", L"\\searchhost.exe",
                                           L"\\startmenuexperiencehost.exe", L"\\shellexperiencehost.exe"};
    if (program.empty()) return true;
    for (const wchar_t* n : names) {
        const size_t len = wcslen(n);
        if (program.size() >= len && program.compare(program.size() - len, len, n) == 0) return true;
    }
    return false;
}

bool big_picture(HWND hwnd) {
    wchar_t title[256] = {};
    GetWindowTextW(hwnd, title, 256);
    return wcsstr(title, L"Big Picture") != nullptr;
}

}  // namespace

uint32_t SteamGameWatch::running_app_id() {
    DWORD value = 0, size = sizeof(value);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Valve\\Steam", L"RunningAppID", RRF_RT_REG_DWORD, nullptr,
                     &value, &size) != ERROR_SUCCESS)
        return 0;
    return value;
}

bool SteamGameWatch::alive(uint32_t pid) {
    HANDLE p = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (!p) return false;
    const bool running = WaitForSingleObject(p, 0) == WAIT_TIMEOUT;
    CloseHandle(p);
    return running;
}

uint32_t SteamGameWatch::game_in_front() {
    HWND hwnd = GetForegroundWindow();
    if (!hwnd || !IsWindowVisible(hwnd) || big_picture(hwnd)) return 0;
    DWORD pid = 0;
    if (shell_program(window_program(hwnd, &pid))) return 0;
    // A launcher or loading box is a small window; the game itself fills (most of) the screen.
    RECT r{};
    MONITORINFO mi{sizeof(mi)};
    if (!GetWindowRect(hwnd, &r) || !GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &mi))
        return 0;
    const double win = double(r.right - r.left) * (r.bottom - r.top);
    const double mon = double(mi.rcMonitor.right - mi.rcMonitor.left) * (mi.rcMonitor.bottom - mi.rcMonitor.top);
    return mon > 0 && win >= 0.8 * mon ? pid : 0;
}

void* SteamGameWatch::prompt_in_front() {
    HWND hwnd = GetForegroundWindow();
    if (!hwnd || !IsWindowVisible(hwnd) || big_picture(hwnd)) return nullptr;
    const auto program = window_program(hwnd);
    // Steam's own dialogs count (they wait for the player); the desktop and Jinky don't.
    const bool steam_dialog = program.size() >= 10 && program.compare(program.size() - 10, 10, L"\\steam.exe") == 0;
    if (!steam_dialog && shell_program(program)) return nullptr;
    return hwnd;
}

bool SteamGameWatch::close_game() {
    DWORD pid = game_pid_ && alive(game_pid_) ? game_pid_ : 0;
    if (!pid) pid = game_in_front();
    if (!pid) {
        TCL_WARN("game", "close requested, but no game is in front");
        return false;
    }
    // Like "quit game" on a console: no save prompt, the game just ends.
    HANDLE p = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
    const bool ok = p && TerminateProcess(p, 0);
    if (p) CloseHandle(p);
    TCL_INFO("game", "closing the game ({}): {}", pid, ok ? "done" : "failed");
    return ok;
}

void SteamGameWatch::reset() {
    started_ = ready_ = attention_ = false;
    game_pid_ = 0;
    prompt_ = nullptr;
    prompt_since_ = {};
    began_ = std::chrono::steady_clock::now();
    running_since_ = gone_since_ = {};
}

SteamGameWatch::Event SteamGameWatch::poll() {
    using namespace std::chrono;
    const auto now = steady_clock::now();
    const uint32_t in_front = game_in_front();
    // Follow the game's process (non-Steam games aren't reported by Steam); a launcher handing
    // over to the real game, or an emulator restarting, shows up as a new full-screen process.
    if (in_front && (ready_ || started_)) game_pid_ = in_front;

    // Something other than the game waiting in front (launcher, EULA, Steam prompt, another
    // store's app): after 3 s in front, show it on the TV so the player can deal with it.
    if (!ready_ && !attention_ && !in_front && now - began_ > 4s) {
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

    const uint32_t steam_app = running_app_id();
    const bool running = steam_app != 0 || (game_pid_ && alive(game_pid_)) || (in_front && now - began_ > 4s);
    if (running) {
        if (!started_) {
            started_ = true;
            running_since_ = now;
            if (steam_app) TCL_INFO("game", "Steam reports game {} running", steam_app);
            else TCL_INFO("game", "a full-screen program is running (non-Steam game)");
        }
        gone_since_ = {};
        // Reveal the picture once the game is in front; after 40 s show whatever is there (a
        // launcher waiting for a click, an update dialog...).
        if (!ready_ && (in_front || now - running_since_ > 40s)) {
            ready_ = true;
            if (in_front) game_pid_ = in_front;
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
