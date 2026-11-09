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
    // `big_picture`: also open Steam Big Picture (not for a game started from the TV's library).
    void enter(const std::wstring& display, bool big_picture = true);

    // A virtual screen (a workplace without a real one, or Jinky's own) goes to the size the TV
    // asked for ([width] x [height] at 60 Hz) while console mode is on; real monitors keep theirs.
    void fit_virtual(const std::wstring& display, int width, int height);
    // HDR sessions (the TV asked for HDR): turns Windows HDR on for the display, if it supports it
    // and it was off; leave() (or restore_if_left after a crash) turns it off again.
    void set_hdr(bool on);
    bool hdr_turned_on() const { return hdr_turned_on_; }
    // [close_big_picture] false: the display and sound go back to normal but Big Picture stays
    // open, so the next game starts sooner (closed when Jinky itself closes).
    void leave(bool close_big_picture = true);
    bool active() const { return active_; }

    // True once when the user has left Big Picture (its window existed and has been gone for 2 s):
    // the session is over. Call about once a second while active.
    bool big_picture_closed();

    // Undoes a console mode left behind by a host that was killed. Returns true if it did anything.
    static bool restore_if_left();

    // Closes Big Picture (left open between games with --keep-big-picture).
    static void close_big_picture();

    // Whether Steam's Big Picture window exists (open, even if behind a game); with
    // `visible_only`, only once it is actually shown (it is created hidden while it loads).
    static bool big_picture_window_open(bool visible_only = false);

private:
    static bool set_refresh_60(const std::wstring& display, int& previous_hz);
    // Windows HDR ("advanced color") of the monitor shown as [display]: reads it, or sets it.
    static bool windows_hdr(const std::wstring& display, bool* supported, bool* enabled);
    static bool set_windows_hdr(const std::wstring& display, bool on);
    void write_marker() const;
    static void restore_display(const std::wstring& display);

    bool active_ = false;
    bool bp_seen_ = false;
    bool bp_opened_ = false;
    std::chrono::steady_clock::time_point bp_missing_since_{};
    std::wstring display_;
    bool hdr_turned_on_ = false;  // this session turned Windows HDR on (to undo when it ends)
};

}  // namespace tcl
