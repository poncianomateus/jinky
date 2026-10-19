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
    // Controllers
    Input = 30,   // receiver -> host: full gamepad state (see InputState)
    Rumble = 31,  // host -> receiver: vibration request (see RumbleMessage)
};

enum PacketFlags : uint8_t {
    kFlagKeyframe = 1 << 0,
    kFlagHevc = 1 << 1,  // video frame is H.265/HEVC (otherwise H.264)
};

// Hello.value: video codecs the receiver can decode in hardware (bit mask).
enum CodecSupport : uint32_t {
    kCodecH264 = 1 << 0,
    kCodecHevc = 1 << 1,
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
    bool is_hevc() const { return flags & kFlagHevc; }

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

// Xbox 360 button bits (same values as XINPUT / XUSB, so the host can pass them straight through).
enum GamepadButtons : uint16_t {
    kBtnDpadUp = 0x0001,
    kBtnDpadDown = 0x0002,
    kBtnDpadLeft = 0x0004,
    kBtnDpadRight = 0x0008,
    kBtnStart = 0x0010,
    kBtnBack = 0x0020,
    kBtnLeftThumb = 0x0040,
    kBtnRightThumb = 0x0080,
    kBtnLeftShoulder = 0x0100,
    kBtnRightShoulder = 0x0200,
    kBtnGuide = 0x0400,
    kBtnA = 0x1000,
    kBtnB = 0x2000,
    kBtnX = 0x4000,
    kBtnY = 0x8000,
};

// Complete state of one controller. Sent on every change and repeated every 50 ms, so a lost
// datagram is corrected by the next one; `seq` lets the host ignore reordered (older) states.
struct InputState {
    uint32_t session_id = 0;
    uint8_t pad_index = 0;    // 0..3, which controller on the TV
    bool connected = true;    // false = controller went away, unplug the virtual pad
    uint32_t seq = 0;
    uint16_t buttons = 0;     // GamepadButtons
    uint8_t left_trigger = 0, right_trigger = 0;  // 0..255
    int16_t lx = 0, ly = 0, rx = 0, ry = 0;       // -32768..32767, +y = up (XInput convention)

    static constexpr size_t kSize = 32;

    void write(std::span<uint8_t> out) const;
    static std::optional<InputState> read(std::span<const uint8_t> in);
};

struct RumbleMessage {
    uint32_t session_id = 0;
    uint8_t pad_index = 0;
    uint8_t large_motor = 0, small_motor = 0;  // 0..255

    static constexpr size_t kSize = 16;

    void write(std::span<uint8_t> out) const;
    static std::optional<RumbleMessage> read(std::span<const uint8_t> in);
};

// Peeks at the packet type without fully parsing. nullopt if not a TclStream packet.
std::optional<PacketType> peek_type(std::span<const uint8_t> in);

}  // namespace tclproto
