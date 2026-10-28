// "Monitor virtual": during a TV session the PC's desktop lives on a virtual screen at the TV's
// size and rate, and the PC's own monitors go dark, like a console. The real monitors are never
// changed (no 60 Hz, no resolution switch); when the session ends Windows' layout comes back
// exactly as it was.
//
// The screen comes from the Virtual Display Driver (github.com/VirtualDrivers/Virtual-Display-Driver,
// MIT licence, signed by the SignPath Foundation), an Indirect Display driver. Its monitor reports
// itself as "MTT1337"; it reads its modes from vdd_settings.xml in the folder named by
// HKLM\SOFTWARE\MikeTheTech\VirtualDisplayDriver\VDDPATH.
#pragma once

#include <windows.h>

#include <cstdint>
#include <string>
#include <vector>

namespace tcl {

class VirtualDisplay {
public:
    // The driver is installed and its screen is connected (in the desktop or not).
    static bool available();
    // The driver's device is installed (its screen may still be missing).
    static bool installed();
    // ASTER (several people on one PC, each with their own monitors) runs here: its display
    // filter takes new screens over, and switching monitors would darken the other workplaces.
    // No virtual screen then.
    static bool multiseat();

    // Makes the virtual screen, at width x height @ hz, the whole desktop (the PC's monitors go
    // dark). On success `gdi_name` is its GDI name (\\.\DISPLAYn), for the capture.
    bool enter(uint32_t width, uint32_t height, uint32_t hz, std::wstring& gdi_name);
    // Back to the layout from before enter().
    void leave();
    bool active() const { return active_; }

    // Outside a session the virtual screen must not be part of the desktop (windows would get
    // lost on a screen nobody sees): takes it out and saves that as Windows' layout. First undoes a
    // session left behind by a host that was killed. Returns true if it changed anything.
    static bool tidy();

    // jinky-engine --install-virtual-display <folder with MttVDD.inf> [--lift-display-policy]
    // (as administrator). With lift_policy, a Windows policy blocking display drivers is set
    // aside for the install and put back right after.
    static int install(const std::wstring& folder, bool lift_policy = false);
    // jinky-engine --uninstall-virtual-display (as administrator).
    static int uninstall();

private:
    bool active_ = false;
    std::vector<DISPLAYCONFIG_PATH_INFO> saved_paths_;
    std::vector<DISPLAYCONFIG_MODE_INFO> saved_modes_;
};

}  // namespace tcl
