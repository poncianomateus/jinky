// Virtual controllers on the PC (via the ViGEmBus driver), driven by the TV's gamepads: Xbox 360
// pads, or DualShock 4 pads (with touchpad and motion) when the TV says it holds a PlayStation
// controller and the user chose "Controle PlayStation no PC".
// Everything ViGEm-specific stays in this file pair so the driver can be swapped later.
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>

#include "tclproto/protocol.h"

namespace tcl {

class GamepadHub {
public:
    static constexpr int kMaxPads = 4;
    // Called from a driver thread when a game asks for vibration on a pad.
    using RumbleCallback = std::function<void(uint8_t pad, uint8_t large_motor, uint8_t small_motor)>;

    ~GamepadHub();
    // Fails (with error()) if the ViGEmBus driver is not installed/running.
    bool init(RumbleCallback on_rumble);

    // Applies a state from the TV: plugs a virtual pad on first use, unplugs on connected=false.
    void apply(const tclproto::InputState& state);
    // Touchpad and motion of a PlayStation controller (and whether to plug it as a DualShock 4).
    void apply_extras(const tclproto::PadExtras& extras);
    // Unplugs pads that have not been updated for `timeout` (TV app closed or crashed).
    void expire(std::chrono::steady_clock::duration timeout);
    void unplug_all();

    const std::string& error() const { return error_; }

private:
    struct Pad {
        std::atomic<void*> target{nullptr};  // PVIGEM_TARGET; also read by the driver's rumble thread
        uint32_t last_seq = 0;
        std::chrono::steady_clock::time_point last_update;
        bool ds4 = false;       // the plugged target is a DualShock 4 (else an Xbox 360 pad)
        bool want_ds4 = false;  // the TV asks for a DualShock 4
        tclproto::InputState state{};
        tclproto::PadExtras extras{};
        uint8_t touch_counter = 0;
    };
    void unplug(int index);
    bool plug(int index);
    void send(int index);

public:
    // Driver callback entry point (see gamepad.cpp); not for general use.
    void on_rumble_from_driver(void* target, uint8_t large_motor, uint8_t small_motor);

private:
    void* client_ = nullptr;  // PVIGEM_CLIENT
    Pad pads_[kMaxPads];
    RumbleCallback on_rumble_;
    std::mutex mu_;
    std::string error_;
};

}  // namespace tcl
