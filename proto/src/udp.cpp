#include "tclproto/udp.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#ifndef SIO_UDP_CONNRESET
#define SIO_UDP_CONNRESET _WSAIOW(IOC_VENDOR, 12)
#endif
#else
#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace tclproto {

namespace {
#ifdef _WIN32
struct WinsockInit {
    WinsockInit() {
        WSADATA d;
        WSAStartup(MAKEWORD(2, 2), &d);
    }
    ~WinsockInit() { WSACleanup(); }
};
void ensure_winsock() { static WinsockInit init; }
int last_error() { return WSAGetLastError(); }
using Native = SOCKET;
#else
using Native = int;
void ensure_winsock() {}
int last_error() { return errno; }
#endif

sockaddr_in to_sockaddr(const Endpoint& e) {
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(e.ip);
    a.sin_port = htons(e.port);
    return a;
}
}  // namespace

std::string Endpoint::to_string() const {
    return std::to_string((ip >> 24) & 255) + "." + std::to_string((ip >> 16) & 255) + "." +
           std::to_string((ip >> 8) & 255) + "." + std::to_string(ip & 255) + ":" +
           std::to_string(port);
}

std::optional<Endpoint> Endpoint::parse(const std::string& ip, uint16_t port) {
    in_addr a{};
    if (inet_pton(AF_INET, ip.c_str(), &a) != 1) return std::nullopt;
    return Endpoint{ntohl(a.s_addr), port};
}

UdpSocket::~UdpSocket() { close(); }

void UdpSocket::set_error(const char* what) {
    error_ = std::string(what) + " failed (error " + std::to_string(last_error()) + ")";
}

bool UdpSocket::open(uint16_t port, int buffer_bytes) {
    ensure_winsock();
    close();
    auto fd = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (fd == Native(kInvalid)) {
        set_error("socket");
        return false;
    }
    fd_ = Handle(fd);

    // Large buffers absorb the burst of datagrams that makes up a keyframe.
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&buffer_bytes), sizeof buffer_bytes);
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&buffer_bytes), sizeof buffer_bytes);

#ifdef _WIN32
    // Otherwise an ICMP "port unreachable" from a departed receiver makes recvfrom fail.
    BOOL report_reset = FALSE;
    DWORD unused = 0;
    WSAIoctl(fd, SIO_UDP_CONNRESET, &report_reset, sizeof report_reset, nullptr, 0, &unused, nullptr, nullptr);
#endif

    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons(port);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0) {
        set_error("bind");
        close();
        return false;
    }
    return true;
}

bool UdpSocket::enable_broadcast() {
    int on = 1;
    if (setsockopt(Native(fd_), SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&on), sizeof on) != 0) {
        set_error("SO_BROADCAST");
        return false;
    }
    return true;
}

bool UdpSocket::set_tos(int tos) {
#ifdef _WIN32
    (void)tos;
    return false;
#else
    return setsockopt(fd_, IPPROTO_IP, IP_TOS, &tos, sizeof tos) == 0;
#endif
}

void UdpSocket::close() {
    if (fd_ == kInvalid) return;
#ifdef _WIN32
    ::closesocket(Native(fd_));
#else
    ::close(fd_);
#endif
    fd_ = kInvalid;
}

bool UdpSocket::send_to(const Endpoint& to, std::span<const uint8_t> data) {
    sockaddr_in a = to_sockaddr(to);
    auto n = ::sendto(Native(fd_), reinterpret_cast<const char*>(data.data()),
                      int(data.size()), 0, reinterpret_cast<sockaddr*>(&a), sizeof a);
    if (n < 0) {
        set_error("sendto");
        return false;
    }
    return true;
}

int UdpSocket::recv_from(std::span<uint8_t> buf, Endpoint& from, int timeout_ms) {
#ifdef _WIN32
    WSAPOLLFD p{Native(fd_), POLLRDNORM, 0};
    int r = WSAPoll(&p, 1, timeout_ms);
#else
    pollfd p{fd_, POLLIN, 0};
    int r = ::poll(&p, 1, timeout_ms);
#endif
    if (r == 0) return 0;
    if (r < 0) {
        set_error("poll");
        return -1;
    }
    sockaddr_in a{};
    socklen_t len = sizeof a;
    auto n = ::recvfrom(Native(fd_), reinterpret_cast<char*>(buf.data()),
                        int(buf.size()), 0, reinterpret_cast<sockaddr*>(&a), &len);
    if (n < 0) {
        set_error("recvfrom");
        return -1;
    }
    from = Endpoint{ntohl(a.sin_addr.s_addr), ntohs(a.sin_port)};
    return int(n);
}

}  // namespace tclproto
