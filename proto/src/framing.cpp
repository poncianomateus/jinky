#include "tclproto/framing.h"

#include <algorithm>
#include <cstring>

namespace tclproto {

size_t write_fragment(const MediaHeader& base, std::span<const uint8_t> frame, size_t index, std::span<uint8_t> out) {
    const size_t count = fragment_count(frame.size());
    if (frame.empty() || count > UINT16_MAX || index >= count || out.size() < MediaHeader::kSize + kMaxPayload)
        return 0;
    MediaHeader h = base;
    h.frame_size = uint32_t(frame.size());
    h.frag_count = uint16_t(count);
    h.frag_index = uint16_t(index);
    h.write(out);
    const size_t offset = index * kMaxPayload;
    const size_t len = std::min(kMaxPayload, frame.size() - offset);
    std::memcpy(out.data() + MediaHeader::kSize, frame.data() + offset, len);
    return MediaHeader::kSize + len;
}

size_t fragment_frame(const MediaHeader& base, std::span<const uint8_t> frame,
                      const std::function<void(std::span<const uint8_t>)>& send) {
    const size_t count = fragment_count(frame.size());
    if (frame.empty() || count > UINT16_MAX) return 0;
    uint8_t buf[MediaHeader::kSize + kMaxPayload];
    for (size_t i = 0; i < count; ++i) send(std::span<const uint8_t>(buf, write_fragment(base, frame, i, buf)));
    return count;
}

FrameAssembler::Slot& FrameAssembler::slot_for(uint32_t frame_id) {
    if (auto it = slots_.find(frame_id); it != slots_.end()) return it->second;
    // Frames between the newest one seen so far and this one: none of their packets came (yet).
    // They get a slot too, so poll() asks for them.
    if (has_newest_ && seq_newer(frame_id, newest_))
        for (uint32_t id = newest_ + 1; id != frame_id; ++id) slots_.try_emplace(id);
    return slots_[frame_id];
}

void FrameAssembler::push(const MediaHeader& h, std::span<const uint8_t> payload, int64_t now_us) {
    stats_.packets_received++;

    if (has_last_ && !seq_newer(h.frame_id, last_id_)) {
        stats_.packets_stale++;
        return;
    }
    if (h.frag_count == 0 || h.frag_index >= h.frag_count) return;

    // Far ahead of anything held (a long network outage): none of it can be used any more; start
    // again from this frame, on a keyframe.
    if (has_newest_ && seq_newer(h.frame_id, newest_) && h.frame_id - newest_ > kMaxInFlight) {
        stats_.frames_lost += h.frame_id - (has_last_ ? last_id_ : newest_) - 1;
        slots_.clear();
        has_last_ = true;
        last_id_ = h.frame_id - 1;
        has_newest_ = false;
        waiting_for_keyframe_ = true;
    }

    Slot& s = slot_for(h.frame_id);
    if (!has_newest_ || seq_newer(h.frame_id, newest_)) {
        newest_ = h.frame_id;
        has_newest_ = true;
    }
    if (!s.known) {
        s.known = true;
        s.header = h;
        s.data.resize(h.frame_size);
        s.have.assign(h.frag_count, false);
    } else if (s.header.frame_size != h.frame_size || s.header.frag_count != h.frag_count) {
        return;  // inconsistent with earlier fragments of this frame; ignore as corrupt
    }
    s.last_packet_us = now_us;
    if (s.complete || s.have[h.frag_index]) {
        stats_.packets_duplicate++;
        return;
    }

    const size_t offset = size_t(h.frag_index) * kMaxPayload;
    const size_t expected = std::min(kMaxPayload, size_t(h.frame_size) - offset);
    if (payload.size() != expected) return;

    std::memcpy(s.data.data() + offset, payload.data(), expected);
    s.have[h.frag_index] = true;
    s.highest = std::max<uint16_t>(s.highest, uint16_t(h.frag_index + 1));
    if (++s.received == h.frag_count) {
        s.complete = true;
        if (s.tries > 0) stats_.frames_recovered++;
        // A whole keyframe makes everything before it unnecessary: stop waiting for those.
        if (s.header.is_keyframe()) skip_to(h.frame_id);
    }
    while (slots_.size() > kMaxInFlight) drop_front(true);
    deliver_ready();
}

std::vector<NackMessage::Range> FrameAssembler::missing_ranges(uint32_t frame_id, const Slot& s, int64_t now_us) const {
    if (!s.known) return {{0, NackMessage::kWholeFrame}};
    // Fragments go out in order, so a hole below the highest one received is missing. For the tail
    // that is only clear once a newer frame started, or the frame has been quiet for a while.
    const bool older = has_newest_ && seq_newer(newest_, frame_id);
    const bool quiet = now_us - s.last_packet_us >= 2 * timing_.reorder_us;
    const uint16_t end = older || quiet ? s.header.frag_count : s.highest;
    std::vector<NackMessage::Range> out;
    for (uint16_t i = 0; i < end && out.size() < NackMessage::kMaxRanges; ++i) {
        if (s.have[i]) continue;
        if (!out.empty() && out.back().first + out.back().count == i)
            out.back().count++;
        else
            out.push_back({i, 1});
    }
    return out;
}

std::vector<FrameAssembler::Nack> FrameAssembler::poll(int64_t now_us) {
    std::vector<Nack> out;
    for (auto& [id, s] : slots_) {
        if (s.complete) continue;
        // While resyncing, only a keyframe is worth asking for.
        if (waiting_for_keyframe_ && s.known && !s.header.is_keyframe()) continue;
        auto ranges = missing_ranges(id, s, now_us);
        if (ranges.empty()) {
            s.missing_since_us = 0;
            continue;
        }
        if (!s.missing_since_us) s.missing_since_us = now_us;
        if (now_us - s.missing_since_us < timing_.reorder_us) continue;
        if (s.tries >= timing_.max_tries || (s.tries > 0 && now_us - s.last_nack_us < timing_.retry_us)) continue;
        if (s.tries == 0)
            for (const auto& r : ranges) stats_.packets_missing += r.count == NackMessage::kWholeFrame ? 1 : r.count;
        s.tries++;
        s.last_nack_us = now_us;
        out.push_back({id, std::move(ranges)});
    }
    // The oldest frame has been missing pieces too long: lost; the stream resyncs on a keyframe.
    while (!slots_.empty()) {
        const Slot& s = slots_.begin()->second;
        if (s.complete || !s.missing_since_us || now_us - s.missing_since_us < timing_.give_up_us) break;
        drop_front(true);
    }
    deliver_ready();
    return out;
}

bool FrameAssembler::has_gaps() const {
    return std::any_of(slots_.begin(), slots_.end(), [](const auto& e) { return !e.second.complete; });
}

void FrameAssembler::deliver_ready() {
    while (!slots_.empty()) {
        auto it = slots_.begin();
        Slot& s = it->second;
        const uint32_t id = it->first;
        if (!s.complete) {
            // Resyncing anyway: a P-frame (or a frame we know nothing of) at the front is useless,
            // so it must not hold back the keyframe behind it.
            if (waiting_for_keyframe_ && !(s.known && s.header.is_keyframe())) {
                drop_front(false);
                continue;
            }
            break;
        }
        const bool key = s.header.is_keyframe();
        Frame f{id, key, s.header.is_hevc(), s.header.capture_time_us, std::move(s.data)};
        slots_.erase(it);
        has_last_ = true;
        last_id_ = id;
        if (waiting_for_keyframe_ && !key) {
            stats_.frames_discarded++;
            continue;
        }
        if (key) waiting_for_keyframe_ = false;
        stats_.frames_delivered++;
        on_frame_(std::move(f));
    }
}

void FrameAssembler::drop_front(bool lost) {
    auto it = slots_.begin();
    if (lost && !it->second.complete) {
        stats_.frames_lost++;
        waiting_for_keyframe_ = true;
    } else {
        stats_.frames_discarded++;
    }
    has_last_ = true;
    last_id_ = it->first;
    slots_.erase(it);
}

void FrameAssembler::skip_to(uint32_t keyframe_id) {
    while (!slots_.empty() && seq_newer(keyframe_id, slots_.begin()->first)) {
        auto it = slots_.begin();
        if (it->second.complete) stats_.frames_discarded++;
        else stats_.frames_lost++;
        has_last_ = true;
        last_id_ = it->first;
        slots_.erase(it);
    }
}

void FrameAssembler::reset() {
    slots_.clear();
    has_last_ = false;
    last_id_ = 0;
    has_newest_ = false;
    newest_ = 0;
    waiting_for_keyframe_ = true;
    stats_ = {};
}

}  // namespace tclproto
