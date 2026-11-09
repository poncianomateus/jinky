#include "virtual_display.h"

// clang-format off
#include <windows.h>
#include <setupapi.h>
#include <devguid.h>
#include <newdev.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
// clang-format on

#include <algorithm>
#include <chrono>
#include <cwctype>
#include <fstream>
#include <thread>

#include "log.h"

namespace tcl {

namespace {

constexpr wchar_t kHardwareId[] = L"Root\\MttVDD";
constexpr wchar_t kDriverKey[] = L"SOFTWARE\\MikeTheTech\\VirtualDisplayDriver";

std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), int(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(size_t(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), int(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring marker_path() {
    wchar_t base[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    std::wstring dir = std::wstring(base) + L"\\Jinky";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir + L"\\virtual-display.flag";
}

bool query(UINT32 flags, std::vector<DISPLAYCONFIG_PATH_INFO>& paths, std::vector<DISPLAYCONFIG_MODE_INFO>& modes) {
    for (int tries = 0; tries < 4; ++tries) {
        UINT32 np = 0, nm = 0;
        if (GetDisplayConfigBufferSizes(flags, &np, &nm) != ERROR_SUCCESS) return false;
        paths.resize(np);
        modes.resize(nm);
        const LONG r = QueryDisplayConfig(flags, &np, paths.data(), &nm, modes.data(), nullptr);
        if (r == ERROR_INSUFFICIENT_BUFFER) continue;  // the layout changed in between
        if (r != ERROR_SUCCESS) return false;
        paths.resize(np);
        modes.resize(nm);
        return true;
    }
    return false;
}

// The driver's monitor (EDID maker "MTT", product 0x1337).
bool is_virtual(const DISPLAYCONFIG_PATH_INFO& p) {
    DISPLAYCONFIG_TARGET_DEVICE_NAME n{};
    n.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME;
    n.header.size = sizeof n;
    n.header.adapterId = p.targetInfo.adapterId;
    n.header.id = p.targetInfo.id;
    if (DisplayConfigGetDeviceInfo(&n.header) != ERROR_SUCCESS) return false;
    std::wstring path = n.monitorDevicePath;
    std::transform(path.begin(), path.end(), path.begin(), [](wchar_t c) { return wchar_t(std::towupper(c)); });
    return path.find(L"MTT1337") != std::wstring::npos;
}

std::wstring source_name(const DISPLAYCONFIG_PATH_INFO& p) {
    DISPLAYCONFIG_SOURCE_DEVICE_NAME n{};
    n.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
    n.header.size = sizeof n;
    n.header.adapterId = p.sourceInfo.adapterId;
    n.header.id = p.sourceInfo.id;
    if (DisplayConfigGetDeviceInfo(&n.header) != ERROR_SUCCESS) return {};
    return n.viewGdiDeviceName;
}

// Keeps the paths `keep` says yes to, with only the modes they use (indices renumbered).
void keep_only(std::vector<DISPLAYCONFIG_PATH_INFO>& paths, std::vector<DISPLAYCONFIG_MODE_INFO>& modes,
               bool (*keep)(const DISPLAYCONFIG_PATH_INFO&)) {
    std::vector<DISPLAYCONFIG_PATH_INFO> out_paths;
    std::vector<DISPLAYCONFIG_MODE_INFO> out_modes;
    auto remap = [&](UINT32& idx) {
        if (idx == DISPLAYCONFIG_PATH_MODE_IDX_INVALID || idx >= modes.size()) return;
        out_modes.push_back(modes[idx]);
        idx = UINT32(out_modes.size() - 1);
    };
    for (auto p : paths) {
        if (!keep(p)) continue;
        remap(p.sourceInfo.modeInfoIdx);
        remap(p.targetInfo.modeInfoIdx);
        out_paths.push_back(p);
    }
    paths.swap(out_paths);
    modes.swap(out_modes);
}

bool not_virtual(const DISPLAYCONFIG_PATH_INFO& p) { return !is_virtual(p); }

// Windows' saved layout for the monitors connected now; without one, every monitor on (tidy()
// then takes the virtual screen out again). Never leaves the PC without its monitors.
bool apply_saved_layout() {
    if (SetDisplayConfig(0, nullptr, 0, nullptr, SDC_APPLY | SDC_USE_DATABASE_CURRENT) == ERROR_SUCCESS) return true;
    return SetDisplayConfig(0, nullptr, 0, nullptr, SDC_APPLY | SDC_TOPOLOGY_EXTEND) == ERROR_SUCCESS;
}

// Every device made for the driver (Root\MttVDD), whatever its class: one that never got its
// driver has none yet.
template <class Pred>
bool for_each_device(Pred pred) {
    HDEVINFO set = SetupDiGetClassDevsW(nullptr, nullptr, nullptr, DIGCF_ALLCLASSES | DIGCF_PRESENT);
    if (set == INVALID_HANDLE_VALUE) return false;
    SP_DEVINFO_DATA d{};
    d.cbSize = sizeof d;
    bool any = false;
    for (DWORD i = 0; SetupDiEnumDeviceInfo(set, i, &d); ++i) {
        wchar_t ids[512] = {};
        if (!SetupDiGetDeviceRegistryPropertyW(set, &d, SPDRP_HARDWAREID, nullptr, reinterpret_cast<BYTE*>(ids),
                                               sizeof ids - sizeof(wchar_t), nullptr))
            continue;
        for (const wchar_t* id = ids; *id; id += wcslen(id) + 1) {
            if (_wcsicmp(id, kHardwareId) == 0) {
                any = true;
                pred(set, d);
                break;
            }
        }
    }
    SetupDiDestroyDeviceInfoList(set);
    return any;
}

// "Prevent installation of devices using drivers that match these device setup classes" with the
// display class (often set so Windows Update can't replace the NVIDIA driver) blocks this driver.
constexpr wchar_t kDenyClassesKey[] = L"SOFTWARE\\Policies\\Microsoft\\Windows\\DeviceInstall\\Restrictions\\DenyDeviceClasses";
constexpr wchar_t kDisplayClass[] = L"{4d36e968-e325-11ce-bfc1-08002be10318}";

// Takes the display class out of that list, returning the entries taken (name, value) so
// restore_display_block() puts back exactly them.
std::vector<std::pair<std::wstring, std::wstring>> lift_display_block() {
    std::vector<std::pair<std::wstring, std::wstring>> taken;
    HKEY key;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, kDenyClassesKey, 0, KEY_READ | KEY_SET_VALUE, &key) != ERROR_SUCCESS) return taken;
    for (DWORD i = 0;; ++i) {
        wchar_t name[256], value[256];
        DWORD name_len = 256, value_size = sizeof value - sizeof(wchar_t), type = 0;
        const LONG r = RegEnumValueW(key, i, name, &name_len, nullptr, &type, reinterpret_cast<BYTE*>(value), &value_size);
        if (r == ERROR_NO_MORE_ITEMS) break;
        if (r != ERROR_SUCCESS || type != REG_SZ) continue;
        value[value_size / sizeof(wchar_t)] = 0;
        if (_wcsicmp(value, kDisplayClass) == 0) taken.emplace_back(name, value);
    }
    for (const auto& [name, value] : taken) RegDeleteValueW(key, name.c_str());
    RegCloseKey(key);
    if (!taken.empty()) TCL_INFO("vdisplay", "display driver block lifted for the install ({} entr{})", taken.size(), taken.size() == 1 ? "y" : "ies");
    return taken;
}

void restore_display_block(const std::vector<std::pair<std::wstring, std::wstring>>& taken) {
    if (taken.empty()) return;
    HKEY key;
    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, kDenyClassesKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) != ERROR_SUCCESS) {
        TCL_ERROR("vdisplay", "could not put the display driver block back");
        return;
    }
    for (const auto& [name, value] : taken)
        RegSetValueExW(key, name.c_str(), 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()), DWORD((value.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    TCL_INFO("vdisplay", "display driver block put back as it was");
}

std::wstring settings_folder() {
    wchar_t base[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"ProgramData", base, MAX_PATH);
    std::wstring root = n && n < MAX_PATH ? std::wstring(base) : L"C:\\ProgramData";
    return root + L"\\Jinky\\VirtualDisplay";
}

// The GPU that renders the virtual screen: the NVIDIA one (the capture and NVENC live there).
std::wstring render_gpu_name() {
    Microsoft::WRL::ComPtr<IDXGIFactory1> f;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&f)))) return L"default";
    Microsoft::WRL::ComPtr<IDXGIAdapter1> a;
    for (UINT i = 0; f->EnumAdapters1(i, &a) != DXGI_ERROR_NOT_FOUND; ++i) {
        DXGI_ADAPTER_DESC1 d;
        if (SUCCEEDED(a->GetDesc1(&d)) && d.VendorId == 0x10DE && !(d.Flags & DXGI_ADAPTER_FLAG_SOFTWARE)) return d.Description;
    }
    return L"default";
}

