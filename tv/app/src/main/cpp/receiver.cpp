#include "receiver.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iterator>
#include <utility>
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
    // Controller input and resend requests ride the Wi-Fi's highest-priority queue to the PC.
    if (!sock_.set_tos(0xB8)) LOGW("could not set the packet priority (IP_TOS)");
    running_ = true;
    state_ = kActive;
    session_wanted_ = false;  // home screen until the user picks "Jogar" or "Área de trabalho"
    thread_ = std::thread(&Receiver::net_loop, this);
    return true;
}

void Receiver::stop() {
    if (!running_.exchange(false)) return;
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
    sock_.close();
}

void Receiver::connect(uint32_t ip, uint16_t port, bool desktop, bool game, bool pair_only, uint32_t power) {
    connect_power_ = power & (tclproto::kHelloPowerSleep | tclproto::kHelloPowerOff);
    connect_desktop_ = desktop;
    connect_game_ = game;
    connect_pair_only_ = pair_only;
    connect_port_ = port;
    connect_ip_ = ip;
}

void Receiver::close_game() {
    // From the UI thread: address the PC like send_input does (host_ belongs to the net thread).
    if (!host_known_ || !session_wanted_) return;
    uint8_t buf[tclproto::ControlMessage::kSize];
    tclproto::ControlMessage{PacketType::CloseGame, session_id_, 0}.write(buf);
    for (int i = 0; i < 2; ++i) sock_.send_to(tclproto::Endpoint{host_ip_, host_port_}, buf);
}

void Receiver::set_mouse_mode(bool on) {
    if (!host_known_ || !session_wanted_) return;
    uint8_t buf[tclproto::ControlMessage::kSize];
    tclproto::ControlMessage{PacketType::MouseMode, session_id_, on ? 1u : 0u}.write(buf);
    for (int i = 0; i < 3; ++i) sock_.send_to(tclproto::Endpoint{host_ip_, host_port_}, buf);  // UDP: say it thrice
}

void Receiver::screenshot() {
    if (!host_known_ || !session_wanted_) return;
    uint8_t buf[tclproto::ControlMessage::kSize];
    tclproto::ControlMessage{PacketType::Screenshot, session_id_, 0}.write(buf);
    for (int i = 0; i < 3; ++i) sock_.send_to(tclproto::Endpoint{host_ip_, host_port_}, buf);  // the PC takes one per burst
}

void Receiver::type_text(const std::string& text, uint8_t flags) {
    if (!host_known_ || !session_wanted_) return;
    uint8_t buf[tclproto::TextMessage::kHeader + tclproto::TextMessage::kMaxText];
    const size_t n = tclproto::TextMessage{session_id_, ++text_seq_, flags, text}.write(buf);
    // UDP: thrice; the PC types each seq once.
    for (int i = 0; i < 3; ++i) sock_.send_to(tclproto::Endpoint{host_ip_, host_port_}, std::span<const uint8_t>(buf, n));
}

void Receiver::disconnect() { disconnect_requested_ = true; }

std::string Receiver::hosts() const {
    std::lock_guard lock(hosts_mu_);
    std::string out;
    const int64_t now = now_ms();
    char buf[64];
    for (const auto& [id, h] : hosts_) {
        std::snprintf(buf, sizeof buf, "%016llx|%u.%u.%u.%u|%d|", (unsigned long long)id, h.ip >> 24, (h.ip >> 16) & 255,
                      (h.ip >> 8) & 255, h.ip & 255, int(h.info.state));
        out += buf;
        for (uint8_t b : h.info.mac) {
            std::snprintf(buf, sizeof buf, "%02x", b);
            out += buf;
        }
        out += "|" + std::to_string(now - h.seen_ms) + "|" + h.info.name + "|" + h.info.busy_with + "|" +
               std::to_string(h.info.tv_version_code) + "|" + std::to_string(h.info.update_port) + "|" +
               std::to_string(h.port) + "|" + std::to_string(int(h.info.flags)) + "\n";
    }
    return out;
}

