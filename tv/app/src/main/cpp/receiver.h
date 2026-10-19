// Network side of the TV receiver: finds the host, receives and reassembles video frames,
// asks for keyframes when the stream breaks. Decoding happens in Kotlin (MediaCodec).
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

#include "tclproto/framing.h"
#include "tclproto/udp.h"

namespace tcltv {

class Receiver {
public:
    struct FrameMeta {
        uint32_t frame_id = 0;
        bool keyframe = false;
        uint64_t capture_time_us = 0;
    };

    ~Receiver();

    bool start(uint16_t host_port);
    void stop();

    // Blocks up to timeout_ms for the next decodable frame and copies it into dst.
    // Returns its size, 0 on timeout, or -(size) if dst is too small (frame dropped, keyframe requested).
    int read_frame(uint8_t* dst, size_t capacity, int timeout_ms, FrameMeta& meta);

    // Decoder lost sync (error, stall, recreation): next frames are useless until a keyframe.
    void request_keyframe();
    void report_presented(uint64_t count) { presented_ = count; }
    // Average capture-to-display latency measured by the decoder side, forwarded to the host log.
    void report_latency_us(uint32_t us) { latency_us_ = us; }

    // Host clock minus local clock (steady/monotonic, microseconds). Valid once clock_synced().
    int64_t clock_offset_us() const { return clock_offset_us_; }
    int64_t rtt_us() const { return rtt_us_; }
    bool clock_synced() const { return clock_synced_; }

    std::string status() const;
    const std::string& error() const { return error_; }

private:
    void net_loop();
    void on_frame(tclproto::FrameAssembler::Frame&& f);
    void send_control(tclproto::PacketType type, uint32_t value);
    void send_time_request();
    void on_time_response(const tclproto::TimeSync& t);

    static constexpr size_t kMaxQueuedFrames = 4;

    tclproto::UdpSocket sock_;
    uint16_t host_port_ = 0;
    std::thread thread_;
    std::atomic<bool> running_{false};

    // Network thread only
    tclproto::FrameAssembler assembler_{[this](tclproto::FrameAssembler::Frame&& f) { on_frame(std::move(f)); }};
    std::optional<tclproto::Endpoint> host_;
    uint32_t session_id_ = 0;
    bool have_session_ = false;

    // Shared with the decoder thread
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::deque<tclproto::FrameAssembler::Frame> queue_;
    // Set when the decoder needs a keyframe: P-frames are dropped until one arrives.
    std::atomic<bool> await_keyframe_{false};
    std::atomic<uint64_t> presented_{0};
    std::atomic<uint32_t> latency_us_{0};

    // Clock sync: keep the sample with the lowest round trip among the recent ones (least jitter).
    static constexpr int kSyncSamples = 8;
    struct SyncSample { int64_t rtt = 0, offset = 0; };
    SyncSample samples_[kSyncSamples];
    int sample_count_ = 0, sample_next_ = 0;
    std::atomic<int64_t> clock_offset_us_{0}, rtt_us_{0};
    std::atomic<bool> clock_synced_{false};

    // Status (written by network thread, read by UI)
    std::atomic<bool> host_known_{false};
    std::atomic<uint32_t> host_ip_{0};
    std::atomic<uint64_t> packets_{0}, frames_ok_{0}, frames_lost_{0}, frames_dropped_{0}, idr_requests_{0};
    std::atomic<int64_t> last_packet_ms_{0};
    std::string error_;
};

}  // namespace tcltv
