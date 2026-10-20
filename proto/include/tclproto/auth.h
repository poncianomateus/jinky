// Pairing and authentication messages.
//
// Pairing (once per TV): the TV and the PC exchange P-256 public keys (ECDH). Both derive the
// same 32-byte pair key from the shared secret and show a 6-digit code computed from it; the user
// checks the codes match and approves on the PC. A man in the middle would end up with different
// keys on each side and therefore different codes (same idea as Bluetooth numeric comparison).
//
// After pairing, every Hello and every controller state carries an HMAC-SHA256 tag (128-bit)
// made with the pair key. Hellos also sign a random nonce the PC gave to that TV's address, so a
// captured Hello is useless from any other address and needs no clock agreement.
#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>

#include "tclproto/crypto.h"

namespace tclproto {

using PairKey = std::array<uint8_t, 32>;
using PublicKey = std::array<uint8_t, 65>;  // uncompressed P-256 point: 0x04 || X || Y

// Host -> TV: "identify yourself". Layout: magic(4) version(1) type(1) reserved(2) host_id(8) nonce(8)
struct AuthRequired {
    uint64_t host_id = 0;
    uint64_t nonce = 0;  // random, bound by the PC to the address it was sent to
    static constexpr size_t kSize = 24;
    void write(std::span<uint8_t> out) const;
    static std::optional<AuthRequired> read(std::span<const uint8_t> in);
};

// TV -> host: signed Hello. Layout: magic(4) version(1) type(1) reserved(2) device_id(8)
// nonce(8) flags(4) reserved(4) mac(16) = 48; the MAC covers bytes 0..31.
struct AuthHello {
    uint64_t device_id = 0;
    uint64_t nonce = 0;    // from the PC's latest AuthRequired to this TV
    uint32_t flags = 0;    // same bits as Hello.value (codec support, kHelloTakeover)
    static constexpr size_t kSize = 48;
    void write(std::span<uint8_t> out, const PairKey& key) const;
    // Parses without checking the MAC (the device id is needed to find the key).
    static std::optional<AuthHello> read(std::span<const uint8_t> in);
    static bool verify(std::span<const uint8_t> in, const PairKey& key);
};

// TV -> host. Layout: magic(4) version(1) type(1) name_len(1) reserved(1) device_id(8) pubkey(65) name
struct PairRequest {
    uint64_t device_id = 0;
    PublicKey tv_public{};
    std::string name;
    static constexpr size_t kHeader = 81;
    static constexpr size_t kMaxName = 64;
    size_t write(std::span<uint8_t> out) const;  // returns bytes written
    static std::optional<PairRequest> read(std::span<const uint8_t> in);
};

// Host -> TV. Layout: magic(4) version(1) type(1) reserved(2) host_id(8) device_id(8) pubkey(65) = 89
struct PairChallenge {
    uint64_t host_id = 0;
    uint64_t device_id = 0;
    PublicKey pc_public{};
    static constexpr size_t kSize = 89;
    void write(std::span<uint8_t> out) const;
    static std::optional<PairChallenge> read(std::span<const uint8_t> in);
};

// Host -> TV. Layout: magic(4) version(1) type(1) accepted(1) reserved(1) host_id(8) device_id(8)
// proof(16) = 40. proof = MAC(pair key, "jinky-accept" || device_id): the TV only trusts a result
// from the PC that holds the same key.
struct PairResult {
    uint64_t host_id = 0;
    uint64_t device_id = 0;
    bool accepted = false;
    Mac proof{};
    static constexpr size_t kSize = 40;
    void write(std::span<uint8_t> out) const;
    static std::optional<PairResult> read(std::span<const uint8_t> in);
};

// Pair key = SHA-256("jinky-pair-v1" || ecdh_secret || tv_public || pc_public).
// `ecdh_secret` is the big-endian X coordinate of the shared point (Java's KeyAgreement output).
PairKey derive_pair_key(std::span<const uint8_t> ecdh_secret, const PublicKey& tv_public, const PublicKey& pc_public);

// The 6 digits both sides show, "000000".."999999".
std::string pairing_code(const PairKey& key);

Mac accept_proof(const PairKey& key, uint64_t device_id);

}  // namespace tclproto
