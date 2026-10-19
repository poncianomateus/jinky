// Minimal self-contained tests for the wire format and frame reassembly.
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "tclproto/framing.h"
#include "tclproto/protocol.h"

using namespace tclproto;

static int g_failures = 0;
#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

using Datagram = std::vector<uint8_t>;

static std::vector<Datagram> make_frame(uint32_t id, size_t size, bool key) {
    std::vector<uint8_t> frame(size);
    for (size_t i = 0; i < size; ++i) frame[i] = uint8_t(i * 31 + id);
    MediaHeader h;
    h.frame_id = id;
    h.flags = key ? kFlagKeyframe : 0;
    h.capture_time_us = 1000 + id;
    std::vector<Datagram> out;
    fragment_frame(h, frame, [&](std::span<const uint8_t> d) { out.emplace_back(d.begin(), d.end()); });
    return out;
}

static void feed(FrameAssembler& a, const Datagram& d) {
    auto h = MediaHeader::read(d);
    CHECK(h.has_value());
    if (h) a.push(*h, std::span<const uint8_t>(d).subspan(MediaHeader::kSize));
}

static void test_header_roundtrip() {
    MediaHeader h;
    h.type = PacketType::Video;
    h.flags = kFlagKeyframe;
    h.session_id = 0xDEADBEEF;
    h.frame_id = 42;
    h.frag_index = 2;
    h.frag_count = 3;
    h.frame_size = 3000;
    h.capture_time_us = 0x0123456789ABCDEFull;
    uint8_t buf[MediaHeader::kSize];
    h.write(buf);
    auto r = MediaHeader::read(buf);
    CHECK(r.has_value());
    CHECK(r->session_id == h.session_id && r->frame_id == 42 && r->frag_index == 2);
    CHECK(r->frame_size == 3000 && r->capture_time_us == h.capture_time_us && r->is_keyframe());

    buf[0] ^= 1;  // bad magic
    CHECK(!MediaHeader::read(buf).has_value());
}

static void test_header_rejects_inconsistent() {
    MediaHeader h;
    h.frame_size = 3000;
    h.frag_count = 5;  // should be 3
    uint8_t buf[MediaHeader::kSize];
    h.write(buf);
    CHECK(!MediaHeader::read(buf).has_value());
}

static void test_control_roundtrip() {
    ControlMessage m{PacketType::RequestIdr, 7, 99};
    uint8_t buf[ControlMessage::kSize];
    m.write(buf);
    auto r = ControlMessage::read(buf);
    CHECK(r && r->type == PacketType::RequestIdr && r->session_id == 7 && r->value == 99);
    CHECK(peek_type(buf) == PacketType::RequestIdr);
}

static void test_time_sync_roundtrip() {
    TimeSync t{PacketType::TimeResponse, 3, 0x1122334455667788ull, 0x99AABBCCDDEEFF00ull};
    uint8_t buf[TimeSync::kSize];
    t.write(buf);
    auto r = TimeSync::read(buf);
    CHECK(r && r->type == PacketType::TimeResponse && r->client_send_us == t.client_send_us &&
          r->host_us == t.host_us);
    CHECK(!ControlMessage::read(buf).has_value());  // not mistaken for a control message
}

static void test_input_roundtrip() {
    InputState in;
    in.session_id = 9;
    in.pad_index = 2;
    in.connected = true;
    in.seq = 123456;
    in.buttons = kBtnA | kBtnDpadLeft | kBtnGuide;
    in.left_trigger = 17;
    in.right_trigger = 255;
    in.lx = -32768;
    in.ly = 32767;
    in.rx = -1;
    in.ry = 1000;
    uint8_t buf[InputState::kSize];
    in.write(buf);
    auto r = InputState::read(buf);
    CHECK(r && r->pad_index == 2 && r->connected && r->seq == 123456 && r->buttons == in.buttons);
    CHECK(r->left_trigger == 17 && r->right_trigger == 255);
    CHECK(r->lx == -32768 && r->ly == 32767 && r->rx == -1 && r->ry == 1000);
    CHECK(peek_type(buf) == PacketType::Input);
    buf[6] = 7;  // pad index out of range
    CHECK(!InputState::read(buf).has_value());

    RumbleMessage m{9, 1, 200, 30};
    uint8_t rb[RumbleMessage::kSize];
    m.write(rb);
    auto rm = RumbleMessage::read(rb);
    CHECK(rm && rm->pad_index == 1 && rm->large_motor == 200 && rm->small_motor == 30);
}

