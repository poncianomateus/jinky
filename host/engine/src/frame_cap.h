// Frame cap for game sessions: the NVIDIA driver's global "Max Frame Rate" set to the stream's
// rate while a TV plays. A game rendering above 60 fps keeps the GPU at 100%, and then the
// capture conversion and the encoder wait behind it (encode 3 ms -> 11-20 ms, late frames that
// the TV has to drop); frames beyond 60 are never shown on the TV anyway. The user's own value
// is put back when the session ends, and on the next start if the host died (marker file).
#pragma once

#include <cstdint>

namespace tcl {

class FrameCap {
public:
    // Caps games started from now on at `fps`. Does nothing without an NVIDIA driver, or if
    // the user already caps at `fps` or lower. Returns true if it changed the driver setting.
    bool enter(uint32_t fps);
    void leave();
    bool active() const { return active_; }

    // Puts back a cap left behind by a host that was killed. Returns true if it did anything.
    static bool restore_if_left();

private:
    bool active_ = false;
};

}  // namespace tcl
