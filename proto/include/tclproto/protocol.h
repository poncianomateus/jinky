// TclStream wire protocol — shared by the Windows host and the Android receiver.
// All multi-byte fields are little-endian and (de)serialized explicitly, never via struct casts.
#pragma once

#include <cstddef>
#include <cstdint>
#include <array>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace tclproto {

inline constexpr uint32_t kMagic = 0x534C4354;  // "TCLS" in little-endian byte order
inline constexpr uint8_t kVersion = 1;

// Payload per UDP datagram. Kept well under a 1500-byte Ethernet MTU so Wi-Fi,
// PPPoE and VLAN overhead never cause IP fragmentation.
inline constexpr size_t kMaxPayload = 1200;

inline constexpr uint16_t kDefaultMediaPort = 47100;
// Several Jinkys can run on one PC at once (one per Windows user / ASTER workplace), each its own
// "PC" for the TVs: the first on kDefaultMediaPort, the next ones kMediaPortStep further on. TVs
// look for PCs on all of them and use the port each one answered from.
inline constexpr uint16_t kMediaPortStep = 10;
inline constexpr int kMaxHostsPerPc = 4;

enum class PacketType : uint8_t {
    Video = 1,
    Audio = 2,
    // Receiver -> host
    Hello = 10,        // "start sending to me"
    RequestIdr = 11,   // decoder lost sync, needs a keyframe
    Stats = 12,        // periodic receiver statistics
    Bye = 13,          // receiver is leaving
    LatencyReport = 14,  // value = average capture-to-display latency in microseconds
    // Host -> receiver
    Displaced = 15,   // another TV took over this session ("the game continues elsewhere")
    SessionEnd = 16,  // the PC ended the session; value = SessionEndReason
    // Receiver -> host
    DeviceName = 17,  // the TV's user-visible name ("Sala", "TCL 55P7K"), see NameMessage
    // Home screen discovery (no session): TV broadcasts Discover, each PC answers with HostInfo.
    Discover = 18,
    HostInfo = 19,
    // Clock synchronisation (both directions), see TimeSync
    TimeRequest = 20,
    TimeResponse = 21,
    // Game sessions (kHelloGame)
    GameReady = 22,  // host -> TV: reveal the picture; value = GameReadyReason
    CloseGame = 23,  // TV -> host: quit the game that is running (menu "Fechar jogo")
    // Network quality
    Nack = 24,       // TV -> host: video fragments that never arrived, to send again (NackMessage)
    NetReport = 25,  // TV -> host: once a second, how the network did (value = NetReport::pack())
    Screenshot = 27,  // TV -> host: save the picture being streamed (menu "Capturar tela")
    PauseGame = 29,   // TV -> host: freeze the game in front, to continue later (menu "Início")
    TypeText = 28,    // TV -> host: type this on the PC (menu "Teclado"), see TextMessage  // TV -> host: save the picture being streamed (menu "Capturar tela")
    MouseMode = 26,  // TV -> host: value 1 = the first controller works as mouse/keyboard (menu "Mouse"), 0 = back to the game
    // Controllers
    Input = 30,   // receiver -> host: full gamepad state (see InputState)
    Rumble = 31,  // host -> receiver: vibration request (see RumbleMessage)
    PadExtras = 32,  // receiver -> host: a PlayStation controller's touchpad and motion (see PadExtras)
    // Pairing and authentication (see auth.h)
    AuthRequired = 40,   // host -> TV
    AuthHello = 41,      // TV -> host
    PairRequest = 42,    // TV -> host
    PairChallenge = 43,  // host -> TV
    PairResult = 44,     // host -> TV
};

enum PacketFlags : uint8_t {
    kFlagKeyframe = 1 << 0,
    kFlagHevc = 1 << 1,  // video frame is H.265/HEVC (otherwise H.264)
    kFlagHdr = 1 << 2,   // HDR10 (HEVC Main10, BT.2020, PQ): the TV sets its decoder up for HDR
};

// Hello.value: video codecs the receiver can decode in hardware (bit mask).
enum CodecSupport : uint32_t {
    kCodecH264 = 1 << 0,
    kCodecHevc = 1 << 1,
};

