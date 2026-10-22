// Splitting encoded frames into datagrams (host) and putting them back together (receiver).
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <span>
#include <vector>

#include "tclproto/protocol.h"

namespace tclproto {

// Datagrams needed for a frame of `frame_size` bytes.
inline size_t fragment_count(size_t frame_size) { return (frame_size + kMaxPayload - 1) / kMaxPayload; }

// Builds datagram `index` of `frame` into `out` (at least MediaHeader::kSize + kMaxPayload bytes).
// `base` supplies type/flags/session/frame_id/capture time. Returns its size, 0 if out of range.
size_t write_fragment(const MediaHeader& base, std::span<const uint8_t> frame, size_t index, std::span<uint8_t> out);

// Splits `frame` into datagrams of at most MediaHeader::kSize + kMaxPayload bytes.
// `send` is called once per datagram, in order. Returns the number of datagrams produced.
size_t fragment_frame(const MediaHeader& base, std::span<const uint8_t> frame,
                      const std::function<void(std::span<const uint8_t>)>& send);

// True if sequence number a is newer than b, tolerant of 32-bit wraparound.
inline bool seq_newer(uint32_t a, uint32_t b) { return int32_t(a - b) > 0; }

// Reassembles video frames and delivers them strictly in order. A frame with missing fragments
// holds back the ones after it while poll() asks the host to send the gaps again; only when that
// fails (or takes too long) is the frame lost, and then nothing is delivered until a keyframe, so
// the decoder never receives a broken reference chain.
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
        uint64_t packets_missing = 0;   // gaps asked for again (a whole missing frame counts once)
        uint64_t frames_delivered = 0;
        uint64_t frames_recovered = 0;  // completed thanks to a resend
        uint64_t frames_lost = 0;       // never completed
        uint64_t frames_discarded = 0;  // completed but undecodable (waiting for keyframe)
    };

    // A request to resend fragments of one frame (see NackMessage).
    struct Nack {
        uint32_t frame_id = 0;
        std::vector<NackMessage::Range> ranges;
    };

    struct Timing {
        int64_t reorder_us = 3'000;   // a gap younger than this may only be packets out of order
        int64_t retry_us = 15'000;    // ask again after this if still missing (about 2 round trips)
        int max_tries = 3;
        int64_t give_up_us = 70'000;  // missing this long: lost, resync on a keyframe
    };

    using FrameCallback = std::function<void(Frame&&)>;

    // Frames held at once (incomplete or waiting for an earlier one); beyond this the oldest is lost.
    static constexpr size_t kMaxInFlight = 32;

    explicit FrameAssembler(FrameCallback on_frame) : on_frame_(std::move(on_frame)) {}

    // `payload` is the datagram content after the header; `now_us` any monotonic clock.
    void push(const MediaHeader& header, std::span<const uint8_t> payload, int64_t now_us);

    // Call every few milliseconds: returns the resend requests due now, and gives up on frames
    // that stayed incomplete too long.
    std::vector<Nack> poll(int64_t now_us);

    // True while some frame is incomplete (poll() has work to do).
    bool has_gaps() const;

    void set_timing(const Timing& t) { timing_ = t; }

    // True while the stream is unusable until a keyframe arrives (initially true).
    bool waiting_for_keyframe() const { return waiting_for_keyframe_; }

    const Stats& stats() const { return stats_; }

    // Drops all state, e.g. when a new session starts.
    void reset();

private:
    struct Slot {
        bool known = false;  // a fragment arrived (otherwise only its absence was noticed)
        MediaHeader header;
        std::vector<uint8_t> data;
        std::vector<bool> have;
        uint16_t received = 0;
        uint16_t highest = 0;  // highest fragment index received + 1
        bool complete = false;
        int64_t last_packet_us = 0;
        int64_t missing_since_us = 0;  // 0 = no gap noticed yet
        int64_t last_nack_us = 0;
        int tries = 0;
    };
    struct SeqLess {
        bool operator()(uint32_t a, uint32_t b) const { return seq_newer(b, a); }
    };

    Slot& slot_for(uint32_t frame_id);
    std::vector<NackMessage::Range> missing_ranges(uint32_t frame_id, const Slot& s, int64_t now_us) const;
    void deliver_ready();
    void drop_front(bool lost);
    void skip_to(uint32_t keyframe_id);

    FrameCallback on_frame_;
    std::map<uint32_t, Slot, SeqLess> slots_;  // frames after last_id_, oldest first
    bool has_last_ = false;
    uint32_t last_id_ = 0;  // newest frame delivered or abandoned
    bool has_newest_ = false;
    uint32_t newest_ = 0;   // newest frame any packet belonged to
    bool waiting_for_keyframe_ = true;
    Timing timing_;
    Stats stats_;
};

}  // namespace tclproto
