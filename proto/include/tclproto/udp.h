// Thin cross-platform UDP socket (Winsock on Windows, BSD sockets on Android/Linux). IPv4 only:
// TclStream is LAN-only by design.
#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string>

namespace tclproto {

struct Endpoint {
    uint32_t ip = 0;  // host byte order
    uint16_t port = 0;

    bool operator==(const Endpoint&) const = default;
    std::string to_string() const;
    static std::optional<Endpoint> parse(const std::string& ip, uint16_t port);
    static Endpoint broadcast(uint16_t port) { return {0xFFFFFFFFu, port}; }
};

class UdpSocket {
public:
    UdpSocket() = default;
    ~UdpSocket();
    UdpSocket(const UdpSocket&) = delete;
    UdpSocket& operator=(const UdpSocket&) = delete;

    // Binds to 0.0.0.0:port (port 0 = ephemeral). Returns false and fills error() on failure.
    bool open(uint16_t port, int buffer_bytes = 4 * 1024 * 1024);
    void close();
    // Allows sending to 255.255.255.255 (used by receivers to find the host without typing an IP).
    bool enable_broadcast();
    bool is_open() const { return fd_ != kInvalid; }

    bool send_to(const Endpoint& to, std::span<const uint8_t> data);
    // Waits up to timeout_ms. Returns bytes received, 0 on timeout, -1 on error.
    int recv_from(std::span<uint8_t> buf, Endpoint& from, int timeout_ms);

    const std::string& error() const { return error_; }

private:
#ifdef _WIN32
    using Handle = uintptr_t;
    static constexpr Handle kInvalid = ~Handle(0);
#else
    using Handle = int;
    static constexpr Handle kInvalid = -1;
#endif
    void set_error(const char* what);

    Handle fd_ = kInvalid;
    std::string error_;
};

}  // namespace tclproto
