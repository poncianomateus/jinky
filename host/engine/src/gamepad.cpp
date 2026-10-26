#include "gamepad.h"

#include <windows.h>

#include <algorithm>

// The header's comments are Latin-1, not UTF-8; harmless, so silence C4828 for it only.
#pragma warning(push)
#pragma warning(disable : 4828)
#include <ViGEm/Client.h>
#pragma warning(pop)

#include "log.h"

namespace tcl {

namespace {

VOID CALLBACK x360_notification(PVIGEM_CLIENT, PVIGEM_TARGET target, UCHAR large_motor, UCHAR small_motor, UCHAR,
                                LPVOID user) {
    static_cast<GamepadHub*>(user)->on_rumble_from_driver(target, large_motor, small_motor);
}

VOID CALLBACK ds4_notification(PVIGEM_CLIENT, PVIGEM_TARGET target, UCHAR large_motor, UCHAR small_motor,
                               DS4_LIGHTBAR_COLOR, LPVOID user) {
    static_cast<GamepadHub*>(user)->on_rumble_from_driver(target, large_motor, small_motor);
}

// Stick -32768..32767 (+y up) to the DualShock 4's 0..255 (+y down, 128 = centre).
BYTE ds4_axis(int16_t v, bool invert) {
    const int b = (int(v) + 32768) >> 8;
    return BYTE(invert ? 255 - b : b);
}

// The pad's state, Xbox layout (as the TV sends it), as a complete DualShock 4 report with the
// touchpad and motion from [e].
DS4_REPORT_EX ds4_report(const tclproto::InputState& s, const tclproto::PadExtras& e, uint8_t touch_counter) {
    DS4_REPORT_EX r{};
    auto& d = r.Report;
    d.bThumbLX = ds4_axis(s.lx, false);
    d.bThumbLY = ds4_axis(s.ly, true);
    d.bThumbRX = ds4_axis(s.rx, false);
    d.bThumbRY = ds4_axis(s.ry, true);
    USHORT b = 0;
    const uint16_t x = s.buttons;
    if (x & tclproto::kBtnA) b |= DS4_BUTTON_CROSS;
    if (x & tclproto::kBtnB) b |= DS4_BUTTON_CIRCLE;
    if (x & tclproto::kBtnX) b |= DS4_BUTTON_SQUARE;
    if (x & tclproto::kBtnY) b |= DS4_BUTTON_TRIANGLE;
    if (x & tclproto::kBtnLeftShoulder) b |= DS4_BUTTON_SHOULDER_LEFT;
    if (x & tclproto::kBtnRightShoulder) b |= DS4_BUTTON_SHOULDER_RIGHT;
    if (x & tclproto::kBtnBack) b |= DS4_BUTTON_SHARE;
    if (x & tclproto::kBtnStart) b |= DS4_BUTTON_OPTIONS;
    if (x & tclproto::kBtnLeftThumb) b |= DS4_BUTTON_THUMB_LEFT;
    if (x & tclproto::kBtnRightThumb) b |= DS4_BUTTON_THUMB_RIGHT;
    if (s.left_trigger > 30) b |= DS4_BUTTON_TRIGGER_LEFT;
    if (s.right_trigger > 30) b |= DS4_BUTTON_TRIGGER_RIGHT;
    const bool up = x & tclproto::kBtnDpadUp, down = x & tclproto::kBtnDpadDown;
    const bool left = x & tclproto::kBtnDpadLeft, right = x & tclproto::kBtnDpadRight;
    DS4_DPAD_DIRECTIONS dpad = DS4_BUTTON_DPAD_NONE;
    if (up && right) dpad = DS4_BUTTON_DPAD_NORTHEAST;
    else if (down && right) dpad = DS4_BUTTON_DPAD_SOUTHEAST;
    else if (down && left) dpad = DS4_BUTTON_DPAD_SOUTHWEST;
    else if (up && left) dpad = DS4_BUTTON_DPAD_NORTHWEST;
    else if (up) dpad = DS4_BUTTON_DPAD_NORTH;
    else if (right) dpad = DS4_BUTTON_DPAD_EAST;
    else if (down) dpad = DS4_BUTTON_DPAD_SOUTH;
    else if (left) dpad = DS4_BUTTON_DPAD_WEST;
    d.wButtons = USHORT(b | (dpad & 0xF));
    d.bSpecial = BYTE(((x & tclproto::kBtnGuide) ? DS4_SPECIAL_BUTTON_PS : 0) |
                      ((e.flags & tclproto::PadExtras::kTouchClick) ? DS4_SPECIAL_BUTTON_TOUCHPAD : 0));
    d.bTriggerL = s.left_trigger;
    d.bTriggerR = s.right_trigger;
    // Timestamp in the DualShock 4's 5.33 us units.
    d.wTimestamp = USHORT(GetTickCount64() * 3 * 1000 / 16);
    d.bBatteryLvl = 0xFF;
    d.bBatteryLvlSpecial = 0x1B;  // cable connected, full: games don't nag about battery
    d.wGyroX = e.gyro[0];
    d.wGyroY = e.gyro[1];
    d.wGyroZ = e.gyro[2];
    d.wAccelX = e.accel[0];
    d.wAccelY = e.accel[1];
    d.wAccelZ = e.accel[2];
    // Touchpad: up to two fingers, 12-bit x and y packed into 3 bytes; bit 7 set = not touching.
    d.bTouchPacketsN = 1;
    d.sCurrentTouch.bPacketCounter = touch_counter;
    auto pack = [](const tclproto::PadExtras::Touch& t, BYTE& tracking, BYTE* data) {
        tracking = BYTE((t.id & 0x7F) | (t.down ? 0 : 0x80));
        const uint16_t tx = std::min<uint16_t>(t.x, 1919), ty = std::min<uint16_t>(t.y, 942);
        data[0] = BYTE(tx & 0xFF);
        data[1] = BYTE(((tx >> 8) & 0x0F) | ((ty & 0x0F) << 4));
        data[2] = BYTE((ty >> 4) & 0xFF);
    };
    pack(e.touch[0], d.sCurrentTouch.bIsUpTrackingNum1, d.sCurrentTouch.bTouchData1);
    pack(e.touch[1], d.sCurrentTouch.bIsUpTrackingNum2, d.sCurrentTouch.bTouchData2);
    return r;
}

}  // namespace

GamepadHub::~GamepadHub() {
    unplug_all();
    if (client_) {
        vigem_disconnect(static_cast<PVIGEM_CLIENT>(client_));
        vigem_free(static_cast<PVIGEM_CLIENT>(client_));
    }
}

bool GamepadHub::init(RumbleCallback on_rumble) {
    on_rumble_ = std::move(on_rumble);
    auto client = vigem_alloc();
    if (!client) {
        error_ = "out of memory";
        return false;
    }
    const VIGEM_ERROR err = vigem_connect(client);
    if (!VIGEM_SUCCESS(err)) {
        vigem_free(client);
        error_ = err == VIGEM_ERROR_BUS_NOT_FOUND ? "ViGEmBus driver not installed (controllers disabled)"
                                                  : "could not connect to ViGEmBus (error " + std::to_string(err) + ")";
        return false;
    }
    client_ = client;
    return true;
}

bool GamepadHub::plug(int index) {
    Pad& pad = pads_[index];
    auto client = static_cast<PVIGEM_CLIENT>(client_);
    const bool ds4 = pad.want_ds4;
    auto target = ds4 ? vigem_target_ds4_alloc() : vigem_target_x360_alloc();
    const VIGEM_ERROR err = vigem_target_add(client, target);
    if (!VIGEM_SUCCESS(err)) {
        vigem_target_free(target);
        TCL_ERROR("gamepad", "could not plug virtual {} pad {} (error {})", ds4 ? "DualShock 4" : "Xbox", index, unsigned(err));
        return false;
    }
#pragma warning(push)
#pragma warning(disable : 4996)  // the DS4 notification is "deprecated" but is the one with rumble values
    if (ds4) vigem_target_ds4_register_notification(client, target, ds4_notification, this);
#pragma warning(pop)
    else vigem_target_x360_register_notification(client, target, x360_notification, this);
    pad.ds4 = ds4;
    pad.target = target;
    TCL_INFO("gamepad", "virtual {} pad {} plugged in", ds4 ? "DualShock 4" : "Xbox 360", index);
    emit_event("pad", std::format("\"index\":{},\"on\":true,\"ds4\":{}", index, ds4 ? "true" : "false"));
    return true;
}

void GamepadHub::send(int index) {
    Pad& pad = pads_[index];
    auto client = static_cast<PVIGEM_CLIENT>(client_);
    auto target = static_cast<PVIGEM_TARGET>(pad.target.load());
    if (!target) return;
    if (pad.ds4) {
        vigem_target_ds4_update_ex(client, target, ds4_report(pad.state, pad.extras, pad.touch_counter));
        return;
    }
    const auto& s = pad.state;
    XUSB_REPORT report{};
    report.wButtons = s.buttons;
    report.bLeftTrigger = s.left_trigger;
    report.bRightTrigger = s.right_trigger;
    report.sThumbLX = s.lx;
    report.sThumbLY = s.ly;
    report.sThumbRX = s.rx;
    report.sThumbRY = s.ry;
    vigem_target_x360_update(client, target, report);
}

void GamepadHub::apply(const tclproto::InputState& s) {
    std::lock_guard lock(mu_);
    if (!client_ || s.pad_index >= kMaxPads) return;
    Pad& pad = pads_[s.pad_index];

    if (!s.connected) {
        if (pad.target) {
            TCL_INFO("gamepad", "pad {} disconnected on the TV", s.pad_index);
            emit_event("pad", std::format("\"index\":{},\"on\":false", s.pad_index));
        }
        unplug(s.pad_index);
        pad.want_ds4 = false;
        return;
    }
    // States are repeated and may arrive out of order; only move forward (wraparound-safe).
    if (pad.target && int32_t(s.seq - pad.last_seq) <= 0) return;
    // The TV switched this pad between Xbox and DualShock 4: plug it again as the other kind.
    if (pad.target && pad.ds4 != pad.want_ds4) unplug(s.pad_index);
    if (!pad.target && !plug(s.pad_index)) return;
    pad.last_seq = s.seq;
    pad.last_update = std::chrono::steady_clock::now();
    pad.state = s;
    send(s.pad_index);
}

void GamepadHub::apply_extras(const tclproto::PadExtras& e) {
    std::lock_guard lock(mu_);
    if (!client_ || e.pad_index >= kMaxPads) return;
    Pad& pad = pads_[e.pad_index];
    pad.want_ds4 = e.flags & tclproto::PadExtras::kPlayStation;
    const bool touch_changed = e.touch[0].down != pad.extras.touch[0].down || e.touch[0].x != pad.extras.touch[0].x ||
                               e.touch[0].y != pad.extras.touch[0].y || e.touch[1].down != pad.extras.touch[1].down ||
                               e.touch[1].x != pad.extras.touch[1].x || e.touch[1].y != pad.extras.touch[1].y;
    if (touch_changed) pad.touch_counter++;
    pad.extras = e;
    // Motion and touch go out right away (the buttons' state stays as last received).
    if (pad.target && pad.ds4 && pad.want_ds4) send(e.pad_index);
}

void GamepadHub::expire(std::chrono::steady_clock::duration timeout) {
    std::lock_guard lock(mu_);
    const auto now = std::chrono::steady_clock::now();
    for (int i = 0; i < kMaxPads; ++i) {
        if (pads_[i].target && now - pads_[i].last_update > timeout) {
            TCL_WARN("gamepad", "pad {} silent, unplugging it", i);
            emit_event("pad", std::format("\"index\":{},\"on\":false", i));
            unplug(i);
        }
    }
}

void GamepadHub::unplug_all() {
    std::lock_guard lock(mu_);
    for (int i = 0; i < kMaxPads; ++i) unplug(i);
}

void GamepadHub::unplug(int index) {
    Pad& pad = pads_[index];
    if (!pad.target) return;
    auto client = static_cast<PVIGEM_CLIENT>(client_);
    auto target = static_cast<PVIGEM_TARGET>(pad.target.exchange(nullptr));
    // Stop vibration on the TV side too, so a pad doesn't keep buzzing after unplug.
    if (on_rumble_) on_rumble_(uint8_t(index), 0, 0);
#pragma warning(push)
#pragma warning(disable : 4996)
    if (pad.ds4) vigem_target_ds4_unregister_notification(target);
#pragma warning(pop)
    else vigem_target_x360_unregister_notification(target);
    vigem_target_remove(client, target);
    vigem_target_free(target);
    pad.last_seq = 0;
    pad.ds4 = false;
}

void GamepadHub::on_rumble_from_driver(void* target, uint8_t large_motor, uint8_t small_motor) {
    // Runs on a driver thread; find which pad this is without taking mu_ (apply() may hold it
    // while calling into the driver, which could deadlock). Targets are atomics.
    for (int i = 0; i < kMaxPads; ++i) {
        if (pads_[i].target == target) {
            if (on_rumble_) on_rumble_(uint8_t(i), large_motor, small_motor);
            return;
        }
    }
}

}  // namespace tcl
