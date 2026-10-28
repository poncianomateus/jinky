#include "tclproto/fec.h"

#include <algorithm>
#include <cstring>

#include "tclproto/protocol.h"

#if defined(_M_X64) || defined(__SSSE3__)
#include <tmmintrin.h>
#define TCL_FEC_SSSE3 1
#elif defined(__aarch64__)
#include <arm_neon.h>
#define TCL_FEC_NEON 1
#endif

namespace tclproto::fec {
namespace {

// GF(2^8) with the polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
struct Field {
    uint8_t exp[512];
    uint8_t log[256];
    uint8_t mul[256][256];

    Field() {
        unsigned x = 1;
        for (int i = 0; i < 255; ++i) {
            exp[i] = uint8_t(x);
            log[x] = uint8_t(i);
            x <<= 1;
            if (x & 0x100) x ^= 0x11D;
        }
        for (int i = 255; i < 512; ++i) exp[i] = exp[i - 255];
        log[0] = 0;
        for (int a = 0; a < 256; ++a)
            for (int b = 0; b < 256; ++b) mul[a][b] = a && b ? exp[log[a] + log[b]] : 0;
    }
    uint8_t inv(uint8_t a) const { return exp[255 - log[a]]; }
};

const Field& field() {
    static const Field f;
    return f;
}

// Coefficient of data fragment `d` (index within its block) in parity fragment `p` (index within
// its block): 1 / (x_p + y_d) with x_p = 128 + p and y_d = d, all distinct, so every square piece
// of the matrix can be inverted (Cauchy), which is what makes any e parity fragments enough.
uint8_t coef(size_t p, size_t d) { return field().inv(uint8_t((kMaxBlockData + p) ^ d)); }

// dst ^= c * src, over n bytes.
void mul_add(uint8_t* dst, const uint8_t* src, size_t n, uint8_t c) {
    if (!c) return;
    size_t t = 0;
    if (c == 1) {
        for (; t < n; ++t) dst[t] ^= src[t];
        return;
    }
    const uint8_t* row = field().mul[c];
#if defined(TCL_FEC_SSSE3) || defined(TCL_FEC_NEON)
    // Product by nibbles: c*s = c*(s & 15) ^ c*(s >> 4 << 4), each a 16-entry table lookup.
    alignas(16) uint8_t lo[16], hi[16];
    for (int i = 0; i < 16; ++i) {
        lo[i] = row[i];
        hi[i] = row[i << 4];
    }
#endif
#if defined(TCL_FEC_SSSE3)
    const __m128i tl = _mm_load_si128(reinterpret_cast<const __m128i*>(lo));
    const __m128i th = _mm_load_si128(reinterpret_cast<const __m128i*>(hi));
    const __m128i mask = _mm_set1_epi8(0x0F);
    for (; t + 16 <= n; t += 16) {
        const __m128i s = _mm_loadu_si128(reinterpret_cast<const __m128i*>(src + t));
        const __m128i l = _mm_and_si128(s, mask);
        const __m128i h = _mm_and_si128(_mm_srli_epi64(s, 4), mask);
        const __m128i p = _mm_xor_si128(_mm_shuffle_epi8(tl, l), _mm_shuffle_epi8(th, h));
        const __m128i d = _mm_loadu_si128(reinterpret_cast<const __m128i*>(dst + t));
        _mm_storeu_si128(reinterpret_cast<__m128i*>(dst + t), _mm_xor_si128(d, p));
    }
#elif defined(TCL_FEC_NEON)
    const uint8x16_t tl = vld1q_u8(lo), th = vld1q_u8(hi), mask = vdupq_n_u8(0x0F);
    for (; t + 16 <= n; t += 16) {
        const uint8x16_t s = vld1q_u8(src + t);
        const uint8x16_t p = veorq_u8(vqtbl1q_u8(tl, vandq_u8(s, mask)), vqtbl1q_u8(th, vshrq_n_u8(s, 4)));
        vst1q_u8(dst + t, veorq_u8(vld1q_u8(dst + t), p));
    }
#endif
    for (; t < n; ++t) dst[t] ^= row[src[t]];
}

size_t fragment_len(size_t frame_size, size_t i) { return std::min(kMaxPayload, frame_size - i * kMaxPayload); }

// Inverts the n x n matrix a (row-major) in place; false if singular (never for a Cauchy matrix).
bool invert(std::vector<uint8_t>& a, size_t n) {
    const Field& f = field();
    std::vector<uint8_t> b(n * n, 0);
    for (size_t i = 0; i < n; ++i) b[i * n + i] = 1;
    for (size_t col = 0; col < n; ++col) {
        size_t pivot = col;
        while (pivot < n && !a[pivot * n + col]) ++pivot;
        if (pivot == n) return false;
        if (pivot != col)
            for (size_t k = 0; k < n; ++k) {
                std::swap(a[pivot * n + k], a[col * n + k]);
                std::swap(b[pivot * n + k], b[col * n + k]);
            }
        const uint8_t inv = f.inv(a[col * n + col]);
        for (size_t k = 0; k < n; ++k) {
            a[col * n + k] = f.mul[inv][a[col * n + k]];
            b[col * n + k] = f.mul[inv][b[col * n + k]];
        }
        for (size_t r = 0; r < n; ++r) {
            const uint8_t factor = a[r * n + col];
            if (r == col || !factor) continue;
            for (size_t k = 0; k < n; ++k) {
                a[r * n + k] ^= f.mul[factor][a[col * n + k]];
                b[r * n + k] ^= f.mul[factor][b[col * n + k]];
            }
        }
    }
    a.swap(b);
    return true;
}

}  // namespace

size_t parity_for(size_t data_fragments, unsigned percent) {
    if (!data_fragments || !percent) return 0;
    const size_t want = std::max<size_t>(1, (data_fragments * percent + 99) / 100);
    // Never more parity than data, and at most kMaxBlockData per block (the Cauchy matrix size).
    return std::min({want, data_fragments, kMaxParity, kMaxBlockData * block_count(data_fragments)});
}

void encode(std::span<const uint8_t> frame, size_t count, std::vector<std::vector<uint8_t>>& parity) {
    parity.resize(count);
    for (auto& p : parity) p.assign(kMaxPayload, 0);
    const size_t k = (frame.size() + kMaxPayload - 1) / kMaxPayload;
    if (!k || !count) return;
    const size_t blocks = block_count(k);
    for (size_t i = 0; i < k; ++i) {
        const size_t b = i % blocks;
        const uint8_t* src = frame.data() + i * kMaxPayload;
        const size_t len = fragment_len(frame.size(), i);
        for (size_t p = b; p < count; p += blocks) mul_add(parity[p].data(), src, len, coef(p / blocks, i / blocks));
    }
}

size_t recover_block(std::span<uint8_t> frame, std::vector<bool>& have,
                     const std::vector<std::vector<uint8_t>>& parity, size_t block) {
    const size_t k = have.size();
    if (!k || parity.empty()) return 0;
    const size_t blocks = block_count(k);
    if (block >= blocks) return 0;
    std::vector<size_t> missing, avail;
    for (size_t i = block; i < k; i += blocks)
        if (!have[i]) missing.push_back(i);
    for (size_t p = block; p < parity.size(); p += blocks)
        if (parity[p].size() == kMaxPayload) avail.push_back(p);
    const size_t e = missing.size();
    if (!e || avail.size() < e) return 0;
    avail.resize(e);

    // What each chosen parity fragment still owes to the missing data: the parity minus the part
    // made of the data fragments that did arrive.
    std::vector<std::vector<uint8_t>> rest(e);
    for (size_t r = 0; r < e; ++r) {
        rest[r] = parity[avail[r]];
        for (size_t i = block; i < k; i += blocks)
            if (have[i])
                mul_add(rest[r].data(), frame.data() + i * kMaxPayload, fragment_len(frame.size(), i),
                        coef(avail[r] / blocks, i / blocks));
    }
    // rest = A * missing, with A[r][c] = coef(parity r, missing c): missing = A^-1 * rest.
    std::vector<uint8_t> a(e * e);
    for (size_t r = 0; r < e; ++r)
        for (size_t c = 0; c < e; ++c) a[r * e + c] = coef(avail[r] / blocks, missing[c] / blocks);
    if (!invert(a, e)) return 0;
    std::vector<uint8_t> out(kMaxPayload);
    for (size_t c = 0; c < e; ++c) {
        std::fill(out.begin(), out.end(), uint8_t(0));
        for (size_t r = 0; r < e; ++r) mul_add(out.data(), rest[r].data(), kMaxPayload, a[c * e + r]);
        const size_t i = missing[c];
        std::memcpy(frame.data() + i * kMaxPayload, out.data(), fragment_len(frame.size(), i));
        have[i] = true;
    }
    return e;
}

void encode_shards(const std::vector<std::vector<uint8_t>>& data, size_t count, size_t size,
                   std::vector<std::vector<uint8_t>>& parity) {
    parity.resize(count);
    for (auto& p : parity) p.assign(size, 0);
    if (data.size() > kMaxBlockData || count > kMaxBlockData) return;
    for (size_t d = 0; d < data.size(); ++d)
        if (data[d].size() == size)
            for (size_t p = 0; p < count; ++p) mul_add(parity[p].data(), data[d].data(), size, coef(p, d));
}

size_t recover_shards(std::vector<std::vector<uint8_t>>& data, const std::vector<std::vector<uint8_t>>& parity, size_t size) {
    if (data.size() > kMaxBlockData || parity.size() > kMaxBlockData || !size) return 0;
    std::vector<size_t> missing, avail;
    for (size_t d = 0; d < data.size(); ++d)
        if (data[d].size() != size) missing.push_back(d);
    for (size_t p = 0; p < parity.size(); ++p)
        if (parity[p].size() == size) avail.push_back(p);
    const size_t e = missing.size();
    if (!e || avail.size() < e) return 0;
    avail.resize(e);
    std::vector<std::vector<uint8_t>> rest(e);
    for (size_t r = 0; r < e; ++r) {
        rest[r] = parity[avail[r]];
        for (size_t d = 0; d < data.size(); ++d)
            if (data[d].size() == size) mul_add(rest[r].data(), data[d].data(), size, coef(avail[r], d));
    }
    std::vector<uint8_t> a(e * e);
    for (size_t r = 0; r < e; ++r)
        for (size_t c = 0; c < e; ++c) a[r * e + c] = coef(avail[r], missing[c]);
    if (!invert(a, e)) return 0;
    for (size_t c = 0; c < e; ++c) {
        std::vector<uint8_t> out(size, 0);
        for (size_t r = 0; r < e; ++r) mul_add(out.data(), rest[r].data(), size, a[c * e + r]);
        data[missing[c]] = std::move(out);
    }
    return e;
}

}  // namespace tclproto::fec
