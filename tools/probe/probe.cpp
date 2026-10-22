// tclstream-probe — pretends to be a TV on the same PC: says Hello to the host, reassembles
// the stream and checks the H.264 bitstream (first frame must be an IDR with SPS/PPS).
// Usage: tclstream-probe [host-ip] [seconds] [out.h264]
//        tclstream-probe --input-test   (sends a fake gamepad and checks it via XInput)
#include <windows.h>
#include <xinput.h>

#include <chrono>
#include <thread>
#include <cstdio>
#include <random>
#include <cstdlib>
#include <string>

#include "tclproto/framing.h"
#include "tclproto/protocol.h"
#include "tclproto/udp.h"

using namespace std::chrono;
using namespace tclproto;

namespace {

// Returns a bitmask of NAL unit types present in an Annex-B access unit.
uint32_t nal_types(const std::vector<uint8_t>& au) {
    uint32_t mask = 0;
    for (size_t i = 0; i + 3 < au.size(); ++i) {
        if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) {
            mask |= 1u << (au[i + 3] & 0x1F);
            i += 2;
        }
    }
    return mask;
}

}  // namespace

// Sends "A + left stick right" as pad 0, then reads it back through XInput like a game would.
int input_test() {
    auto host = Endpoint::parse("127.0.0.1", kDefaultMediaPort);
    UdpSocket sock;
    if (!sock.open(0)) return 1;
    uint8_t hello[ControlMessage::kSize];
    ControlMessage{PacketType::Hello, 0, 0}.write(hello);
    sock.send_to(*host, hello);
    std::this_thread::sleep_for(300ms);

    InputState in;
    in.buttons = kBtnA;
    in.lx = 20000;
    in.right_trigger = 200;
    bool seen = false;
    for (int i = 0; i < 30 && !seen; ++i) {
        in.seq++;
        uint8_t buf[InputState::kSize];
        in.write(buf);
        sock.send_to(*host, buf);
        sock.send_to(*host, hello);
        std::this_thread::sleep_for(50ms);
        for (DWORD u = 0; u < XUSER_MAX_COUNT; ++u) {
            XINPUT_STATE xs{};
            if (XInputGetState(u, &xs) == ERROR_SUCCESS && (xs.Gamepad.wButtons & XINPUT_GAMEPAD_A)) {
                std::printf("XInput user %lu: buttons=0x%04x LX=%d RT=%d\n", u, xs.Gamepad.wButtons,
                            xs.Gamepad.sThumbLX, xs.Gamepad.bRightTrigger);
                seen = xs.Gamepad.sThumbLX == 20000 && xs.Gamepad.bRightTrigger == 200;
            }
        }
    }
    in.connected = false;
    in.seq++;
    uint8_t buf[InputState::kSize];
    in.write(buf);
    sock.send_to(*host, buf);
    uint8_t bye[ControlMessage::kSize];
    ControlMessage{PacketType::Bye, 0, 0}.write(bye);
    sock.send_to(*host, bye);
    std::puts(seen ? "INPUT TEST OK" : "INPUT TEST FAILED");
    return seen ? 0 : 1;
}

