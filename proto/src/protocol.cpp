#include "tclproto/protocol.h"

#include <algorithm>
#include <cstring>

#include "tclproto/crypto.h"

namespace tclproto {
namespace {

void put_u16(uint8_t* p, uint16_t v) {
    p[0] = uint8_t(v);
    p[1] = uint8_t(v >> 8);
}
void put_u32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i));
}
void put_u64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = uint8_t(v >> (8 * i));
}
uint16_t get_u16(const uint8_t* p) { return uint16_t(p[0] | (p[1] << 8)); }
uint32_t get_u32(const uint8_t* p) {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= uint32_t(p[i]) << (8 * i);
    return v;
}
uint64_t get_u64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= uint64_t(p[i]) << (8 * i);
    return v;
}

bool is_media(PacketType t) { return t == PacketType::Video || t == PacketType::Audio; }
bool is_control(PacketType t) {
    return t == PacketType::Hello || t == PacketType::RequestIdr || t == PacketType::Stats ||
           t == PacketType::Bye || t == PacketType::LatencyReport || t == PacketType::Displaced ||
           t == PacketType::SessionEnd || t == PacketType::Discover || t == PacketType::GameReady ||
           t == PacketType::CloseGame || t == PacketType::NetReport || t == PacketType::MouseMode ||
           t == PacketType::Screenshot;
}

// Upper bound on a single encoded frame; anything larger is treated as corrupt.
constexpr uint32_t kMaxFrameSize = 16u * 1024 * 1024;

}  // namespace

// Layout: magic(4) version(1) type(1) flags(1) reserved(1) session(4) frame_id(4)
//         frag_index(2) frag_count(2) frame_size(4) capture_time_us(8) = 32 bytes
void MediaHeader::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(type);
    p[6] = flags;
    p[7] = 0;
    put_u32(p + 8, session_id);
    put_u32(p + 12, frame_id);
    put_u16(p + 16, frag_index);
    put_u16(p + 18, frag_count);
    put_u32(p + 20, frame_size);
    put_u64(p + 24, capture_time_us);
}

std::optional<MediaHeader> MediaHeader::read(std::span<const uint8_t> in) {
    if (in.size() < kSize) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion) return std::nullopt;
    MediaHeader h;
    h.type = PacketType(p[5]);
    if (!is_media(h.type)) return std::nullopt;
    h.flags = p[6];
    h.session_id = get_u32(p + 8);
    h.frame_id = get_u32(p + 12);
    h.frag_index = get_u16(p + 16);
    h.frag_count = get_u16(p + 18);
    h.frame_size = get_u32(p + 20);
    h.capture_time_us = get_u64(p + 24);
    if (h.frag_count == 0 || h.frag_index >= h.frag_count) return std::nullopt;
    if (h.frame_size == 0 || h.frame_size > kMaxFrameSize) return std::nullopt;
    // Fragment count must match what the sender would produce for this frame size.
    const uint32_t expected = (h.frame_size + kMaxPayload - 1) / kMaxPayload;
    if (h.frag_count != expected) return std::nullopt;
    return h;
}

// Layout: magic(4) version(1) type(1) reserved(2) session(4) value(4) = 16 bytes
void ControlMessage::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(type);
    p[6] = 0;
    p[7] = 0;
    put_u32(p + 8, session_id);
    put_u32(p + 12, value);
}

std::optional<ControlMessage> ControlMessage::read(std::span<const uint8_t> in) {
    if (in.size() < kSize) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion) return std::nullopt;
    ControlMessage m;
    m.type = PacketType(p[5]);
    if (!is_control(m.type)) return std::nullopt;
    m.session_id = get_u32(p + 8);
    m.value = get_u32(p + 12);
    return m;
}

