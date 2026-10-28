// tclstream-host — proof-of-life streamer.
// Captures the desktop, encodes H.264 with NVENC and streams it over UDP to the first receiver
// that says Hello. No pairing/encryption yet: LAN testing only (see docs/ARCHITECTURE.md §9).
#include <windows.h>
#include <shellapi.h>
#include <xinput.h>
#include <avrt.h>
#include <timeapi.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <deque>
#include <map>
#include <mutex>
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
#include "frame_cap.h"
#include "pad_mouse.h"
#include "screenshot.h"
#include "converter.h"
#include "gamepad.h"
#include "log.h"
#include "net_tuning.h"
#include "netinfo.h"
#include "nvenc_encoder.h"
#include "rate_control.h"
#include "virtual_display.h"
#include "pairing.h"
#include "steam_game.h"
#include "tclproto/auth.h"
#include "tclproto/fec.h"
#include "test_pattern.h"
#include "tclproto/framing.h"
#include "tclproto/protocol.h"
#include "tclproto/udp.h"

using namespace std::chrono;
using tclproto::PacketType;

namespace {

std::atomic<bool> g_quit{false};
// Commands from the Jinky app on stdin ("pair-accept <id>", "unpair <id>", ...).
std::mutex g_cmd_mu;
std::deque<std::string> g_cmds;
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
    bool probe_hdr = false;  // check that this GPU can convert and encode HDR10, then exit
    bool audio = true;
    bool mute_pc = false;
    bool console = false;  // Big Picture + 60 Hz + mute while a TV is connected
    bool frame_cap = false;  // console sessions also cap games at the stream's rate (NVIDIA driver)
    bool keep_big_picture = false;  // a session ending leaves Big Picture open (closed when the engine stops)
    bool stdin_control = false;
    bool insecure = false;  // accept any TV without pairing (test tools only)
    bool virtual_display = false;  // console sessions play on the virtual screen ("Monitor virtual")
    std::string vdisplay_admin;    // install/uninstall/status of the virtual screen driver, then exit
    std::string vdisplay_folder;   // where the driver to install is
    uint32_t tv_update_version = 0;  // TV app update the Jinky app serves (version code), 0 = none
    uint16_t tv_update_port = 0;  // "quit" on stdin (or stdin closing) stops the engine cleanly
    std::string codec = "auto";  // auto = HEVC when the TV decodes it in hardware, else H.264
    std::string name;  // how TVs list this PC ("" = the computer's name)
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
        else if (a == "--probe-hdr") o.probe_hdr = true;
        else if (a == "--no-audio") o.audio = false;
        else if (a == "--mute-pc") o.mute_pc = true;
        else if (a == "--console") o.console = true;
        else if (a == "--frame-cap") o.frame_cap = true;
        else if (a == "--keep-big-picture") o.keep_big_picture = true;
        else if (a == "--events") tcl::events_enabled() = true;
        else if (a == "--stdin-control") o.stdin_control = true;
        else if (a == "--insecure") o.insecure = true;
        else if (a == "--virtual-display") o.virtual_display = true;
        else if (a == "--install-virtual-display" && (v = next())) {
            o.vdisplay_admin = "install";
            o.vdisplay_folder = v;
        }
        else if (a == "--uninstall-virtual-display") o.vdisplay_admin = "uninstall";
        else if (a == "--lift-display-policy") {}  // read again as Unicode with --install-virtual-display
        else if (a == "--virtual-display-status") o.vdisplay_admin = "status";
        else if (a == "--tv-update" && (v = next())) {
            // "<versionCode>:<port>" — the desktop app serves the TV app on that port.
            const std::string spec = v;
            const auto colon = spec.find(':');
            if (colon != std::string::npos) {
                o.tv_update_version = uint32_t(std::strtoul(spec.substr(0, colon).c_str(), nullptr, 10));
                o.tv_update_port = uint16_t(std::strtoul(spec.substr(colon + 1).c_str(), nullptr, 10));
            }
        }
        else if (a == "--version") {
            std::printf("jinky-engine %s\n", JINKY_VERSION);
            std::exit(0);
        }
        else if (a == "--codec" && (v = next())) o.codec = v;
        else if (a == "--name" && (v = next())) o.name = v;
        else if (a == "--refs" && (v = next())) o.enc.max_ref_frames = uint32_t(std::atoi(v));
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
                         "                      [--fps 60] [--size 1920x1080] [--test-pattern] [--stress] [--no-audio] [--mute-pc] [--console] [--frame-cap] [--name PC] [--insecure]\n"
                         "                      [--codec auto|hevc|h264]\n");
            return false;
        }
    }
    return o.enc.fps > 0 && o.enc.bitrate_kbps > 0 && o.enc.width % 2 == 0 && o.enc.height % 2 == 0;
}

// Someone playing on the PC itself (keyboard, mouse, or a controller plugged into the PC): a game
// the TV left behind is then theirs, and is never paused for the TV.
class LocalPlayer {
public:
    void start() {
        if (!get_state_) {
            if (HMODULE x = LoadLibraryW(L"xinput1_4.dll"))
                get_state_ = reinterpret_cast<decltype(get_state_)>(GetProcAddress(x, "XInputGetState"));
        }
        active_ = false;
        since_ = GetTickCount();
        for (DWORD i = 0; i < 4; ++i) known_[i] = read(i, packet_[i]);
    }
    // About once a second: true once anyone touched the PC since start().
    bool poll() {
        if (active_) return true;
        LASTINPUTINFO li{sizeof(li)};
        if (GetLastInputInfo(&li) && int32_t(li.dwTime - since_) > 0) active_ = true;
        for (DWORD i = 0; i < 4 && !active_; ++i) {
            DWORD packet = 0;
            if (!read(i, packet)) {
                known_[i] = false;
            } else if (!known_[i]) {
                known_[i] = true;  // just plugged in: its first press counts
                packet_[i] = packet;
            } else if (packet != packet_[i]) {
                active_ = true;
            }
        }
        return active_;
    }

private:
    bool read(DWORD slot, DWORD& packet) const {
        XINPUT_STATE st{};
        if (!get_state_ || get_state_(slot, &st) != ERROR_SUCCESS) return false;
        packet = st.dwPacketNumber;
        return true;
    }
    DWORD(WINAPI* get_state_)(DWORD, XINPUT_STATE*) = nullptr;
    bool active_ = false;
    DWORD since_ = 0;
    bool known_[4]{};
    DWORD packet_[4]{};
};

}  // namespace

