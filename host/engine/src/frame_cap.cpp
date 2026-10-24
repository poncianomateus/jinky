#include "frame_cap.h"

#include <windows.h>

#include <cstdlib>
#include <fstream>
#include <string>

#include "log.h"
#include "nvapi.h"
#include "NvApiDriverSettings.h"  // after nvapi.h (uses its types)

namespace tcl {

namespace {

// %LOCALAPPDATA%\Jinky\frame-cap.flag holds the user's own value while the cap is on:
// a number, or "none" when the global profile had no value of its own.
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

    // The cap in effect (0 = none) and whether the global profile holds it itself (rather than
    // inheriting a default).
    void read(NvU32& value, bool& own) {
        NVDRS_SETTING st{};
        st.version = NVDRS_SETTING_VER;
        value = 0;
        own = false;
        if (NvAPI_DRS_GetSetting(session_, profile_, FRL_FPS_ID, &st) != NVAPI_OK) return;
        value = st.u32CurrentValue;
        own = st.settingLocation == NVDRS_CURRENT_PROFILE_LOCATION;
    }

    NvAPI_Status set(NvU32 value) {
        NVDRS_SETTING st{};
        st.version = NVDRS_SETTING_VER;
        st.settingId = FRL_FPS_ID;
        st.settingType = NVDRS_DWORD_TYPE;
        st.u32CurrentValue = value;
        return NvAPI_DRS_SetSetting(session_, profile_, &st);
    }
    NvAPI_Status remove() { return NvAPI_DRS_DeleteProfileSetting(session_, profile_, FRL_FPS_ID); }
    NvAPI_Status save() { return NvAPI_DRS_SaveSettings(session_); }

private:
    NvDRSSessionHandle session_ = nullptr;
    NvDRSProfileHandle profile_ = nullptr;
};

// Puts the user's value (from the marker) back. True when the driver has it again.
bool restore_from(const std::wstring& marker) {
    std::string previous;
    std::getline(std::ifstream(marker), previous);
    Drs drs;
    if (!drs.open()) return false;
    NvAPI_Status s = NVAPI_OK;
    if (previous == "none") {
        s = drs.remove();
        if (s == NVAPI_SETTING_NOT_FOUND) s = NVAPI_OK;
    } else {
        s = drs.set(NvU32(std::strtoul(previous.c_str(), nullptr, 10)));
    }
    if (s == NVAPI_OK) s = drs.save();
    if (s != NVAPI_OK) {
        TCL_WARN("framecap", "could not restore the NVIDIA frame rate limit: {}", message(s));
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
    NvU32 value = 0;
    bool own = false;
    drs.read(value, own);
    if (value != 0 && value <= fps) {
        TCL_INFO("framecap", "games already capped at {} fps by the NVIDIA settings", value);
        return false;
    }
    // Note the user's value first: if the host dies with the cap on, the next start restores it.
    const auto marker = marker_path();
    if (marker.empty()) return false;
    {
        std::ofstream out(marker);
        out << (own ? std::to_string(value) : std::string("none"));
        if (!out) return false;
    }
    NvAPI_Status s = drs.set(fps);
    if (s == NVAPI_OK) s = drs.save();
    if (s != NVAPI_OK) {
        TCL_WARN("framecap", "could not cap games at {} fps: {}", fps, message(s));
        DeleteFileW(marker.c_str());
        return false;
    }
    active_ = true;
    TCL_INFO("framecap", "games started from now on are capped at {} fps (NVIDIA driver; was {})", fps,
             value ? std::to_string(value) : std::string("no limit"));
    return true;
}

void FrameCap::leave() {
    if (!active_) return;
    active_ = false;
    const auto marker = marker_path();
    if (!marker.empty() && restore_from(marker)) TCL_INFO("framecap", "NVIDIA frame rate limit restored");
}

bool FrameCap::restore_if_left() {
    const auto marker = marker_path();
    if (marker.empty() || GetFileAttributesW(marker.c_str()) == INVALID_FILE_ATTRIBUTES) return false;
    return restore_from(marker);
}

}  // namespace tcl
