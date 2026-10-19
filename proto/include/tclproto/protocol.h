// TclStream wire protocol — shared by the Windows host and the Android receiver.
// All multi-byte fields are little-endian and (de)serialized explicitly, never via struct casts.
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>

namespace tclproto {

inline constexpr uint32_t kMagic = 0x534C4354;  // "TCLS" in little-endian byte order
inline constexpr uint8_t kVersion = 1;

// Payload per UDP datagram. Kept well under a 1500-byte Ethernet MTU so Wi-Fi,
// PPPoE and VLAN overhead never cause IP fragmentation.
inline constexpr size_t kMaxPayload = 1200;

inline constexpr uint16_t kDefaultMediaPort = 47100;

enum class PacketType : uint8_t {
    Video = 1,
    Audio = 2,
    // Receiver -> host
    Hello = 10,        // "start sending to me"
    RequestIdr = 11,   // decoder lost sync, needs a keyframe
    Stats = 12,        // periodic receiver statistics
    Bye = 13,          // receiver is leaving
    LatencyReport = 14,  // value = average capture-to-display latency in microseconds
    // Clock synchronisation (both directions), see TimeSync
    TimeRequest = 20,
    TimeResponse = 21,
};

enum PacketFlags : uint8_t {
    kFlagKeyframe = 1 << 0,
};

// Header that precedes every media fragment.
struct MediaHeader {
    PacketType type = PacketType::Video;
    uint8_t flags = 0;
    uint32_t session_id = 0;
    uint32_t frame_id = 0;        // monotonically increasing per stream
    uint16_t frag_index = 0;      // 0 .. frag_count-1
    uint16_t frag_count = 0;
    uint32_t frame_size = 0;      // total bytes of the reassembled frame
    uint64_t capture_time_us = 0; // host clock, for latency measurement

    static constexpr size_t kSize = 32;

    bool is_keyframe() const { return flags & kFlagKeyframe; }

    // Writes kSize bytes into out (out.size() must be >= kSize).
    void write(std::span<uint8_t> out) const;
    // Returns nullopt if the buffer is too short, has the wrong magic/version or is inconsistent.
    static std::optional<MediaHeader> read(std::span<const uint8_t> in);
};

// Small control message sent receiver -> host on the media socket.
struct ControlMessage {
    PacketType type = PacketType::Hello;
    uint32_t session_id = 0;
    uint32_t value = 0;  // meaning depends on type (e.g. last frame id for RequestIdr)

    static constexpr size_t kSize = 16;

    void write(std::span<uint8_t> out) const;
    static std::optional<ControlMessage> read(std::span<const uint8_t> in);
};

// NTP-style clock sync so the receiver can express host timestamps in its own clock.
// Receiver sends TimeRequest{client_send_us}; host echoes it back as TimeResponse with host_us set.
struct TimeSync {
    PacketType type = PacketType::TimeRequest;
    uint32_t session_id = 0;
    uint64_t client_send_us = 0;  // receiver clock
    uint64_t host_us = 0;         // host clock when answering

    static constexpr size_t kSize = 32;

    void write(std::span<uint8_t> out) const;
    static std::optional<TimeSync> read(std::span<const uint8_t> in);
};

// Peeks at the packet type without fully parsing. nullopt if not a TclStream packet.
std::optional<PacketType> peek_type(std::span<const uint8_t> in);

}  // namespace tclproto