void Receiver::on_host_info(const tclproto::HostInfo& info, const tclproto::Endpoint& from) {
    std::lock_guard lock(hosts_mu_);
    auto& h = hosts_[info.host_id];
    h.info = info;
    h.ip = from.ip;
    h.port = from.port;
    h.seen_ms = now_ms();
}

void Receiver::apply_connect_requests() {
    if (disconnect_requested_.exchange(false)) {
        if (host_ && session_wanted_) send_control(PacketType::Bye, 0);
        session_wanted_ = false;
        host_silent_ = false;
        host_.reset();
        host_known_ = false;
        have_session_ = false;
        state_ = kActive;
        takeover_until_ms_ = 0;
        clock_synced_ = false;
        sample_count_ = sample_next_ = 0;
        std::lock_guard lock(mu_);
        queue_.clear();
    }
    if (const uint32_t ip = connect_ip_.exchange(0)) {
        if (const uint16_t port = connect_port_.exchange(0)) host_port_ = port;
        host_ = tclproto::Endpoint{ip, host_port_};
        host_known_ = true;
        host_ip_ = ip;
        host_silent_ = false;
        last_packet_ms_ = now_ms();
        desktop_ = connect_desktop_.load();
        game_ = connect_game_.load();
        pair_only_ = connect_pair_only_.load();
        power_ = connect_power_.load();
        game_ready_ = -1;
        end_reason_ = 0;
        session_wanted_ = true;
        state_ = kActive;
        takeover_until_ms_ = now_ms() + 4000;  // the user chose to play here: take it from any other TV
        std::lock_guard lock(auth_mu_);
        auth_required_ = false;  // the PC will challenge us again
    }
}

void Receiver::send_hello(uint32_t flags) {
    std::lock_guard lock(auth_mu_);
    if (auth_required_ && host_) {
        if (auto it = keys_.find(host_id_); it != keys_.end()) {
            uint8_t buf[tclproto::AuthHello::kSize];
            tclproto::AuthHello{device_id_, nonce_, flags}.write(buf, it->second);
            sock_.send_to(*host_, buf);
            return;
        }
    }
    // Discovery (broadcast until a PC answers) or a PC running without pairing.
    uint8_t buf[tclproto::ControlMessage::kSize];
    tclproto::ControlMessage{PacketType::Hello, session_id_, flags}.write(buf);
    sock_.send_to(host_ ? *host_ : tclproto::Endpoint::broadcast(host_port_), buf);
}

bool Receiver::handle_auth(std::span<const uint8_t> packet, const tclproto::Endpoint& from) {
    const auto type = tclproto::peek_type(packet);
    if (type == PacketType::AuthRequired) {
        auto ar = tclproto::AuthRequired::read(packet);
        // While our PC is quiet, only it may answer from a new address (not another PC in the house).
        const bool moved = host_silent_ && ar && ar->host_id == host_id_;
        if (!ar || (host_ && from != *host_ && state_ == kActive && host_known_ && !moved)) return true;
        std::lock_guard lock(auth_mu_);
        host_ = from;
        host_known_ = true;
        host_ip_ = from.ip;
        last_packet_ms_ = now_ms();
        host_id_ = ar->host_id;
        nonce_ = ar->nonce;
        auth_required_ = true;
        if (!keys_.count(host_id_) && state_ == kActive) {
            LOGI("PC %016llx needs pairing", (unsigned long long)host_id_);
            state_ = kNeedPairing;
        }
        return true;
    }
    if (type == PacketType::PairChallenge) {
        auto ch = tclproto::PairChallenge::read(packet);
        std::lock_guard lock(auth_mu_);
        if (ch && ch->device_id == device_id_ && state_ == kNeedPairing && !challenge_pc_) {
            challenge_pc_ = ch->pc_public;
            host_id_ = ch->host_id;
        }
        return true;
    }
    if (type == PacketType::PairResult) {
        auto r = tclproto::PairResult::read(packet);
        if (!r || r->device_id != device_id_) return true;
        std::lock_guard lock(auth_mu_);
        if (r->accepted) {
            // Only trust an approval proven with the key both sides derived.
            if (state_ == kPairingCode && pending_key_ && r->proof == tclproto::accept_proof(*pending_key_, device_id_)) {
                keys_[r->host_id] = *pending_key_;
                new_pairing_ = std::make_pair(r->host_id, *pending_key_);
                pending_key_.reset();
                challenge_pc_.reset();
                LOGI("paired with PC %016llx", (unsigned long long)r->host_id);
                state_ = kActive;
                takeover_until_ms_ = now_ms() + 4000;  // the user is here to play
            }
        } else if (state_ == kPairingCode || (state_ == kNeedPairing && !pair_request_.empty())) {
            // A refusal of *our* pairing request. (Duplicates of a "you're no longer paired"
            // notice arrive while we are merely waiting to pair, and must not count as a refusal.)
            state_ = kPairingRejected;
        } else if (keys_.erase(r->host_id)) {
            // The PC removed us: drop the stale key and pair again.
            LOGW("PC %016llx no longer knows this TV; pairing again", (unsigned long long)r->host_id);
            forgotten_host_ = r->host_id;
            state_ = kNeedPairing;
        }
        return true;
    }
    return false;
}

