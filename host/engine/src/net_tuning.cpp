#include "net_tuning.h"

#include <qos2.h>
#include <wlanapi.h>
#include <ws2tcpip.h>

#include "log.h"

namespace tcl {

StreamNetTuning::~StreamNetTuning() {
    set_peer(0, std::nullopt);
    set_streaming(false);
    if (qos_) QOSCloseHandle(qos_);
}

void StreamNetTuning::set_peer(uintptr_t sock, const std::optional<tclproto::Endpoint>& peer) {
    if (flow_) {
        QOSRemoveSocketFromFlow(qos_, SOCKET(flow_sock_), flow_, 0);
        flow_ = 0;
    }
    if (!peer) return;
    if (!qos_) {
        QOS_VERSION v{1, 0};
        if (!QOSCreateHandle(&v, &qos_)) {
            TCL_WARN("net", "QoS unavailable (error {})", GetLastError());
            qos_ = nullptr;
            return;
        }
    }
    sockaddr_in to{};
    to.sin_family = AF_INET;
    to.sin_addr.s_addr = htonl(peer->ip);
    to.sin_port = htons(peer->port);
    QOS_FLOWID id = 0;
    if (QOSAddSocketToFlow(qos_, SOCKET(sock), reinterpret_cast<sockaddr*>(&to), QOSTrafficTypeAudioVideo,
                           QOS_NON_ADAPTIVE_FLOW, &id)) {
        flow_ = id;
        flow_sock_ = sock;
        TCL_INFO("net", "packets to the TV marked as video traffic (QoS)");
    } else {
        TCL_WARN("net", "could not mark packets as video traffic (error {})", GetLastError());
    }
}

void StreamNetTuning::set_streaming(bool on) {
    if (on == streaming_) return;
    streaming_ = on;
    if (on && !wlan_) {
        DWORD version = 0;
        if (WlanOpenHandle(2, nullptr, &version, &wlan_) != ERROR_SUCCESS) {
            wlan_ = nullptr;
            return;  // no Wi-Fi service (wired PC): nothing to do
        }
    }
    if (!wlan_) return;
    PWLAN_INTERFACE_INFO_LIST list = nullptr;
    if (WlanEnumInterfaces(wlan_, nullptr, &list) == ERROR_SUCCESS) {
        for (DWORD i = 0; i < list->dwNumberOfItems; ++i) {
            const auto& itf = list->InterfaceInfo[i];
            if (itf.isState != wlan_interface_state_connected) continue;
            BOOL scan = on ? FALSE : TRUE, media = on ? TRUE : FALSE;
            const DWORD a = WlanSetInterface(wlan_, &itf.InterfaceGuid, wlan_intf_opcode_background_scan_enabled,
                                             sizeof scan, &scan, nullptr);
            const DWORD b = WlanSetInterface(wlan_, &itf.InterfaceGuid, wlan_intf_opcode_media_streaming_mode,
                                             sizeof media, &media, nullptr);
            if (on)
                TCL_INFO("net", "Wi-Fi tuned for streaming: background scans {}, media streaming mode {}",
                         a == ERROR_SUCCESS ? "off" : "unchanged (error " + std::to_string(a) + ")",
                         b == ERROR_SUCCESS ? "on" : "unchanged (error " + std::to_string(b) + ")");
        }
        WlanFreeMemory(list);
    }
    if (!on) {
        // Closing the handle also hands the settings back to Windows.
        WlanCloseHandle(wlan_, nullptr);
        wlan_ = nullptr;
    }
}

bool raise_gpu_scheduling_priority() {
    // D3DKMT_SCHEDULINGPRIORITYCLASS_HIGH; the function lives in gdi32 (WDK header not needed).
    using Fn = LONG(APIENTRY*)(HANDLE, int);
    const auto fn = reinterpret_cast<Fn>(
        GetProcAddress(GetModuleHandleW(L"gdi32.dll"), "D3DKMTSetProcessSchedulingPriorityClass"));
    return fn && fn(GetCurrentProcess(), 4) >= 0;
}

}  // namespace tcl
