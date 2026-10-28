// Network side of the TV receiver: finds the host, receives and reassembles video frames,
// asks for keyframes when the stream breaks. Decoding happens in Kotlin (MediaCodec).
#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <map>

#include "tclproto/auth.h"
#include "tclproto/fec.h"
#include "tclproto/framing.h"
#include "tclproto/udp.h"

namespace tcltv {

class Receiver {
public:
    struct FrameMeta {
        uint32_t frame_id = 0;
        bool keyframe = false;
        bool hevc = false;
        uint64_t capture_time_us = 0;
    };

    ~Receiver();

    // Starts on the home screen: discovers PCs (Discover/HostInfo) without starting any session.
    bool start(uint16_t host_port);
    void stop();

    // Home screen actions. connect() starts a session with the PC at `ip` (host byte order):
    // console mode ("Jogar") or just its screen ("Área de trabalho"). disconnect() ends it and
    // returns to discovery.
    // `game`: a game chosen in the TV's library (console mode without Big Picture; the PC says
    // when the game is on screen and ends the session when it closes).
    // [port]: the PC's streaming port from its answer to discovery (0 = keep the current one).
    // [pair_only]: "Parear um PC" — authenticate (show the code) but start no session.
    // [power] (with pair_only): Hello flags kHelloPowerSleep / kHelloPowerOff ("Suspender/Desligar o PC").
    void connect(uint32_t ip, uint16_t port, bool desktop, bool game = false, bool pair_only = false, uint32_t power = 0);
    // -1 = not yet; else tclproto::GameReadyReason (on screen / a prompt waits for the player).
    int game_ready() const { return game_ready_; }
    int end_reason() const { return end_reason_; }
    void close_game();
    // Menu "Mouse": the first controller drives the PC's mouse (true) or the game again (false).
    void set_mouse_mode(bool on);
    // Menu "Capturar tela": the PC saves the picture being streamed.
    void screenshot();
    // Menu "Início": the PC freezes the game, to continue it later (like an Xbox's Quick Resume).
    void pause_game();
    // Menu "Teclado": types [text] on the PC (flags: tclproto::TextMessage::Flags).
    void type_text(const std::string& text, uint8_t flags);
    // A PlayStation pad's touchpad and motion ("Controle PlayStation no PC").
    void send_extras(tclproto::PadExtras extras);
    void disconnect();
    bool in_session() const { return session_wanted_; }
    // PCs seen recently, one per line: "hostId|ip|state|mac|ageMs|name|busyWith|tvVersion|updatePort".
    std::string hosts() const;

    // Blocks up to timeout_ms for the next decodable frame and copies it into dst.
    // Returns its size, 0 on timeout, or -(size) if dst is too small (frame dropped, keyframe requested).
    int read_frame(uint8_t* dst, size_t capacity, int timeout_ms, FrameMeta& meta);

    // Decoder lost sync (error, stall, recreation): next frames are useless until a keyframe.
    void request_keyframe();
    void report_presented(uint64_t count) { presented_ = count; }
    // Session state as seen by this TV.
    // kNeedPairing: the PC asked us to identify and we have no key for it; Kotlin starts pairing.
    // kPairingCode: the 6-digit code is on screen, waiting for the user to approve on the PC.
    enum State { kActive = 0, kDisplaced = 1, kEnded = 2, kNeedPairing = 3, kPairingCode = 4, kPairingRejected = 5 };
    int state() const { return state_; }
    // "Play here": take the session over from whichever TV has it (also automatic for a few
    // seconds after start, since opening Jinky means the user wants to play on this TV).
    void request_takeover();
    // --- pairing (crypto keys live in Kotlin storage; ECDH itself runs in Java) ---
    void set_identity(uint64_t device_id);
    void add_paired_host(uint64_t host_id, const tclproto::PairKey& key);
    // A PC address remembered from earlier runs: discovery also asks it directly (Wi-Fi routers
    // often delay or drop broadcasts; a direct question gets through).
    void add_known_address(const std::string& ip);
    // Starts (or restarts) pairing with the PC that challenged us: sends our public key.
    void start_pairing(const tclproto::PublicKey& tv_public);
    // The PC's public key once its PairChallenge arrived.
    std::optional<tclproto::PublicKey> pairing_challenge();
    // ECDH done in Java: derive the pair key, return the 6-digit code to show.
    std::string set_pair_secret(std::span<const uint8_t> secret, const tclproto::PublicKey& tv_public,
                                const tclproto::PublicKey& pc_public);
    // After the PC approved: the new (host id, key) to persist, once.
    std::optional<std::pair<uint64_t, tclproto::PairKey>> take_new_pairing();
    // A PC that no longer knows us (unpaired there): its id, once, so Kotlin deletes the stale key.
    uint64_t take_forgotten_host();
    void retry_pairing();