void Receiver::set_identity(uint64_t device_id) {
    std::lock_guard lock(auth_mu_);
    device_id_ = device_id;
}

void Receiver::add_known_address(const std::string& ip) {
    auto e = tclproto::Endpoint::parse(ip, 0);
    if (!e) return;
    std::lock_guard lock(hosts_mu_);
    if (std::find(known_ips_.begin(), known_ips_.end(), e->ip) == known_ips_.end()) known_ips_.push_back(e->ip);
}

void Receiver::add_paired_host(uint64_t host_id, const tclproto::PairKey& key) {
    std::lock_guard lock(auth_mu_);
    keys_[host_id] = key;
}

void Receiver::start_pairing(const tclproto::PublicKey& tv_public) {
    std::string name;
    {
        std::lock_guard lock(name_mu_);
        name = device_name_;
    }
    std::lock_guard lock(auth_mu_);
    pair_request_.resize(tclproto::PairRequest::kHeader + tclproto::PairRequest::kMaxName);
    pair_request_.resize(tclproto::PairRequest{device_id_, tv_public, name}.write(pair_request_));
    last_pair_request_ms_ = 0;  // send now
    challenge_pc_.reset();
    pending_key_.reset();
}

std::optional<tclproto::PublicKey> Receiver::pairing_challenge() {
    std::lock_guard lock(auth_mu_);
    return challenge_pc_;
}

std::string Receiver::set_pair_secret(std::span<const uint8_t> secret, const tclproto::PublicKey& tv_public,
                                      const tclproto::PublicKey& pc_public) {
    std::lock_guard lock(auth_mu_);
    pending_key_ = tclproto::derive_pair_key(secret, tv_public, pc_public);
    pair_request_.clear();
    state_ = kPairingCode;
    return tclproto::pairing_code(*pending_key_);
}

std::optional<std::pair<uint64_t, tclproto::PairKey>> Receiver::take_new_pairing() {
    std::lock_guard lock(auth_mu_);
    auto r = new_pairing_;
    new_pairing_.reset();
    return r;
}

uint64_t Receiver::take_forgotten_host() {
    std::lock_guard lock(auth_mu_);
    return std::exchange(forgotten_host_, 0);
}