// Layout: magic(4) version(1) type(1) reserved(2) session(4) reserved(4) client_send_us(8) host_us(8)
void TimeSync::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(type);
    p[6] = 0;
    p[7] = 0;
    put_u32(p + 8, session_id);
    put_u32(p + 12, 0);
    put_u64(p + 16, client_send_us);
    put_u64(p + 24, host_us);
}

std::optional<TimeSync> TimeSync::read(std::span<const uint8_t> in) {
    if (in.size() < kSize) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion) return std::nullopt;
    TimeSync t;
    t.type = PacketType(p[5]);
    if (t.type != PacketType::TimeRequest && t.type != PacketType::TimeResponse) return std::nullopt;
    t.session_id = get_u32(p + 8);
    t.client_send_us = get_u64(p + 16);
    t.host_us = get_u64(p + 24);
    return t;
}

// Layout: magic(4) version(1) type(1) pad(1) flags(1) session(4) seq(4) buttons(2) lt(1) rt(1)
//         lx(2) ly(2) rx(2) ry(2) reserved(4) = 32 bytes
void InputState::write(std::span<uint8_t> out, std::span<const uint8_t> key) const {
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::Input);
    p[6] = pad_index;
    p[7] = connected ? 1 : 0;
    put_u32(p + 8, session_id);
    put_u32(p + 12, seq);
    put_u16(p + 16, buttons);
    p[18] = left_trigger;
    p[19] = right_trigger;
    put_u16(p + 20, uint16_t(lx));
    put_u16(p + 22, uint16_t(ly));
    put_u16(p + 24, uint16_t(rx));
    put_u16(p + 26, uint16_t(ry));
    put_u32(p + 28, sent_us);
    const Mac m = key.empty() ? Mac{} : mac(key, {p, 32});
    std::memcpy(p + 32, m.data(), kMacSize);
}

bool InputState::verify(std::span<const uint8_t> in, std::span<const uint8_t> key) {
    if (in.size() < kSize) return false;
    const Mac m = mac(key, in.first(32));
    return mac_equal(m, in.subspan(32, kMacSize));
}

std::optional<InputState> InputState::read(std::span<const uint8_t> in) {
    if (in.size() < kSize) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::Input)) return std::nullopt;
    InputState s;
    s.pad_index = p[6];
    if (s.pad_index > 3) return std::nullopt;
    s.connected = p[7] & 1;
    s.session_id = get_u32(p + 8);
    s.seq = get_u32(p + 12);
    s.buttons = get_u16(p + 16);
    s.left_trigger = p[18];
    s.right_trigger = p[19];
    s.lx = int16_t(get_u16(p + 20));
    s.ly = int16_t(get_u16(p + 22));
    s.rx = int16_t(get_u16(p + 24));
    s.ry = int16_t(get_u16(p + 26));
    s.sent_us = get_u32(p + 28);
    return s;
}

// Layout: magic(4) version(1) type(1) pad(1) reserved(1) session(4) large(1) small(1) reserved(2)
void RumbleMessage::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::Rumble);
    p[6] = pad_index;
    p[7] = 0;
    put_u32(p + 8, session_id);
    p[12] = large_motor;
    p[13] = small_motor;
    p[14] = p[15] = 0;
}

std::optional<RumbleMessage> RumbleMessage::read(std::span<const uint8_t> in) {
    if (in.size() < kSize) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::Rumble)) return std::nullopt;
    RumbleMessage m;
    m.pad_index = p[6];
    if (m.pad_index > 3) return std::nullopt;
    m.session_id = get_u32(p + 8);
    m.large_motor = p[12];
    m.small_motor = p[13];
    return m;
}

namespace {
size_t utf8_cut(const std::string& s, size_t max) {
    size_t len = std::min(s.size(), max);
    while (len > 0 && len < s.size() && (uint8_t(s[len]) & 0xC0) == 0x80) --len;
    return len;
}
}  // namespace

