// Host side of pairing: P-256 key agreement (Windows CNG) and the list of paired TVs.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tclproto/auth.h"

namespace tcl {

// One ephemeral P-256 key pair; agree() gives the shared secret in the same form Java's
// KeyAgreement does (big-endian X coordinate), so both sides derive the same pair key.
class EcdhP256 {
public:
    EcdhP256();
    ~EcdhP256();
    EcdhP256(const EcdhP256&) = delete;
    EcdhP256& operator=(const EcdhP256&) = delete;

    bool ok() const { return key_ != nullptr; }
    const tclproto::PublicKey& public_key() const { return public_; }
    std::optional<std::array<uint8_t, 32>> agree(const tclproto::PublicKey& peer) const;

private:
    void* alg_ = nullptr;  // BCRYPT_ALG_HANDLE
    void* key_ = nullptr;  // BCRYPT_KEY_HANDLE
    tclproto::PublicKey public_{};
};

struct PairedTv {
    uint64_t device_id = 0;
    tclproto::PairKey key{};
    std::string name;
};

// %LOCALAPPDATA%\Jinky\paired.txt — this PC's id and the TVs allowed to connect. The keys are
// secrets: the file lives in the user's profile, readable only by them.
class PairedStore {
public:
    bool load();
    uint64_t host_id() const { return host_id_; }
    const PairedTv* find(uint64_t device_id) const;
    void add(const PairedTv& tv);
    bool remove(uint64_t device_id);
    const std::vector<PairedTv>& all() const { return tvs_; }

private:
    bool save() const;
    uint64_t host_id_ = 0;
    std::vector<PairedTv> tvs_;
};

uint64_t random_u64();
std::string hex_id(uint64_t v);
std::optional<uint64_t> parse_hex_id(const std::string& s);

}  // namespace tcl
