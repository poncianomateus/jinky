#include "pad_mouse.h"

#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <vector>

#include "log.h"

namespace tcl {

namespace {

void key(WORD vk, bool down) {
    INPUT in{};
    in.type = INPUT_KEYBOARD;
    in.ki.wVk = vk;
    in.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
    SendInput(1, &in, sizeof in);
}

void mouse(DWORD flags, LONG dx = 0, LONG dy = 0, DWORD data = 0) {
    INPUT in{};
    in.type = INPUT_MOUSE;
    in.mi.dx = dx;
    in.mi.dy = dy;
    in.mi.mouseData = data;
    in.mi.dwFlags = flags;
    SendInput(1, &in, sizeof in);
}

// Stick value (-32768..32767) to -1..1 with a dead zone and a curve: fine control near the
// centre, fast across the screen at full tilt.
double axis(int16_t v) {
    const double x = std::clamp(v / 32767.0, -1.0, 1.0);
    const double dead = 0.18;
    if (std::abs(x) < dead) return 0;
    const double t = (std::abs(x) - dead) / (1 - dead);
    return std::copysign(t * t, x);
}

}  // namespace

void type_text(const std::string& utf8, bool enter, bool backspace) {
    if (backspace) {
        key(VK_BACK, true);
        key(VK_BACK, false);
        return;
    }
    std::wstring text;
    if (!utf8.empty()) {
        const int n = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), int(utf8.size()), nullptr, 0);
        text.resize(size_t(std::max(n, 0)));
        if (n > 0) MultiByteToWideChar(CP_UTF8, 0, utf8.data(), int(utf8.size()), text.data(), n);
    }
    std::vector<INPUT> events;
    events.reserve(text.size() * 2 + 2);
    for (wchar_t c : text) {
        if (c == L'\r') continue;
        if (c == L'\n') {  // a line break is Enter
            for (bool down : {true, false}) {
                INPUT in{};
                in.type = INPUT_KEYBOARD;
                in.ki.wVk = VK_RETURN;
                in.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
                events.push_back(in);
            }
            continue;
        }
        for (bool down : {true, false}) {
            INPUT in{};
            in.type = INPUT_KEYBOARD;
            in.ki.wScan = c;
            in.ki.dwFlags = KEYEVENTF_UNICODE | (down ? 0 : KEYEVENTF_KEYUP);
            events.push_back(in);
        }
    }
    if (!events.empty()) SendInput(UINT(events.size()), events.data(), sizeof(INPUT));
    if (enter) {
        key(VK_RETURN, true);
        key(VK_RETURN, false);
    }
}

PadMouse::~PadMouse() { set_active(false); }

void PadMouse::set_active(bool on) {
    if (on == active_) return;
    active_ = on;
    if (on) {
        {
            std::lock_guard lock(mu_);
            have_state_ = false;  // buttons held right now don't count as presses
        }
        thread_ = std::jthread([this](std::stop_token stop) { run(stop); });
        TCL_INFO("padmouse", "controller works as mouse/keyboard while a window waits for the player");
    } else if (thread_.joinable()) {
        thread_.request_stop();
        thread_.join();
        TCL_INFO("padmouse", "controller back to the game");
    }
}

void PadMouse::update(const tclproto::InputState& state) {
    if (!active_ || state.pad_index != 0) return;
    std::lock_guard lock(mu_);
    state_ = state;
    have_state_ = true;
}

void PadMouse::run(std::stop_token stop) {
    using namespace std::chrono;
    // Buttons -> what they press. Presses and releases follow the controller.
    struct Map {
        uint16_t button;
        bool is_key;
        WORD vk;
        DWORD down, up;  // mouse flags
    };
    static constexpr Map kMap[] = {
        {tclproto::kBtnA, false, 0, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP},
        {tclproto::kBtnX, false, 0, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP},
        {tclproto::kBtnB, true, VK_ESCAPE, 0, 0},
        {tclproto::kBtnY, true, VK_TAB, 0, 0},
        {tclproto::kBtnStart, true, VK_RETURN, 0, 0},
        {tclproto::kBtnDpadUp, true, VK_UP, 0, 0},
        {tclproto::kBtnDpadDown, true, VK_DOWN, 0, 0},
        {tclproto::kBtnDpadLeft, true, VK_LEFT, 0, 0},
        {tclproto::kBtnDpadRight, true, VK_RIGHT, 0, 0},
    };
    uint16_t held = 0;
    bool primed = false;  // first state seen: what is already held then is not a press
    double carry_x = 0, carry_y = 0, carry_wheel = 0;
    auto previous = steady_clock::now();
    while (!stop.stop_requested()) {
        std::this_thread::sleep_for(8ms);
        const auto now = steady_clock::now();
        const double dt = duration<double>(now - previous).count();
        previous = now;
        tclproto::InputState s;
        {
            std::lock_guard lock(mu_);
            if (!have_state_) continue;
            s = state_;
        }
        if (!primed) {
            held = s.buttons;
            primed = true;
            continue;
        }
        for (const auto& m : kMap) {
            const bool now_down = s.buttons & m.button, was_down = held & m.button;
            if (now_down == was_down) continue;
            if (m.is_key) key(m.vk, now_down);
            else mouse(now_down ? m.down : m.up);
        }
        held = s.buttons;
        // Pointer: up to ~1500 px/s at full tilt (+y on the stick is up, screen y grows down).
        carry_x += axis(s.lx) * 1500 * dt;
        carry_y -= axis(s.ly) * 1500 * dt;
        const LONG mx = LONG(carry_x), my = LONG(carry_y);
        carry_x -= mx;
        carry_y -= my;
        if (mx || my) mouse(MOUSEEVENTF_MOVE, mx, my);
        // Right stick: wheel, a notch at a time.
        carry_wheel += axis(s.ry) * 12 * dt;
        if (std::abs(carry_wheel) >= 1) {
            const int notches = int(carry_wheel);
            carry_wheel -= notches;
            mouse(MOUSEEVENTF_WHEEL, 0, 0, DWORD(notches * WHEEL_DELTA));
        }
    }
    // Leave nothing pressed.
    for (const auto& m : kMap) {
        if (!(held & m.button)) continue;
        if (m.is_key) key(m.vk, false);
        else mouse(m.up);
    }
}

}  // namespace tcl
