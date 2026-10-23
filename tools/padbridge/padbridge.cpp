// jinky-padbridge — drives the Android TV emulator with a DualSense, for trying the Jinky TV app
// on the PC (the emulator does not pass host controllers through).
//
// Reads the controller straight from HID (USB or Bluetooth) and mirrors it into the emulator as a
// real gamepad: Android's `hid` tool (over adb) creates a virtual HID device there, so the TV app
// sees a controller exactly as it would on a TV — buttons, D-pad, sticks and triggers.
//
// Usage: jinky-padbridge
#include <windows.h>
#include <hidsdi.h>
#include <setupapi.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

namespace {

struct Pad {
    HANDLE handle = INVALID_HANDLE_VALUE;
    size_t report_len = 0;
    bool bluetooth = false;
};

Pad open_dualsense() {
    GUID hid;
    HidD_GetHidGuid(&hid);
    HDEVINFO set = SetupDiGetClassDevsW(&hid, nullptr, nullptr, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (set == INVALID_HANDLE_VALUE) return {};
    Pad found;
    SP_DEVICE_INTERFACE_DATA itf{sizeof itf};
    for (DWORD i = 0; found.handle == INVALID_HANDLE_VALUE && SetupDiEnumDeviceInterfaces(set, nullptr, &hid, i, &itf); ++i) {
        DWORD need = 0;
        SetupDiGetDeviceInterfaceDetailW(set, &itf, nullptr, 0, &need, nullptr);
        std::vector<uint8_t> buf(need);
        auto* detail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_W*>(buf.data());
        detail->cbSize = sizeof *detail;
        if (!SetupDiGetDeviceInterfaceDetailW(set, &itf, detail, need, nullptr, nullptr)) continue;
        HANDLE h = CreateFileW(detail->DevicePath, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                               nullptr, OPEN_EXISTING, 0, nullptr);
        if (h == INVALID_HANDLE_VALUE) continue;
        HIDD_ATTRIBUTES a{sizeof a};
        PHIDP_PREPARSED_DATA pp = nullptr;
        HIDP_CAPS caps{};
        const bool dualsense = HidD_GetAttributes(h, &a) && a.VendorID == 0x054C &&
                               (a.ProductID == 0x0CE6 || a.ProductID == 0x0DF2);  // DualSense, DualSense Edge
        if (dualsense && HidD_GetPreparsedData(h, &pp)) {
            HidP_GetCaps(pp, &caps);
            HidD_FreePreparsedData(pp);
            if (caps.UsagePage == 1 && (caps.Usage == 4 || caps.Usage == 5)) {
                found = {h, caps.InputReportByteLength, caps.InputReportByteLength > 64};
                // Over Bluetooth the pad sends a reduced report until its calibration is read once.
                if (found.bluetooth && caps.FeatureReportByteLength) {
                    std::vector<uint8_t> feature(caps.FeatureReportByteLength);
                    feature[0] = 0x05;
                    HidD_GetFeature(h, feature.data(), ULONG(feature.size()));
                }
                continue;
            }
        }
        CloseHandle(h);
    }
    SetupDiDestroyDeviceInfoList(set);
    return found;
}

// The virtual gamepad's report: id, 16 buttons, hat (0-7, 8 = centred), LX LY RX RY, L2 R2.
using Report = std::vector<int>;

// Gamepad with 16 buttons, a hat, two sticks and two analog triggers (brake/gas), report id 1.
// Buttons follow Linux's gamepad order: 1 A, 2 B, 4 X, 5 Y, 7 L1, 8 R1, 9 L2, 10 R2, 11 Select,
// 12 Start, 13 Mode, 14 L3, 15 R3.
const char* kDescriptor =
    "[5,1,9,5,161,1,133,1,"
    "5,9,25,1,41,16,21,0,37,1,117,1,149,16,129,2,"
    "5,1,9,57,21,0,37,7,53,0,70,59,1,101,20,117,4,149,1,129,66,"
    "117,4,149,1,129,3,"
    "5,1,9,48,9,49,9,50,9,53,21,0,38,255,0,117,8,149,4,129,2,"
    "5,2,9,197,9,196,21,0,38,255,0,117,8,149,2,129,2,"
    "192]";

bool to_report(const Pad& pad, const uint8_t* r, size_t n, Report& out) {
    const uint8_t* s;    // lx ly rx ry l2 r2
    uint8_t b0, b1, b2;  // hat + face buttons, shoulders/sticks/menus, PS
    if (!pad.bluetooth && r[0] == 0x01 && n >= 11) {
        s = r + 1, b0 = r[8], b1 = r[9], b2 = r[10];
    } else if (pad.bluetooth && r[0] == 0x31 && n >= 12) {
        s = r + 2, b0 = r[9], b1 = r[10], b2 = r[11];
    } else if (pad.bluetooth && r[0] == 0x01 && n >= 10) {  // reduced Bluetooth report
        static uint8_t sticks[6];
        std::copy(r + 1, r + 5, sticks);
        sticks[4] = r[8], sticks[5] = r[9];
        s = sticks, b0 = r[5], b1 = r[6], b2 = r[7];
    } else {
        return false;
    }
    int buttons = 0;
    auto set = [&](bool on, int button) { if (on) buttons |= 1 << (button - 1); };
    set(b0 & 0x20, 1);   // Cross    -> A
    set(b0 & 0x40, 2);   // Circle   -> B
    set(b0 & 0x10, 4);   // Square   -> X
    set(b0 & 0x80, 5);   // Triangle -> Y
    set(b1 & 0x01, 7);   // L1
    set(b1 & 0x02, 8);   // R1
    set(b1 & 0x04, 9);   // L2
    set(b1 & 0x08, 10);  // R2
    set(b1 & 0x10, 11);  // Create   -> Select (View)
    set(b1 & 0x20, 12);  // Options  -> Start (Menu)
    set(b2 & 0x01, 13);  // PS       -> Mode (Guide)
    set(b1 & 0x40, 14);  // L3
    set(b1 & 0x80, 15);  // R3
    out = {1, buttons & 0xFF, buttons >> 8, b0 & 0x0F, s[0], s[1], s[2], s[3], s[4], s[5]};
    return true;
}

std::string json(const Report& r) {
    std::string s = R"({"id":1,"command":"report","report":[)";
    for (size_t i = 0; i < r.size(); ++i) s += (i ? "," : "") + std::to_string(r[i]);
    return s + "]}\n";
}

// `adb -e shell hid -`: Android's HID tool reading commands from our pipe.
class Emulator {
public:
    ~Emulator() { stop(); }

    bool send(const std::string& line) {
        if (!stdin_ && !start()) return false;
        DWORD n = 0;
        if (!WriteFile(stdin_, line.data(), DWORD(line.size()), &n, nullptr)) {
            std::puts("emulator gone; waiting for it");
            stop();
            return false;
        }
        return true;
    }

private:
    bool start() {
        const char* local = std::getenv("LOCALAPPDATA");
        if (!local) return false;
        std::string cmd = "\"" + std::string(local) + "\\Android\\Sdk\\platform-tools\\adb.exe\" -e shell hid -";
        SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
        HANDLE read = nullptr, write = nullptr;
        if (!CreatePipe(&read, &write, &sa, 0)) return false;
        SetHandleInformation(write, HANDLE_FLAG_INHERIT, 0);
        STARTUPINFOA si{sizeof si};
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdInput = read;
        si.hStdOutput = si.hStdError = GetStdHandle(STD_OUTPUT_HANDLE);
        PROCESS_INFORMATION pi{};
        const bool ok = CreateProcessA(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr,
                                       &si, &pi);
        CloseHandle(read);
        if (!ok) {
            CloseHandle(write);
            return false;
        }
        CloseHandle(pi.hThread);
        process_ = pi.hProcess;
        stdin_ = write;
        const std::string reg = std::string(R"j({"id":1,"command":"register","name":"DualSense (Jinky bridge)",)j") +
                                R"j("vid":4617,"pid":1,"bus":"usb","descriptor":)j" + kDescriptor + "}\n";
        DWORD n = 0;
        WriteFile(stdin_, reg.data(), DWORD(reg.size()), &n, nullptr);
        std::puts("virtual controller created in the emulator");
        return true;
    }
    void stop() {
        if (stdin_) CloseHandle(stdin_);
        if (process_) {
            TerminateProcess(process_, 0);
            CloseHandle(process_);
        }
        stdin_ = process_ = nullptr;
    }

    HANDLE stdin_ = nullptr, process_ = nullptr;
};

}  // namespace

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    std::puts("jinky-padbridge: your DualSense becomes a controller inside the Android TV emulator (Ctrl+C to stop)");
    Emulator emulator;
    while (true) {
        Pad pad = open_dualsense();
        if (pad.handle == INVALID_HANDLE_VALUE) {
            std::puts("waiting for a DualSense (USB or Bluetooth)...");
            Sleep(2000);
            continue;
        }
        std::printf("DualSense found (%s)\n", pad.bluetooth ? "Bluetooth" : "USB");
        std::vector<uint8_t> raw(pad.report_len);
        Report last;
        ULONGLONG last_sent = 0;
        DWORD n = 0;
        while (ReadFile(pad.handle, raw.data(), DWORD(raw.size()), &n, nullptr)) {
            Report r;
            if (!to_report(pad, raw.data(), n, r) || r == last) continue;
            // Buttons always go at once; stick-only changes at most every 8 ms.
            const bool buttons = last.empty() || r[1] != last[1] || r[2] != last[2] || r[3] != last[3];
            if (!buttons && GetTickCount64() - last_sent < 8) continue;
            if (!emulator.send(json(r))) {
                Sleep(1000);
                continue;
            }
            last = r;
            last_sent = GetTickCount64();
        }
        std::puts("DualSense disconnected");
        CloseHandle(pad.handle);
    }
}
