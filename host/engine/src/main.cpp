// tclstream-host — proof-of-life streamer.
// Captures the desktop, encodes H.264 with NVENC and streams it over UDP to the first receiver
// that says Hello. No pairing/encryption yet: LAN testing only (see docs/ARCHITECTURE.md §9).
#include <windows.h>
#include <avrt.h>
#include <timeapi.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <iostream>
#include <chrono>
#include <cstdlib>
#include <optional>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "audio_capture.h"
#include "capture.h"
#include "console_mode.h"
#include "converter.h"
#include "gamepad.h"
#include "log.h"
#include "nvenc_encoder.h"
#include "test_pattern.h"
#include "tclproto/framing.h"
#include "tclproto/protocol.h"
#include "tclproto/udp.h"

using namespace std::chrono;
using tclproto::PacketType;

namespace {

std::atomic<bool> g_quit{false};
HANDLE g_cleanup_done = CreateEventW(nullptr, TRUE, FALSE, nullptr);

std::string utf8(const std::wstring& w) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), int(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(size_t(n), ' ');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), int(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

BOOL WINAPI on_console_ctrl(DWORD type) {
    g_quit = true;
    // Closing the window (or logoff/shutdown) terminates the process as soon as this returns;
    // wait for main() to clean up first (give the PC its sound back, unplug virtual pads).
    if (type == CTRL_CLOSE_EVENT || type == CTRL_LOGOFF_EVENT || type == CTRL_SHUTDOWN_EVENT)
        WaitForSingleObject(g_cleanup_done, 4000);
    return TRUE;
}

// Sleeps until `t` with ~0.5 ms precision (Sleep() alone can overshoot by a full scheduler tick).
void sleep_until_precise(steady_clock::time_point t) {
    static HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
                                                 TIMER_ALL_ACCESS);
    const auto remaining = t - steady_clock::now();
    if (remaining <= steady_clock::duration::zero()) return;
    if (!timer) {
        std::this_thread::sleep_until(t);
        return;
    }
    LARGE_INTEGER due;
    due.QuadPart = -duration_cast<nanoseconds>(remaining).count() / 100;  // relative, 100 ns units
    SetWaitableTimerEx(timer, &due, 0, nullptr, nullptr, nullptr, 0);
    WaitForSingleObject(timer, INFINITE);
}

uint64_t now_us() { return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count(); }

struct Options {
    uint16_t port = tclproto::kDefaultMediaPort;
    int output = 0;
    bool test_pattern = false;
    bool stress = false;
    bool audio = true;
    bool mute_pc = false;
    bool console = false;  // Big Picture + 60 Hz + mute while a TV is connected
    bool stdin_control = false;  // "quit" on stdin (or stdin closing) stops the engine cleanly
    std::string codec = "auto";  // auto = HEVC when the TV decodes it in hardware, else H.264
    tcl::EncoderConfig enc;
};

bool parse_args(int argc, char** argv, Options& o) {
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> const char* { return i + 1 < argc ? argv[++i] : nullptr; };
        const char* v = nullptr;
        if (a == "--port" && (v = next())) o.port = uint16_t(std::atoi(v));
        else if (a == "--output" && (v = next())) o.output = std::atoi(v);
        else if (a == "--test-pattern") o.test_pattern = true;
        else if (a == "--stress") o.test_pattern = o.stress = true;
        else if (a == "--no-audio") o.audio = false;
        else if (a == "--mute-pc") o.mute_pc = true;
        else if (a == "--console") o.console = true;
        else if (a == "--events") tcl::events_enabled() = true;
        else if (a == "--stdin-control") o.stdin_control = true;
        else if (a == "--codec" && (v = next())) o.codec = v;
        else if (a == "--bitrate" && (v = next())) o.enc.bitrate_kbps = uint32_t(std::atoi(v));
        else if (a == "--fps" && (v = next())) o.enc.fps = uint32_t(std::atoi(v));
        else if (a == "--size" && (v = next())) {
            std::string size = v;
            const auto x = size.find('x');
            if (x == std::string::npos) return false;
            o.enc.width = uint32_t(std::atoi(size.substr(0, x).c_str()));
            o.enc.height = uint32_t(std::atoi(size.substr(x + 1).c_str()));
        } else {
            std::fprintf(stderr,
                         "usage: jinky-engine [--port 47100] [--output 0] [--bitrate 40000]\n"
                         "                      [--fps 60] [--size 1920x1080] [--test-pattern] [--stress] [--no-audio] [--mute-pc] [--console]\n"
                         "                      [--codec auto|hevc|h264]\n");
            return false;
        }
    }
    return o.enc.fps > 0 && o.enc.bitrate_kbps > 0 && o.enc.width % 2 == 0 && o.enc.height % 2 == 0;
}

}  // namespace

