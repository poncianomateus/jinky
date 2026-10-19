#include "gamepad.h"

#include <windows.h>

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

void GamepadHub::apply(const tclproto::InputState& s) {
    std::lock_guard lock(mu_);
    if (!client_ || s.pad_index >= kMaxPads) return;
    Pad& pad = pads_[s.pad_index];
    auto client = static_cast<PVIGEM_CLIENT>(client_);

    if (!s.connected) {
        if (pad.target) {
            TCL_INFO("gamepad", "pad {} disconnected on the TV", s.pad_index);
            emit_event("pad", std::format("\"index\":{},\"on\":false", s.pad_index));
        }
        unplug(s.pad_index);
        return;
    }
    // States are repeated and may arrive out of order; only move forward (wraparound-safe).
    if (pad.target && int32_t(s.seq - pad.last_seq) <= 0) return;

    if (!pad.target) {
        auto target = vigem_target_x360_alloc();
        const VIGEM_ERROR err = vigem_target_add(client, target);
        if (!VIGEM_SUCCESS(err)) {
            vigem_target_free(target);
            TCL_ERROR("gamepad", "could not plug virtual Xbox pad {} (error {})", s.pad_index, unsigned(err));
            return;
        }
        vigem_target_x360_register_notification(client, target, x360_notification, this);
        pad.target = target;
        TCL_INFO("gamepad", "virtual Xbox 360 pad {} plugged in", s.pad_index);
        emit_event("pad", std::format("\"index\":{},\"on\":true", s.pad_index));
    }
    pad.last_seq = s.seq;
    pad.last_update = std::chrono::steady_clock::now();

    XUSB_REPORT report{};
    report.wButtons = s.buttons;
    report.bLeftTrigger = s.left_trigger;
    report.bRightTrigger = s.right_trigger;
    report.sThumbLX = s.lx;
    report.sThumbLY = s.ly;
    report.sThumbRX = s.rx;
    report.sThumbRY = s.ry;
    vigem_target_x360_update(client, static_cast<PVIGEM_TARGET>(pad.target.load()), report);
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
    vigem_target_x360_unregister_notification(target);
    vigem_target_remove(client, target);
    vigem_target_free(target);
    pad.last_seq = 0;
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
