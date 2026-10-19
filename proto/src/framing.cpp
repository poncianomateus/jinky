#include "tclproto/framing.h"

#include <algorithm>
#include <cstring>

namespace tclproto {

size_t fragment_frame(const MediaHeader& base, std::span<const uint8_t> frame,
                      const std::function<void(std::span<const uint8_t>)>& send) {
    if (frame.empty()) return 0;
    const size_t count = (frame.size() + kMaxPayload - 1) / kMaxPayload;
    if (count > UINT16_MAX) return 0;

    uint8_t buf[MediaHeader::kSize + kMaxPayload];
    MediaHeader h = base;
    h.frame_size = uint32_t(frame.size());
    h.frag_count = uint16_t(count);

    for (size_t i = 0; i < count; ++i) {
        const size_t offset = i * kMaxPayload;
        const size_t len = std::min(kMaxPayload, frame.size() - offset);
        h.frag_index = uint16_t(i);
        h.write(buf);
        std::memcpy(buf + MediaHeader::kSize, frame.data() + offset, len);
        send(std::span<const uint8_t>(buf, MediaHeader::kSize + len));
    }
    return count;
}

void FrameAssembler::push(const MediaHeader& h, std::span<const uint8_t> payload) {
    stats_.packets_received++;

    if (has_last_ && !seq_newer(h.frame_id, last_id_)) {
        stats_.packets_stale++;
        return;
    }

    auto it = partials_.find(h.frame_id);
    if (it == partials_.end()) {
        if (partials_.size() >= kMaxInFlight) {
            // Oldest by sequence order; the gap is accounted for when a newer frame completes.
            auto oldest = std::min_element(partials_.begin(), partials_.end(),
                                           [](const auto& a, const auto& b) {
                                               return seq_newer(b.first, a.first);
                                           });
            partials_.erase(oldest);
        }
        Partial p;
        p.header = h;
        p.data.resize(h.frame_size);
        p.have.assign(h.frag_count, false);
        it = partials_.emplace(h.frame_id, std::move(p)).first;
    } else if (it->second.header.frame_size != h.frame_size ||
               it->second.header.frag_count != h.frag_count) {
        return;  // inconsistent with earlier fragments of this frame; ignore as corrupt
    }

    Partial& p = it->second;
    if (p.have[h.frag_index]) {
        stats_.packets_duplicate++;
        return;
    }

    const size_t offset = size_t(h.frag_index) * kMaxPayload;
    const size_t expected = std::min(kMaxPayload, size_t(h.frame_size) - offset);
    if (payload.size() != expected) return;

    std::memcpy(p.data.data() + offset, payload.data(), expected);
    p.have[h.frag_index] = true;
    if (++p.received == h.frag_count) complete(h.frame_id);
}

void FrameAssembler::complete(uint32_t frame_id) {
    auto node = partials_.extract(frame_id);
    Partial& p = node.mapped();
    abandon_older_than(frame_id);

    if (has_last_ && frame_id != last_id_ + 1) {
        stats_.frames_lost += frame_id - last_id_ - 1;
        waiting_for_keyframe_ = true;
    }
    has_last_ = true;
    last_id_ = frame_id;

    const bool key = p.header.is_keyframe();
    if (waiting_for_keyframe_ && !key) {
        stats_.frames_discarded++;
        return;
    }
    if (key) waiting_for_keyframe_ = false;

    stats_.frames_delivered++;
    on_frame_(Frame{frame_id, key, p.header.capture_time_us, std::move(p.data)});
}

void FrameAssembler::abandon_older_than(uint32_t frame_id) {
    for (auto it = partials_.begin(); it != partials_.end();) {
        if (seq_newer(frame_id, it->first))
            it = partials_.erase(it);
        else
            ++it;
    }
}

void FrameAssembler::reset() {
    partials_.clear();
    has_last_ = false;
    last_id_ = 0;
    waiting_for_keyframe_ = true;
    stats_ = {};
}

}  // namespace tclproto
