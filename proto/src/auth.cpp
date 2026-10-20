#include "tclproto/auth.h"

#include <algorithm>
#include <cstring>

#include "tclproto/protocol.h"

namespace tclproto {

namespace {

void put_u32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i));
}
void put_u64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = uint8_t(v >> (8 * i));
}
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

void header(uint8_t* p, PacketType type) {
    put_u32(p, kMagic);
    p[4] = kVersion;
    p[5] = uint8_t(type);
    p[6] = p[7] = 0;
}

bool check(std::span<const uint8_t> in, size_t size, PacketType type) {
    return in.size() >= size && get_u32(in.data()) == kMagic && in[4] == kVersion && in[5] == uint8_t(type);
}

std::span<const uint8_t> str(const char* s) { return {reinterpret_cast<const uint8_t*>(s), std::strlen(s)}; }

}  // namespace

void AuthRequired::write(std::span<uint8_t> out) const {
    header(out.data(), PacketType::AuthRequired);
    put_u64(out.data() + 8, host_id);
    put_u64(out.data() + 16, nonce);
}

std::optional<AuthRequired> AuthRequired::read(std::span<const uint8_t> in) {
    if (!check(in, kSize, PacketType::AuthRequired)) return std::nullopt;
    return AuthRequired{get_u64(in.data() + 8), get_u64(in.data() + 16)};
}

void AuthHello::write(std::span<uint8_t> out, const PairKey& key) const {
    uint8_t* p = out.data();
    header(p, PacketType::AuthHello);
    put_u64(p + 8, device_id);
    put_u64(p + 16, nonce);
    put_u32(p + 24, flags);
    put_u32(p + 28, 0);
    const Mac m = mac(key, {p, 32});
    std::memcpy(p + 32, m.data(), kMacSize);
}

std::optional<AuthHello> AuthHello::read(std::span<const uint8_t> in) {
    if (!check(in, kSize, PacketType::AuthHello)) return std::nullopt;
    const uint8_t* p = in.data();
    return AuthHello{get_u64(p + 8), get_u64(p + 16), get_u32(p + 24)};
}

bool AuthHello::verify(std::span<const uint8_t> in, const PairKey& key) {
    if (!check(in, kSize, PacketType::AuthHello)) return false;
    const Mac m = mac(key, in.first(32));
    return mac_equal(m, in.subspan(32, kMacSize));
}

size_t PairRequest::write(std::span<uint8_t> out) const {
    size_t len = std::min(name.size(), kMaxName);
    while (len > 0 && len < name.size() && (uint8_t(name[len]) & 0xC0) == 0x80) --len;  // keep UTF-8 whole
    uint8_t* p = out.data();
    header(p, PacketType::PairRequest);
    p[6] = uint8_t(len);
    put_u64(p + 8, device_id);
    std::memcpy(p + 16, tv_public.data(), tv_public.size());
    std::memcpy(p + kHeader, name.data(), len);
    return kHeader + len;
}

std::optional<PairRequest> PairRequest::read(std::span<const uint8_t> in) {
    if (!check(in, kHeader, PacketType::PairRequest)) return std::nullopt;
    const uint8_t* p = in.data();
    const size_t len = p[6];
    if (len > kMaxName || in.size() < kHeader + len || p[16] != 0x04) return std::nullopt;
    PairRequest r;
    r.device_id = get_u64(p + 8);
    std::memcpy(r.tv_public.data(), p + 16, r.tv_public.size());
    r.name.assign(reinterpret_cast<const char*>(p + kHeader), len);
    return r;
}

void PairChallenge::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    header(p, PacketType::PairChallenge);
    put_u64(p + 8, host_id);
    put_u64(p + 16, device_id);
    std::memcpy(p + 24, pc_public.data(), pc_public.size());
}

std::optional<PairChallenge> PairChallenge::read(std::span<const uint8_t> in) {
    if (!check(in, kSize, PacketType::PairChallenge) || in[24] != 0x04) return std::nullopt;
    const uint8_t* p = in.data();
    PairChallenge c;
    c.host_id = get_u64(p + 8);
    c.device_id = get_u64(p + 16);
    std::memcpy(c.pc_public.data(), p + 24, c.pc_public.size());
    return c;
}

void PairResult::write(std::span<uint8_t> out) const {
    uint8_t* p = out.data();
    header(p, PacketType::PairResult);
    p[6] = accepted ? 1 : 0;
    put_u64(p + 8, host_id);
    put_u64(p + 16, device_id);
    std::memcpy(p + 24, proof.data(), kMacSize);
}

std::optional<PairResult> PairResult::read(std::span<const uint8_t> in) {
    if (!check(in, kSize, PacketType::PairResult)) return std::nullopt;
    const uint8_t* p = in.data();
    PairResult r;
    r.accepted = p[6] == 1;
    r.host_id = get_u64(p + 8);
    r.device_id = get_u64(p + 16);
    std::memcpy(r.proof.data(), p + 24, kMacSize);
    return r;
}

PairKey derive_pair_key(std::span<const uint8_t> ecdh_secret, const PublicKey& tv_public, const PublicKey& pc_public) {
    Sha256 h;
    h.update(str("jinky-pair-v1"));
    h.update(ecdh_secret);
    h.update(tv_public);
    h.update(pc_public);
    return h.finish();
}

std::string pairing_code(const PairKey& key) {
    const auto d = hmac_sha256(key, str("jinky-code"));
    const uint32_t v = (uint32_t(d[0]) << 24 | uint32_t(d[1]) << 16 | uint32_t(d[2]) << 8 | d[3]) % 1000000;
    std::string s = std::to_string(v);
    return std::string(6 - s.size(), '0') + s;
}

Mac accept_proof(const PairKey& key, uint64_t device_id) {
    uint8_t msg[12 + 8];
    std::memcpy(msg, "jinky-accept", 12);
    put_u64(msg + 12, device_id);
    return mac(key, msg);
}

}  // namespace tclproto
