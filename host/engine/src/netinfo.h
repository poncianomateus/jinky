// Facts about this PC that the TV's home screen shows or needs (name, Wake-on-LAN address).
#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace tcl {

// The PC's name as the user sees it in Windows (e.g. "DESKTOP-BF21OEI"), UTF-8.
std::string computer_name();

// MAC address of the network adapter Windows uses to reach `ip` (host byte order): the one a
// Wake-on-LAN packet from that TV must target. Zeros if unknown.
std::array<uint8_t, 6> mac_toward(uint32_t ip);

}  // namespace tcl