    // The TV's user-visible name, sent to the PC (shown in the Jinky PC app).
    void set_device_name(std::string name);

    // Codecs this device decodes in hardware (tclproto::CodecSupport), announced in every Hello.
    void set_codec_support(uint32_t mask) { codec_support_ = mask; }
    // Video actually arriving, in kbit/s (last second), for the menu.
    uint32_t video_kbps() const { return video_kbps_; }

    // Average capture-to-display latency measured by the decoder side, forwarded to the host log.
    void report_latency_us(uint32_t us) { latency_us_ = us; }

    // Host clock minus local clock (steady/monotonic, microseconds). Valid once clock_synced().
    int64_t clock_offset_us() const { return clock_offset_us_; }
    int64_t rtt_us() const { return rtt_us_; }
    bool clock_synced() const { return clock_synced_; }

    // Fills `frames` stereo frames of 48 kHz PCM. Always fills the whole buffer (silence on underrun),
    // so the caller can write it straight to the audio device, which then paces the calls.
    void read_audio(int16_t* dst, size_t frames);
    // Current buffered audio in milliseconds (for diagnostics).
    double audio_buffer_ms() const;
    int audio_target_ms() const;

    // Sends a gamepad state to the host (any thread).
    void send_input(tclproto::InputState state);
    // Next pending vibration request from the host, if any (any thread).
    std::optional<tclproto::RumbleMessage> poll_rumble();

    std::string status() const;
    // "a.b.c.d" of the PC streaming to us, or "" while still searching.
    std::string host_address() const;
    const std::string& error() const { return error_; }

private:
    void net_loop();
    void on_frame(tclproto::FrameAssembler::Frame&& f);
    void on_audio(const tclproto::MediaHeader& h, std::span<const uint8_t> payload);
    void send_control(tclproto::PacketType type, uint32_t value);
    void send_hello(uint32_t flags);
    void apply_connect_requests();
    void on_host_info(const tclproto::HostInfo& info, const tclproto::Endpoint& from);
    // Handles AuthRequired / PairChallenge / PairResult; true if the packet was one of them.
    bool handle_auth(std::span<const uint8_t> packet, const tclproto::Endpoint& from);
    void send_time_request();
    void send_nacks(int64_t now_us);
    void send_net_report();
    void on_time_response(const tclproto::TimeSync& t);

    static constexpr size_t kMaxQueuedFrames = 4;

    tclproto::UdpSocket sock_;
    std::atomic<uint16_t> host_port_{0};  // the session PC's port (several Jinkys may share an IP)
    std::thread thread_;
    std::atomic<bool> running_{false};

    // Network thread only
    tclproto::FrameAssembler assembler_{[this](tclproto::FrameAssembler::Frame&& f) { on_frame(std::move(f)); }};
    std::optional<tclproto::Endpoint> host_;
    std::atomic<uint32_t> session_id_{0};
    bool have_session_ = false;

    // Shared with the decoder thread
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::deque<tclproto::FrameAssembler::Frame> queue_;
    // Set when the decoder needs a keyframe: P-frames are dropped until one arrives.
    std::atomic<bool> await_keyframe_{false};
    std::atomic<uint64_t> presented_{0};
    std::atomic<uint32_t> codec_support_{tclproto::kCodecH264};
    std::atomic<int> state_{kActive};
    std::atomic<int64_t> takeover_until_ms_{0};

    // Home screen / session control (requests from the UI thread, applied by the network thread)
    std::atomic<bool> session_wanted_{false};
    std::atomic<bool> desktop_{false};
    std::atomic<uint32_t> connect_ip_{0};  // pending connect request (0 = none)
    std::atomic<uint16_t> connect_port_{0};
    std::atomic<bool> connect_desktop_{false};
    std::atomic<bool> connect_game_{false};
    std::atomic<bool> connect_pair_only_{false};
    std::atomic<bool> pair_only_{false};
    std::atomic<uint32_t> connect_power_{0};
    std::atomic<uint32_t> power_{0};
    std::atomic<uint32_t> text_seq_{0};
    std::atomic<bool> game_{false};
    std::atomic<int> game_ready_{-1};
    std::atomic<int> end_reason_{0};
    std::atomic<bool> disconnect_requested_{false};
    int64_t last_discover_ms_ = 0;
    struct KnownHost {
        tclproto::HostInfo info;
        uint32_t ip = 0;
        uint16_t port = 0;  // the port it answered from: its streaming port
        int64_t seen_ms = 0;
    };
    mutable std::mutex hosts_mu_;
    std::map<uint64_t, KnownHost> hosts_;
    std::vector<uint32_t> known_ips_;  // guarded by hosts_mu_
    std::mutex name_mu_;
    std::string device_name_;

