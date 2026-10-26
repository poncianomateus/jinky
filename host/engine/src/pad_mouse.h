// The TV's controller as mouse and keyboard, while a window waits for the player in front of the
// game: Steam's license agreement on a game's first start, a launcher's "Play" button, a "cloud
// sync conflict" dialog. Those windows have no controller support and the TV has no mouse.
//   left stick  moves the pointer (the TV shows it: the cursor is drawn into the picture)
//   right stick scrolls        A  click          X  right click
//   B  Esc (cancel)            Y  Tab (next button)
//   Start  Enter (confirm)     D-pad  arrow keys
// Only while turned on (set_active); the game itself always gets the virtual Xbox pad.
#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

#include "tclproto/protocol.h"

namespace tcl {

// Menu "Teclado" on the TV: types [utf8] on the PC as if on a keyboard (any language: Unicode
// key events), then Enter if asked. [backspace] presses Backspace once instead.
void type_text(const std::string& utf8, bool enter, bool backspace);

class PadMouse {
public:
    ~PadMouse();

    void set_active(bool on);
    bool active() const { return active_; }

    // The latest state of the TV's first controller (call for every input packet).
    void update(const tclproto::InputState& state);

private:
    void run(std::stop_token stop);

    std::atomic<bool> active_{false};
    std::mutex mu_;
    tclproto::InputState state_{};
    bool have_state_ = false;
    std::jthread thread_;
};

}  // namespace tcl
