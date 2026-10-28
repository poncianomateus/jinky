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

// The program's full path (lowercased), from its process id.
std::wstring process_program(DWORD pid) {
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

// When the process started (FILETIME units), 0 if unknown.
uint64_t process_started(DWORD pid) {
    HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!p) return 0;
    FILETIME created{}, exited{}, kernel{}, user{};
    const bool ok = GetProcessTimes(p, &created, &exited, &kernel, &user);
    CloseHandle(p);
    return ok ? (uint64_t(created.dwHighDateTime) << 32 | created.dwLowDateTime) : 0;
}

uint64_t now_filetime() {
    FILETIME ft{};
    GetSystemTimeAsFileTime(&ft);
    return uint64_t(ft.dwHighDateTime) << 32 | ft.dwLowDateTime;
}

bool big_picture(HWND hwnd) {
    wchar_t title[256] = {};
    GetWindowTextW(hwnd, title, 256);
    return wcsstr(title, L"Big Picture") != nullptr;
}

std::wstring window_title(HWND hwnd) {
    wchar_t title[256] = {};
    GetWindowTextW(hwnd, title, 256);
    return title;
}

// For the log: "title" (program.exe), ASCII only.
std::string describe(HWND hwnd, const std::wstring& program) {
    std::string out = "\"";
    for (wchar_t c : window_title(hwnd)) out.push_back(c < 128 ? char(c) : '?');
    out += "\" (";
    std::string name;
    for (wchar_t c : program) name.push_back(c < 128 ? char(c) : '?');
    if (const auto slash = name.find_last_of('\\'); slash != std::string::npos) name.erase(0, slash + 1);
    return out + name + ")";
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

bool SteamGameWatch::could_be_the_game(uint32_t pid) const {
    // A Steam game lives in a Steam library; anything else (emulators and other stores added as
    // "non-Steam games") must have started during this session. Programs the user already had
    // open — a maximised slicer, browser or editor — are never taken for the game: closing the
    // game from the TV once killed a 3D-printing slicer the user had switched to on the PC.
    if (process_program(pid).find(L"\\steamapps\\common\\") != std::wstring::npos) return true;
    const uint64_t started = process_started(pid);
    return started != 0 && started >= began_wall_;
}

uint32_t SteamGameWatch::game_candidate() const {
    const uint32_t pid = game_in_front();
    return pid && could_be_the_game(pid) ? pid : 0;
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
    // Steam's own dialogs count (they wait for the player); the desktop and Jinky don't. Steam
    // draws most of its windows (license agreement on a game's first start, cloud sync, "in use
    // on another computer") with steamwebhelper.exe, so that counts too (Big Picture doesn't).
    auto ends_with = [&](const wchar_t* tail) {
        const size_t n = wcslen(tail);
        return program.size() >= n && program.compare(program.size() - n, n, tail) == 0;
    };
    const bool steam_dialog = ends_with(L"\\steam.exe") || ends_with(L"\\steamwebhelper.exe");
    if (!steam_dialog && shell_program(program)) return nullptr;
    if (steam_dialog) {
        // Steam's main window ("Steam") and its untitled helper windows wait for no one: they
        // show up while Steam opens, and once made every game start with the controller as a mouse.
        const auto title = window_title(hwnd);
        if (title.empty() || title == L"Steam") return nullptr;
    }
    return hwnd;
}

void SteamGameWatch::steam_busy() { busy_until_ = std::chrono::steady_clock::now() + std::chrono::seconds(5); }

void SteamGameWatch::steam_waiting() { steam_waiting_ = true; }

bool SteamGameWatch::minimize_steam_window() {
    HWND hwnd = GetForegroundWindow();
    if (!hwnd || !IsWindowVisible(hwnd) || IsIconic(hwnd) || big_picture(hwnd)) return false;
    const auto program = window_program(hwnd);
    const bool steam = program.size() >= 20 && (program.ends_with(L"\\steamwebhelper.exe") || program.ends_with(L"\\steam.exe"));
    if (!steam || window_title(hwnd) != L"Steam") return false;
    // The library window is big; Steam's dialogs (also titled "Steam" at times) are small.
    RECT r{};
    if (!GetWindowRect(hwnd, &r) || r.right - r.left < 900 || r.bottom - r.top < 500) return false;
    ShowWindow(hwnd, SW_MINIMIZE);
    TCL_INFO("game", "Steam's main window was in front of the game: minimised");
    return true;
}

uint32_t SteamGameWatch::current_pid() const {
    if (game_pid_ && alive(game_pid_)) return game_pid_;
    return game_candidate();
}

void SteamGameWatch::launched() {
    if (launched_ == std::chrono::steady_clock::time_point{}) launched_ = std::chrono::steady_clock::now();
}

bool SteamGameWatch::close_game() {
    if (closing_) return true;
    DWORD pid = game_pid_ && alive(game_pid_) ? game_pid_ : 0;
    if (!pid) pid = game_candidate();
    if (!pid) {
        TCL_WARN("game", "close requested, but no game is in front");
        return false;
    }
    std::string name;  // for the log: which program was closed
    for (wchar_t c : process_program(pid)) name.push_back(c < 128 ? char(c) : '?');
    if (const auto slash = name.find_last_of('\\'); slash != std::string::npos) name.erase(0, slash + 1);
    // Like "quit game" on a console: no save prompt, the game just ends.
    HANDLE p = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
    const bool ok = p && TerminateProcess(p, 0);
    if (p) CloseHandle(p);
    TCL_INFO("game", "closing the game ({} {}): {}", name, pid, ok ? "done" : "failed");
    if (ok) {
        closing_ = true;
        game_pid_ = pid;
    }
    return ok;
}

void SteamGameWatch::reset() {
    started_ = ready_ = attention_ = closing_ = steam_waiting_ = false;
    busy_until_ = {};
    game_pid_ = 0;
    prompt_ = nullptr;
    prompt_since_ = {};
    began_ = std::chrono::steady_clock::now();
    launched_ = {};
    began_wall_ = now_filetime() - 50'000'000;  // 5 s of slack (FILETIME is in 100 ns units)
    running_since_ = gone_since_ = {};
}

SteamGameWatch::Event SteamGameWatch::poll() {
    using namespace std::chrono;
    const auto now = steady_clock::now();
    // The player closed it from the TV: over as soon as the process is gone. Steam keeps
    // reporting the game for several seconds after it ends (cloud sync), so don't ask Steam.
    if (closing_) {
        if (game_pid_ && alive(game_pid_)) return Event::None;
        closing_ = started_ = false;
        game_pid_ = 0;
        return Event::Closed;
    }
    const uint32_t in_front = game_candidate();
    // Follow the game's process (non-Steam games aren't reported by Steam); a launcher handing
    // over to the real game, or an emulator restarting, shows up as a new full-screen process.
    // While the followed game is alive it stays the game, whatever else comes to the front.
    if (in_front && (ready_ || started_) && !(game_pid_ && alive(game_pid_))) game_pid_ = in_front;

    // Something other than the game waiting in front (launcher, EULA, Steam prompt, another
    // store's app): after 3 s in front, show it on the TV so the player can deal with it.
    // Only once the game was asked to start, with a moment for it to come up.
    // Steam itself says it waits for the player: no need to wait 3 s to be sure.
    if (!ready_ && !attention_ && !in_front && steam_waiting_) {
        attention_ = true;
        TCL_INFO("game", "Steam waits for the player before starting the game: showing it on the TV");
        return Event::NeedsAttention;
    }
    if (!ready_ && !attention_ && !in_front && launched_ != steady_clock::time_point{} && now - launched_ > 5s) {
        void* prompt = prompt_in_front();
        if (prompt != prompt_) {
            prompt_ = prompt;
            prompt_since_ = now;
        } else if (prompt && now - prompt_since_ >= 3s) {
            attention_ = true;
            const HWND hwnd = HWND(prompt);
            TCL_INFO("game", "a window is waiting for the player in front of the game: showing it on the TV ({})",
                     describe(hwnd, window_program(hwnd)));
            return Event::NeedsAttention;
        }
    }

    const uint32_t steam_app = running_app_id();
    // Once the game was on screen, its own process decides: when it ends, Steam still reports
    // the game for a while (cloud sync), which made the TV wait ~15 s to get back home.
    const bool game_ended = ready_ && game_pid_ && !alive(game_pid_) && !in_front;
    const bool running = (steam_app != 0 && !game_ended) || (game_pid_ && alive(game_pid_)) ||
                         (in_front && now - began_ > 4s);
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
        // While Steam reports work on the launch (an update can take minutes), keep waiting.
        if (now < busy_until_) began_ = std::max(began_, now - 50s);
        if (!ready_ && now - began_ > 60s) {
            ready_ = true;
            return Event::Ready;
        }
        return Event::None;
    }
    if (gone_since_ == steady_clock::time_point{}) gone_since_ = now;
    // Launchers often exit and start the game as a new process; Steam may report "nothing
    // running" for a moment in between.
    if (now - gone_since_ < (game_ended ? 4s : 10s)) return Event::None;
    started_ = false;
    return Event::Closed;
}

}  // namespace tcl
