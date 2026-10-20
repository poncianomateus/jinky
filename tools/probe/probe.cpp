// tclstream-probe — pretends to be a TV on the same PC: says Hello to the host, reassembles
// the stream and checks the H.264 bitstream (first frame must be an IDR with SPS/PPS).
// Usage: tclstream-probe [host-ip] [seconds] [out.h264]
//        tclstream-probe --input-test   (sends a fake gamepad and checks it via XInput)
#include <windows.h>
#include <xinput.h>

#include <chrono>
#include <thread>
#include <cstdio>
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
    // --takeover (last argument): ask for the session like a TV the user just opened.
    bool takeover = false;
    if (argc > 1 && std::string(argv[argc - 1]) == "--takeover") {
        takeover = true;
        --argc;
    }
    const std::string host_ip = argc > 1 ? argv[1] : "127.0.0.1";
    const int seconds = argc > 2 ? std::atoi(argv[2]) : 5;
    FILE* out = argc > 3 ? std::fopen(argv[3], "wb") : nullptr;

    auto host = Endpoint::parse(host_ip, kDefaultMediaPort);
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
        if (n <= 0) continue;
        auto h = MediaHeader::read(std::span<const uint8_t>(buf, size_t(n)));
        if (h && h->type == PacketType::Video) assembler.push(*h, std::span<const uint8_t>(buf + MediaHeader::kSize, size_t(n) - MediaHeader::kSize));
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
    const bool ok = first_ok && frames > 0;
    std::puts(ok ? "PROBE OK" : "PROBE FAILED");
    return ok ? 0 : 1;
}
