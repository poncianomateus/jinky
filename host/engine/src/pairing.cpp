#include "pairing.h"

#include <windows.h>
#include <bcrypt.h>

#include <algorithm>
#include <cstring>
#include <fstream>
#include <sstream>

#include "log.h"

namespace tcl {

namespace {

std::wstring store_path() {
    wchar_t base[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    std::wstring dir = std::wstring(base) + L"\\Jinky";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir + L"\\paired.txt";
}

std::string to_hex(std::span<const uint8_t> d) {
    static const char* x = "0123456789abcdef";
    std::string s;
    for (uint8_t b : d) {
        s += x[b >> 4];
        s += x[b & 15];
    }
    return s;
}

bool from_hex(const std::string& s, std::span<uint8_t> out) {
    if (s.size() != out.size() * 2) return false;
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < out.size(); ++i) {
        const int hi = nib(s[2 * i]), lo = nib(s[2 * i + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = uint8_t(hi << 4 | lo);
    }
    return true;
}

}  // namespace

uint64_t random_u64() {
    uint64_t v = 0;
    BCryptGenRandom(nullptr, reinterpret_cast<PUCHAR>(&v), sizeof v, BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    return v;
}

std::string hex_id(uint64_t v) {
    char buf[17];
    std::snprintf(buf, sizeof buf, "%016llx", static_cast<unsigned long long>(v));
    return buf;
}

std::optional<uint64_t> parse_hex_id(const std::string& s) {
    uint8_t b[8];
    if (!from_hex(s, b)) return std::nullopt;
    uint64_t v = 0;
    for (uint8_t x : b) v = v << 8 | x;
    return v;
}

// --- ECDH ----------------------------------------------------------------------------------

EcdhP256::EcdhP256() {
    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_KEY_HANDLE key = nullptr;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_ECDH_P256_ALGORITHM, nullptr, 0) != 0) return;
    alg_ = alg;
    if (BCryptGenerateKeyPair(alg, &key, 256, 0) != 0 || BCryptFinalizeKeyPair(key, 0) != 0) return;
    // Public blob: BCRYPT_ECCKEY_BLOB header, then X and Y (big-endian, 32 bytes each).
    uint8_t blob[sizeof(BCRYPT_ECCKEY_BLOB) + 64];
    ULONG len = 0;
    if (BCryptExportKey(key, nullptr, BCRYPT_ECCPUBLIC_BLOB, blob, sizeof blob, &len, 0) != 0 || len != sizeof blob) {
        BCryptDestroyKey(key);
        return;
    }
    key_ = key;
    public_[0] = 0x04;
    std::memcpy(public_.data() + 1, blob + sizeof(BCRYPT_ECCKEY_BLOB), 64);
}

EcdhP256::~EcdhP256() {
    if (key_) BCryptDestroyKey(static_cast<BCRYPT_KEY_HANDLE>(key_));
    if (alg_) BCryptCloseAlgorithmProvider(static_cast<BCRYPT_ALG_HANDLE>(alg_), 0);
}

std::optional<std::array<uint8_t, 32>> EcdhP256::agree(const tclproto::PublicKey& peer) const {
    if (!key_ || peer[0] != 0x04) return std::nullopt;
    uint8_t blob[sizeof(BCRYPT_ECCKEY_BLOB) + 64];
    auto* hdr = reinterpret_cast<BCRYPT_ECCKEY_BLOB*>(blob);
    hdr->dwMagic = BCRYPT_ECDH_PUBLIC_P256_MAGIC;
    hdr->cbKey = 32;
    std::memcpy(blob + sizeof(BCRYPT_ECCKEY_BLOB), peer.data() + 1, 64);
    BCRYPT_KEY_HANDLE peer_key = nullptr;
    // Import fails for points not on the curve, which rejects invalid-curve tricks.
    if (BCryptImportKeyPair(static_cast<BCRYPT_ALG_HANDLE>(alg_), nullptr, BCRYPT_ECCPUBLIC_BLOB, &peer_key, blob,
                            sizeof blob, 0) != 0)
        return std::nullopt;
    BCRYPT_SECRET_HANDLE secret = nullptr;
    std::optional<std::array<uint8_t, 32>> out;
    if (BCryptSecretAgreement(static_cast<BCRYPT_KEY_HANDLE>(key_), peer_key, &secret, 0) == 0) {
        std::array<uint8_t, 32> raw{};
        ULONG len = 0;
        if (BCryptDeriveKey(secret, BCRYPT_KDF_RAW_SECRET, nullptr, raw.data(), ULONG(raw.size()), &len, 0) == 0 &&
            len == raw.size()) {
            // CNG returns the raw secret little-endian; Java (the TV) uses big-endian.
            std::reverse(raw.begin(), raw.end());
            out = raw;
        }
        BCryptDestroySecret(secret);
    }
    BCryptDestroyKey(peer_key);
    return out;
}

// --- paired TVs ----------------------------------------------------------------------------
// Format: first line "host <16 hex>"; then one line per TV: "tv <16 hex id> <64 hex key> <name>".

bool PairedStore::load() {
    tvs_.clear();
    host_id_ = 0;
    std::ifstream f(store_path());
    std::string line;
    while (std::getline(f, line)) {
        std::istringstream in(line);
        std::string kind, id;
        in >> kind >> id;
        if (kind == "host") {
            if (auto v = parse_hex_id(id)) host_id_ = *v;
        } else if (kind == "tv") {
            std::string key;
            in >> key;
            std::string name;
            std::getline(in, name);
            if (!name.empty() && name[0] == ' ') name.erase(0, 1);
            PairedTv tv;
            auto v = parse_hex_id(id);
            if (!v || !from_hex(key, tv.key)) continue;
            tv.device_id = *v;
            tv.name = name;
            tvs_.push_back(tv);
        }
    }
    if (host_id_ == 0) {
        host_id_ = random_u64();
        return save();
    }
    return true;
}

const PairedTv* PairedStore::find(uint64_t device_id) const {
    for (const auto& tv : tvs_)
        if (tv.device_id == device_id) return &tv;
    return nullptr;
}

void PairedStore::add(const PairedTv& tv) {
    remove(tv.device_id);
    tvs_.push_back(tv);
    if (!save()) TCL_WARN("pairing", "could not save the paired TV list");
}

bool PairedStore::remove(uint64_t device_id) {
    const auto before = tvs_.size();
    std::erase_if(tvs_, [&](const PairedTv& t) { return t.device_id == device_id; });
    if (tvs_.size() == before) return false;
    save();
    return true;
}

bool PairedStore::save() const {
    const auto path = store_path();
    if (path.empty()) return false;
    std::ofstream f(path, std::ios::trunc);
    f << "host " << hex_id(host_id_) << "\n";
    for (const auto& tv : tvs_) {
        std::string name = tv.name;
        std::replace(name.begin(), name.end(), '\n', ' ');
        f << "tv " << hex_id(tv.device_id) << " " << to_hex(tv.key) << " " << name << "\n";
    }
    return bool(f);
}

}  // namespace tcl
