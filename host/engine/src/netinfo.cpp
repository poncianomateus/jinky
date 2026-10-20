#include "netinfo.h"

#include <winsock2.h>
#include <iphlpapi.h>
#include <windows.h>

#include <cstring>
#include <vector>

namespace tcl {

std::string computer_name() {
    wchar_t w[256];
    DWORD n = 256;
    if (!GetComputerNameExW(ComputerNamePhysicalDnsHostname, w, &n)) return "PC";
    const int len = WideCharToMultiByte(CP_UTF8, 0, w, int(n), nullptr, 0, nullptr, nullptr);
    std::string s(size_t(len), ' ');
    WideCharToMultiByte(CP_UTF8, 0, w, int(n), s.data(), len, nullptr, nullptr);
    return s;
}

std::array<uint8_t, 6> mac_toward(uint32_t ip) {
    std::array<uint8_t, 6> mac{};
    DWORD if_index = 0;
    if (GetBestInterface(htonl(ip), &if_index) != NO_ERROR) return mac;
    ULONG size = 16 * 1024;
    std::vector<uint8_t> buf(size);
    auto* list = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
                             nullptr, list, &size) == ERROR_BUFFER_OVERFLOW) {
        buf.resize(size);
        list = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
        if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
                                 nullptr, list, &size) != NO_ERROR)
            return mac;
    }
    for (auto* a = list; a; a = a->Next) {
        if (a->IfIndex == if_index && a->PhysicalAddressLength == 6) {
            std::memcpy(mac.data(), a->PhysicalAddress, 6);
            break;
        }
    }
    return mac;
}

}  // namespace tcl