int main(int argc, char** argv) {
    if (argc > 1 && std::string(argv[1]) == "--input-test") return input_test();
    // --takeover: ask for the session like a TV the user just opened.
    // --port N: the engine's port. --loss P: drop P% of video packets on purpose; the probe then
    // asks for them again like a TV does, to exercise resends.
    bool takeover = false;
    uint16_t port = kDefaultMediaPort;
    double loss = 0;
    std::vector<std::string> args;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--takeover") takeover = true;
        else if (a == "--port" && i + 1 < argc) port = uint16_t(std::atoi(argv[++i]));
        else if (a == "--loss" && i + 1 < argc) loss = std::atof(argv[++i]) / 100.0;
        else args.push_back(a);
    }
    const std::string host_ip = args.size() > 0 ? args[0] : "127.0.0.1";
    const int seconds = args.size() > 1 ? std::atoi(args[1].c_str()) : 5;
    FILE* out = args.size() > 2 ? std::fopen(args[2].c_str(), "wb") : nullptr;
    std::mt19937 rng(7);
    std::uniform_real_distribution<double> dice(0, 1);
    uint64_t dropped = 0;
    uint32_t session = 0;
    auto last_idr = steady_clock::now(), last_report = steady_clock::now();
    uint64_t rep_packets = 0, rep_missing = 0, rep_lost = 0;

    auto host = Endpoint::parse(host_ip, port);
    UdpSocket sock;
    if (!host || !sock.open(0)) {
        std::fprintf(stderr, "setup failed: %s\n", sock.error().c_str());
        return 1;
    }

    uint64_t frames = 0, keyframes = 0, bytes = 0;
    bool first_ok = false, first_seen = false;
    FrameAssembler assembler([&](FrameAssembler::Frame&& f) {
        const uint32_t types = nal_types(f.data);
        if (!first_seen) {
            first_seen = true;
            // 7 = SPS, 8 = PPS, 5 = IDR slice
            first_ok = f.keyframe && (types & (1u << 7)) && (types & (1u << 8)) && (types & (1u << 5));
            std::printf("first frame: id=%u keyframe=%d SPS=%d PPS=%d IDR=%d size=%zu\n", f.frame_id, f.keyframe,
                        !!(types & (1u << 7)), !!(types & (1u << 8)), !!(types & (1u << 5)), f.data.size());
        }
        frames++;
        keyframes += f.keyframe;
        bytes += f.data.size();
        if (out) std::fwrite(f.data.data(), 1, f.data.size(), out);
    });

    uint8_t buf[2048], hello[ControlMessage::kSize];
    ControlMessage{PacketType::Hello, 0, takeover ? kHelloTakeover : 0u}.write(hello);
    const auto end = steady_clock::now() + seconds * 1s;
    auto last_hello = steady_clock::now() - 2s;
    while (steady_clock::now() < end) {
        if (steady_clock::now() - last_hello >= 1s) {
            sock.send_to(*host, hello);
            last_hello = steady_clock::now();
        }
        Endpoint from;
        const int n = sock.recv_from(buf, from, 20);
        const int64_t now = duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
        for (const auto& nk : assembler.poll(now)) {
            uint8_t nb[NackMessage::kMaxSize];
            sock.send_to(*host, std::span<const uint8_t>(nb, NackMessage{session, nk.frame_id, nk.ranges}.write(nb)));
        }
        if (steady_clock::now() - last_report >= 1s) {
            // Like a TV: what share of the video had to be asked for again (automatic quality).
            const auto& st = assembler.stats();
            const uint64_t pk = st.packets_received - rep_packets, miss = st.packets_missing - rep_missing;
            NetReport r{pk ? uint32_t(miss * 1000 / (pk + miss)) : 0, uint32_t(st.frames_lost - rep_lost), uint32_t(pk)};
            rep_packets = st.packets_received;
            rep_missing = st.packets_missing;
            rep_lost = st.frames_lost;
            uint8_t rb[ControlMessage::kSize];
            ControlMessage{PacketType::NetReport, session, r.pack()}.write(rb);
            sock.send_to(*host, rb);
            last_report = steady_clock::now();
        }
        if (assembler.waiting_for_keyframe() && steady_clock::now() - last_idr > 100ms) {
            uint8_t idr[ControlMessage::kSize];
            ControlMessage{PacketType::RequestIdr, session, 0}.write(idr);
            sock.send_to(*host, idr);
            last_idr = steady_clock::now();
        }
        if (n <= 0) continue;
        auto h = MediaHeader::read(std::span<const uint8_t>(buf, size_t(n)));
        if (h && h->type == PacketType::Video) {
            session = h->session_id;
            if (loss > 0 && dice(rng) < loss) {
                dropped++;
                continue;
            }
            assembler.push(*h, std::span<const uint8_t>(buf + MediaHeader::kSize, size_t(n) - MediaHeader::kSize), now);
        }
    }
    uint8_t bye[ControlMessage::kSize];
    ControlMessage{PacketType::Bye, 0, 0}.write(bye);
    sock.send_to(*host, bye);
    if (out) std::fclose(out);

    const auto& s = assembler.stats();
    std::printf("%llu frames (%llu keyframes), %.1f fps, %.1f Mbit/s, lost %llu, packets %llu\n",
                (unsigned long long)frames, (unsigned long long)keyframes, double(frames) / seconds,
                bytes * 8.0 / seconds / 1e6, (unsigned long long)s.frames_lost,
                (unsigned long long)s.packets_received);
    if (loss > 0)
        std::printf("dropped on purpose %llu, asked again %llu, frames saved by a resend %llu\n",
                    (unsigned long long)dropped, (unsigned long long)s.packets_missing,
                    (unsigned long long)s.frames_recovered);
    const bool ok = first_ok && frames > 0;
    std::puts(ok ? "PROBE OK" : "PROBE FAILED");
    return ok ? 0 : 1;
}