int main(int argc, char** argv) {
    Options opt;
    if (!parse_args(argc, argv, opt)) return 2;
    // If a previous run was killed while the PC was muted for streaming, give the sound back.
    if (tcl::ConsoleMode::restore_if_left())
        TCL_INFO("console", "display and sound restored (a previous run ended in console mode)");
    if (tcl::AudioCapture::restore_if_left_muted()) TCL_INFO("audio", "PC sound restored (left muted by a previous run)");
    SetConsoleCtrlHandler(on_console_ctrl, TRUE);
    if (opt.stdin_control) {
        // The desktop app runs the engine without a console; it stops it by writing "quit" or by
        // closing the pipe, so cleanup (display, sound, virtual pads) always runs.
        std::thread([] {
            std::string line;
            while (std::getline(std::cin, line) && line != "quit") {}
            g_quit = true;
        }).detach();
    }
    timeBeginPeriod(1);
    // Real-time-ish scheduling: a frame that waits for the CPU is a frame the TV shows late.
    SetPriorityClass(GetCurrentProcess(), ABOVE_NORMAL_PRIORITY_CLASS);
    DWORD mmcss_task = 0;
    HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Capture", &mmcss_task);
    if (!mmcss) TCL_WARN("main", "MMCSS registration failed ({})", GetLastError());

    // --- Milestone 1: capture ---
    tcl::DesktopCapture capture;
    if (!capture.init(opt.output)) {
        TCL_ERROR("capture", "init failed: {}", capture.error());
        return 1;
    }
    TCL_INFO("capture", "desktop {}x{} on GPU \"{}\"", capture.width(), capture.height(),
             utf8(capture.adapter_name()));
    {
        Microsoft::WRL::ComPtr<IDXGIDevice> dxgi;
        if (SUCCEEDED(capture.device()->QueryInterface(IID_PPV_ARGS(&dxgi))) && FAILED(dxgi->SetGPUThreadPriority(7)))
            TCL_WARN("main", "could not raise GPU priority (needs admin); continuing at normal priority");
    }

    tcl::FrameCompositor compositor;  // desktop + mouse cursor
    tcl::Nv12Converter converter;
    if (!converter.init(capture.device(), capture.context(), capture.width(), capture.height(), opt.enc.width,
                        opt.enc.height)) {
        TCL_ERROR("convert", "init failed: {}", converter.error());
        return 1;
    }

    // --- Milestone 2: encoder ---
    tcl::TestPattern pattern;
    uint64_t pattern_frame = 0;
    if (opt.test_pattern) {
        if (!pattern.init(capture.device(), capture.context(), capture.width(), capture.height())) {
            TCL_ERROR("pattern", "init failed");
            return 1;
        }
        TCL_INFO("pattern", "test pattern mode: synthetic animated frames instead of the desktop");
    }

    tcl::NvencEncoder encoder;
    if (!encoder.open(capture.device(), converter.output(), opt.enc)) {
        TCL_ERROR("encoder", "{}", encoder.error());
        return 1;
    }
    TCL_INFO("encoder", "active: {}", encoder.description());
    tcl::emit_event("ready", "\"gpu\":" + tcl::json_str(utf8(capture.adapter_name())) + ",\"desktop\":\"" +
                                 std::to_string(capture.width()) + "x" + std::to_string(capture.height()) + "\"");

    tclproto::UdpSocket sock;
    if (!sock.open(opt.port)) {
        TCL_ERROR("net", "UDP port {}: {}", opt.port, sock.error());
        return 1;
    }
    TCL_INFO("net", "waiting for a receiver on UDP port {} (Ctrl+C to stop)", opt.port);

    std::optional<tclproto::Endpoint> peer;
    bool busy_logged = false;
    uint32_t session_id = std::random_device{}();

    // The audio thread needs the current receiver: (ip << 16 | port), 0 = nobody connected.
    std::atomic<uint64_t> audio_peer{0};
    std::atomic<uint32_t> audio_session{0};
    tcl::ConsoleMode console;
    auto publish_peer = [&] {
        audio_session = session_id;
        audio_peer = peer ? (uint64_t(peer->ip) << 16 | peer->port) : 0;
        if (opt.console && !opt.test_pattern) {
            if (peer) console.enter(capture.display_name());
            else console.leave();
            return;
        }
        // Silence the PC's speakers while a TV is watching; give them back when it leaves.
        if (opt.mute_pc && opt.audio && !opt.test_pattern) {
            if (!tcl::AudioCapture::set_pc_muted(peer.has_value()))
                TCL_WARN("audio", "could not change PC mute state");
            else
                TCL_INFO("audio", "PC speakers {}", peer ? "muted (sound goes to the TV)" : "restored");
        }
    };
    tcl::AudioCapture audio;
    uint32_t audio_seq = 0;

    // Controllers: the TV's gamepads become virtual Xbox 360 pads; vibration goes back to the TV.
    tcl::GamepadHub gamepads;
    const bool gamepads_ok = gamepads.init([&](uint8_t pad, uint8_t large_motor, uint8_t small_motor) {
        const uint64_t p = audio_peer;
        if (!p) return;
        uint8_t msg[tclproto::RumbleMessage::kSize];
        tclproto::RumbleMessage{audio_session, pad, large_motor, small_motor}.write(msg);
        sock.send_to({uint32_t(p >> 16), uint16_t(p & 0xFFFF)}, msg);
    });
    tcl::emit_event("gamepads", std::string("\"available\":") + (gamepads_ok ? "true" : "false"));
    if (gamepads_ok) TCL_INFO("gamepad", "ViGEmBus ready: TV controllers will appear as Xbox 360 pads");
    else TCL_WARN("gamepad", "{}", gamepads.error());
    // In test-pattern mode the bar restarts every 180 frames; the beep marks that moment.
    const uint64_t pattern_epoch_us = now_us() + 500'000;
    if (opt.audio) {
        auto send_audio = [&](const int16_t* samples, uint64_t time_us) {
            const uint64_t p = audio_peer;
            if (!p) return;
            tclproto::MediaHeader h;
            h.type = PacketType::Audio;
            h.session_id = audio_session;
            h.frame_id = audio_seq++;
            h.capture_time_us = time_us;
            const tclproto::Endpoint to{uint32_t(p >> 16), uint16_t(p & 0xFFFF)};
            const auto* bytes = reinterpret_cast<const uint8_t*>(samples);
            tclproto::fragment_frame(h, {bytes, tcl::AudioCapture::kChunkFrames * tcl::AudioCapture::kChannels * 2},
                                     [&](std::span<const uint8_t> d) { sock.send_to(to, d); });
        };
        const bool ok = opt.test_pattern
                            ? audio.start_test_tone(send_audio, pattern_epoch_us, 180'000'000 / opt.enc.fps)
                            : audio.start(send_audio);
        if (ok) TCL_INFO("audio", "active: {}", audio.description());
        else TCL_WARN("audio", "disabled: {}", audio.error());
    }
    uint32_t frame_id = 0;
    bool idr_pending = true;
    uint64_t content_time_us = 0;  // when the content currently in the NV12 texture was presented on the PC
    bool have_content = false, dirty = false, first_frame_logged = false, first_sent_logged = false;
    steady_clock::time_point last_peer_msg, last_idr, last_encode, last_stats = steady_clock::now();
    steady_clock::time_point next_frame = steady_clock::now();
    const auto frame_interval = duration_cast<steady_clock::duration>(duration<double>(1.0 / opt.enc.fps));
    uint64_t frames_sent = 0, bytes_sent = 0, encode_us_total = 0, encode_us_max = 0;
    uint64_t last_send_us = 0, send_gap_max_us = 0, late_sends = 0;  // send-interval jitter
    std::vector<uint8_t> bitstream;
    uint8_t rx[2048];

    while (!g_quit) {
        // --- control messages from the receiver ---
        tclproto::Endpoint from;
        int n;
        while ((n = sock.recv_from(rx, from, 0)) > 0) {
            const std::span<const uint8_t> packet(rx, size_t(n));
            if (tclproto::peek_type(packet) == PacketType::Input) {
                if (auto in = tclproto::InputState::read(packet); in && peer && from == *peer) {
                    last_peer_msg = steady_clock::now();
                    gamepads.apply(*in);
                }
                continue;
            }
            if (tclproto::peek_type(packet) == PacketType::TimeRequest) {
                if (auto t = tclproto::TimeSync::read(packet)) {
                    t->type = PacketType::TimeResponse;
                    t->host_us = now_us();
                    uint8_t reply[tclproto::TimeSync::kSize];
                    t->write(reply);
                    sock.send_to(from, reply);
                }
                continue;
            }
            auto msg = tclproto::ControlMessage::read(std::span<const uint8_t>(rx, size_t(n)));
            if (!msg) continue;
            if (msg->type == PacketType::Hello) {
                if (peer && *peer != from && steady_clock::now() - last_peer_msg < 5s) {
                    // Another device while one is streaming: keep the current one. It takes over
                    // only after the current receiver leaves or goes silent.
                    if (!busy_logged) TCL_WARN("session", "{} wants to connect, but {} is streaming; ignoring",
                                               from.to_string(), peer->to_string());
                    busy_logged = true;
                    continue;
                }
                if (!peer || *peer != from) {
                    busy_logged = false;
                    TCL_INFO("session", "receiver connected: {}", from.to_string());
                    tcl::emit_event("connected", "\"tv\":" + tcl::json_str(from.to_string()));
                    // Pick the codec for this TV (Hello.value lists what it decodes in hardware).
                    const bool tv_hevc = msg->value & tclproto::kCodecHevc;
                    const auto want = (opt.codec == "hevc" || (opt.codec == "auto" && tv_hevc)) ? tcl::VideoCodec::HEVC
                                                                                                : tcl::VideoCodec::H264;
                    if (want != opt.enc.codec) {
                        opt.enc.codec = want;
                        encoder.close();
                        if (!encoder.open(capture.device(), converter.output(), opt.enc)) {
                            TCL_ERROR("encoder", "{}", encoder.error());
                            return 1;
                        }
                    }
                    TCL_INFO("encoder", "active: {}", encoder.description());
                    tcl::emit_event("codec", "\"codec\":" + tcl::json_str(opt.enc.codec == tcl::VideoCodec::HEVC ? "HEVC" : "H.264") +
                                                 ",\"bitrate_kbps\":" + std::to_string(opt.enc.bitrate_kbps));
                    peer = from;
                    session_id++;
                    idr_pending = true;
                    first_sent_logged = false;
                    publish_peer();
                }
                last_peer_msg = steady_clock::now();
            } else if (peer && from == *peer) {
                last_peer_msg = steady_clock::now();
                if (msg->type == PacketType::RequestIdr) {
                    if (!idr_pending) TCL_INFO("session", "receiver requested keyframe (after frame {})", msg->value);
                    idr_pending = true;
                } else if (msg->type == PacketType::Stats) {
                    TCL_INFO("receiver", "frames presented so far: {}", msg->value);
                } else if (msg->type == PacketType::LatencyReport) {
                    TCL_INFO("receiver", "capture-to-display latency: {:.1f} ms", msg->value / 1000.0);
                    tcl::emit_event("latency", std::format("\"ms\":{:.1f}", msg->value / 1000.0));
                } else if (msg->type == PacketType::Bye) {
                    TCL_INFO("session", "receiver left");
                    tcl::emit_event("disconnected", "\"reason\":\"left\"");
                    peer.reset();
                    publish_peer();
                    gamepads.unplug_all();
                }
            }
        }
        if (n < 0) TCL_WARN("net", "{}", sock.error());
        if (peer && steady_clock::now() - last_peer_msg > 5s) {
            TCL_WARN("session", "receiver {} silent for 5 s, dropping it", peer->to_string());
            tcl::emit_event("disconnected", "\"reason\":\"silent\"");
            peer.reset();
            publish_peer();
            gamepads.unplug_all();
        }
        // The TV repeats every pad's state every 50 ms; silence means the pad or app is gone.
        gamepads.expire(3s);

        // --- capture ---
        auto wait = duration_cast<milliseconds>(next_frame - steady_clock::now()).count();
        if (opt.test_pattern) {
            // Exactly one fresh synthetic frame per slot: exercises the full 60 fps path and latency.
            sleep_until_precise(next_frame);
            // Frame index from wall time, so the bar stays locked to the beep even if a slot is missed.
            const uint64_t t = now_us();
            pattern_frame = t > pattern_epoch_us ? (t - pattern_epoch_us) * opt.enc.fps / 1'000'000 : 0;
            pattern.draw(pattern_frame, opt.stress);
            if (converter.convert(pattern.texture())) {
                have_content = dirty = true;
                content_time_us = now_us();
            }
        }
        Microsoft::WRL::ComPtr<ID3D11Texture2D> desktop;
        switch (opt.test_pattern ? tcl::DesktopCapture::Result::Timeout : capture.acquire(int(std::clamp<long long>(wait, 0, 100)), desktop)) {
        case tcl::DesktopCapture::Result::NewFrame:
            // Keep a copy so the duplication frame can be released at once and the cursor can be
            // redrawn later when only the mouse moves.
            if (!compositor.set_desktop(capture.device(), capture.context(), desktop.Get())) {
                TCL_ERROR("capture", "could not copy desktop frame");
                capture.release();
                break;
            }
            capture.release();
            if (!converter.convert(compositor.compose(capture.context(), capture.cursor())))
                TCL_ERROR("convert", "{}", converter.error());
            else {
                have_content = dirty = true;
                content_time_us = capture.present_time_us();
            }
            if (!first_frame_logged) {
                TCL_INFO("capture", "first desktop frame captured");
                first_frame_logged = true;
            }
            break;
        case tcl::DesktopCapture::Result::CursorMoved:
            capture.release();
            if (compositor.has_desktop() && converter.convert(compositor.compose(capture.context(), capture.cursor()))) {
                have_content = dirty = true;
                content_time_us = now_us();
            }
            break;
        case tcl::DesktopCapture::Result::Timeout:
            break;
        case tcl::DesktopCapture::Result::AccessLost:
        case tcl::DesktopCapture::Result::Error:
            TCL_WARN("capture", "capture lost ({}), reopening", capture.error());
            while (!g_quit && !capture.reopen()) std::this_thread::sleep_for(500ms);
            if (capture.width() != converter.src_width() || capture.height() != converter.src_height()) {
                TCL_INFO("capture", "desktop resolution changed to {}x{}", capture.width(), capture.height());
                if (!converter.init(capture.device(), capture.context(), capture.width(), capture.height(),
                                    opt.enc.width, opt.enc.height)) {
                    TCL_ERROR("convert", "{}", converter.error());
                    return 1;
                }
            }
            idr_pending = true;
            continue;
        }

        // --- encode + send, paced to the target frame rate ---
        auto now = steady_clock::now();
        if (now < next_frame) continue;
        next_frame += frame_interval;
        if (next_frame < now) next_frame = now + frame_interval;  // fell behind; don't burst

        // Re-send a frame at least twice a second even on a static desktop, so the receiver can
        // tell "nothing changed" apart from "stream died".
        const bool heartbeat = now - last_encode > 500ms;
        if (!peer || !have_content || !(dirty || idr_pending || heartbeat)) continue;
        // Keyframes are big; limit how often a lossy link can make us send them.
        const bool send_idr = idr_pending && now - last_idr > 200ms;

        bool key = false;
        const bool fresh = dirty;
        const uint64_t t0 = now_us();
        if (!encoder.encode(send_idr, t0, bitstream, key)) {
            TCL_ERROR("encoder", "{}", encoder.error());
            continue;
        }
        const uint64_t encode_us = now_us() - t0;
        encode_us_total += encode_us;
        encode_us_max = std::max(encode_us_max, encode_us);
        last_encode = now;
        dirty = false;
        if (key) {
            idr_pending = false;
            last_idr = now;
        }

        tclproto::MediaHeader h;
        h.type = PacketType::Video;
        h.flags = uint8_t((key ? tclproto::kFlagKeyframe : 0) |
                          (opt.enc.codec == tcl::VideoCodec::HEVC ? tclproto::kFlagHevc : 0));
        h.session_id = session_id;
        h.frame_id = frame_id++;
        // 0 = repeated old content (heartbeat/keyframe), so the receiver must not count it as latency.
        h.capture_time_us = fresh ? content_time_us : 0;
        tclproto::fragment_frame(h, bitstream, [&](std::span<const uint8_t> d) {
            if (!sock.send_to(*peer, d)) TCL_WARN("net", "{}", sock.error());
        });
        frames_sent++;
        bytes_sent += bitstream.size();
        {
            const uint64_t t = now_us();
            if (last_send_us) {
                const uint64_t gap = t - last_send_us;
                send_gap_max_us = std::max(send_gap_max_us, gap);
                // More than 1.5 frame intervals between sends = the receiver will miss a refresh.
                if (gap * opt.enc.fps > 1'500'000) late_sends++;
            }
            last_send_us = t;
        }
        if (!first_sent_logged) {
            TCL_INFO("session", "first frame sent ({}, {} bytes)", key ? "keyframe" : "P-frame", bitstream.size());
            first_sent_logged = true;
        }

        if (now - last_stats >= 2s) {
            const double secs = duration<double>(now - last_stats).count();
            tcl::emit_event("stats", std::format("\"fps\":{:.1f},\"mbps\":{:.1f},\"encode_ms\":{:.2f}", frames_sent / secs,
                                                 bytes_sent * 8 / secs / 1e6,
                                                 frames_sent ? encode_us_total / 1000.0 / frames_sent : 0.0));
            TCL_INFO("stats", "{:.1f} fps sent, {:.1f} Mbit/s, encode avg {:.2f} max {:.2f} ms, send gap max {:.1f} ms, late sends {}",
                     frames_sent / secs, bytes_sent * 8 / secs / 1e6,
                     frames_sent ? encode_us_total / 1000.0 / frames_sent : 0.0, encode_us_max / 1000.0,
                     send_gap_max_us / 1000.0, late_sends);
            frames_sent = bytes_sent = encode_us_total = encode_us_max = send_gap_max_us = late_sends = 0;
            if (opt.audio && peer) {
                const double db = audio.take_peak_dbfs();
                if (std::isinf(db)) TCL_INFO("stats", "audio peak: silence");
                else TCL_INFO("stats", "audio peak: {:.1f} dBFS", db);
            }
            last_stats = now;
        }
    }

    TCL_INFO("main", "stopping");
    tcl::emit_event("stopping");
    audio.stop();
    console.leave();
    if (opt.mute_pc) tcl::AudioCapture::set_pc_muted(false);  // never leave the PC muted on exit
    gamepads.unplug_all();
    SetEvent(g_cleanup_done);
    if (mmcss) AvRevertMmThreadCharacteristics(mmcss);
    timeEndPeriod(1);
    return 0;
}
