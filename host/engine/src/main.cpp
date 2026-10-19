// tclstream-host — proof-of-life streamer.
// Captures the desktop, encodes H.264 with NVENC and streams it over UDP to the first receiver
// that says Hello. No pairing/encryption yet: LAN testing only (see docs/ARCHITECTURE.md §9).
#include <windows.h>
#include <timeapi.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <optional>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "capture.h"
#include "converter.h"
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

std::string utf8(const std::wstring& w) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), int(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(size_t(n), ' ');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), int(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

BOOL WINAPI on_console_ctrl(DWORD) {
    g_quit = true;
    return TRUE;
}

uint64_t now_us() { return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count(); }

struct Options {
    uint16_t port = tclproto::kDefaultMediaPort;
    int output = 0;
    bool test_pattern = false;
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
                         "usage: tclstream-host [--port 47100] [--output 0] [--bitrate 20000]\n"
                         "                      [--fps 60] [--size 1920x1080] [--test-pattern]\n");
            return false;
        }
    }
    return o.enc.fps > 0 && o.enc.bitrate_kbps > 0 && o.enc.width % 2 == 0 && o.enc.height % 2 == 0;
}

}  // namespace

int main(int argc, char** argv) {
    Options opt;
    if (!parse_args(argc, argv, opt)) return 2;
    SetConsoleCtrlHandler(on_console_ctrl, TRUE);
    timeBeginPeriod(1);

    // --- Milestone 1: capture ---
    tcl::DesktopCapture capture;
    if (!capture.init(opt.output)) {
        TCL_ERROR("capture", "init failed: {}", capture.error());
        return 1;
    }
    TCL_INFO("capture", "desktop {}x{} on GPU \"{}\"", capture.width(), capture.height(),
             utf8(capture.adapter_name()));

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

    tclproto::UdpSocket sock;
    if (!sock.open(opt.port)) {
        TCL_ERROR("net", "UDP port {}: {}", opt.port, sock.error());
        return 1;
    }
    TCL_INFO("net", "waiting for a receiver on UDP port {} (Ctrl+C to stop)", opt.port);

    std::optional<tclproto::Endpoint> peer;
    uint32_t session_id = std::random_device{}();
    uint32_t frame_id = 0;
    bool idr_pending = true;
    uint64_t content_time_us = 0;  // when the content currently in the NV12 texture was presented on the PC
    bool have_content = false, dirty = false, first_frame_logged = false, first_sent_logged = false;
    steady_clock::time_point last_peer_msg, last_idr, last_encode, last_stats = steady_clock::now();
    steady_clock::time_point next_frame = steady_clock::now();
    const auto frame_interval = duration_cast<steady_clock::duration>(duration<double>(1.0 / opt.enc.fps));
    uint64_t frames_sent = 0, bytes_sent = 0, encode_us_total = 0;
    std::vector<uint8_t> bitstream;
    uint8_t rx[2048];

    while (!g_quit) {
        // --- control messages from the receiver ---
        tclproto::Endpoint from;
        int n;
        while ((n = sock.recv_from(rx, from, 0)) > 0) {
            const std::span<const uint8_t> packet(rx, size_t(n));
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
                if (!peer || *peer != from) {
                    TCL_INFO("session", "receiver connected: {}", from.to_string());
                    peer = from;
                    session_id++;
                    idr_pending = true;
                    first_sent_logged = false;
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
                } else if (msg->type == PacketType::Bye) {
                    TCL_INFO("session", "receiver left");
                    peer.reset();
                }
            }
        }
        if (n < 0) TCL_WARN("net", "{}", sock.error());
        if (peer && steady_clock::now() - last_peer_msg > 5s) {
            TCL_WARN("session", "receiver {} silent for 5 s, dropping it", peer->to_string());
            peer.reset();
        }

        // --- capture ---
        auto wait = duration_cast<milliseconds>(next_frame - steady_clock::now()).count();
        if (opt.test_pattern) {
            // Fresh synthetic frame every slot: exercises the full 60 fps path and latency.
            if (wait > 0) std::this_thread::sleep_for(milliseconds(wait));
            pattern.draw(pattern_frame++);
            if (converter.convert(pattern.texture())) {
                have_content = dirty = true;
                content_time_us = now_us();
            }
        }
        Microsoft::WRL::ComPtr<ID3D11Texture2D> desktop;
        switch (opt.test_pattern ? tcl::DesktopCapture::Result::Timeout : capture.acquire(int(std::clamp<long long>(wait, 0, 100)), desktop)) {
        case tcl::DesktopCapture::Result::NewFrame:
            if (!converter.convert(desktop.Get())) TCL_ERROR("convert", "{}", converter.error());
            else {
                have_content = dirty = true;
                content_time_us = capture.present_time_us();
            }
            capture.release();
            if (!first_frame_logged) {
                TCL_INFO("capture", "first desktop frame captured");
                first_frame_logged = true;
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
        encode_us_total += now_us() - t0;
        last_encode = now;
        dirty = false;
        if (key) {
            idr_pending = false;
            last_idr = now;
        }

        tclproto::MediaHeader h;
        h.type = PacketType::Video;
        h.flags = key ? tclproto::kFlagKeyframe : 0;
        h.session_id = session_id;
        h.frame_id = frame_id++;
        // 0 = repeated old content (heartbeat/keyframe), so the receiver must not count it as latency.
        h.capture_time_us = fresh ? content_time_us : 0;
        tclproto::fragment_frame(h, bitstream, [&](std::span<const uint8_t> d) {
            if (!sock.send_to(*peer, d)) TCL_WARN("net", "{}", sock.error());
        });
        frames_sent++;
        bytes_sent += bitstream.size();
        if (!first_sent_logged) {
            TCL_INFO("session", "first frame sent ({}, {} bytes)", key ? "keyframe" : "P-frame", bitstream.size());
            first_sent_logged = true;
        }

        if (now - last_stats >= 2s) {
            const double secs = duration<double>(now - last_stats).count();
            TCL_INFO("stats", "{:.1f} fps sent, {:.1f} Mbit/s, avg encode {:.2f} ms", frames_sent / secs,
                     bytes_sent * 8 / secs / 1e6, frames_sent ? encode_us_total / 1000.0 / frames_sent : 0.0);
            frames_sent = bytes_sent = encode_us_total = 0;
            last_stats = now;
        }
    }

    TCL_INFO("main", "stopping");
    timeEndPeriod(1);
    return 0;
}
