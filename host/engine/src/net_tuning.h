// Network tweaks while a TV is watching, the same ones other streaming hosts use:
// - QoS: packets to the TV go in the Wi-Fi "video" queue (WMM) and carry a video DSCP mark, so
//   routers and the access point let them jump ahead of downloads and other bulk traffic;
// - Wi-Fi: the PC's adapter stops background network scans (each one pauses the radio for tens of
//   milliseconds, a classic cause of periodic lag spikes) and enters media streaming mode.
// Everything is best effort: a call the driver refuses just leaves things as they were.
#pragma once

#include <winsock2.h>
#include <windows.h>

#include <optional>
#include <string>

#include "tclproto/udp.h"

namespace tcl {

class StreamNetTuning {
public:
    ~StreamNetTuning();

    // Marks traffic from `sock` to `peer` as video; nullopt removes the mark.
    void set_peer(uintptr_t sock, const std::optional<tclproto::Endpoint>& peer);

    // While true, the PC's connected Wi-Fi adapters skip background scans and stream-optimise.
    void set_streaming(bool on);

private:
    HANDLE qos_ = nullptr;
    uint32_t flow_ = 0;
    uintptr_t flow_sock_ = 0;
    HANDLE wlan_ = nullptr;
    bool streaming_ = false;
};

// Asks the GPU scheduler to run this process's work (capture, colour conversion, encode) ahead of
// the game's frames when the GPU is fully loaded. "High", not "realtime": realtime is known to
// hang NVENC with hardware-accelerated GPU scheduling.
bool raise_gpu_scheduling_priority();

}  // namespace tcl
