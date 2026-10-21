// Minimal self-contained tests for the wire format and frame reassembly.
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "tclproto/auth.h"
#include "tclproto/crypto.h"
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

static void test_name_and_session_messages() {
    NameMessage n{5, "Sala · Fire TV"};
    uint8_t buf[NameMessage::kHeader + NameMessage::kMaxName];
    const size_t len = n.write(buf);
    auto r = NameMessage::read(std::span<const uint8_t>(buf, len));
    CHECK(r && r->session_id == 5 && r->name == "Sala · Fire TV");
    CHECK(peek_type(std::span<const uint8_t>(buf, len)) == PacketType::DeviceName);

    NameMessage long_name{1, std::string(100, 'x') + "é"};
    CHECK(long_name.write(buf) == NameMessage::kHeader + NameMessage::kMaxName);

    // Every host<->TV control message must survive a round trip (a type left out of the
    // parser is silently dropped by the other side).
    for (auto t : {PacketType::Displaced, PacketType::SessionEnd, PacketType::GameReady, PacketType::CloseGame}) {
        uint8_t c[ControlMessage::kSize];
        ControlMessage{t, 3, 1}.write(c);
        auto m = ControlMessage::read(c);
        CHECK(m && m->type == t && m->value == 1);
    }
}

static std::string hex(std::span<const uint8_t> d) {
    static const char* x = "0123456789abcdef";
    std::string s;
    for (uint8_t b : d) { s += x[b >> 4]; s += x[b & 15]; }
    return s;
}

static std::span<const uint8_t> bytes(const std::string& s) {
    return {reinterpret_cast<const uint8_t*>(s.data()), s.size()};
}

static void test_sha256_and_hmac_vectors() {
    // FIPS 180-4 examples
    CHECK(hex(Sha256::hash(bytes(""))) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    CHECK(hex(Sha256::hash(bytes("abc"))) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    CHECK(hex(Sha256::hash(bytes("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))) ==
          "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    Sha256 million;  // one million 'a', fed in odd-sized chunks to exercise buffering
    const std::string chunk(997, 'a');
    size_t left = 1000000;
    while (left) {
        const size_t n = std::min(left, chunk.size());
        million.update(bytes(chunk).first(n));
        left -= n;
    }
    CHECK(hex(million.finish()) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");

    // RFC 4231 test cases 1, 2 and 6
    CHECK(hex(hmac_sha256(bytes(std::string(20, '\x0b')), bytes("Hi There"))) ==
          "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    CHECK(hex(hmac_sha256(bytes("Jefe"), bytes("what do ya want for nothing?"))) ==
          "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    CHECK(hex(hmac_sha256(bytes(std::string(131, '\xaa')), bytes("Test Using Larger Than Block-Size Key - Hash Key First"))) ==
          "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");

    const auto m1 = mac(bytes("key"), bytes("msg"));
    auto m2 = m1;
    CHECK(mac_equal(m1, m2));
    m2[15] ^= 1;
    CHECK(!mac_equal(m1, m2));
}

static void test_pairing_and_auth() {
    // Both sides derive the same key and code from the same inputs; any difference changes both.
    PublicKey tv{}, pc{};
    tv[0] = pc[0] = 0x04;
    for (int i = 1; i < 65; ++i) { tv[i] = uint8_t(i); pc[i] = uint8_t(200 - i); }
    uint8_t secret[32];
    for (int i = 0; i < 32; ++i) secret[i] = uint8_t(i * 7);
    const PairKey k1 = derive_pair_key(secret, tv, pc);
    const PairKey k2 = derive_pair_key(secret, tv, pc);
    CHECK(k1 == k2 && pairing_code(k1) == pairing_code(k2) && pairing_code(k1).size() == 6);
    secret[0] ^= 1;  // a man in the middle ends up with a different secret
    CHECK(derive_pair_key(secret, tv, pc) != k1);

    // Signed hello: verifies with the right key, fails with another or when tampered.
    AuthHello h{0x1122334455667788ull, 1760000000000, kCodecHevc | kHelloTakeover};
    uint8_t buf[AuthHello::kSize];
    h.write(buf, k1);
    auto r = AuthHello::read(buf);
    CHECK(r && r->device_id == h.device_id && r->nonce == h.nonce && r->flags == h.flags);
    CHECK(AuthHello::verify(buf, k1));
    PairKey other = k1;
    other[5] ^= 1;
    CHECK(!AuthHello::verify(buf, other));
    buf[24] ^= 1;  // flip a flag bit
    CHECK(!AuthHello::verify(buf, k1));

    // Signed controller state.
    InputState in;
    in.buttons = kBtnA;
    in.seq = 9;
    uint8_t ib[InputState::kSize];
    in.write(ib, k1);
    CHECK(InputState::verify(ib, k1) && !InputState::verify(ib, other));
    ib[17] ^= kBtnB >> 8;  // forge a button (B lives in the high byte of the bitmask)
    CHECK(!InputState::verify(ib, k1));

    // Pairing messages round-trip.
    PairRequest pr{42, tv, "Sala"};
    uint8_t pb[PairRequest::kHeader + PairRequest::kMaxName];
    const size_t plen = pr.write(pb);
    auto prr = PairRequest::read(std::span<const uint8_t>(pb, plen));
    CHECK(prr && prr->device_id == 42 && prr->tv_public == tv && prr->name == "Sala");
    PairChallenge pc2{7, 42, pc};
    uint8_t cb[PairChallenge::kSize];
    pc2.write(cb);
    auto pcr = PairChallenge::read(cb);
    CHECK(pcr && pcr->host_id == 7 && pcr->device_id == 42 && pcr->pc_public == pc);
    PairResult res{7, 42, true, accept_proof(k1, 42)};
    uint8_t rb[PairResult::kSize];
    res.write(rb);
    auto rr = PairResult::read(rb);
    CHECK(rr && rr->accepted && rr->proof == accept_proof(k1, 42) && rr->proof != accept_proof(k1, 43));
}

static void test_host_info() {
    HostInfo h;
    h.host_id = 0xAABBCCDDEEFF0011ull;
    h.state = HostInfo::kBusy;
    h.mac = {0xDC, 0x71, 0x96, 0x6A, 0x45, 0x28};
    h.name = "DESKTOP-BF21OEI";
    h.busy_with = "TCL 55P7K";
    h.tv_version_code = 300;
    h.update_port = 47101;
    uint8_t buf[HostInfo::kHeader + 2 * HostInfo::kMaxText + 6];
    const size_t len = h.write(buf);
    auto r = HostInfo::read(std::span<const uint8_t>(buf, len));
    CHECK(r && r->host_id == h.host_id && r->state == HostInfo::kBusy && r->mac == h.mac);
    CHECK(r->name == "DESKTOP-BF21OEI" && r->busy_with == "TCL 55P7K");
    CHECK(r->tv_version_code == 300 && r->update_port == 47101);
    // A message from an older PC (no update fields) still parses, with no update offered.
    auto old = HostInfo::read(std::span<const uint8_t>(buf, len - 6));
    CHECK(old && old->name == "DESKTOP-BF21OEI" && old->tv_version_code == 0);
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
    test_sha256_and_hmac_vectors();
    test_pairing_and_auth();
    test_input_roundtrip();
    test_host_info();
    test_name_and_session_messages();
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
