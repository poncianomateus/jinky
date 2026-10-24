#include "frame_cap.h"

#include <windows.h>

#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "log.h"
#include "nvapi.h"
#include "NvApiDriverSettings.h"  // after nvapi.h (uses its types)

namespace tcl {

namespace {

// %LOCALAPPDATA%\Jinky\frame-cap.flag holds the user's own values while the settings are
// changed, one "<setting id> <value>" line each ("none" when the global profile had no value of
// its own). A file from 0.6.3-0.6.5 holds just the frame rate limit's value.
std::wstring marker_path() {
    wchar_t base[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    std::wstring dir = std::wstring(base) + L"\\Jinky";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir + L"\\frame-cap.flag";
}

std::string message(NvAPI_Status s) {
    NvAPI_ShortString text{};
    NvAPI_GetErrorMessage(s, text);
    return std::string(text) + " (" + std::to_string(int(s)) + ")";
}

bool initialized() {
    static const NvAPI_Status status = NvAPI_Initialize();  // fails without an NVIDIA driver
    return status == NVAPI_OK;
}

// The driver's settings database, opened on the global profile (what "Global settings" in the
// NVIDIA control panel edits).
class Drs {
public:
    bool open() {
        if (!initialized()) return false;
        NvAPI_Status s = NvAPI_DRS_CreateSession(&session_);
        if (s == NVAPI_OK) s = NvAPI_DRS_LoadSettings(session_);
        if (s == NVAPI_OK) s = NvAPI_DRS_GetCurrentGlobalProfile(session_, &profile_);
        if (s != NVAPI_OK) TCL_WARN("framecap", "could not read the NVIDIA driver settings: {}", message(s));
        return s == NVAPI_OK;
    }
    ~Drs() {
        if (session_) NvAPI_DRS_DestroySession(session_);
    }

    // The value in effect (0 = none/default) and whether the global profile holds it itself
    // (rather than inheriting a default).
    void read(NvU32 id, NvU32& value, bool& own) {
        NVDRS_SETTING st{};
        st.version = NVDRS_SETTING_VER;
        value = 0;
        own = false;
        if (NvAPI_DRS_GetSetting(session_, profile_, id, &st) != NVAPI_OK) return;
        value = st.u32CurrentValue;
        own = st.settingLocation == NVDRS_CURRENT_PROFILE_LOCATION;
    }

    NvAPI_Status set(NvU32 id, NvU32 value) {
        NVDRS_SETTING st{};
        st.version = NVDRS_SETTING_VER;
        st.settingId = id;
        st.settingType = NVDRS_DWORD_TYPE;
        st.u32CurrentValue = value;
        return NvAPI_DRS_SetSetting(session_, profile_, &st);
    }
    NvAPI_Status remove(NvU32 id) {
        const NvAPI_Status s = NvAPI_DRS_DeleteProfileSetting(session_, profile_, id);
        return s == NVAPI_SETTING_NOT_FOUND ? NVAPI_OK : s;
    }
    NvAPI_Status save() { return NvAPI_DRS_SaveSettings(session_); }

private:
    NvDRSSessionHandle session_ = nullptr;
    NvDRSProfileHandle profile_ = nullptr;
};

// Puts the user's values (from the marker) back. True when the driver has them again.
bool restore_from(const std::wstring& marker) {
    std::vector<std::pair<NvU32, std::string>> previous;
    {
        std::ifstream in(marker);
        std::string line;
        while (std::getline(in, line)) {
            std::istringstream fields(line);
            std::string a, b;
            fields >> a >> b;
            if (a.empty()) continue;
            if (b.empty()) previous.emplace_back(FRL_FPS_ID, a);  // old one-value marker
            else previous.emplace_back(NvU32(std::strtoul(a.c_str(), nullptr, 16)), b);
        }
    }
    Drs drs;
    if (!drs.open()) return false;
    NvAPI_Status s = NVAPI_OK;
    for (const auto& [id, value] : previous) {
        s = value == "none" ? drs.remove(id) : drs.set(id, NvU32(std::strtoul(value.c_str(), nullptr, 10)));
        if (s != NVAPI_OK) break;
    }
    if (s == NVAPI_OK) s = drs.save();
    if (s != NVAPI_OK) {
        TCL_WARN("framecap", "could not restore the NVIDIA settings: {}", message(s));
        return false;
    }
    DeleteFileW(marker.c_str());
    return true;
}

}  // namespace

bool FrameCap::enter(uint32_t fps) {
    if (active_) return true;
    Drs drs;
    if (!drs.open()) return false;

    // What to change: the frame rate limit (unless the user already caps at fps or lower), and
    // "Low Latency Mode" = at most 1 frame queued ahead of the GPU (DX9-DX11 games; DX12 and
    // Vulkan games manage their own queue), so a button press reaches the screen a frame or two
    // sooner. Each user's value is noted first, so the next start restores it if the host dies.
    struct Change {
        NvU32 id, value;
        const char* what;
        NvU32 before;
        bool own;
    };
    std::vector<Change> changes;
    NvU32 value = 0;
    bool own = false;
    drs.read(FRL_FPS_ID, value, own);
    if (value != 0 && value <= fps) TCL_INFO("framecap", "games already capped at {} fps by the NVIDIA settings", value);
    else changes.push_back({FRL_FPS_ID, fps, "frame rate limit", value, own});
    drs.read(PRERENDERLIMIT_ID, value, own);
    if (value != 1) changes.push_back({PRERENDERLIMIT_ID, 1, "pre-rendered frames", value, own});
    if (changes.empty()) return false;

    const auto marker = marker_path();
    if (marker.empty()) return false;
    {
        std::ofstream out(marker);
        for (const auto& c : changes)
            out << std::hex << c.id << std::dec << ' ' << (c.own ? std::to_string(c.before) : std::string("none")) << '\n';
        if (!out) return false;
    }
    NvAPI_Status s = NVAPI_OK;
    for (const auto& c : changes) {
        s = drs.set(c.id, c.value);
        if (s != NVAPI_OK) break;
    }
    if (s == NVAPI_OK) s = drs.save();
    if (s != NVAPI_OK) {
        TCL_WARN("framecap", "could not change the NVIDIA settings: {}", message(s));
        DeleteFileW(marker.c_str());
        return false;
    }
    active_ = true;
    for (const auto& c : changes)
        TCL_INFO("framecap", "{} set to {} for games started from now on (NVIDIA driver; was {})", c.what, c.value,
                 c.before ? std::to_string(c.before) : std::string("default"));
    return true;
}

void FrameCap::leave() {
    if (!active_) return;
    active_ = false;
    const auto marker = marker_path();
    if (!marker.empty() && restore_from(marker)) TCL_INFO("framecap", "NVIDIA settings restored");
}

bool FrameCap::restore_if_left() {
    const auto marker = marker_path();
    if (marker.empty() || GetFileAttributesW(marker.c_str()) == INVALID_FILE_ATTRIBUTES) return false;
    return restore_from(marker);
}

}  // namespace tcl
