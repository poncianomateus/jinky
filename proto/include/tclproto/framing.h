// Splitting encoded frames into datagrams (host) and putting them back together (receiver).
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <span>
#include <vector>

#include "tclproto/protocol.h"

namespace tclproto {

// Splits `frame` into datagrams of at most MediaHeader::kSize + kMaxPayload bytes.
// `base` supplies type/flags/session/frame_id/capture time; fragment fields are filled in.
// `send` is called once per datagram, in order. Returns the number of datagrams produced.
size_t fragment_frame(const MediaHeader& base, std::span<const uint8_t> frame,
                      const std::function<void(std::span<const uint8_t>)>& send);

// True if sequence number a is newer than b, tolerant of 32-bit wraparound.
inline bool seq_newer(uint32_t a, uint32_t b) { return int32_t(a - b) > 0; }

// Reassembles video frames and enforces decodability: after any loss, nothing is
// delivered until a keyframe arrives, so the decoder never receives a broken reference chain.
class FrameAssembler {
public:
    struct Frame {
        uint32_t frame_id = 0;
        bool keyframe = false;
        bool hevc = false;
        uint64_t capture_time_us = 0;
        std::vector<uint8_t> data;
    };

    struct Stats {
        uint64_t packets_received = 0;
        uint64_t packets_duplicate = 0;
        uint64_t packets_stale = 0;     // arrived for a frame already delivered/abandoned
        uint64_t frames_delivered = 0;
        uint64_t frames_lost = 0;       // never completed
        uint64_t frames_discarded = 0;  // completed but undecodable (waiting for keyframe)
    };

    using FrameCallback = std::function<void(Frame&&)>;

    // Max frames being reassembled at once; older partial frames beyond this are declared lost.
    static constexpr size_t kMaxInFlight = 8;

    explicit FrameAssembler(FrameCallback on_frame) : on_frame_(std::move(on_frame)) {}

    // `payload` is the datagram content after the header.
    void push(const MediaHeader& header, std::span<const uint8_t> payload);

    // True while the stream is unusable until a keyframe arrives (initially true).
    bool waiting_for_keyframe() const { return waiting_for_keyframe_; }

    const Stats& stats() const { return stats_; }

    // Drops all state, e.g. when a new session starts.
    void reset();

private:
    struct Partial {
        MediaHeader header;
        std::vector<uint8_t> data;
        std::vector<bool> have;
        uint16_t received = 0;
    };

    void complete(uint32_t frame_id);
    void abandon_older_than(uint32_t frame_id);

    FrameCallback on_frame_;
    std::map<uint32_t, Partial> partials_;  // keyed by frame_id; small, so ordering cost is trivial
    bool has_last_ = false;
    uint32_t last_id_ = 0;  // newest frame delivered or abandoned
    bool waiting_for_keyframe_ = true;
    Stats stats_;
};

}  // namespace tclproto
