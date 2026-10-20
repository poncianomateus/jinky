#include "receiver.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iterator>
#include <sstream>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Jinky", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "Jinky", __VA_ARGS__)

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
    state_ = kActive;
    takeover_until_ms_ = now_ms() + 4000;
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
        // A displaced TV stays silent until the user presses OK on it: otherwise, when the other TV
        // stops playing, this one would silently grab the session (console mode for an empty room).
        if (state_ == kActive && (now - last_hello >= 1000 || (takeover_until_ms_ > now && now - last_hello >= 250))) {
            const uint32_t takeover = now < takeover_until_ms_ ? tclproto::kHelloTakeover : 0;
            send_control(PacketType::Hello, codec_support_ | takeover);
            last_hello = now;
            if (host_ && state_ == kActive) {
                std::string name;
                {
                    std::lock_guard lock(name_mu_);
                    name = device_name_;
                }
                if (!name.empty()) {
                    uint8_t nbuf[tclproto::NameMessage::kHeader + tclproto::NameMessage::kMaxName];
                    const size_t len = tclproto::NameMessage{session_id_, name}.write(nbuf);
                    sock_.send_to(*host_, std::span<const uint8_t>(nbuf, len));
                }
            }
        }
        if (state_ != kActive) {
            // Displaced or ended: no stats, sync or keyframe requests; just wait.
            tclproto::Endpoint from;
            uint8_t b[64];
            const int n = sock_.recv_from(b, from, 20);
            if (n > 0) {
                auto m = tclproto::ControlMessage::read(std::span<const uint8_t>(b, size_t(n)));
                if (m && m->type == PacketType::SessionEnd) state_ = kEnded;
                if (m) last_packet_ms_ = now_ms();  // the host is alive; don't forget it
            }
            continue;
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
        if (tclproto::peek_type(packet) == PacketType::Rumble) {
            if (auto r = tclproto::RumbleMessage::read(packet); r && host_ && from == *host_) {
                std::lock_guard lock(rumble_mu_);
                if (rumble_.size() < 32) rumble_.push_back(*r);
            }
            continue;
        }
        if (const auto t = tclproto::peek_type(packet); t == PacketType::Displaced || t == PacketType::SessionEnd) {
            if (!host_) {  // e.g. we broadcast while another TV is playing: remember the PC anyway
                host_ = from;
                host_known_ = true;
                host_ip_ = from.ip;
            }
            if (from == *host_) {
                LOGI("%s", *t == PacketType::Displaced ? "another TV took the session" : "the PC ended the session");
                state_ = *t == PacketType::Displaced ? kDisplaced : kEnded;
                takeover_until_ms_ = 0;
                last_packet_ms_ = now_ms();
                std::lock_guard lock(mu_);
                queue_.clear();
            }
            continue;
        }
        if (tclproto::peek_type(packet) == PacketType::TimeResponse) {
            if (auto t = tclproto::TimeSync::read(packet); t && host_ && from == *host_) on_time_response(*t);
            continue;
        }
        auto h = tclproto::MediaHeader::read(std::span<const uint8_t>(buf, size_t(n)));
        if (!h) continue;
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
            {
                std::lock_guard lock(mu_);
                queue_.clear();
            }
            std::lock_guard lock(audio_mu_);
            audio_fill_ = 0;
            audio_priming_ = true;
            have_audio_seq_ = false;
        }
        if (h->type == PacketType::Audio) {
            on_audio(*h, std::span<const uint8_t>(buf + tclproto::MediaHeader::kSize,
                                                  size_t(n) - tclproto::MediaHeader::kSize));
            last_packet_ms_ = now_ms();
            continue;
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
        const int64_t t = now_us();
        if (last_frame_us_) {
            const int64_t gap = t - last_frame_us_;
            gap_max_us_ = std::max(gap_max_us_, gap);
            if (gap > 25'000) late_frames_++;  // > 1.5 frames at 60 fps: a refresh will be missed
        }
        last_frame_us_ = t;
        frames_in_window_++;
        if (t / 1000 - last_jitter_log_ms_ >= 2000) {
            LOGI("arrival: %llu frames in window, %llu late (>25 ms gap), max gap %.1f ms",
                 (unsigned long long)frames_in_window_, (unsigned long long)late_frames_, gap_max_us_ / 1000.0);
            last_jitter_log_ms_ = t / 1000;
            gap_max_us_ = 0;
            late_frames_ = frames_in_window_ = 0;
        }
    }
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

void Receiver::on_audio(const tclproto::MediaHeader& h, std::span<const uint8_t> payload) {
    if (h.frag_count != 1 || payload.size() % 4 != 0) return;  // audio chunks are single datagrams
    audio_packets_++;
    std::lock_guard lock(audio_mu_);
    if (have_audio_seq_) {
        if (!tclproto::seq_newer(h.frame_id, last_audio_seq_)) return;  // late or duplicate: too late to play
        const uint32_t missing = h.frame_id - last_audio_seq_ - 1;
        if (missing > 0) {
            audio_lost_ += missing;
            // Keep the timeline for short gaps by inserting silence; long gaps just resume.
            const size_t silence = std::min<size_t>(missing, 4) * (payload.size() / 4);
            for (size_t i = 0; i < silence && audio_fill_ < kAudioRingFrames; ++i) {
                const size_t w = (audio_read_ + audio_fill_) % kAudioRingFrames;
                audio_ring_[w * 2] = audio_ring_[w * 2 + 1] = 0;
                audio_fill_++;
            }
        }
    }
    have_audio_seq_ = true;
    last_audio_seq_ = h.frame_id;

    const size_t frames = payload.size() / 4;
    const auto* src = payload.data();
    for (size_t i = 0; i < frames; ++i) {
        if (audio_fill_ >= kAudioRingFrames) break;  // reader stalled; drop the overflow
        const size_t w = (audio_read_ + audio_fill_) % kAudioRingFrames;
        audio_ring_[w * 2] = int16_t(src[i * 4] | (src[i * 4 + 1] << 8));
        audio_ring_[w * 2 + 1] = int16_t(src[i * 4 + 2] | (src[i * 4 + 3] << 8));
        audio_fill_++;
    }
    // Sender and TV audio clocks drift and bursts pile up: never let latency creep upward.
    if (audio_fill_ > audio_max_frames()) {
        const size_t trim = audio_fill_ - audio_target_;
        audio_read_ = (audio_read_ + trim) % kAudioRingFrames;
        audio_fill_ -= trim;
        audio_trimmed_ms_ += trim * 1000 / kAudioRate;
    }
}

void Receiver::read_audio(int16_t* dst, size_t frames) {
    std::lock_guard lock(audio_mu_);
    const int64_t now = now_ms();
    if (now - audio_last_underrun_ms_ > 10'000 && now - audio_last_shrink_ms_ > 10'000 &&
        audio_target_ > kAudioMinTarget) {
        audio_target_ -= kAudioRate * 5 / 1000;  // network has been calm: try a bit less latency
        audio_last_shrink_ms_ = now;
    }
    if (audio_priming_ && audio_fill_ >= audio_target_) audio_priming_ = false;
    if (!audio_priming_ && audio_fill_ < frames) {
        // Ran dry (network hiccup or host silent): play silence and rebuild the cushion.
        audio_priming_ = true;
        audio_level_avg_ = double(audio_target_);
        if (have_audio_seq_) {
            audio_underruns_++;
            audio_last_underrun_ms_ = now;
            audio_target_ = std::min(audio_target_ + kAudioRate * 10 / 1000, kAudioMaxTarget);
            LOGW("audio underrun (packets so far %llu); jitter target now %zu ms",
                 (unsigned long long)audio_packets_.load(), audio_target_ * 1000 / kAudioRate);
        }
    }
    if (audio_priming_) {
        std::fill(dst, dst + frames * 2, int16_t(0));
        return;
    }
    // Clock drift compensation: PC and TV audio clocks differ slightly, so the buffer slowly
    // drains or fills. Keep it at the target by consuming one frame more or less per chunk
    // (about 0.4%, inaudible), stretching the chunk with linear interpolation.
    audio_level_avg_ = audio_level_avg_ * 0.98 + double(audio_fill_) * 0.02;
    size_t consume = frames;
    if (audio_level_avg_ < audio_target_ * 0.7) consume = frames - 1;  // TV faster: stretch
    else if (audio_level_avg_ > audio_target_ * 1.4 && audio_fill_ > frames + 1) consume = frames + 1;  // shrink
    if (consume != frames) audio_adjustments_++;
    for (size_t i = 0; i < frames; ++i) {
        const double pos = double(i) * double(consume - 1) / double(frames - 1);
        const size_t i0 = size_t(pos);
        const size_t i1 = std::min(i0 + 1, consume - 1);
        const double f = pos - double(i0);
        const size_t r0 = (audio_read_ + i0) % kAudioRingFrames, r1 = (audio_read_ + i1) % kAudioRingFrames;
        for (int ch = 0; ch < 2; ++ch)
            dst[i * 2 + ch] = int16_t(audio_ring_[r0 * 2 + ch] * (1 - f) + audio_ring_[r1 * 2 + ch] * f);
    }
    audio_read_ = (audio_read_ + consume) % kAudioRingFrames;
    audio_fill_ -= consume;
}

int Receiver::audio_target_ms() const {
    std::lock_guard lock(audio_mu_);
    return int(audio_target_ * 1000 / kAudioRate);
}

double Receiver::audio_buffer_ms() const {
    std::lock_guard lock(audio_mu_);
    return audio_fill_ * 1000.0 / kAudioRate;
}

void Receiver::send_input(tclproto::InputState state) {
    if (!host_known_) return;
    const uint32_t ip = host_ip_;
    state.session_id = session_id_;
    uint8_t buf[tclproto::InputState::kSize];
    state.write(buf);
    sock_.send_to(tclproto::Endpoint{ip, host_port_}, buf);
    inputs_sent_++;
}

std::optional<tclproto::RumbleMessage> Receiver::poll_rumble() {
    std::lock_guard lock(rumble_mu_);
    if (rumble_.empty()) return std::nullopt;
    auto r = rumble_.front();
    rumble_.pop_front();
    return r;
}

void Receiver::request_takeover() {
    takeover_until_ms_ = now_ms() + 4000;
    state_ = kActive;
}

void Receiver::set_device_name(std::string name) {
    std::lock_guard lock(name_mu_);
    device_name_ = std::move(name);
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
    meta = {f.frame_id, f.keyframe, f.hevc, f.capture_time_us};
    const int size = int(f.data.size());
    queue_.pop_front();
    return size;
}

std::string Receiver::host_address() const {
    if (!host_known_) return {};
    const uint32_t ip = host_ip_;
    return std::to_string(ip >> 24) + '.' + std::to_string((ip >> 16) & 255) + '.' + std::to_string((ip >> 8) & 255) +
           '.' + std::to_string(ip & 255);
}

std::string Receiver::status() const {
    std::ostringstream s;
    if (host_known_) {
        const uint32_t ip = host_ip_;
        s << "PC: " << (ip >> 24) << '.' << ((ip >> 16) & 255) << '.' << ((ip >> 8) & 255) << '.' << (ip & 255);
    } else {
        s << "PC: procurando na rede...";
    }
    s << "\náudio: pacotes " << audio_packets_ << "  perdidos " << audio_lost_ << "  buffer "
      << int(audio_buffer_ms()) << " ms (alvo " << audio_target_ms() << ")  faltas " << audio_underruns_ << "  cortado " << audio_trimmed_ms_
      << " ms  ajustes de relógio " << audio_adjustments_;
    s << "\ncontrole: estados enviados " << inputs_sent_;
    s << "\npacotes: " << packets_ << "  quadros recebidos: " << frames_ok_ << "  perdidos: " << frames_lost_
      << "  descartados: " << frames_dropped_ << "  pedidos de keyframe: " << idr_requests_;
    return s.str();
}

}  // namespace tcltv
