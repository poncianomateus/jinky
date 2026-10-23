// jinky-padbridge — drives the Android TV emulator with a DualSense, for trying the Jinky TV app
// on the PC (the emulator does not pass host controllers through).
//
// Reads the controller straight from HID (USB or Bluetooth) and sends key events to the emulator
// console (127.0.0.1:5554), which reach Android instantly:
//   D-pad / left stick = arrows   Cross = OK   Circle = Back   Options = Menu
//
// Usage: jinky-padbridge [console-port]
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <hidsdi.h>
#include <setupapi.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <thread>
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
                if (found.bluetooth) {
                    std::vector<uint8_t> feature(caps.FeatureReportByteLength);
                    if (!feature.empty()) {
                        feature[0] = 0x05;
                        HidD_GetFeature(h, feature.data(), ULONG(feature.size()));
                    }
                }
                continue;
            }
        }
        CloseHandle(h);
    }
    SetupDiDestroyDeviceInfoList(set);
    return found;
}

struct Buttons {
    bool up = false, down = false, left = false, right = false, ok = false, back = false, menu = false;
    bool operator==(const Buttons&) const = default;
};

// DualSense input report -> the buttons the TV app cares about.
bool parse(const Pad& pad, const uint8_t* r, size_t n, Buttons& b) {
    uint8_t lx, ly, b0, b1;
    if (!pad.bluetooth && r[0] == 0x01 && n >= 11) {  // USB
        lx = r[1], ly = r[2], b0 = r[8], b1 = r[9];
    } else if (pad.bluetooth && r[0] == 0x31 && n >= 12) {  // Bluetooth, full report
        lx = r[2], ly = r[3], b0 = r[9], b1 = r[10];
    } else if (pad.bluetooth && r[0] == 0x01 && n >= 7) {  // Bluetooth, reduced report
        lx = r[1], ly = r[2], b0 = r[5], b1 = r[6];
    } else {
        return false;
    }
    const int hat = b0 & 0x0F;  // 0 = up, clockwise, 8 = released
    b.up = hat == 7 || hat == 0 || hat == 1 || ly < 50;
    b.right = (hat >= 1 && hat <= 3) || lx > 205;
    b.down = (hat >= 3 && hat <= 5) || ly > 205;
    b.left = (hat >= 5 && hat <= 7) || lx < 50;
    b.ok = b0 & 0x20;     // Cross
    b.back = b0 & 0x40;   // Circle
    b.menu = b1 & 0x20;   // Options
    return true;
}

class Console {
public:
    explicit Console(int port) : port_(port) {}
    ~Console() { close(); }

    bool send(const std::string& line) {
        if (s_ == INVALID_SOCKET && !connect()) return false;
        const std::string data = line + "\n";
        if (::send(s_, data.data(), int(data.size()), 0) < 0) {
            close();
            return false;
        }
        return true;
    }

private:
    bool connect() {
        s_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons(uint16_t(port_));
        inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
        if (::connect(s_, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0) {
            close();
            return false;
        }
        // Drain the console's answers ("OK") so its buffer never fills.
        reader_ = std::thread([s = s_] {
            char buf[1024];
            while (recv(s, buf, sizeof buf, 0) > 0) {
            }
        });
        reader_.detach();
        std::string token;
        if (const char* home = std::getenv("USERPROFILE"))
            std::getline(std::ifstream(std::string(home) + "\\.emulator_console_auth_token"), token);
        const std::string auth = "auth " + token + "\n";
        ::send(s_, auth.data(), int(auth.size()), 0);
        std::printf("connected to the emulator on port %d\n", port_);
        return true;
    }
    void close() {
        if (s_ != INVALID_SOCKET) closesocket(s_);
        s_ = INVALID_SOCKET;
    }

    int port_;
    SOCKET s_ = INVALID_SOCKET;
    std::thread reader_;
};

void key(Console& c, const char* name, bool down) {
    c.send(std::string("event send EV_KEY:") + name + ":" + (down ? "1" : "0"));
}

}  // namespace

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    Console console(argc > 1 ? std::atoi(argv[1]) : 5554);
    std::puts("jinky-padbridge: D-pad/stick = arrows, Cross = OK, Circle = Back, Options = Menu (Ctrl+C to stop)");

    while (true) {
        Pad pad = open_dualsense();
        if (pad.handle == INVALID_HANDLE_VALUE) {
            std::puts("waiting for a DualSense (USB or Bluetooth)...");
            Sleep(2000);
            continue;
        }
        std::printf("DualSense found (%s)\n", pad.bluetooth ? "Bluetooth" : "USB");
        std::vector<uint8_t> report(pad.report_len);
        Buttons last;
        DWORD n = 0;
        while (ReadFile(pad.handle, report.data(), DWORD(report.size()), &n, nullptr)) {
            Buttons b;
            if (!parse(pad, report.data(), n, b) || b == last) continue;
            // Android repeats a held arrow by itself, like a TV remote.
            if (b.up != last.up) key(console, "KEY_UP", b.up);
            if (b.down != last.down) key(console, "KEY_DOWN", b.down);
            if (b.left != last.left) key(console, "KEY_LEFT", b.left);
            if (b.right != last.right) key(console, "KEY_RIGHT", b.right);
            if (b.ok != last.ok) key(console, "KEY_ENTER", b.ok);
            if (b.back != last.back) key(console, "KEY_BACK", b.back);
            if (b.menu != last.menu) key(console, "KEY_MENU", b.menu);
            last = b;
        }
        std::puts("DualSense disconnected");
        CloseHandle(pad.handle);
    }
}