int main(int argc, char** argv) {
    Options opt;
    if (!parse_args(argc, argv, opt)) return 2;
    // argv is in the ANSI code page: read --name again as Unicode (Windows user names may have
    // accents) and keep it as UTF-8, which is what the protocol carries.
    if (int wargc = 0; LPWSTR* wargv = CommandLineToArgvW(GetCommandLineW(), &wargc)) {
        for (int i = 1; i + 1 < wargc; ++i)
            if (std::wstring_view(wargv[i]) == L"--name") opt.name = utf8(wargv[i + 1]);
        LocalFree(wargv);
    }
    // The Jinky app asks (as administrator, for install/uninstall) about the virtual screen driver.
    if (opt.vdisplay_admin == "status") return tcl::VirtualDisplay::available() ? 0 : 1;
    if (opt.vdisplay_admin == "uninstall") return tcl::VirtualDisplay::uninstall();
    if (opt.vdisplay_admin == "install") {
        int wargc = 0;
        std::wstring folder;
        bool lift = false;
        if (LPWSTR* wargv = CommandLineToArgvW(GetCommandLineW(), &wargc)) {
            for (int i = 1; i < wargc; ++i) {
                if (std::wstring_view(wargv[i]) == L"--install-virtual-display" && i + 1 < wargc) folder = wargv[i + 1];
                if (std::wstring_view(wargv[i]) == L"--lift-display-policy") lift = true;
            }
            LocalFree(wargv);
        }
        return tcl::VirtualDisplay::install(folder, lift);
    }
    // A previous run that died on the virtual screen: the PC's monitors come back first (the
    // capture starts on them). Outside sessions the virtual screen is never part of the desktop.
    tcl::VirtualDisplay::tidy();
    // If a previous run was killed while the PC was muted for streaming, give the sound back.
    if (tcl::ConsoleMode::restore_if_left())
        TCL_INFO("console", "display and sound restored (a previous run ended in console mode)");
    if (tcl::AudioCapture::restore_if_left_muted()) TCL_INFO("audio", "PC sound restored (left muted by a previous run)");
    if (tcl::FrameCap::restore_if_left()) TCL_INFO("framecap", "NVIDIA frame rate limit restored (left on by a previous run)");
    SetConsoleCtrlHandler(on_console_ctrl, TRUE);
    if (opt.stdin_control) {
        // The desktop app runs the engine without a console; it stops it by writing "quit" or by
        // closing the pipe, so cleanup (display, sound, virtual pads) always runs.
        std::thread([] {
            std::string line;
            while (std::getline(std::cin, line) && line != "quit") {
                std::lock_guard lock(g_cmd_mu);
                g_cmds.push_back(line);
            }
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
        // When a game keeps the GPU at 100%, capture and encode would otherwise wait behind its frames.
        if (tcl::raise_gpu_scheduling_priority()) TCL_INFO("main", "GPU scheduling priority: high");
        else TCL_WARN("main", "could not raise the GPU scheduling priority class");
    }

    tcl::FrameCompositor compositor;  // desktop + mouse cursor
    tcl::Nv12Converter converter;
    if (!converter.init(capture.device(), capture.context(), capture.width(), capture.height(), opt.enc.width,
                        opt.enc.height)) {
        TCL_ERROR("convert", "init failed: {}", converter.error());
        return 1;
    }

    if (opt.probe_hdr) {
        // HDR10 path without touching the display: scRGB -> P010 conversion and HEVC Main10.
        tcl::Nv12Converter c;
        const bool conv = c.init(capture.device(), capture.context(), 1920, 1080, 1920, 1080, true);
        std::printf("hdr converter: %s\n", conv ? "ok" : c.error().c_str());
        if (conv) {
            tcl::EncoderConfig cfg = opt.enc;
            cfg.codec = tcl::VideoCodec::HEVC;
            cfg.hdr = true;
            tcl::NvencEncoder e;
            const bool enc = e.open(capture.device(), c.output(), cfg);
            std::printf("hdr encoder: %s\n", enc ? e.description().c_str() : e.error().c_str());
            // A synthetic scRGB picture, all white at 1.0 (80 nits): through the shaders, then read
            // back. PQ(80 nits) = 0.4818 -> Y code 64 + 876 * 0.4818 = 486; CbCr 512 (no colour).
            {
                D3D11_TEXTURE2D_DESC td{};
                td.Width = 1920;
                td.Height = 1080;
                td.MipLevels = td.ArraySize = 1;
                td.Format = DXGI_FORMAT_R16G16B16A16_FLOAT;
                td.SampleDesc.Count = 1;
                td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
                std::vector<uint16_t> white(size_t(1920) * 1080 * 4, 0x3C00);  // half-float 1.0
                D3D11_SUBRESOURCE_DATA init{white.data(), 1920 * 8, 0};
                Microsoft::WRL::ComPtr<ID3D11Texture2D> src, staging;
                capture.device()->CreateTexture2D(&td, &init, &src);
                const bool converted = src && c.convert(src.Get());
                D3D11_TEXTURE2D_DESC sd{};
                c.output()->GetDesc(&sd);
                sd.Usage = D3D11_USAGE_STAGING;
                sd.BindFlags = 0;
                sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
                capture.device()->CreateTexture2D(&sd, nullptr, &staging);
                D3D11_MAPPED_SUBRESOURCE m{};
                if (converted && staging) {
                    capture.context()->CopyResource(staging.Get(), c.output());
                    if (SUCCEEDED(capture.context()->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &m))) {
                        const auto* y = static_cast<const uint16_t*>(m.pData);
                        const auto* uv = reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(m.pData) + size_t(m.RowPitch) * 1080);
                        std::printf("hdr white 80 nits: Y code %u (expect ~486), Cb %u Cr %u (expect 512)\n",
                                    unsigned(y[960] >> 6), unsigned(uv[960] >> 6), unsigned(uv[961] >> 6));
                        capture.context()->Unmap(staging.Get(), 0);
                    }
                } else {
                    std::printf("hdr conversion failed: %s\n", c.error().c_str());
                }
            }
            if (enc) {
                std::vector<uint8_t> out;
                bool key = false;
                const bool frame = e.encode(true, 0, out, key);
                std::printf("hdr frame: %s, %zu bytes, keyframe %d\n", frame ? "ok" : e.error().c_str(), out.size(), int(key));
            }
        }
        float mx = 0, mn = 0, fall = 0;
        capture.luminance(mx, mn, fall);
        std::printf("monitor luminance: max %.0f, min %.4f, full frame %.0f nits; Windows HDR %s\n", mx, mn, fall,
                    capture.display_hdr_on() ? "on" : "off");
        return 0;
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
    // The encoder reads the converter's output texture (registered with NVENC at open). Whenever
    // that texture is recreated (desktop resolution change), or encoding keeps failing, open the
    // encoder again on the current texture, at the bitrate it was using. Without this a game
    // switching resolution left every later frame failing until Jinky was restarted.
    uint32_t encode_failures = 0;
    // The converter's output must be what the encoder expects: 10-bit HDR10 only with an HDR
    // capture and HEVC, else 8-bit SDR. Call before (re)opening the encoder; sets opt.enc.hdr.
    auto sync_converter = [&]() {
        const bool want_hdr = capture.hdr() && opt.enc.codec == tcl::VideoCodec::HEVC;
        if (converter.hdr() != want_hdr || converter.src_width() != capture.width() || converter.src_height() != capture.height()) {
            if (!converter.init(capture.device(), capture.context(), capture.width(), capture.height(), opt.enc.width,
                                opt.enc.height, want_hdr)) {
                if (!want_hdr) {
                    TCL_ERROR("convert", "{}", converter.error());
                    return false;
                }
                // This GPU can't make HDR10 from the desktop: stay in SDR.
                TCL_WARN("convert", "{}; streaming in SDR", converter.error());
                capture.want_hdr(false);
                capture.reopen();
                if (!converter.init(capture.device(), capture.context(), capture.width(), capture.height(), opt.enc.width,
                                    opt.enc.height, false)) {
                    TCL_ERROR("convert", "{}", converter.error());
                    return false;
                }
            }
        }
        opt.enc.hdr = converter.hdr();
        if (opt.enc.hdr) capture.luminance(opt.enc.hdr_max_nits, opt.enc.hdr_min_nits, opt.enc.hdr_max_fall_nits);
        return true;
    };
    auto reopen_encoder = [&](const char* why) {
        if (!sync_converter()) return false;
        const uint32_t kbps = encoder.bitrate_kbps();
        encoder.close();
        if (!encoder.open(capture.device(), converter.output(), opt.enc)) {
            TCL_ERROR("encoder", "reopen after {} failed: {}", why, encoder.error());
            return false;
        }
        if (kbps && kbps != opt.enc.bitrate_kbps && !encoder.set_bitrate(kbps)) TCL_WARN("encoder", "{}", encoder.error());
        encode_failures = 0;
        TCL_INFO("encoder", "reopened after {}: {}", why, encoder.description());
        return true;
    };
    tcl::emit_event("ready", "\"gpu\":" + tcl::json_str(utf8(capture.adapter_name())) + ",\"desktop\":\"" +
                                 std::to_string(capture.width()) + "x" + std::to_string(capture.height()) + "\"");

    tclproto::UdpSocket sock;
    if (!sock.open(opt.port)) {
        // Another Jinky has the port: another Windows user's, or another ASTER workplace's. Not a
        // crash: the desktop app waits and starts this engine again once the port is free.
        const std::string err = sock.error();
        if (err.find("10048") != std::string::npos || err.find("10013") != std::string::npos) {
            TCL_WARN("net", "UDP port {} is in use by another Jinky", opt.port);
            tcl::emit_event("port_busy", "\"port\":" + std::to_string(opt.port));
            return 3;
        }
        TCL_ERROR("net", "UDP port {}: {}", opt.port, err);
        return 1;
    }
    TCL_INFO("net", "waiting for a receiver on UDP port {} (Ctrl+C to stop)", opt.port);
    tcl::StreamNetTuning net_tuning;  // QoS marks and Wi-Fi scan pause while a TV is watching

    std::optional<tclproto::Endpoint> peer;
    bool peer_desktop = false;  // the TV chose "Área de trabalho": no console mode for this session
    bool peer_game = false;     // a game chosen in the TV's library: console mode without Big Picture
    bool peer_hdr = false;      // the TV asked for HDR (and decodes HEVC)
    tcl::FecControl fec;        // loss protection, for TVs that rebuild lost packets from parity
    std::vector<std::vector<uint8_t>> parity;
    bool capture_reopen = false;  // capture again now (HDR turned on/off for the session)
    tcl::SteamGameWatch game_watch;
    std::string peer_name;
    bool busy_logged = false;
    steady_clock::time_point last_displaced_sent{}, last_bp_check{};
    // A game the TV asked for is started by the desktop app once this says "launch_ready": in
    // console mode only after Big Picture is up, or Big Picture opening late lands on top of a
    // game that starts fast (Resident Evil 7: the game played behind it).
    steady_clock::time_point launch_wait_until{}, bp_seen_at{};
    bool launch_ready_pending = false;
    // Quick Resume: the TV continues a paused game (no Big Picture needed), and may let the PC
    // pause the game itself if the TV vanishes (Wi-Fi gone, TV switched off).
    bool peer_resume = false, peer_auto_pause = false;
    uint32_t auto_pause_pid = 0;  // the game to pause if the TV that vanished doesn't come back
    steady_clock::time_point auto_pause_at{};  // ...by then
    LocalPlayer local_player;

    // --- pairing / authentication ---
    tcl::PairedStore paired;
    if (!opt.insecure) {
        paired.load();
        TCL_INFO("pairing", "this PC is {}; {} paired TV(s)", tcl::hex_id(paired.host_id()), paired.all().size());
    } else {
        TCL_WARN("pairing", "--insecure: any device on the network can connect (testing only)");
    }
    auto emit_paired_list = [&] {
        std::string list = "[";
        for (const auto& tv : paired.all()) {
            if (list.size() > 1) list += ",";
            list += "{\"id\":\"" + tcl::hex_id(tv.device_id) + "\",\"name\":" + tcl::json_str(tv.name) + "}";
        }
        tcl::emit_event("paired_list", "\"tvs\":" + list + "]");
    };
    emit_paired_list();
    // Nonce per TV address: a signed Hello must carry the nonce given to *that* address.
    struct Nonce { uint64_t value; steady_clock::time_point sent; };
    std::map<uint64_t, Nonce> nonces;
    auto addr_key = [](const tclproto::Endpoint& e) { return uint64_t(e.ip) << 16 | e.port; };
    tclproto::PairKey peer_key{};
    uint64_t peer_device = 0;
    struct PendingPair {
        uint64_t device_id;
        std::string name;
        tclproto::PairKey key;
        tclproto::Endpoint from;
        steady_clock::time_point created;
        std::vector<uint8_t> challenge;
        tclproto::PublicKey tv_public{};
    };
    std::optional<PendingPair> pending;
    std::map<uint64_t, steady_clock::time_point> discover_replies;
    // Several Jinkys on one PC (one per Windows user / ASTER workplace) tell TVs apart by name.
    const std::string pc_name = opt.name.empty() ? tcl::computer_name() : opt.name;
    auto send_auth_required = [&](const tclproto::Endpoint& to) {
        auto& n = nonces[addr_key(to)];
        if (n.value == 0) n.value = tcl::random_u64() | 1;
        if (steady_clock::now() - n.sent < 300ms) return;
        n.sent = steady_clock::now();
        uint8_t buf[tclproto::AuthRequired::kSize];
        tclproto::AuthRequired{paired.host_id(), n.value}.write(buf);
        sock.send_to(to, buf);
    };
    auto send_pair_result = [&](const PendingPair& p, bool accepted) {
        uint8_t buf[tclproto::PairResult::kSize];
        tclproto::PairResult{paired.host_id(), p.device_id, accepted,
                             accepted ? tclproto::accept_proof(p.key, p.device_id) : tclproto::Mac{}}
            .write(buf);
        for (int i = 0; i < 3; ++i) sock.send_to(p.from, buf);  // UDP: say it thrice
    };
    uint32_t session_id = std::random_device{}();
    auto send_control = [&](const tclproto::Endpoint& to, PacketType type, uint32_t value = 0) {
        uint8_t buf[tclproto::ControlMessage::kSize];
        tclproto::ControlMessage{type, session_id, value}.write(buf);
        sock.send_to(to, buf);
    };

    // The receive thread (below) applies controller input itself; it needs the current receiver
    // and its key. Everything else it queues for the main loop.
    struct RxPacket { tclproto::Endpoint from; std::vector<uint8_t> bytes; };
    std::mutex rx_mu;
    std::deque<RxPacket> rx_queue;
    std::optional<tclproto::Endpoint> input_peer;
    tclproto::PairKey input_key{};
    std::atomic<steady_clock::rep> last_input{0};  // steady_clock ticks of the last accepted input
    // Controller input's trip from the TV (its send time is stamped on this PC's clock).
    std::atomic<uint64_t> input_trip_sum_us{0}, input_trip_count{0}, input_trip_max_us{0};

    // The audio thread needs the current receiver: (ip << 16 | port), 0 = nobody connected.
    std::atomic<uint64_t> audio_peer{0};
    std::atomic<uint32_t> audio_session{0};
    tcl::ConsoleMode console;
    tcl::FrameCap frame_cap;
    tcl::VirtualDisplay virtual_display;
    std::wstring pc_display;  // the monitor captured before the virtual screen took over
    // Points the capture at another monitor (it shows up a moment after a layout change); the
    // main loop then captures it again (and sizes the encoder to it).
    auto capture_monitor = [&](const std::wstring& name) {
        for (int i = 0; i < 30; ++i) {
            if (capture.retarget(name)) {
                capture_reopen = true;
                return true;
            }
            std::this_thread::sleep_for(100ms);
        }
        return false;
    };
    // keep_console: the TV went silent (Wi-Fi dropped) rather than leaving; console mode (Big
    // Picture, 60 Hz, muted PC) stays on for a while so it can come straight back to the game.
    auto publish_peer = [&](bool keep_console = false) {
        net_tuning.set_peer(sock.native_handle(), peer);
        net_tuning.set_streaming(peer.has_value());
        {
            std::lock_guard lock(rx_mu);
            input_peer = peer;
            input_key = peer_key;
        }
        audio_session = session_id;
        audio_peer = peer ? (uint64_t(peer->ip) << 16 | peer->port) : 0;
        if (opt.console && !opt.test_pattern) {
            if (peer && !peer_desktop) {
                // "Monitor virtual": the session plays on a virtual screen at the TV's size and
                // rate; the PC's monitors stay as they are (dark meanwhile). Not for HDR, which
                // needs the real monitor's Windows HDR.
                if (opt.virtual_display && !peer_hdr && !virtual_display.active() && !console.active()) {
                    std::wstring vname;
                    const std::wstring before = capture.display_name();
                    if (virtual_display.enter(opt.enc.width, opt.enc.height, opt.enc.fps, vname)) {
                        if (capture_monitor(vname)) {
                            pc_display = before;
                        } else {
                            TCL_WARN("vdisplay", "the virtual screen can't be captured ({}); using the PC's monitor", capture.error());
                            virtual_display.leave();
                            if (!capture_monitor(before)) capture_monitor(L"");
                        }
                    }
                }
                // Games from the TV's library also start inside Big Picture (it handles Steam's
                // prompts and controller input in launchers); the TV hides it until the game shows.
                console.enter(capture.display_name(), !peer_resume);
                console.set_hdr(peer_hdr);
                if (opt.frame_cap && !frame_cap.active() && frame_cap.enter(opt.enc.fps))
                    tcl::emit_event("frame_cap", "\"on\":true,\"fps\":" + std::to_string(opt.enc.fps));
                return;
            }
            if (keep_console && console.active()) return;
            console.leave(!opt.keep_big_picture);  // no-op unless a console session just ended
            if (virtual_display.active()) {
                virtual_display.leave();
                if (!capture_monitor(pc_display) && !capture_monitor(L"")) TCL_WARN("capture", "{}", capture.error());
            }
            if (!peer && capture.hdr()) {
                capture.want_hdr(false);
                capture_reopen = true;  // back to the SDR capture
            }
            if (frame_cap.active()) {
                frame_cap.leave();
                tcl::emit_event("frame_cap", "\"on\":false");
            }
            // "Área de trabalho": the PC stays as it is; only its sound moves to the TV.
            if (opt.audio && !tcl::AudioCapture::set_pc_muted(peer.has_value()))
                TCL_WARN("audio", "could not change PC mute state");
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
    auto last_power_request = steady_clock::now() - 1h;  // "Suspender/Desligar o PC" from a TV
    uint32_t last_paused_pid = 0;                         // "Início": the game last paused
    uint32_t last_text_seq = 0;                           // "Teclado": the last text typed
    auto last_screenshot = steady_clock::now() - 1h;     // "Capturar tela" from a TV
    std::atomic<bool> manual_mouse{false};  // menu "Mouse" on the TV
    tcl::PadMouse pad_mouse;  // the controller as mouse/keyboard while a window waits for the player
    const bool gamepads_ok = gamepads.init([&](uint8_t pad, uint8_t large_motor, uint8_t small_motor) {
        const uint64_t p = audio_peer;
        if (!p) return;
        uint8_t msg[tclproto::RumbleMessage::kSize];
        tclproto::RumbleMessage{audio_session, pad, large_motor, small_motor}.write(msg);
        sock.send_to({uint32_t(p >> 16), uint16_t(p & 0xFFFF)}, msg);
    });
    tcl::emit_event("gamepads", std::string("\"available\":") + (gamepads_ok ? "true" : "false"));
    if (gamepads_ok) TCL_INFO("gamepad", "ViGEmBus ready: TV controllers will appear as Xbox 360 (or DualShock 4) pads");
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
    const uint32_t default_bitrate_kbps = opt.enc.bitrate_kbps;
    const uint32_t default_width = opt.enc.width, default_height = opt.enc.height;  // a TV may ask for 720p

    // Recently sent video frames (about a second), so fragments a TV missed can be sent again.
    struct SentFrame {
        tclproto::MediaHeader header;
        std::vector<uint8_t> data;
        int resends = 0;
    };
    std::mutex sent_mu;
    std::array<SentFrame, 64> sent;  // by frame_id % size
    std::atomic<uint64_t> packets_resent{0};
    uint64_t parity_sent = 0;
    auto resend = [&](const tclproto::Endpoint& to, const tclproto::NackMessage& nack) {
        std::lock_guard lock(sent_mu);
        SentFrame& f = sent[nack.frame_id % sent.size()];
        if (f.data.empty() || f.header.frame_id != nack.frame_id || f.header.session_id != nack.session_id) return;
        if (++f.resends > 4) return;  // asked over and over: that frame is beyond saving
        const size_t count = tclproto::fragment_count(f.data.size());
        uint8_t buf[tclproto::MediaHeader::kSize + tclproto::kMaxPayload];
        for (const auto& r : nack.ranges) {
            const size_t end = r.count == tclproto::NackMessage::kWholeFrame ? count : std::min<size_t>(count, r.first + r.count);
            for (size_t i = r.first; i < end; ++i) {
                if (const size_t n = tclproto::write_fragment(f.header, f.data, i, buf)) {
                    sock.send_to(to, std::span<const uint8_t>(buf, n));
                    packets_resent++;
                }
            }
        }
    };
    tcl::RateControl rate;
    steady_clock::time_point console_linger_until{};

    // Network receive thread. Controller input and clock-sync requests are answered the moment
    // they arrive; the main loop can be busy for up to a frame (waiting for the desktop, encoding),
    // which used to add 0-17 ms to every button press.
    std::jthread rx_thread([&](std::stop_token stop) {
        DWORD task_index = 0;
        const HANDLE task = AvSetMmThreadCharacteristicsW(L"Games", &task_index);
        if (task) AvSetMmThreadPriority(task, AVRT_PRIORITY_HIGH);
        uint8_t rx[2048];
        tclproto::Endpoint from;
        while (!stop.stop_requested() && !g_quit) {
            const int n = sock.recv_from(rx, from, 50);
            if (n < 0) {
                TCL_WARN("net", "{}", sock.error());
                std::this_thread::sleep_for(50ms);
                continue;
            }
            if (n == 0) continue;
            const std::span<const uint8_t> packet(rx, size_t(n));
            const auto type = tclproto::peek_type(packet);
            if (type == PacketType::Input) {
                auto in = tclproto::InputState::read(packet);
                bool ok;
                {
                    std::lock_guard lock(rx_mu);
                    ok = in && input_peer && from == *input_peer &&
                         (opt.insecure || tclproto::InputState::verify(packet, input_key));
                }
                if (ok) {
                    // Mouse mode: the first controller drives the mouse, the game sees it idle.
                    if (manual_mouse && in->pad_index == 0) {
                        tclproto::InputState idle;
                        idle.session_id = in->session_id;
                        idle.pad_index = 0;
                        idle.connected = in->connected;
                        idle.seq = in->seq;
                        gamepads.apply(idle);
                    } else {
                        gamepads.apply(*in);
                    }
                    pad_mouse.update(*in);
                    last_input = steady_clock::now().time_since_epoch().count();
                    if (in->sent_us) {
                        const int32_t trip = int32_t(uint32_t(now_us()) - in->sent_us);
                        // Within a second only: anything else is a clock not yet settled.
                        if (trip > -5'000 && trip < 1'000'000) {
                            input_trip_sum_us += uint64_t(std::max(trip, 0));
                            input_trip_count++;
                            uint64_t seen = input_trip_max_us.load();
                            while (uint64_t(std::max(trip, 0)) > seen &&
                                   !input_trip_max_us.compare_exchange_weak(seen, uint64_t(std::max(trip, 0)))) {}
                        }
                    }
                }
                continue;
            }
            if (type == PacketType::PadExtras) {
                // A PlayStation pad's touchpad and motion: straight to the virtual DualShock 4.
                auto ex = tclproto::PadExtras::read(packet);
                bool ok;
                {
                    std::lock_guard lock(rx_mu);
                    ok = ex && input_peer && from == *input_peer;
                }
                if (ok) gamepads.apply_extras(*ex);
                continue;
            }
            if (type == PacketType::Nack) {
                // Answered at once: the TV holds the frames after the gap until it is filled.
                auto nack = tclproto::NackMessage::read(packet);
                std::optional<tclproto::Endpoint> to;
                {
                    std::lock_guard lock(rx_mu);
                    if (nack && input_peer && from == *input_peer) to = input_peer;
                }
                if (to) resend(*to, *nack);
                continue;
            }
            if (type == PacketType::TimeRequest) {
                if (auto t = tclproto::TimeSync::read(packet)) {
                    t->type = PacketType::TimeResponse;
                    t->host_us = now_us();
                    uint8_t reply[tclproto::TimeSync::kSize];
                    t->write(reply);
                    sock.send_to(from, reply);
                }
                continue;
            }
            std::lock_guard lock(rx_mu);
            if (rx_queue.size() >= 512) rx_queue.pop_front();  // main loop stuck (e.g. capture lost)
            rx_queue.push_back({from, std::vector<uint8_t>(packet.begin(), packet.end())});
        }
        if (task) AvRevertMmThreadCharacteristics(task);
    });

    while (!g_quit) {
        // --- commands from the Jinky app ---
        std::deque<std::string> cmds;
        {
            std::lock_guard lock(g_cmd_mu);
            cmds.swap(g_cmds);
        }
        for (const auto& line : cmds) {
            const auto space = line.find(' ');
            const std::string cmd = line.substr(0, space);
            const auto id = space == std::string::npos ? std::nullopt : tcl::parse_hex_id(line.substr(space + 1));
            if ((cmd == "pair-accept" || cmd == "pair-reject") && pending && id == pending->device_id) {
                const bool ok = cmd == "pair-accept";
                if (ok) paired.add({pending->device_id, pending->key, pending->name});
                send_pair_result(*pending, ok);
                TCL_INFO("pairing", "{} {} ({})", ok ? "paired" : "rejected", pending->name, tcl::hex_id(pending->device_id));
                tcl::emit_event(ok ? "paired" : "pair_rejected", "\"id\":\"" + tcl::hex_id(pending->device_id) +
                                                                   "\",\"name\":" + tcl::json_str(pending->name));
                pending.reset();
                emit_paired_list();
            } else if (cmd == "unpair" && id) {
                if (paired.remove(*id)) {
                    TCL_INFO("pairing", "unpaired {}", tcl::hex_id(*id));
                    if (peer && peer_device == *id) {
                        // It is playing right now: end the session and tell it it is no longer
                        // paired, so it forgets the key and shows the pairing screen.
                        send_pair_result(PendingPair{*id, {}, {}, *peer, steady_clock::now(), {}}, false);
                        peer.reset();
                        peer_name.clear();
                        publish_peer();
                        gamepads.unplug_all();
                        tcl::emit_event("disconnected", "\"reason\":\"unpaired\"");
                    }
                    emit_paired_list();
                }
            } else if (cmd == "list-paired") {
                emit_paired_list();
            }
        }
        if (pending && steady_clock::now() - pending->created > 120s) {
            TCL_INFO("pairing", "pairing request from {} expired", pending->name);
            tcl::emit_event("pair_expired");
            pending.reset();
        }
        // --- control messages from the receiver ---
        audio.set_keepalive(peer.has_value() && !opt.test_pattern);
        // (input and clock sync are handled by the receive thread)
        if (const auto t = steady_clock::time_point(steady_clock::duration(last_input.load())); t > last_peer_msg)
            last_peer_msg = t;
        std::deque<RxPacket> inbox;
        {
            std::lock_guard lock(rx_mu);
            inbox.swap(rx_queue);
        }
        for (const RxPacket& item : inbox) {
            const tclproto::Endpoint& from = item.from;
            const std::span<const uint8_t> packet(item.bytes);
            if (tclproto::peek_type(packet) == PacketType::DeviceName) {
                if (auto nm = tclproto::NameMessage::read(packet); nm && peer && from == *peer && nm->name != peer_name) {
                    peer_name = nm->name;
                    TCL_INFO("session", "receiver is \"{}\"", peer_name);
                    tcl::emit_event("tv_name", "\"name\":" + tcl::json_str(peer_name));
                }
                continue;
            }
            if (tclproto::peek_type(packet) == PacketType::TypeText) {
                // Menu "Teclado": only from the TV in the session; each text once (it comes thrice).
                if (auto tm = tclproto::TextMessage::read(packet); tm && peer && from == *peer && tm->seq != last_text_seq) {
                    last_text_seq = tm->seq;
                    tcl::type_text(tm->text, tm->flags & tclproto::TextMessage::kEnter, tm->flags & tclproto::TextMessage::kBackspace);
                    TCL_INFO("input", "typed {} characters from the TV's keyboard", tm->text.size());
                }
                continue;
            }
            if (tclproto::peek_type(packet) == PacketType::PairRequest) {
                auto req = tclproto::PairRequest::read(packet);
                if (!req || opt.insecure) continue;
                if (pending && pending->device_id == req->device_id && pending->tv_public == req->tv_public) {
                    sock.send_to(from, pending->challenge);  // retransmission: same challenge
                    continue;
                }
                // Another TV mid-pairing: one at a time. The same TV with a new key (app reopened)
                // starts over below, with a new code, instead of reusing the old challenge.
                if (pending && pending->device_id != req->device_id) continue;
                tcl::EcdhP256 ecdh;
                auto secret = ecdh.ok() ? ecdh.agree(req->tv_public) : std::nullopt;
                if (!secret) {
                    TCL_WARN("pairing", "invalid pairing request from {}", from.to_string());
                    continue;
                }
                PendingPair p{req->device_id, req->name.empty() ? from.to_string() : req->name,
                              tclproto::derive_pair_key(*secret, req->tv_public, ecdh.public_key()), from,
                              steady_clock::now(), {}};
                p.tv_public = req->tv_public;
                p.challenge.resize(tclproto::PairChallenge::kSize);
                tclproto::PairChallenge{paired.host_id(), req->device_id, ecdh.public_key()}.write(p.challenge);
                sock.send_to(from, p.challenge);
                const std::string code = tclproto::pairing_code(p.key);
                TCL_INFO("pairing", "\"{}\" wants to pair, code {}", p.name, code);
                tcl::emit_event("pair_request", "\"id\":\"" + tcl::hex_id(p.device_id) + "\",\"name\":" +
                                                    tcl::json_str(p.name) + ",\"code\":\"" + code + "\"");
                pending = std::move(p);
                continue;
            }
            if (tclproto::peek_type(packet) == PacketType::Discover) {
                // A TV's home screen looking for PCs: say who we are and whether we are free.
                auto& replied = discover_replies[addr_key(from)];
                if (steady_clock::now() - replied < 300ms) continue;
                replied = steady_clock::now();
                tclproto::HostInfo hi;
                hi.host_id = paired.host_id();
                // Newer TVs put their device id in the Discover (session_id:value): tell them
                // whether they are still paired here, so a TV removed on this PC forgets it.
                if (auto d = tclproto::ControlMessage::read(packet)) {
                    const uint64_t device = uint64_t(d->session_id) << 32 | d->value;
                    if (device) hi.flags = tclproto::HostInfo::kPairingKnown |
                                           (paired.find(device) ? tclproto::HostInfo::kPairedWithYou : 0);
                }
                const bool busy = peer && *peer != from && steady_clock::now() - last_peer_msg < 5s;
                hi.state = busy ? tclproto::HostInfo::kBusy : tclproto::HostInfo::kIdle;
                hi.mac = tcl::mac_toward(from.ip);
                hi.name = pc_name;
                if (busy) hi.busy_with = peer_name.empty() ? peer->to_string() : peer_name;
                hi.tv_version_code = opt.tv_update_version;
                hi.update_port = opt.tv_update_port;
                uint8_t reply[tclproto::HostInfo::kHeader + 2 * tclproto::HostInfo::kMaxText + 6];
                sock.send_to(from, std::span<const uint8_t>(reply, hi.write(reply)));
                continue;
            }
            std::optional<tclproto::ControlMessage> msg;
            std::optional<tclproto::PairKey> hello_key;
            uint64_t hello_device = 0;
            if (tclproto::peek_type(packet) == PacketType::AuthHello) {
                auto ah = tclproto::AuthHello::read(packet);
                if (!ah || opt.insecure) continue;
                const tcl::PairedTv* tv = paired.find(ah->device_id);
                if (!tv) {
                    // It believes it is paired but this PC doesn't know it (unpaired here): tell it to pair again.
                    PendingPair forget{ah->device_id, {}, {}, from, steady_clock::now(), {}};
                    send_pair_result(forget, false);
                    continue;
                }
                auto it = nonces.find(addr_key(from));
                if (it == nonces.end() || it->second.value != ah->nonce || !tclproto::AuthHello::verify(packet, tv->key)) {
                    send_auth_required(from);  // stale nonce (e.g. PC restarted) or forged: challenge again
                    continue;
                }
                msg = tclproto::ControlMessage{PacketType::Hello, 0, ah->flags};
                hello_key = tv->key;
                hello_device = tv->device_id;
            } else {
                msg = tclproto::ControlMessage::read(packet);
                if (!msg) continue;
                if (msg->type == PacketType::Hello && !opt.insecure) {
                    send_auth_required(from);
                    continue;
                }
            }
            if (msg->type == PacketType::Hello && (msg->value & tclproto::kHelloPairOnly)) {
                // "Parear um PC" on the TV: it is paired (authenticated) and leaves; nothing starts.
                // With a power flag ("Suspender/Desligar o PC"), and only from a TV that proved its
                // key, the Jinky app puts the PC to sleep or shuts it down. The TV repeats its Hello
                // every second: act once.
                const uint32_t power = msg->value & (tclproto::kHelloPowerSleep | tclproto::kHelloPowerOff);
                if (power && hello_key && steady_clock::now() - last_power_request > 30s) {
                    last_power_request = steady_clock::now();
                    const bool off = power & tclproto::kHelloPowerOff;
                    TCL_INFO("power", "a paired TV asks to {} the PC", off ? "shut down" : "suspend");
                    tcl::emit_event("power", std::string("\"action\":\"") + (off ? "shutdown" : "sleep") + "\"");
                }
                continue;
            }
            if (msg->type == PacketType::Hello) {
                const bool takeover = msg->value & tclproto::kHelloTakeover;
                if (peer && *peer != from && steady_clock::now() - last_peer_msg < 5s) {
                    if (!takeover) {
                        // Another TV that is merely open (not asking to play): keep the current
                        // session, and tell it so it shows "the game continues on another TV".
                        if (!busy_logged) TCL_WARN("session", "{} is open, but {} is streaming; telling it",
                                                   from.to_string(), peer->to_string());
                        busy_logged = true;
                        if (steady_clock::now() - last_displaced_sent > 1s) {
                            send_control(from, PacketType::Displaced);
                            last_displaced_sent = steady_clock::now();
                        }
                        continue;
                    }
                    // Handoff (bedroom -> living room): the game keeps running on the PC, only the
                    // destination changes. Console mode stays on; the old TV is told to stand by.
                    TCL_INFO("session", "{} took over the session from {}", from.to_string(), peer->to_string());
                    tcl::emit_event("handoff", "\"from\":" + tcl::json_str(peer_name.empty() ? peer->to_string() : peer_name));
                    send_control(*peer, PacketType::Displaced);
                    gamepads.unplug_all();  // the new TV's pads plug in again as players 1..4
                    peer.reset();
                    peer_name.clear();
                }
                if (!peer || *peer != from) {
                    busy_logged = false;
                    TCL_INFO("session", "receiver connected: {}", from.to_string());
                    tcl::emit_event("connected", "\"tv\":" + tcl::json_str(from.to_string()));
                    // Pick the codec for this TV (Hello.value lists what it decodes in hardware).
                    const bool tv_hevc = msg->value & tclproto::kCodecHevc;
                    const auto want = (opt.codec == "hevc" || (opt.codec == "auto" && tv_hevc)) ? tcl::VideoCodec::HEVC
                                                                                                : tcl::VideoCodec::H264;
                    // Quality chosen on the TV (Configurações), else the PC's own setting.
                    const uint32_t tv_mbps = tclproto::hello_bitrate_mbps(msg->value);
                    const uint32_t want_kbps = tv_mbps >= 5 && tv_mbps <= 150 ? tv_mbps * 1000 : default_bitrate_kbps;
                    // Picture size chosen on the TV ("Resolução"), else the PC's own.
                    const bool want_720 = tclproto::hello_size(msg->value) == tclproto::kSize720p;
                    const uint32_t want_w = want_720 ? 1280 : default_width, want_h = want_720 ? 720 : default_height;
                    if (want_w != opt.enc.width || want_h != opt.enc.height) {
                        opt.enc.width = want_w;
                        opt.enc.height = want_h;
                        TCL_INFO("encoder", "picture size for this TV: {}x{}", want_w, want_h);
                        if (!converter.init(capture.device(), capture.context(), capture.width(), capture.height(), want_w, want_h,
                                            capture.hdr() && want == tcl::VideoCodec::HEVC)) {
                            TCL_ERROR("convert", "{}", converter.error());
                            return 1;
                        }
                        encoder.close();  // reopened below at the new size
                    }
                    if (want != opt.enc.codec || want_kbps != opt.enc.bitrate_kbps || !encoder.is_open() ||
                        converter.hdr() != (capture.hdr() && want == tcl::VideoCodec::HEVC)) {
                        opt.enc.codec = want;
                        opt.enc.bitrate_kbps = want_kbps;
                        encoder.close();
                        if (!sync_converter()) return 1;
                        if (!encoder.open(capture.device(), converter.output(), opt.enc)) {
                            TCL_ERROR("encoder", "{}", encoder.error());
                            return 1;
                        }
                    }
                    // A new session starts at the chosen quality; automatic quality works below it.
                    rate.reset(opt.enc.bitrate_kbps);
                    fec.reset(msg->value & tclproto::kHelloFec);
                    if (fec.percent()) TCL_INFO("net", "loss protection on: {}% extra packets per frame", fec.percent());
                    if (encoder.bitrate_kbps() != opt.enc.bitrate_kbps && !encoder.set_bitrate(opt.enc.bitrate_kbps))
                        TCL_WARN("encoder", "{}", encoder.error());
                    console_linger_until = {};
                    TCL_INFO("encoder", "active: {}", encoder.description());
                    tcl::emit_event("codec", "\"codec\":" + tcl::json_str(opt.enc.codec == tcl::VideoCodec::HEVC ? "HEVC" : "H.264") +
                                                 ",\"bitrate_kbps\":" + std::to_string(opt.enc.bitrate_kbps));
                    peer = from;
                    peer_desktop = msg->value & tclproto::kHelloDesktop;
                    if (peer_desktop) TCL_INFO("session", "desktop mode: showing the PC as it is (no console mode)");
                    peer_game = !peer_desktop && (msg->value & tclproto::kHelloGame);
                    // HDR: asked by the TV, and only with HEVC (Main10).
                    last_paused_pid = 0;  // a game continued in this session can be paused again
                    peer_hdr = (msg->value & tclproto::kHelloHdr) && opt.enc.codec == tcl::VideoCodec::HEVC;
                    if (peer_hdr) TCL_INFO("session", "the TV asks for HDR");
                    capture.want_hdr(peer_hdr);
                    // Windows HDR already on: capture it in HDR now (when console mode turns it on,
                    // the display change makes the capture start over by itself).
                    if (peer_hdr != capture.hdr()) capture_reopen = true;
                    peer_resume = peer_game && (msg->value & tclproto::kHelloResume);
                    peer_auto_pause = peer_game && (msg->value & tclproto::kHelloAutoPause);
                    auto_pause_pid = 0;
                    if (peer_resume) {
                        TCL_INFO("session", "game session: continuing a paused game");
                        game_watch.reset();
                    } else if (peer_game) {
                        TCL_INFO("session", "game session: Big Picture hidden behind the TV's launch screen");
                        game_watch.reset();
                    }
                    launch_ready_pending = true;
                    launch_wait_until = steady_clock::now() + 10s;
                    bp_seen_at = {};
                    peer_name.clear();
                    peer_key = hello_key.value_or(tclproto::PairKey{});
                    peer_device = hello_device;
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
                } else if (msg->type == PacketType::NetReport) {
                    const auto r = tclproto::NetReport::unpack(msg->value);
                    if (r.lost_permille || r.frames_unrecovered)
                        TCL_INFO("receiver", "network: {:.1f}% of video packets asked again, {} frame(s) lost",
                                 r.lost_permille / 10.0, r.frames_unrecovered);
                    if (fec.on_report(r)) TCL_INFO("net", "loss protection: {}% extra packets per frame", fec.percent());
                    if (const uint32_t kbps = rate.on_report(r)) {
                        if (encoder.set_bitrate(kbps)) {
                            TCL_INFO("encoder", "automatic quality: {} kbit/s (chosen {})", kbps, rate.ceiling());
                            tcl::emit_event("bitrate", "\"kbps\":" + std::to_string(kbps) + ",\"ceiling_kbps\":" +
                                                           std::to_string(rate.ceiling()));
                        } else {
                            TCL_WARN("encoder", "{}", encoder.error());
                        }
                    }
                } else if (msg->type == PacketType::PauseGame) {
                    // "Início" on the TV: the Jinky app freezes the game, to continue later. The TV
                    // says it thrice; one is enough.
                    const uint32_t pid = game_watch.current_pid();
                    if (pid && pid != last_paused_pid) {
                        last_paused_pid = pid;
                        TCL_INFO("game", "the TV pauses the game (process {})", pid);
                        // Its last picture, for the TV's "paused" card (before it is minimised).
                        tcl::save_pause_snapshot(capture.device(), capture.context(), compositor.desktop(), pid);
                        tcl::emit_event("pause", "\"pid\":" + std::to_string(pid));
                    }
                } else if (msg->type == PacketType::Screenshot) {
                    // The TV says it thrice (UDP): one picture per request burst.
                    if (steady_clock::now() - last_screenshot > 1500ms) {
                        last_screenshot = steady_clock::now();
                        if (!tcl::save_screenshot(capture.device(), capture.context(), compositor.desktop(),
                                                  tcl::SteamGameWatch::running_app_id()))
                            TCL_WARN("capture", "screenshot: no picture to save");
                    }
                } else if (msg->type == PacketType::MouseMode) {
                    // Menu "Mouse" on the TV: the controller drives the PC's mouse until turned off.
                    const bool on = msg->value != 0;
                    if (on != manual_mouse) TCL_INFO("padmouse", "mouse mode {} from the TV", on ? "on" : "off");
                    manual_mouse = on;
                } else if (msg->type == PacketType::CloseGame) {
                    // In a Big Picture session with no game open, "Fechar jogo" closes Big Picture
                    // (the session then ends as usual and the TV goes back to its home screen).
                    if (!game_watch.close_game() && !peer_game && console.active()) {
                        TCL_INFO("game", "no game open: closing Big Picture instead");
                        tcl::ConsoleMode::close_big_picture();
                    }
                } else if (msg->type == PacketType::Bye) {
                    TCL_INFO("session", "receiver left");
                    tcl::emit_event("disconnected", "\"reason\":\"left\"");
                    peer.reset();
                    // A game was just paused: the TV is going to its home screen to pick (or
                    // continue) another. Console mode stays on meanwhile, or every switch would
                    // flip the display 60 -> 240 -> 60 Hz (games stutter) and reopen Big Picture
                    // on top of the game being continued.
                    const bool paused_one = last_paused_pid != 0 && console.active();
                    if (paused_one) {
                        TCL_INFO("console", "game paused: keeping console mode for 2 min for the next game");
                        console_linger_until = steady_clock::now() + 120s;
                    }
                    publish_peer(paused_one);
                    gamepads.unplug_all();
                }
            }
        }
        if (peer && steady_clock::now() - last_peer_msg > 5s) {
            TCL_WARN("session", "receiver {} silent for 5 s, dropping it", peer->to_string());
            tcl::emit_event("disconnected", "\"reason\":\"silent\"");
            // "Pausar jogos ao sair": if the TV doesn't come back, the game is paused, not left
            // running (and heard on the PC's speakers) - unless someone plays it on the PC.
            if (peer_auto_pause && peer_game && last_paused_pid == 0) auto_pause_pid = game_watch.current_pid();
            if (auto_pause_pid) {
                local_player.start();
                auto_pause_at = steady_clock::now() + 90s;
            }
            peer.reset();
            if (console.active()) {
                TCL_INFO("console", "keeping console mode for 90 s in case the TV comes back");
                console_linger_until = steady_clock::now() + 90s;
            }
            publish_peer(true);
            gamepads.unplug_all();
        }
        if (!peer && auto_pause_pid && steady_clock::now() - last_bp_check >= 1s) {
            last_bp_check = steady_clock::now();
            if (local_player.poll()) {
                TCL_INFO("game", "someone is using the PC: the game keeps running");
                auto_pause_pid = 0;
            }
        }
        if (!peer && auto_pause_pid && steady_clock::now() >= auto_pause_at) {
            if (tcl::SteamGameWatch::alive(auto_pause_pid) && !local_player.poll()) {
                // Paused first (the Jinky app minimises it, then freezes it); console mode ends a
                // moment later, once the game is out of the way.
                TCL_INFO("game", "the TV did not come back: pausing the game it left (process {})", auto_pause_pid);
                tcl::save_pause_snapshot(capture.device(), capture.context(), compositor.desktop(), auto_pause_pid);
                tcl::emit_event("pause", "\"pid\":" + std::to_string(auto_pause_pid));
                if (console_linger_until != steady_clock::time_point{}) console_linger_until = steady_clock::now() + 3s;
            }
            auto_pause_pid = 0;
        }
        if (!peer && console_linger_until != steady_clock::time_point{} && steady_clock::now() >= console_linger_until) {
            TCL_INFO("console", "the TV did not come back");
            console_linger_until = {};
            publish_peer();
        }
        // The TV repeats every pad's state every 50 ms; silence means the pad or app is gone.
        gamepads.expire(3s);

        // Console mode: leaving Big Picture ends the session — the TV app closes, the PC goes back
        // to normal, like turning a console off.
        // A game started from the TV's library: reveal the picture once the game is in front, and
        // closing the game ends the session (the TV goes back to its home screen).
        if (peer && launch_ready_pending) {
            const auto now = steady_clock::now();
            bool ready = !console.active() || peer_resume;  // no Big Picture to wait for
            if (!ready && bp_seen_at == steady_clock::time_point{} && tcl::ConsoleMode::big_picture_window_open(true))
                bp_seen_at = now;
            // Big Picture takes the screen as it appears; give it a moment more before the game.
            if (!ready && bp_seen_at != steady_clock::time_point{} && now - bp_seen_at >= 1500ms) ready = true;
            if (!ready && now >= launch_wait_until) {
                TCL_WARN("session", "Big Picture did not show up in 10 s; starting the game anyway");
                ready = true;
            }
            if (ready) {
                launch_ready_pending = false;
                if (peer_game) {
                    TCL_INFO("session", "ready to start the game");
                    game_watch.launched();
                }
                tcl::emit_event("launch_ready");
            }
        }
        if (peer && console.active() && steady_clock::now() - last_bp_check >= 1s) {
            last_bp_check = steady_clock::now();
            const auto game = peer_game ? game_watch.poll() : tcl::SteamGameWatch::Event::None;
            if (game == tcl::SteamGameWatch::Event::Ready || game == tcl::SteamGameWatch::Event::NeedsAttention) {
                const bool attention = game == tcl::SteamGameWatch::Event::NeedsAttention;
                if (!attention) TCL_INFO("game", "game on screen: revealing the picture on the TV");
                for (int i = 0; i < 3; ++i)
                    send_control(*peer, PacketType::GameReady, attention ? tclproto::kGameNeedsAttention : tclproto::kGameOnScreen);
            }
            pad_mouse.set_active(manual_mouse || (peer_game && game_watch.waiting_for_player()));
            const bool bp_closed = !peer_game && console.big_picture_closed();
            if (bp_closed || game == tcl::SteamGameWatch::Event::Closed) {
                TCL_INFO("session", "{}: ending the session", bp_closed ? "Big Picture closed" : "game closed");
                tcl::emit_event("session_end", bp_closed ? "\"reason\":\"big_picture_closed\"" : "\"reason\":\"game_closed\"");
                const uint32_t reason = bp_closed ? tclproto::kEndBigPictureClosed : tclproto::kEndGameClosed;
                for (int i = 0; i < 3; ++i) send_control(*peer, PacketType::SessionEnd, reason);  // UDP: say it thrice
                peer.reset();
                peer_name.clear();
                publish_peer();
                gamepads.unplug_all();
            }
        }

        // Session over (or not a game session): the controller is a controller again.
        if (!peer) manual_mouse = false;
        if (manual_mouse && peer && !pad_mouse.active()) pad_mouse.set_active(true);
        if (pad_mouse.active() && (!peer || (!peer_game && !manual_mouse))) pad_mouse.set_active(false);

        // --- capture ---
        // A fresh desktop frame is encoded as soon as it is captured (waiting for the next 60 Hz
        // tick added 0-16 ms). next_frame is the nominal slot of the next encode: a frame may go
        // out up to `slack` early, and slots advance one interval per encode, so on average the
        // rate never exceeds the target even on a 240 Hz desktop. Static screens and keyframe
        // retries still follow the tick.
        const auto slack = frame_interval / 4;
        const bool send_on_capture = peer && !opt.test_pattern;
        const auto deadline = send_on_capture && dirty ? next_frame - slack : next_frame;
        auto wait = duration_cast<milliseconds>(deadline - steady_clock::now()).count();
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
        switch (opt.test_pattern  ? tcl::DesktopCapture::Result::Timeout
                : capture_reopen ? tcl::DesktopCapture::Result::AccessLost  // HDR on/off: capture again
                                 : capture.acquire(int(std::clamp<long long>(wait, 0, 100)), desktop)) {
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
            if (!capture_reopen) TCL_WARN("capture", "capture lost ({}), reopening", capture.error());
            capture_reopen = false;
            while (!g_quit && !capture.reopen()) std::this_thread::sleep_for(500ms);
            if (capture.width() != converter.src_width() || capture.height() != converter.src_height()) {
                TCL_INFO("capture", "desktop resolution changed to {}x{}", capture.width(), capture.height());
                if (!reopen_encoder("a resolution change")) return 1;
            } else if (converter.hdr() != (capture.hdr() && opt.enc.codec == tcl::VideoCodec::HEVC)) {
                TCL_INFO("capture", "{}", capture.hdr() ? "HDR desktop: streaming HDR10" : "SDR desktop: streaming SDR");
                if (!reopen_encoder(capture.hdr() ? "switching to HDR" : "switching to SDR")) return 1;
            }
            idr_pending = true;
            continue;
        }

        // --- encode + send, paced to the target frame rate ---
        auto now = steady_clock::now();
        if (send_on_capture && dirty && now >= next_frame - slack) {
            next_frame = std::max(next_frame, now - slack) + frame_interval;
        } else {
            if (now < next_frame) continue;
            next_frame += frame_interval;
            if (next_frame < now) next_frame = now + frame_interval;  // fell behind; don't burst
        }

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
            // Log the first failures only; after half a second of them, start the encoder over.
            if (++encode_failures <= 3) TCL_ERROR("encoder", "{}", encoder.error());
            if (encode_failures >= 30) {
                if (!reopen_encoder("repeated encode failures")) return 1;
                idr_pending = true;
            }
            continue;
        }
        encode_failures = 0;
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
                          (opt.enc.codec == tcl::VideoCodec::HEVC ? tclproto::kFlagHevc : 0) |
                          (opt.enc.hdr ? tclproto::kFlagHdr : 0));
        h.session_id = session_id;
        h.frame_id = frame_id++;
        // 0 = repeated old content (heartbeat/keyframe), so the receiver must not count it as latency.
        h.capture_time_us = fresh ? content_time_us : 0;
        // Loss protection: parity fragments right after the data, so the TV rebuilds what Wi-Fi
        // dropped without waiting for a resend. Worked out after the data is on its way.
        const size_t data_fragments = tclproto::fragment_count(bitstream.size());
        h.fec_count = uint8_t(tclproto::fec::parity_for(data_fragments, fec.percent()));
        tclproto::fragment_frame(h, bitstream, [&](std::span<const uint8_t> d) {
            if (!sock.send_to(*peer, d)) TCL_WARN("net", "{}", sock.error());
        });
        if (h.fec_count) {
            tclproto::fec::encode(bitstream, h.fec_count, parity);
            uint8_t pbuf[tclproto::MediaHeader::kSize + tclproto::kMaxPayload];
            for (size_t p = 0; p < h.fec_count; ++p)
                if (const size_t n = tclproto::write_parity(h, bitstream.size(), parity[p], p, pbuf))
                    sock.send_to(*peer, std::span<const uint8_t>(pbuf, n));
            parity_sent += h.fec_count;
        }
        {
            std::lock_guard lock(sent_mu);
            SentFrame& f = sent[h.frame_id % sent.size()];
            f.header = h;
            f.data.assign(bitstream.begin(), bitstream.end());
            f.resends = 0;
        }
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
            const uint64_t resent = packets_resent.exchange(0);
            tcl::emit_event("stats", std::format("\"fps\":{:.1f},\"mbps\":{:.1f},\"encode_ms\":{:.2f},\"target_mbps\":{:.1f},\"resent\":{},\"parity\":{},\"fec_pct\":{}",
                                                 frames_sent / secs, bytes_sent * 8 / secs / 1e6,
                                                 frames_sent ? encode_us_total / 1000.0 / frames_sent : 0.0,
                                                 encoder.bitrate_kbps() / 1000.0, resent, parity_sent, fec.percent()));
            parity_sent = 0;
            if (const uint64_t count = input_trip_count.exchange(0)) {
                const double avg_ms = double(input_trip_sum_us.exchange(0)) / double(count) / 1000.0;
                const double max_ms = double(input_trip_max_us.exchange(0)) / 1000.0;
                TCL_INFO("stats", "controller input from the TV: avg {:.1f} ms, max {:.1f} ms ({} packets)", avg_ms, max_ms, count);
                tcl::emit_event("input", std::format("\"avg_ms\":{:.1f},\"max_ms\":{:.1f}", avg_ms, max_ms));
            }
            TCL_INFO("stats", "{:.1f} fps sent, {:.1f} Mbit/s (target {:.0f}), encode avg {:.2f} max {:.2f} ms, send gap max {:.1f} ms, late sends {}, packets resent {}",
                     frames_sent / secs, bytes_sent * 8 / secs / 1e6, encoder.bitrate_kbps() / 1000.0,
                     frames_sent ? encode_us_total / 1000.0 / frames_sent : 0.0, encode_us_max / 1000.0,
                     send_gap_max_us / 1000.0, late_sends, resent);
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
    rx_thread.request_stop();
    rx_thread.join();
    audio.stop();
    console.leave();
    virtual_display.leave();
    // Jinky is closing: Big Picture, if left open between games, goes too.
    if (opt.keep_big_picture) tcl::ConsoleMode::close_big_picture();
    frame_cap.leave();
    if (opt.mute_pc) tcl::AudioCapture::set_pc_muted(false);  // never leave the PC muted on exit
    gamepads.unplug_all();
    SetEvent(g_cleanup_done);
    if (mmcss) AvRevertMmThreadCharacteristics(mmcss);
    timeEndPeriod(1);
    return 0;
}
