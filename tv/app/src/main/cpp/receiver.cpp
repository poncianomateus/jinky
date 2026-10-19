#include "receiver.h"

#include <android/log.h>

#include <chrono>
#include <cstring>
#include <iterator>
#include <sstream>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TclStream", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "TclStream", __VA_ARGS__)

using namespace std::chrono;
using tclproto::PacketType;

namespace tcltv {

namespace {
int64_t now_ms() { return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count(); }
// steady_clock is CLOCK_MONOTONIC on Android, the same clock as Java's System.nanoTime().
int64_t now_us() { return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count(); }
}  // namespace

Receiver::~Receiver() { stop(); }

bool Receiver::start(uint16_t host_port) {
    if (running_) return true;
    host_port_ = host_port;
    if (!sock_.open(0) || !sock_.enable_broadcast()) {
        error_ = sock_.error();
        return false;
    }
    running_ = true;
    thread_ = std::thread(&Receiver::net_loop, this);
    return true;
}

void Receiver::stop() {
    if (!running_.exchange(false)) return;
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
    sock_.close();
}

void Receiver::send_control(PacketType type, uint32_t value) {
    uint8_t buf[tclproto::ControlMessage::kSize];
    tclproto::ControlMessage{type, session_id_, value}.write(buf);
    sock_.send_to(host_ ? *host_ : tclproto::Endpoint::broadcast(host_port_), buf);
}

void Receiver::send_time_request() {
    uint8_t buf[tclproto::TimeSync::kSize];
    tclproto::TimeSync{PacketType::TimeRequest, session_id_, uint64_t(now_us()), 0}.write(buf);
    sock_.send_to(*host_, buf);
}

void Receiver::on_time_response(const tclproto::TimeSync& t) {
    const int64_t now = now_us();
    const int64_t rtt = now - int64_t(t.client_send_us);
    if (rtt < 0 || rtt > 1'000'000) return;
    // Assume symmetric paths: the host read its clock halfway through the round trip.
    samples_[sample_next_] = {rtt, int64_t(t.host_us) + rtt / 2 - now};
    sample_next_ = (sample_next_ + 1) % kSyncSamples;
    if (sample_count_ < kSyncSamples) sample_count_++;
    SyncSample best = samples_[0];
    for (int i = 1; i < sample_count_; ++i)
        if (samples_[i].rtt < best.rtt) best = samples_[i];
    clock_offset_us_ = best.offset;
    rtt_us_ = best.rtt;
    clock_synced_ = true;
}

void Receiver::net_loop() {
    uint8_t buf[2048];
    int64_t last_hello = 0, last_idr_req = 0, last_stats = 0, last_sync = 0;

    while (running_) {
        const int64_t now = now_ms();
        // Hello doubles as discovery (broadcast until the host answers) and keepalive.
        if (now - last_hello >= 1000) {
            send_control(PacketType::Hello, 0);
            last_hello = now;
        }
        if (host_ && now - last_stats >= 1000) {
            send_control(PacketType::Stats, uint32_t(presented_.load()));
            if (latency_us_) send_control(PacketType::LatencyReport, latency_us_);
            last_stats = now;
        }
        // Sync quickly at first, then keep tracking drift.
        if (host_ && now - last_sync >= (sample_count_ < kSyncSamples ? 100 : 1000)) {
            send_time_request();
            last_sync = now;
        }
        if (host_ && (assembler_.waiting_for_keyframe() || await_keyframe_) && now - last_idr_req >= 100) {
            send_control(PacketType::RequestIdr, uint32_t(assembler_.stats().frames_delivered));
            idr_requests_++;
            last_idr_req = now;
        }
        // Host gone quiet: forget it and go back to broadcasting, so a restarted host is found again.
        if (host_ && now - last_packet_ms_ > 5000) {
            LOGW("host %s silent for 5 s", host_->to_string().c_str());
            host_.reset();
            host_known_ = false;
            clock_synced_ = false;
            sample_count_ = sample_next_ = 0;
        }

        tclproto::Endpoint from;
        const int n = sock_.recv_from(buf, from, 20);
        if (n <= 0) continue;
        const std::span<const uint8_t> packet(buf, size_t(n));
        if (tclproto::peek_type(packet) == PacketType::TimeResponse) {
            if (auto t = tclproto::TimeSync::read(packet); t && host_ && from == *host_) on_time_response(*t);
            continue;
        }
        auto h = tclproto::MediaHeader::read(std::span<const uint8_t>(buf, size_t(n)));
        if (!h || h->type != PacketType::Video) continue;
        if (host_ && from != *host_) continue;  // only one host per session

        if (!host_) {
            host_ = from;
            host_known_ = true;
            host_ip_ = from.ip;
            LOGI("host found: %s", from.to_string().c_str());
        }
        if (!have_session_ || h->session_id != session_id_) {
            LOGI("new session %08x", h->session_id);
            session_id_ = h->session_id;
            have_session_ = true;
            assembler_.reset();
            std::lock_guard lock(mu_);
            queue_.clear();
        }
        packets_++;
        last_packet_ms_ = now_ms();
        assembler_.push(*h, std::span<const uint8_t>(buf + tclproto::MediaHeader::kSize,
                                                     size_t(n) - tclproto::MediaHeader::kSize));
        if (assembler_.stats().frames_lost != frames_lost_) {
            LOGW("network: %llu frame(s) lost (packets so far %llu)",
                 (unsigned long long)(assembler_.stats().frames_lost - frames_lost_), (unsigned long long)packets_.load());
            frames_lost_ = assembler_.stats().frames_lost;
        }
    }
    if (host_) send_control(PacketType::Bye, 0);
}

void Receiver::on_frame(tclproto::FrameAssembler::Frame&& f) {
    frames_ok_++;
    {
        std::lock_guard lock(mu_);
        if (f.keyframe) {
            await_keyframe_ = false;
        } else if (await_keyframe_) {
            frames_dropped_++;
            return;
        }
        // Only once the decoder is up: while it starts, frames legitimately pile up for a moment.
        if (presented_ > 0 && queue_.size() >= kMaxQueuedFrames && !f.keyframe) {
            // Decoder can't keep up. Dropping a P-frame breaks the reference chain, so flush
            // everything and resynchronise on a fresh keyframe instead of showing corruption.
            frames_dropped_ += queue_.size() + 1;
            LOGW("decoder behind: %zu frames queued, flushing until next keyframe", queue_.size());
            queue_.clear();
            await_keyframe_ = true;
            return;
        }
        if (f.keyframe) queue_.clear();  // a keyframe makes everything queued before it obsolete
        queue_.push_back(std::move(f));
    }
    cv_.notify_one();
}

void Receiver::request_keyframe() {
    std::lock_guard lock(mu_);
    // A fresh decoder can start from the newest queued keyframe; only ask the host if there is none.
    for (auto it = queue_.rbegin(); it != queue_.rend(); ++it) {
        if (it->keyframe) {
            queue_.erase(queue_.begin(), std::prev(it.base()));
            return;
        }
    }
    queue_.clear();
    await_keyframe_ = true;
}

int Receiver::read_frame(uint8_t* dst, size_t capacity, int timeout_ms, FrameMeta& meta) {
    std::unique_lock lock(mu_);
    if (!cv_.wait_for(lock, milliseconds(timeout_ms), [&] { return !queue_.empty() || !running_; }))
        return 0;
    if (queue_.empty()) return 0;
    auto& f = queue_.front();
    if (f.data.size() > capacity) {
        // Can't be decoded with this buffer; skip it and resync on a keyframe.
        const int size = int(f.data.size());
        queue_.clear();
        await_keyframe_ = true;
        return -size;
    }
    std::memcpy(dst, f.data.data(), f.data.size());
    meta = {f.frame_id, f.keyframe, f.capture_time_us};
    const int size = int(f.data.size());
    queue_.pop_front();
    return size;
}

std::string Receiver::status() const {
    std::ostringstream s;
    if (host_known_) {
        const uint32_t ip = host_ip_;
        s << "PC: " << (ip >> 24) << '.' << ((ip >> 16) & 255) << '.' << ((ip >> 8) & 255) << '.' << (ip & 255);
    } else {
        s << "PC: procurando na rede...";
    }
    s << "\npacotes: " << packets_ << "  quadros recebidos: " << frames_ok_ << "  perdidos: " << frames_lost_
      << "  descartados: " << frames_dropped_ << "  pedidos de keyframe: " << idr_requests_;
    return s.str();
}

}  // namespace tcltv
