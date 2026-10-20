// SHA-256 and HMAC-SHA256 (FIPS 180-4 / RFC 2104), shared by the Windows host and the Android
// receiver so both sign and verify messages identically. Small, dependency-free; used only for
// message authentication, never for bulk data.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace tclproto {

using Sha256Digest = std::array<uint8_t, 32>;

class Sha256 {
public:
    Sha256();
    void update(std::span<const uint8_t> data);
    Sha256Digest finish();

    static Sha256Digest hash(std::span<const uint8_t> data);

private:
    void block(const uint8_t* p);
    uint32_t h_[8];
    uint8_t buf_[64];
    size_t buf_len_ = 0;
    uint64_t total_ = 0;
};

Sha256Digest hmac_sha256(std::span<const uint8_t> key, std::span<const uint8_t> message);

// Messages carry the first 16 bytes of the HMAC (128-bit tag).
inline constexpr size_t kMacSize = 16;
using Mac = std::array<uint8_t, kMacSize>;

Mac mac(std::span<const uint8_t> key, std::span<const uint8_t> message);

// Constant-time comparison, so verification time doesn't leak how many bytes matched.
bool mac_equal(std::span<const uint8_t> a, std::span<const uint8_t> b);

}  // namespace tclproto