void Receiver::retry_pairing() {
    std::lock_guard lock(auth_mu_);
    pair_request_.clear();
    challenge_pc_.reset();
    pending_key_.reset();
    state_ = kNeedPairing;
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

void Receiver::send_nacks(int64_t now_us) {
    // Resend timing follows the measured round trip: ask again after about two of them.
    tclproto::FrameAssembler::Timing t;
    const int64_t rtt = clock_synced_ ? rtt_us_.load() : 10'000;
    t.retry_us = std::clamp<int64_t>(rtt * 3 / 2 + 2'000, 6'000, 40'000);
    t.give_up_us = std::clamp<int64_t>(t.reorder_us + t.retry_us * t.max_tries + rtt, 60'000, 150'000);
    assembler_.set_timing(t);
    const auto nacks = assembler_.poll(now_us);
    packets_resent_asked_ = assembler_.stats().packets_missing;
    frames_recovered_ = assembler_.stats().frames_recovered;
    if (nacks.empty() || !host_) return;
    uint8_t buf[tclproto::NackMessage::kMaxSize];
    for (const auto& n : nacks) {
        const size_t len = tclproto::NackMessage{session_id_, n.frame_id, n.ranges}.write(buf);
        sock_.send_to(*host_, std::span<const uint8_t>(buf, len));
    }
}

void Receiver::send_net_report() {
    // Once a second: what fraction of the video had to be asked for again, and frames lost for
    // good. The PC lowers or raises the picture quality from this.
    const auto& st = assembler_.stats();
    const uint64_t packets = st.packets_received - report_packets_;
    const uint64_t missing = st.packets_missing - report_missing_;
    const uint64_t lost = st.frames_lost - report_lost_;
    report_packets_ = st.packets_received;
    report_missing_ = st.packets_missing;
    report_lost_ = st.frames_lost;
    video_kbps_ = uint32_t((video_bytes_ - report_bytes_) * 8 / 1000);
    report_bytes_ = video_bytes_;
    tclproto::NetReport r;
    r.packets = uint32_t(packets);
    r.lost_permille = packets ? uint32_t(std::min<uint64_t>(1000, missing * 1000 / (packets + missing))) : 0;
    r.frames_unrecovered = uint32_t(lost);
    send_control(PacketType::NetReport, r.pack());
}

void Receiver::net_loop() {
    uint8_t buf[2048];
    int64_t last_hello = 0, last_idr_req = 0, last_stats = 0, last_sync = 0;

    while (running_) {
        apply_connect_requests();
        const int64_t now = now_ms();
        if (!session_wanted_) {
            // Home screen: ask who is out there; no session, nothing changes on any PC. Every 2 s
            // once a PC answers, every 0.5 s while none does. Broadcast finds new PCs; known ones
            // are also asked directly, since Wi-Fi often delays or drops broadcasts.
            std::vector<uint32_t> direct;
            bool answered = false;
            {
                std::lock_guard lock(hosts_mu_);
                direct = known_ips_;
                for (const auto& [id, h] : hosts_) {
                    if (now - h.seen_ms < 3000) answered = true;
                    if (std::find(direct.begin(), direct.end(), h.ip) == direct.end()) direct.push_back(h.ip);
                }
            }
            if (now - last_discover_ms_ >= (answered ? 2000 : 500)) {
                uint8_t d[tclproto::ControlMessage::kSize];
                // Our device id rides along, so each PC can say whether it still has us paired.
                tclproto::ControlMessage{PacketType::Discover, uint32_t(device_id_ >> 32), uint32_t(device_id_)}.write(d);
                // Every Jinky a PC may run (one per Windows user / ASTER workplace) has its own port.
                for (int k = 0; k < tclproto::kMaxHostsPerPc; ++k) {
                    const uint16_t port = uint16_t(tclproto::kDefaultMediaPort + k * tclproto::kMediaPortStep);
                    sock_.send_to(tclproto::Endpoint::broadcast(port), d);
                    for (uint32_t ip : direct) sock_.send_to(tclproto::Endpoint{ip, port}, d);
                }
                last_discover_ms_ = now;
            }
            tclproto::Endpoint from;
            const int n = sock_.recv_from(buf, from, 50);
            if (n > 0) {
                if (auto hi = tclproto::HostInfo::read(std::span<const uint8_t>(buf, size_t(n)))) on_host_info(*hi, from);
            }
            continue;
        }
        // Hello doubles as discovery (broadcast until the host answers) and keepalive.
        // A displaced TV stays silent until the user presses OK on it: otherwise, when the other TV
        // stops playing, this one would silently grab the session (console mode for an empty room).
        if (state_ == kActive && host_silent_ && now - last_broadcast_hello_ms_ >= 2000) {
            // The PC went quiet: keep calling it directly (below), and broadcast too in case its
            // address changed.
            uint8_t b[tclproto::ControlMessage::kSize];
            tclproto::ControlMessage{PacketType::Hello, session_id_,
                                     codec_support_ | (desktop_ ? tclproto::kHelloDesktop : 0u) |
                                         (game_ ? tclproto::kHelloGame : 0u)}
                .write(b);
            sock_.send_to(tclproto::Endpoint::broadcast(host_port_), b);
            last_broadcast_hello_ms_ = now;
        }
        if (state_ == kActive && (now - last_hello >= 1000 || (takeover_until_ms_ > now && now - last_hello >= 250))) {
            const uint32_t takeover = now < takeover_until_ms_ ? tclproto::kHelloTakeover : 0;
            send_hello(codec_support_ | takeover | (desktop_ ? tclproto::kHelloDesktop : 0u) |
                       (game_ ? tclproto::kHelloGame : 0u) | (pair_only_ ? tclproto::kHelloPairOnly | power_.load() : 0u));
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
            // Pairing, displaced or ended: no stats, sync or keyframe requests.
            if (state_ == kNeedPairing) {
                std::lock_guard lock(auth_mu_);
                if (!pair_request_.empty() && host_ && now - last_pair_request_ms_ >= 1000) {
                    sock_.send_to(*host_, pair_request_);  // repeated until the PC's challenge arrives
                    last_pair_request_ms_ = now;
                }
            }
            tclproto::Endpoint from;
            uint8_t b[256];
            const int n = sock_.recv_from(b, from, 20);
            if (n > 0) {
                if (handle_auth(std::span<const uint8_t>(b, size_t(n)), from)) continue;
                auto m = tclproto::ControlMessage::read(std::span<const uint8_t>(b, size_t(n)));
                if (m && m->type == PacketType::SessionEnd) {
                    end_reason_ = int(m->value);
                    state_ = kEnded;
                }
                if (m) last_packet_ms_ = now_ms();  // the host is alive; don't forget it
            }
            continue;
        }
        if (host_ && now - last_stats >= 1000) {
            send_control(PacketType::Stats, uint32_t(presented_.load()));
            if (latency_us_) send_control(PacketType::LatencyReport, latency_us_);
            if (!host_silent_) send_net_report();
            last_stats = now;
        }
        send_nacks(now_us());
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
        // Host gone quiet (Wi-Fi dropped, PC restarted): keep calling it, and broadcast as well.
        if (host_ && !host_silent_ && now - last_packet_ms_ > 5000) {
            LOGW("host %s silent for 5 s; calling it again", host_->to_string().c_str());
            host_silent_ = true;
            last_broadcast_hello_ms_ = 0;
            clock_synced_ = false;
            sample_count_ = sample_next_ = 0;
        }

        tclproto::Endpoint from;
        // Short waits while a frame has holes, so resend requests go out on time.
        const int n = sock_.recv_from(buf, from, assembler_.has_gaps() ? 2 : 20);
        if (n <= 0) continue;
        if (host_silent_ && host_ && from.ip == host_->ip) {
            LOGI("host %s is back", host_->to_string().c_str());
            host_silent_ = false;
        }
        const std::span<const uint8_t> packet(buf, size_t(n));
        if (handle_auth(packet, from)) continue;
        if (tclproto::peek_type(packet) == PacketType::Rumble) {
            if (auto r = tclproto::RumbleMessage::read(packet); r && host_ && from == *host_) {
                std::lock_guard lock(rumble_mu_);
                if (rumble_.size() < 32) rumble_.push_back(*r);
            }
            continue;
        }
        if (tclproto::peek_type(packet) == PacketType::GameReady) {
            if (auto m = tclproto::ControlMessage::read(packet); m && host_ && from == *host_ && game_ready_ != int(m->value)) {
                game_ready_ = int(m->value);
                LOGI("%s", m->value == tclproto::kGameNeedsAttention ? "a prompt waits for the player" : "the game is on screen");
            }
            continue;
        }
        if (const auto t = tclproto::peek_type(packet); t == PacketType::Displaced || t == PacketType::SessionEnd) {
            if (*t == PacketType::SessionEnd) {
                if (auto m = tclproto::ControlMessage::read(packet)) end_reason_ = int(m->value);
            }
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
        if (host_ && from != *host_) {
            if (!host_silent_) continue;  // only one host per session
            // It went quiet and now speaks from another address (new IP after a Wi-Fi drop).
            LOGI("host moved to %s", from.to_string().c_str());
            host_ = from;
            host_ip_ = from.ip;
            host_silent_ = false;
        }

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
            report_packets_ = report_missing_ = report_lost_ = 0;
            {
                std::lock_guard lock(mu_);
                queue_.clear();
            }
            std::lock_guard lock(audio_mu_);
            audio_fill_ = 0;
            audio_priming_ = true;
            have_audio_seq_ = false;
            // Start every session with the normal audio cushion: one grown by an earlier session's
            // network hiccups would otherwise add delay after switching TVs.
            audio_target_ = kAudioStartTarget;
            audio_level_avg_ = double(kAudioStartTarget);
            audio_session_start_ms_ = now_ms();
        }
        if (h->type == PacketType::Audio) {
            on_audio(*h, std::span<const uint8_t>(buf + tclproto::MediaHeader::kSize,
                                                  size_t(n) - tclproto::MediaHeader::kSize));
            last_packet_ms_ = now_ms();
            continue;
        }
        packets_++;
        video_bytes_ += size_t(n);
        last_packet_ms_ = now_ms();
        assembler_.push(*h, std::span<const uint8_t>(buf + tclproto::MediaHeader::kSize,
                                                     size_t(n) - tclproto::MediaHeader::kSize),
                        now_us());
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
        // The first moments of a session don't count: the opening keyframe holds back the first
        // audio packets, and that one-off gap used to grow the cushion for the whole session.
        if (have_audio_seq_ && now - audio_session_start_ms_ > 2000) {
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
    // Tight band around the target: sitting near its top used to add ~15 ms of audio delay.
    if (audio_level_avg_ < audio_target_ * 0.85) consume = frames - 1;  // TV faster: stretch
    else if (audio_level_avg_ > audio_target_ * 1.15 && audio_fill_ > frames + 1) consume = frames + 1;  // shrink
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
    // Stamped on the PC's clock so the PC can measure the trip (TV -> Wi-Fi -> PC).
    if (clock_synced_) state.sent_us = uint32_t(now_us() + clock_offset_us_.load());
    uint8_t buf[tclproto::InputState::kSize];
    {
        // Signed with the pair key, so the PC accepts it as coming from this TV.
        std::lock_guard lock(auth_mu_);
        auto it = keys_.find(host_id_);
        if (it != keys_.end()) state.write(buf, it->second);
        else state.write(buf);
    }
    sock_.send_to(tclproto::Endpoint{ip, host_port_}, buf);
    inputs_sent_++;
}

void Receiver::send_extras(tclproto::PadExtras extras) {
    if (!host_known_) return;
    extras.session_id = session_id_;
    uint8_t buf[tclproto::PadExtras::kSize];
    extras.write(buf);
    sock_.send_to(tclproto::Endpoint{host_ip_, host_port_}, buf);
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
    s << "\nreenvio: pacotes pedidos de novo " << packets_resent_asked_ << "  quadros salvos " << frames_recovered_
      << "  vídeo " << video_kbps_ / 1000.0 << " Mbit/s";
    return s.str();
}

}  // namespace tcltv