static void test_in_order_delivery() {
    std::vector<FrameAssembler::Frame> got;
    FrameAssembler a([&](FrameAssembler::Frame&& f) { got.push_back(std::move(f)); });
    for (auto& d : make_frame(0, 5000, true)) feed(a, d);
    for (auto& d : make_frame(1, 1200, false)) feed(a, d);  // exactly one full fragment
    for (auto& d : make_frame(2, 1, false)) feed(a, d);
    CHECK(got.size() == 3);
    CHECK(got[0].keyframe && got[0].data.size() == 5000 && got[0].data[4999] == uint8_t(4999 * 31));
    CHECK(!a.waiting_for_keyframe());
}

static void test_reordered_fragments_and_duplicates() {
    std::vector<FrameAssembler::Frame> got;
    FrameAssembler a([&](FrameAssembler::Frame&& f) { got.push_back(std::move(f)); });
    auto frags = make_frame(0, 10000, true);
    std::mt19937 rng(1);
    std::shuffle(frags.begin(), frags.end(), rng);
    feed(a, frags[0]);
    for (auto& d : frags) feed(a, d);
    CHECK(got.size() == 1);
    CHECK(a.stats().packets_duplicate == 1);
}

static void test_waits_for_first_keyframe() {
    int delivered = 0;
    FrameAssembler a([&](FrameAssembler::Frame&&) { ++delivered; });
    for (auto& d : make_frame(0, 2000, false)) feed(a, d);
    CHECK(delivered == 0 && a.waiting_for_keyframe());
    for (auto& d : make_frame(1, 2000, true)) feed(a, d);
    CHECK(delivered == 1 && !a.waiting_for_keyframe());
}

static void test_loss_blocks_until_keyframe() {
    int delivered = 0;
    FrameAssembler a([&](FrameAssembler::Frame&&) { ++delivered; });
    for (auto& d : make_frame(0, 3000, true)) feed(a, d);
    auto f1 = make_frame(1, 3000, false);
    feed(a, f1[0]);  // frame 1 partially lost
    for (auto& d : make_frame(2, 3000, false)) feed(a, d);
    CHECK(delivered == 1);
    CHECK(a.waiting_for_keyframe());
    CHECK(a.stats().frames_lost == 1 && a.stats().frames_discarded == 1);

    feed(a, f1[1]);  // late fragment of abandoned frame
    CHECK(a.stats().packets_stale == 1);

    for (auto& d : make_frame(3, 3000, true)) feed(a, d);
    CHECK(delivered == 2 && !a.waiting_for_keyframe());
}

static void test_sequence_wraparound() {
    CHECK(seq_newer(0, 0xFFFFFFFFu));
    CHECK(!seq_newer(0xFFFFFFFFu, 0));
    int delivered = 0;
    FrameAssembler a([&](FrameAssembler::Frame&&) { ++delivered; });
    for (auto& d : make_frame(0xFFFFFFFFu, 100, true)) feed(a, d);
    for (auto& d : make_frame(0, 100, false)) feed(a, d);
    CHECK(delivered == 2 && a.stats().frames_lost == 0);
}

int main() {
    test_header_roundtrip();
    test_header_rejects_inconsistent();
    test_control_roundtrip();
    test_time_sync_roundtrip();
    test_input_roundtrip();
    test_in_order_delivery();
    test_reordered_fragments_and_duplicates();
    test_waits_for_first_keyframe();
    test_loss_blocks_until_keyframe();
    test_sequence_wraparound();
    if (g_failures) {
        std::fprintf(stderr, "%d check(s) failed\n", g_failures);
        return EXIT_FAILURE;
    }
    std::puts("all tclproto tests passed");
    return EXIT_SUCCESS;
}