// Hello.value flag: the user just opened Jinky on this TV (or pressed "play here"), so it should
// take the session over from whichever TV has it. Without it, a second TV never steals a session.
inline constexpr uint32_t kHelloTakeover = 1u << 8;
// Hello.value flag: "Área de trabalho" — just show the PC's screen; no console mode (no Big
// Picture, no 60 Hz switch).
inline constexpr uint32_t kHelloDesktop = 1u << 9;
// Hello.value flag: a game chosen in the TV's own library — the TV hides Big Picture behind its own
// launch screen until the game is up; the session ends when the game is closed.
inline constexpr uint32_t kHelloGame = 1u << 10;
// Hello.value flag: pairing only ("Parear um PC"): once the TV is authenticated (the code was
// confirmed), no session starts. Older PCs ignore it (a session starts and the TV leaves at once).
inline constexpr uint32_t kHelloPairOnly = 1u << 11;
// Hello.value flags, with kHelloPairOnly: "Suspender o PC" / "Desligar o PC" on the TV. Honoured
// only from a paired TV that proved its key (AuthHello); nothing else starts.
inline constexpr uint32_t kHelloPowerSleep = 1u << 12;
inline constexpr uint32_t kHelloPowerOff = 1u << 13;
// Hello.value flag: "HDR" on the TV (its screen does HDR10 and it decodes HEVC Main10). The PC
// streams HDR10 when its monitor can do Windows HDR (console mode turns it on for the session).
inline constexpr uint32_t kHelloHdr = 1u << 14;
// Hello.value flag, with kHelloGame: the game is one the PC paused ("Continuar"): it is already
// running, so no Big Picture to wait for (it only came up on top of the game being continued).
inline constexpr uint32_t kHelloResume = 1u << 15;
// Hello.value flag, with kHelloGame: "Pausar jogos ao sair" is on. If the TV vanishes (Wi-Fi gone,
// TV switched off) and doesn't come back, the PC pauses the game instead of leaving it running.
inline constexpr uint32_t kHelloAutoPause = 1u << 26;
// Hello.value flag: the TV rebuilds lost video fragments from parity fragments (fec.h), so the PC
// sends them along with each frame.
inline constexpr uint32_t kHelloFec = 1u << 27;
// Hello.value flag: the TV rebuilds lost sound chunks from parity. The PC then sends, after each
// group of kAudioGroup chunks (frame_id = first..first+3), fec_count parity datagrams: type Audio,
// frame_id = first, frag_count 1, frag_index 1..fec_count, frame_size = the chunk size. Older TVs
// reject them (frag_index past frag_count).
inline constexpr uint32_t kHelloAudioFec = 1u << 28;
inline constexpr uint32_t kAudioGroup = 4;

// GameReady.value
enum GameReadyReason : uint32_t {
    kGameOnScreen = 0,        // the game is in front
    kGameNeedsAttention = 1,  // a launcher or prompt waits for the player
};

// SessionEnd.value
enum SessionEndReason : uint32_t {
    kEndBigPictureClosed = 0,  // the TV app closes, like turning a console off
    kEndGameClosed = 1,        // the TV goes back to its home screen
};

// Hello.value bits 16..23: picture quality the user chose on the TV, in Mbit/s (0 = the PC's own
// setting).
inline constexpr uint32_t kHelloBitrateShift = 16;
inline constexpr uint32_t hello_bitrate_mbps(uint32_t value) { return (value >> kHelloBitrateShift) & 0xFF; }

// Hello.value bits 24..25: picture size the TV asks for ("Resolução"): 0 = the PC's own (1080p),
// 1 = 1280x720 (half the decoding work: less delay on weak TV sticks).
inline constexpr uint32_t kHelloSizeShift = 24;
enum HelloSize : uint32_t { kSizeDefault = 0, kSize720p = 1 };
inline constexpr uint32_t hello_size(uint32_t value) { return (value >> kHelloSizeShift) & 0x3; }

// What the TV saw of the network in the last second, for the PC's automatic quality.
// Packed into ControlMessage.value: bits 0..9 video packets that had to be asked for again, per
// mille; 10..17 frames lost for good (a keyframe was needed); 18..31 video packets received / 4.
struct NetReport {
    uint32_t lost_permille = 0;
    uint32_t frames_unrecovered = 0;
    uint32_t packets = 0;

    uint32_t pack() const;
    static NetReport unpack(uint32_t value);
};

// A TV asking for video fragments again. Wi-Fi drops a packet now and then; resending it costs a
// few milliseconds, while a new keyframe takes longer, looks blurry and is itself easy to hit.
// Layout: magic(4) version(1) type(1) count(1) reserved(1) session(4) frame_id(4), then `count`
// ranges of first(2) n(2). n = kWholeFrame asks for every fragment (none of the frame arrived).
struct NackMessage {
    struct Range {
        uint16_t first = 0;
        uint16_t count = 0;
        bool operator==(const Range&) const = default;
    };
    static constexpr uint16_t kWholeFrame = 0xFFFF;
    static constexpr size_t kHeader = 16;
    static constexpr size_t kMaxRanges = 64;
    static constexpr size_t kMaxSize = kHeader + 4 * kMaxRanges;

    uint32_t session_id = 0;
    uint32_t frame_id = 0;
    std::vector<Range> ranges;  // at most kMaxRanges are written

    size_t write(std::span<uint8_t> out) const;  // out must hold kMaxSize; returns bytes written
    static std::optional<NackMessage> read(std::span<const uint8_t> in);
};

