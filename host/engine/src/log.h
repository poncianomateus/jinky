// Structured console log: "<elapsed ms> <LEVEL> [component] message".
#pragma once

#include <atomic>
#include <chrono>
#include <cstdio>
#include <format>
#include <string_view>

namespace tcl {

// Machine-readable events for the Jinky desktop app: one JSON object per line on stdout, enabled
// with --events. Human-readable logs stay on stderr.
inline std::atomic<bool>& events_enabled() {
    static std::atomic<bool> on{false};
    return on;
}

inline std::string json_str(std::string_view s) {
    std::string out = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        if (static_cast<unsigned char>(c) < 0x20) continue;
        out += c;
    }
    return out + "\"";
}

// `fields` is the JSON object's body after the "ev" key, e.g. R"("fps":60.0)".
inline void emit_event(std::string_view ev, std::string_view fields = {}) {
    if (!events_enabled()) return;
    std::string line = "{\"ev\":" + json_str(ev);
    if (!fields.empty()) line += "," + std::string(fields);
    line += "}\n";
    std::fputs(line.c_str(), stdout);
    std::fflush(stdout);
}

inline std::chrono::steady_clock::time_point log_epoch() {
    static const auto t0 = std::chrono::steady_clock::now();
    return t0;
}

template <class... Args>
void log(std::string_view level, std::string_view component, std::format_string<Args...> fmt,
         Args&&... args) {
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - log_epoch())
                        .count();
    std::string msg = std::format(fmt, std::forward<Args>(args)...);
    std::fprintf(stderr, "%8lld %-5.*s [%.*s] %s\n", static_cast<long long>(ms), int(level.size()),
                 level.data(), int(component.size()), component.data(), msg.c_str());
    std::fflush(stderr);
    if (level != "INFO")
        emit_event("log", "\"level\":" + json_str(level) + ",\"component\":" + json_str(component) +
                              ",\"msg\":" + json_str(msg));
}

#define TCL_INFO(comp, ...) ::tcl::log("INFO", comp, __VA_ARGS__)
#define TCL_WARN(comp, ...) ::tcl::log("WARN", comp, __VA_ARGS__)
#define TCL_ERROR(comp, ...) ::tcl::log("ERROR", comp, __VA_ARGS__)

}  // namespace tcl
