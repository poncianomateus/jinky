// "Console mode": while a TV is connected the PC behaves like a console — Steam Big Picture in
// front, the display at 60 Hz (the TV's rate, so games pace and V-Sync to the stream), speakers
// muted. Everything is put back when the TV leaves, and on the next start if the host died.
#pragma once

#include <chrono>
#include <string>

namespace tcl {

class ConsoleMode {
public:
    // `display` is the GDI device name of the captured monitor (e.g. \\.\DISPLAY1).
    void enter(const std::wstring& display);
    void leave();
    bool active() const { return active_; }

    // True once when the user has left Big Picture (its window existed and has been gone for 2 s):
    // the session is over. Call about once a second while active.
    bool big_picture_closed();

    // Undoes a console mode left behind by a host that was killed. Returns true if it did anything.
    static bool restore_if_left();

private:
    static bool set_refresh_60(const std::wstring& display, int& previous_hz);
    static void restore_display(const std::wstring& display);

    static bool big_picture_window_open();

    bool active_ = false;
    bool bp_seen_ = false;
    std::chrono::steady_clock::time_point bp_missing_since_{};
    std::wstring display_;
};

}  // namespace tcl