// A PC's answer to Discover. Layout: magic(4) version(1) type(1) state(1) name_len(1) host_id(8)
// mac(6) busy_len(1) reserved(1) name busy_with [tv_version_code(4) update_port(2)].
struct HostInfo {
    enum State : uint8_t { kIdle = 0, kBusy = 1 };
    uint64_t host_id = 0;
    State state = kIdle;
    std::array<uint8_t, 6> mac{};  // of the PC's network adapter facing the TV, for Wake-on-LAN
    std::string name;              // the PC's name
    std::string busy_with;         // name of the TV playing, when busy
    // TV app update offered by this PC (appended after the strings; older TVs ignore it).
    uint32_t tv_version_code = 0;  // 0 = none
    uint16_t update_port = 0;      // HTTP port serving /tv/jinky.apk
    // About the TV that asked (its Discover carries its device id): whether this PC still has it
    // paired. A TV forgets a PC that removed it, and doesn't list PCs it isn't paired with.
    enum Flags : uint8_t { kPairingKnown = 1, kPairedWithYou = 2 };
    uint8_t flags = 0;

    static constexpr size_t kHeader = 24;
    static constexpr size_t kMaxText = 64;
    size_t write(std::span<uint8_t> out) const;  // returns bytes written
    static std::optional<HostInfo> read(std::span<const uint8_t> in);
};

// A TV's display name. Layout: magic(4) version(1) type(1) length(1) reserved(1) session(4) name(length)
struct NameMessage {
    uint32_t session_id = 0;
    std::string name;  // UTF-8, truncated to kMaxName bytes

    static constexpr size_t kHeader = 12;
    static constexpr size_t kMaxName = 64;

    // Returns the number of bytes written (header + name).
    size_t write(std::span<uint8_t> out) const;
    static std::optional<NameMessage> read(std::span<const uint8_t> in);
};

// TypeText: text typed on the TV's keyboard, to be typed on the PC (menu "Teclado"). The TV sends
// each one a few times (UDP); [seq] lets the PC type it once.
struct TextMessage {
    enum Flags : uint8_t {
        kEnter = 1,      // press Enter after the text
        kBackspace = 2,  // just press Backspace (text ignored)
        kKeys = 4,       // [text] is keys to press together ("alt+enter", "esc", "f11"), not text to type
    };
    uint32_t session_id = 0;
    uint32_t seq = 0;
    uint8_t flags = 0;
    std::string text;  // UTF-8, cut at kMaxText bytes

    static constexpr size_t kHeader = 16;
    static constexpr size_t kMaxText = 200;

    size_t write(std::span<uint8_t> out) const;
    static std::optional<TextMessage> read(std::span<const uint8_t> in);
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
    // Parity fragments sent after the frame's data (fec.h); they carry frag_index frag_count ..
    // frag_count + fec_count - 1. Older TVs skip them (an index past frag_count is rejected).
    uint8_t fec_count = 0;

    static constexpr size_t kSize = 32;

    bool is_keyframe() const { return flags & kFlagKeyframe; }
    bool is_parity() const { return frag_index >= frag_count; }
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
    // When the TV sent it, on the PC's clock (low 32 bits of its microseconds, from the clock
    // sync); 0 = unknown. Lets the PC measure how long controller input takes to arrive.
    uint32_t sent_us = 0;

    // 32 bytes of state + a 16-byte MAC over them, made with the TV's pair key (zeros when the
    // host runs without pairing, e.g. --insecure test mode).
    static constexpr size_t kSize = 48;

    void write(std::span<uint8_t> out, std::span<const uint8_t> key = {}) const;
    static std::optional<InputState> read(std::span<const uint8_t> in);
    static bool verify(std::span<const uint8_t> in, std::span<const uint8_t> key);
};

// "Controle PlayStation no PC": with it the PC plugs a virtual DualShock 4 instead of an Xbox pad,
// and feeds it the touchpad and motion the TV reads from the DualSense / DualShock. Sent with the
// pad's InputState (same session and pad index), whenever touch or motion changes.
struct PadExtras {
    enum Flags : uint8_t {
        kPlayStation = 1,  // plug this pad as a DualShock 4
        kTouchClick = 2,   // the touchpad is pressed down
    };
    struct Touch {
        uint8_t id = 0;
        bool down = false;
        uint16_t x = 0, y = 0;  // DualShock 4 touchpad: x 0..1919, y 0..942
    };
    uint32_t session_id = 0;
    uint8_t pad_index = 0;
    uint8_t flags = 0;
    uint32_t seq = 0;
    Touch touch[2];
    int16_t gyro[3] = {};   // DualShock 4 units: 16 per degree/s (pitch, yaw, roll)
    int16_t accel[3] = {};  // DualShock 4 units: 8192 per g

    static constexpr size_t kSize = 40;
    void write(std::span<uint8_t> out) const;
    static std::optional<PadExtras> read(std::span<const uint8_t> in);
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
