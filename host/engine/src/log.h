// Structured console log: "<elapsed ms> <LEVEL> [component] message".
#pragma once

#include <chrono>
#include <cstdio>
#include <format>
#include <string_view>

namespace tcl {

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
}

#define TCL_INFO(comp, ...) ::tcl::log("INFO", comp, __VA_ARGS__)
#define TCL_WARN(comp, ...) ::tcl::log("WARN", comp, __VA_ARGS__)
#define TCL_ERROR(comp, ...) ::tcl::log("ERROR", comp, __VA_ARGS__)

}  // namespace tcl