size_t HostInfo::write(std::span<uint8_t> out) const {
    const size_t nlen = utf8_cut(name, kMaxText), blen = utf8_cut(busy_with, kMaxText);
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::HostInfo);
    p[6] = state;
    p[7] = uint8_t(nlen);
    for (int i = 0; i < 8; ++i) p[8 + i] = uint8_t(host_id >> (8 * i));
    std::memcpy(p + 16, mac.data(), 6);
    p[22] = uint8_t(blen);
    p[23] = flags;
    std::memcpy(p + kHeader, name.data(), nlen);
    std::memcpy(p + kHeader + nlen, busy_with.data(), blen);
    uint8_t* t = p + kHeader + nlen + blen;
    put_u32(t, tv_version_code);
    put_u16(t + 4, update_port);
    return kHeader + nlen + blen + 6;
}

std::optional<HostInfo> HostInfo::read(std::span<const uint8_t> in) {
    if (in.size() < kHeader) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::HostInfo)) return std::nullopt;
    const size_t nlen = p[7], blen = p[22];
    if (nlen > kMaxText || blen > kMaxText || in.size() < kHeader + nlen + blen) return std::nullopt;
    HostInfo h;
    h.state = p[6] == kBusy ? kBusy : kIdle;
    h.flags = p[23];
    for (int i = 0; i < 8; ++i) h.host_id |= uint64_t(p[8 + i]) << (8 * i);
    std::memcpy(h.mac.data(), p + 16, 6);
    h.name.assign(reinterpret_cast<const char*>(p + kHeader), nlen);
    h.busy_with.assign(reinterpret_cast<const char*>(p + kHeader + nlen), blen);
    if (in.size() >= kHeader + nlen + blen + 6) {
        const uint8_t* t = p + kHeader + nlen + blen;
        h.tv_version_code = get_u32(t);
        h.update_port = get_u16(t + 4);
    }
    return h;
}

size_t NameMessage::write(std::span<uint8_t> out) const {
    // Cut at kMaxName bytes without splitting a UTF-8 sequence.
    size_t len = std::min(name.size(), kMaxName);
    while (len > 0 && len < name.size() && (uint8_t(name[len]) & 0xC0) == 0x80) --len;
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::DeviceName);
    p[6] = uint8_t(len);
    p[7] = 0;
    put_u32(p + 8, session_id);
    std::memcpy(p + kHeader, name.data(), len);
    return kHeader + len;
}

std::optional<NameMessage> NameMessage::read(std::span<const uint8_t> in) {
    if (in.size() < kHeader) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::DeviceName)) return std::nullopt;
    const size_t len = p[6];
    if (len > kMaxName || in.size() < kHeader + len) return std::nullopt;
    NameMessage m;
    m.session_id = get_u32(p + 8);
    m.name.assign(reinterpret_cast<const char*>(p + kHeader), len);
    return m;
}

// Layout: magic(4) version(1) type(1) pad(1) flags(1) session(4) seq(4)
//         2 x touch [id(1) down(1) x(2) y(2)] gyro(3 x 2) accel(3 x 2) = 40 bytes
void PadExtras::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::PadExtras);
    p[6] = pad_index;
    p[7] = flags;
    put_u32(p + 8, session_id);
    put_u32(p + 12, seq);
    uint8_t* q = p + 16;
    for (const Touch& t : touch) {
        q[0] = t.id;
        q[1] = t.down ? 1 : 0;
        q[2] = uint8_t(t.x);
        q[3] = uint8_t(t.x >> 8);
        q[4] = uint8_t(t.y);
        q[5] = uint8_t(t.y >> 8);
        q += 6;
    }
    for (int16_t v : gyro) { q[0] = uint8_t(v); q[1] = uint8_t(uint16_t(v) >> 8); q += 2; }
    for (int16_t v : accel) { q[0] = uint8_t(v); q[1] = uint8_t(uint16_t(v) >> 8); q += 2; }
}