bool write_settings(const std::wstring& folder) {
    std::string gpu = narrow(render_gpu_name());
    for (char& c : gpu)
        if (c == '<' || c == '>' || c == '&') c = ' ';
    std::ofstream out(folder + L"\\vdd_settings.xml", std::ios::binary | std::ios::trunc);
    if (!out) return false;
    // One screen with only the sizes a TV asks for (1080p and "720p") at 60 Hz. Nothing bigger:
    // a game on its own picks the largest size it is offered (4K came out cropped on the TV).
    out << "<?xml version='1.0' encoding='utf-8'?>\n"
           "<vdd_settings>\n"
           "    <monitors><count>1</count></monitors>\n"
           "    <gpu><friendlyname>" << gpu << "</friendlyname></gpu>\n"
           "    <global>\n"
           "        <g_refresh_rate>60</g_refresh_rate>\n"
           "    </global>\n"
           "    <resolutions>\n"
           "        <resolution><width>1280</width><height>720</height><refresh_rate>60</refresh_rate></resolution>\n"
           "        <resolution><width>1920</width><height>1080</height><refresh_rate>60</refresh_rate></resolution>\n"
           "    </resolutions>\n"
           "    <options>\n"
           "        <CustomEdid>false</CustomEdid>\n"
           "        <PreventSpoof>false</PreventSpoof>\n"
           "        <EdidCeaOverride>false</EdidCeaOverride>\n"
           "        <HardwareCursor>true</HardwareCursor>\n"
           "        <SDR10bit>false</SDR10bit>\n"
           "        <HDRPlus>false</HDRPlus>\n"
           "        <logging>false</logging>\n"
           "        <debuglogging>false</debuglogging>\n"
           "    </options>\n"
           "</vdd_settings>\n";
    return bool(out);
}

}  // namespace

