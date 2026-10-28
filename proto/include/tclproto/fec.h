// Loss protection for video frames (forward error correction). Besides a frame's K data fragments
// the PC sends M parity fragments; the TV rebuilds missing data fragments from them at once, with
// no round trip to the PC (resends, NACK, stay for losses beyond what the parity covers).
//
// Fragments are spread over blocks of at most kMaxBlockData data fragments: data fragment i belongs
// to block i % B and parity fragment p to block p % B (B = block_count(K)), so a burst of losses
// is shared out among the blocks. Each block is a Reed-Solomon code built on a Cauchy matrix over
// GF(2^8): any e missing data fragments of a block come back from any e of its parity fragments.
#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace tclproto::fec {

inline constexpr size_t kMaxBlockData = 128;
inline constexpr size_t kMaxParity = 255;  // MediaHeader::fec_count is one byte

inline size_t block_count(size_t data_fragments) { return (data_fragments + kMaxBlockData - 1) / kMaxBlockData; }

// Parity fragments for a frame of `data_fragments` at about `percent` extra (at least one).
size_t parity_for(size_t data_fragments, unsigned percent);

// Builds the `count` parity fragments of `frame` (each kMaxPayload bytes) into `parity`.
void encode(std::span<const uint8_t> frame, size_t count, std::vector<std::vector<uint8_t>>& parity);

// Rebuilds the missing data fragments of block `block`, if enough of its parity arrived.
// `frame` holds the frame's fragments at their offsets, `have` says which arrived (it is updated),
// `parity` holds the parity fragments received (empty = not received). Returns how many data
// fragments were rebuilt (0 when none were missing or too few parity fragments are there).
size_t recover_block(std::span<uint8_t> frame, std::vector<bool>& have,
                     const std::vector<std::vector<uint8_t>>& parity, size_t block);

}  // namespace tclproto::fec