std::optional<PadExtras> PadExtras::read(std::span<const uint8_t> in) {
    if (in.size() < kSize) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::PadExtras)) return std::nullopt;
    PadExtras e;
    e.pad_index = p[6];
    e.flags = p[7];
    e.session_id = get_u32(p + 8);
    e.seq = get_u32(p + 12);
    const uint8_t* q = p + 16;
    for (Touch& t : e.touch) {
        t.id = q[0];
        t.down = q[1] != 0;
        t.x = uint16_t(q[2] | q[3] << 8);
        t.y = uint16_t(q[4] | q[5] << 8);
        q += 6;
    }
    for (int16_t& v : e.gyro) { v = int16_t(uint16_t(q[0] | q[1] << 8)); q += 2; }
    for (int16_t& v : e.accel) { v = int16_t(uint16_t(q[0] | q[1] << 8)); q += 2; }
    return e;
}

// Layout: magic(4) version(1) type(1) length(1) flags(1) session(4) seq(4) text
size_t TextMessage::write(std::span<uint8_t> out) const {
    size_t len = std::min(text.size(), kMaxText);
    while (len > 0 && len < text.size() && (uint8_t(text[len]) & 0xC0) == 0x80) --len;
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::TypeText);
    p[6] = uint8_t(len);
    p[7] = flags;
    put_u32(p + 8, session_id);
    put_u32(p + 12, seq);
    std::memcpy(p + kHeader, text.data(), len);
    return kHeader + len;
}

std::optional<TextMessage> TextMessage::read(std::span<const uint8_t> in) {
    if (in.size() < kHeader) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::TypeText)) return std::nullopt;
    const size_t len = p[6];
    if (len > kMaxText || in.size() < kHeader + len) return std::nullopt;
    TextMessage m;
    m.flags = p[7];
    m.session_id = get_u32(p + 8);
    m.seq = get_u32(p + 12);
    m.text.assign(reinterpret_cast<const char*>(p + kHeader), len);
    return m;
}

uint32_t NetReport::pack() const {
    return std::min<uint32_t>(lost_permille, 1000) | std::min<uint32_t>(frames_unrecovered, 255) << 10 |
           std::min<uint32_t>(packets / 4, 0x3FFF) << 18;
}

NetReport NetReport::unpack(uint32_t value) {
    return NetReport{value & 0x3FF, (value >> 10) & 0xFF, (value >> 18) * 4};
}

size_t NackMessage::write(std::span<uint8_t> out) const {
    const size_t count = std::min(ranges.size(), kMaxRanges);
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(PacketType::Nack);
    p[6] = uint8_t(count);
    p[7] = 0;
    put_u32(p + 8, session_id);
    put_u32(p + 12, frame_id);
    for (size_t i = 0; i < count; ++i) {
        put_u16(p + kHeader + i * 4, ranges[i].first);
        put_u16(p + kHeader + i * 4 + 2, ranges[i].count);
    }
    return kHeader + count * 4;
}

std::optional<NackMessage> NackMessage::read(std::span<const uint8_t> in) {
    if (in.size() < kHeader) return std::nullopt;
    const uint8_t* p = in.data();
    if (get_u32(p) != kMagic || p[4] != kVersion || p[5] != uint8_t(PacketType::Nack)) return std::nullopt;
    const size_t count = p[6];
    if (count > kMaxRanges || in.size() < kHeader + count * 4) return std::nullopt;
    NackMessage m;
    m.session_id = get_u32(p + 8);
    m.frame_id = get_u32(p + 12);
    m.ranges.resize(count);
    for (size_t i = 0; i < count; ++i)
        m.ranges[i] = {get_u16(p + kHeader + i * 4), get_u16(p + kHeader + i * 4 + 2)};
    return m;
}

std::optional<PacketType> peek_type(std::span<const uint8_t> in) {
    if (in.size() < 6) return std::nullopt;
    if (get_u32(in.data()) != kMagic || in[4] != kVersion) return std::nullopt;
    return PacketType(in[5]);
}

}  // namespace tclproto