    // Pairing / authentication (auth_mu_ guards everything below)
    mutable std::mutex auth_mu_;
    uint64_t device_id_ = 0;
    std::map<uint64_t, tclproto::PairKey> keys_;  // host id -> pair key
    uint64_t host_id_ = 0, nonce_ = 0;
    bool auth_required_ = false;  // the PC asked for signed hellos
    std::vector<uint8_t> pair_request_;
    int64_t last_pair_request_ms_ = 0;
    std::optional<tclproto::PublicKey> challenge_pc_;
    std::optional<tclproto::PairKey> pending_key_;
    std::optional<std::pair<uint64_t, tclproto::PairKey>> new_pairing_;
    uint64_t forgotten_host_ = 0;
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
    bool host_silent_ = false;  // no packet for 5 s (Wi-Fi dropped, PC restarted): keep calling it
    int64_t last_broadcast_hello_ms_ = 0;
    // Network report (net thread): counters at the previous report.
    uint64_t report_packets_ = 0, report_missing_ = 0, report_lost_ = 0, report_bytes_ = 0, report_rebuilt_ = 0;
    uint64_t video_bytes_ = 0;
    std::atomic<uint32_t> video_kbps_{0};
    std::atomic<uint64_t> packets_resent_asked_{0}, frames_recovered_{0};
    std::atomic<uint64_t> packets_rebuilt_{0}, frames_rebuilt_{0};  // by loss protection (parity)
    // Arrival jitter of completed frames (network thread only)
    int64_t last_frame_us_ = 0, gap_max_us_ = 0, last_jitter_log_ms_ = 0;
    uint64_t late_frames_ = 0, frames_in_window_ = 0;
    std::string error_;

    std::mutex rumble_mu_;
    std::deque<tclproto::RumbleMessage> rumble_;
    std::atomic<uint64_t> inputs_sent_{0};

    // Audio jitter buffer: interleaved stereo int16 ring, shared with the audio thread.
    static constexpr size_t kAudioRate = 48000;
    static constexpr size_t kAudioRingFrames = kAudioRate / 2;        // 500 ms capacity
    // Adaptive target: grows 10 ms on every underrun, shrinks 5 ms after 10 s without one.
    static constexpr size_t kAudioMinTarget = kAudioRate * 20 / 1000;
    static constexpr size_t kAudioMaxTarget = kAudioRate * 100 / 1000;
    static constexpr size_t kAudioStartTarget = kAudioRate * 30 / 1000;
    size_t audio_target_ = kAudioStartTarget;
    int64_t audio_last_underrun_ms_ = 0, audio_last_shrink_ms_ = 0;
    int64_t audio_session_start_ms_ = 0;
    size_t audio_max_frames() const { return audio_target_ + kAudioRate * 60 / 1000; }  // above: trim
    mutable std::mutex audio_mu_;
    std::vector<int16_t> audio_ring_ = std::vector<int16_t>(kAudioRingFrames * 2);
    size_t audio_read_ = 0, audio_fill_ = 0;  // in frames
    bool audio_priming_ = true;
    bool have_audio_seq_ = false;
    uint32_t last_audio_seq_ = 0;
    double audio_level_avg_ = kAudioStartTarget;  // smoothed fill level, for drift compensation
    std::atomic<uint64_t> audio_packets_{0}, audio_lost_{0}, audio_underruns_{0}, audio_trimmed_ms_{0},
        audio_adjustments_{0}, audio_rebuilt_{0};
    // Sound protection: the last groups of chunks (and their parity), to rebuild a lost chunk and
    // put it where silence stood in for it, if that part hasn't played yet.
    struct AudioGroup {
        bool used = false;
        uint32_t first = 0;
        std::vector<std::vector<uint8_t>> chunks = std::vector<std::vector<uint8_t>>(tclproto::kAudioGroup);
        std::vector<std::vector<uint8_t>> parity;
        int64_t silence_at[tclproto::kAudioGroup] = {-1, -1, -1, -1};  // frame where silence replaced it
    };
    std::array<AudioGroup, 8> audio_groups_;
    uint64_t audio_read_abs_ = 0;  // frames played (or dropped) so far: positions in the ring
    AudioGroup& audio_group(uint32_t first);
    void audio_overwrite(int64_t at, const std::vector<uint8_t>& chunk);
    void audio_recover(AudioGroup& g);
};

}  // namespace tcltv