bool VirtualDisplay::installed() {
    return for_each_device([](HDEVINFO, SP_DEVINFO_DATA&) {});
}

bool VirtualDisplay::multiseat() {
    // ASTER's kernel driver (mutenx.sys, IBIK LLC).
    HKEY key;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SYSTEM\\CurrentControlSet\\Services\\MUTENX_SERVICE", 0, KEY_READ, &key) != ERROR_SUCCESS)
        return false;
    RegCloseKey(key);
    return true;
}

bool VirtualDisplay::available() {
    std::vector<DISPLAYCONFIG_PATH_INFO> paths;
    std::vector<DISPLAYCONFIG_MODE_INFO> modes;
    if (!query(QDC_ALL_PATHS, paths, modes)) return false;
    return std::any_of(paths.begin(), paths.end(),
                       [](const DISPLAYCONFIG_PATH_INFO& p) { return p.targetInfo.targetAvailable && is_virtual(p); });
}

bool VirtualDisplay::enter(uint32_t width, uint32_t height, uint32_t hz, std::wstring& gdi_name) {
    if (active_) return false;
    if (multiseat()) {
        TCL_WARN("vdisplay", "ASTER runs on this PC: no virtual screen (it would darken the other workplaces)");
        return false;
    }
    std::vector<DISPLAYCONFIG_PATH_INFO> paths;
    std::vector<DISPLAYCONFIG_MODE_INFO> modes;
    if (!query(QDC_ONLY_ACTIVE_PATHS, saved_paths_, saved_modes_) || !query(QDC_ALL_PATHS, paths, modes)) {
        TCL_WARN("vdisplay", "could not read the display layout");
        return false;
    }
    auto it = std::find_if(paths.begin(), paths.end(),
                           [](const DISPLAYCONFIG_PATH_INFO& p) { return p.targetInfo.targetAvailable && is_virtual(p); });
    if (it == paths.end()) {
        TCL_WARN("vdisplay", "the virtual screen is not there (driver not installed or disabled)");
        return false;
    }
    // The layout of now becomes Windows' saved one, so whatever happens (even this host dying)
    // there is always this to go back to. Applying the layout already on changes nothing on screen.
    SetDisplayConfig(UINT32(saved_paths_.size()), saved_paths_.data(), UINT32(saved_modes_.size()), saved_modes_.data(),
                     SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES | SDC_SAVE_TO_DATABASE);
    DISPLAYCONFIG_PATH_INFO only = *it;
    only.flags = DISPLAYCONFIG_PATH_ACTIVE;
    only.sourceInfo.modeInfoIdx = DISPLAYCONFIG_PATH_MODE_IDX_INVALID;
    only.targetInfo.modeInfoIdx = DISPLAYCONFIG_PATH_MODE_IDX_INVALID;
    // The marker first: if the host dies with the PC's monitors dark, the next start brings them back.
    const auto marker = marker_path();
    if (!marker.empty()) std::ofstream(marker) << "on";
    const LONG r = SetDisplayConfig(1, &only, 0, nullptr, SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES);
    if (r != ERROR_SUCCESS) {
        if (!marker.empty()) DeleteFileW(marker.c_str());
        TCL_WARN("vdisplay", "could not switch to the virtual screen (error {})", r);
        return false;
    }
    active_ = true;

    // Its GDI name, once Windows has it in the desktop.
    std::wstring name;
    for (int i = 0; i < 30 && name.empty(); ++i) {
        std::vector<DISPLAYCONFIG_PATH_INFO> act;
        std::vector<DISPLAYCONFIG_MODE_INFO> am;
        if (query(QDC_ONLY_ACTIVE_PATHS, act, am))
            for (const auto& p : act)
                if (is_virtual(p)) name = source_name(p);
        if (name.empty()) std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    if (name.empty()) {
        TCL_WARN("vdisplay", "the virtual screen did not come up");
        leave();
        return false;
    }
    // The TV's size and rate (only for now: nothing is saved as the user's setting).
    DEVMODEW dm{};
    dm.dmSize = sizeof dm;
    dm.dmPelsWidth = width;
    dm.dmPelsHeight = height;
    dm.dmDisplayFrequency = hz;
    dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY;
    LONG c = ChangeDisplaySettingsExW(name.c_str(), &dm, nullptr, 0, nullptr);
    if (c != DISP_CHANGE_SUCCESSFUL) {
        dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT;
        c = ChangeDisplaySettingsExW(name.c_str(), &dm, nullptr, 0, nullptr);
    }
    if (c != DISP_CHANGE_SUCCESSFUL) TCL_WARN("vdisplay", "could not set the virtual screen to {}x{} (error {})", width, height, c);
    TCL_INFO("vdisplay", "virtual screen {} on: {}x{} @ {} Hz, the PC's monitors are dark", narrow(name), width, height, hz);
    emit_event("virtual_display", "\"on\":true");
    gdi_name = name;
    return true;
}

void VirtualDisplay::leave() {
    if (!active_) return;
    active_ = false;
    LONG r = ERROR_INVALID_PARAMETER;
    if (!saved_paths_.empty())
        r = SetDisplayConfig(UINT32(saved_paths_.size()), saved_paths_.data(), UINT32(saved_modes_.size()), saved_modes_.data(),
                             SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES);
    if (r != ERROR_SUCCESS && !apply_saved_layout()) TCL_WARN("vdisplay", "could not bring the PC's monitors back (error {})", r);
    else TCL_INFO("vdisplay", "virtual screen off: the PC's monitors are back as they were");
    if (const auto marker = marker_path(); !marker.empty()) DeleteFileW(marker.c_str());
    emit_event("virtual_display", "\"on\":false");
}

bool VirtualDisplay::tidy() {
    bool changed = false;
    if (const auto marker = marker_path(); !marker.empty() && GetFileAttributesW(marker.c_str()) != INVALID_FILE_ATTRIBUTES) {
        // A host died during a session: Windows' saved layout has the PC's monitors.
        if (!apply_saved_layout()) TCL_WARN("vdisplay", "could not bring the PC's monitors back");
        DeleteFileW(marker.c_str());
        TCL_INFO("vdisplay", "the PC's monitors are back (a previous run ended on the virtual screen)");
        changed = true;
    }
    std::vector<DISPLAYCONFIG_PATH_INFO> paths;
    std::vector<DISPLAYCONFIG_MODE_INFO> modes;
    if (!query(QDC_ONLY_ACTIVE_PATHS, paths, modes)) return changed;
    if (std::none_of(paths.begin(), paths.end(), is_virtual)) return changed;
    keep_only(paths, modes, not_virtual);
    // Only the virtual screen is on and no other monitor is there: leave it (better than no desktop).
    if (paths.empty()) return changed;
    const LONG r = SetDisplayConfig(UINT32(paths.size()), paths.data(), UINT32(modes.size()), modes.data(),
                                    SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES | SDC_SAVE_TO_DATABASE);
    if (r == ERROR_SUCCESS) TCL_INFO("vdisplay", "virtual screen taken out of the desktop (it is only used while a TV plays)");
    else TCL_WARN("vdisplay", "could not take the virtual screen out of the desktop (error {})", r);
    return changed || r == ERROR_SUCCESS;
}

int VirtualDisplay::install(const std::wstring& folder, bool lift_policy) {
    const std::wstring inf = folder + L"\\MttVDD.inf";
    if (GetFileAttributesW(inf.c_str()) == INVALID_FILE_ATTRIBUTES) {
        TCL_ERROR("vdisplay", "driver not found in {}", narrow(folder));
        return 10;
    }
    // Its settings (screen sizes) in Jinky's own folder, which the driver finds through VDDPATH.
    const std::wstring conf = settings_folder();
    CreateDirectoryW((conf.substr(0, conf.rfind(L'\\'))).c_str(), nullptr);
    CreateDirectoryW(conf.c_str(), nullptr);
    if (!write_settings(conf)) {
        TCL_ERROR("vdisplay", "could not write the screen settings in {}", narrow(conf));
        return 11;
    }
    HKEY key;
    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, kDriverKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) != ERROR_SUCCESS) {
        TCL_ERROR("vdisplay", "could not write the driver's settings path (administrator needed)");
        return 12;
    }
    RegSetValueExW(key, L"VDDPATH", 0, REG_SZ, reinterpret_cast<const BYTE*>(conf.c_str()), DWORD((conf.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(key);

    // The device itself (root-enumerated, like "devcon install" makes it): exactly one (earlier
    // attempts may have left some without a driver).
    bool first = true;
    for_each_device([&](HDEVINFO set, SP_DEVINFO_DATA& d) {
        if (!first) SetupDiCallClassInstaller(DIF_REMOVE, set, &d);
        first = false;
    });
    if (first) {
        HDEVINFO set = SetupDiCreateDeviceInfoList(&GUID_DEVCLASS_DISPLAY, nullptr);
        if (set == INVALID_HANDLE_VALUE) return 13;
        SP_DEVINFO_DATA d{};
        d.cbSize = sizeof d;
        const wchar_t hwid[] = L"Root\\MttVDD\0";  // multi-string: the id, then an empty one
        bool ok = SetupDiCreateDeviceInfoW(set, L"Display", &GUID_DEVCLASS_DISPLAY, nullptr, nullptr, DICD_GENERATE_ID, &d) &&
                  SetupDiSetDeviceRegistryPropertyW(set, &d, SPDRP_HARDWAREID, reinterpret_cast<const BYTE*>(hwid), sizeof hwid) &&
                  SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, &d);
        const DWORD err = GetLastError();
        SetupDiDestroyDeviceInfoList(set);
        if (!ok) {
            TCL_ERROR("vdisplay", "could not create the virtual screen device (error {})", err);
            return 13;
        }
    }
    BOOL reboot = FALSE;
    // The user agreed (in the Jinky app) to lift a policy blocking display drivers just for this:
    // it is back right after, exactly as it was.
    const auto lifted = lift_policy ? lift_display_block() : std::vector<std::pair<std::wstring, std::wstring>>{};
    const BOOL installed = UpdateDriverForPlugAndPlayDevicesW(nullptr, kHardwareId, inf.c_str(), INSTALLFLAG_FORCE, &reboot);
    const DWORD err = installed ? 0 : GetLastError();
    restore_display_block(lifted);
    if (!installed) {
        TCL_ERROR("vdisplay", "the driver did not install (error 0x{:08X})", unsigned(err));
        // Leave no device without a driver behind.
        for_each_device([](HDEVINFO set, SP_DEVINFO_DATA& d) { SetupDiCallClassInstaller(DIF_REMOVE, set, &d); });
        return err == 0xE0000248 ? 17 : 14;  // ERROR_DEVICE_INSTALL_BLOCKED: a Windows policy
    }
    // Windows adds a new monitor to the desktop: take it out again (only used while a TV plays).
    for (int i = 0; i < 50 && !available(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(200));
    std::this_thread::sleep_for(std::chrono::milliseconds(1500));
    tidy();
    TCL_INFO("vdisplay", "virtual screen installed{}", reboot ? " (Windows asks for a restart)" : "");
    return available() ? 0 : 15;
}

int VirtualDisplay::uninstall() {
    std::vector<std::wstring> infs;
    for_each_device([&](HDEVINFO set, SP_DEVINFO_DATA& d) {
        if (HKEY k = SetupDiOpenDevRegKey(set, &d, DICS_FLAG_GLOBAL, 0, DIREG_DRV, KEY_READ); k != INVALID_HANDLE_VALUE) {
            wchar_t inf[MAX_PATH] = {};
            DWORD size = sizeof inf - sizeof(wchar_t);
            if (RegQueryValueExW(k, L"InfPath", nullptr, nullptr, reinterpret_cast<BYTE*>(inf), &size) == ERROR_SUCCESS && inf[0])
                infs.push_back(inf);
            RegCloseKey(k);
        }
        SetupDiCallClassInstaller(DIF_REMOVE, set, &d);
    });
    for (const auto& inf : infs) SetupUninstallOEMInfW(inf.c_str(), SUOI_FORCEDELETE, nullptr);
    if (HKEY key; RegOpenKeyExW(HKEY_LOCAL_MACHINE, kDriverKey, 0, KEY_SET_VALUE, &key) == ERROR_SUCCESS) {
        RegDeleteValueW(key, L"VDDPATH");
        RegCloseKey(key);
    }
    const std::wstring conf = settings_folder();
    DeleteFileW((conf + L"\\vdd_settings.xml").c_str());
    RemoveDirectoryW(conf.c_str());
    TCL_INFO("vdisplay", "virtual screen removed");
    return available() ? 16 : 0;
}

}  // namespace tcl
