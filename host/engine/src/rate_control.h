// Automatic picture quality. The TV reports once a second how many video packets it had to ask for
// again and whether a frame was lost for good; the bitrate backs off quickly when the network
// struggles and climbs back slowly once it is calm, never above the quality the user picked.
#pragma once

#include <algorithm>
#include <cstdint>

#include "tclproto/protocol.h"

namespace tcl {

class RateControl {
public:
    static constexpr uint32_t kFloorKbps = 8000;  // below this 1080p60 falls apart anyway

    // A new session: start at the user's quality.
    void reset(uint32_t ceiling_kbps) {
        ceiling_ = current_ = ceiling_kbps;
        calm_ = 0;
        cooldown_ = 0;
    }

    // Returns the bitrate to switch to, or 0 to keep the current one.
    uint32_t on_report(const tclproto::NetReport& r) {
        if (!ceiling_) return 0;
        if (cooldown_ > 0) cooldown_--;
        // A mostly static screen sends a handful of packets; one loss there says little.
        const bool enough = r.packets >= 200;
        const bool bad = r.frames_unrecovered > 0 || (enough && r.lost_permille >= 50);
        const bool shaky = enough && r.lost_permille >= 20;
        uint32_t next = current_;
        if ((bad || shaky) && cooldown_ == 0) {
            // Some loss can just be Wi-Fi noise that a lower rate won't cure (and resends already
            // hide it), so it costs at most 40% of the quality; only frames actually lost or heavy
            // loss go further down.
            const uint32_t floor = bad ? std::min(kFloorKbps, ceiling_)
                                       : std::max(std::min(kFloorKbps, ceiling_), ceiling_ * 60 / 100);
            next = std::max(floor, uint32_t(uint64_t(current_) * (bad ? 70 : 85) / 100));
            if (next > current_) next = current_;
            // Give the change a couple of seconds to show before judging again.
            cooldown_ = 2;
            calm_ = 0;
        } else if (!bad && !shaky && r.lost_permille < 5) {
            // Ten calm seconds before the first step up, then one step every five.
            if (++calm_ >= 10 && current_ < ceiling_) {
                next = std::min(ceiling_, uint32_t(uint64_t(current_) * 115 / 100));
                calm_ = 5;
            }
        } else {
            calm_ = 0;
        }
        if (next == current_ || (next != ceiling_ && next != kFloorKbps &&
                                 std::max(next, current_) - std::min(next, current_) < 500))
            return 0;
        current_ = next;
        return current_;
    }

    uint32_t current() const { return current_; }
    uint32_t ceiling() const { return ceiling_; }

private:
    uint32_t ceiling_ = 0, current_ = 0;
    int calm_ = 0, cooldown_ = 0;
};

}  // namespace tcl
