// Captures whatever Windows is playing on the default output device (WASAPI loopback) and
// delivers it as 48 kHz stereo 16-bit PCM in fixed 5 ms chunks.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <vector>

namespace tcl {

class AudioCapture {
public:
    static constexpr uint32_t kSampleRate = 48000;
    static constexpr uint32_t kChannels = 2;
    static constexpr uint32_t kChunkFrames = kSampleRate / 200;  // 5 ms

    // `samples` holds kChunkFrames * kChannels interleaved values; `time_us` is when the chunk
    // was captured, in steady_clock microseconds.
    using ChunkCallback = std::function<void(const int16_t* samples, uint64_t time_us)>;

    ~AudioCapture();
    bool start(ChunkCallback on_chunk);
    // Test mode: instead of capturing, generates a 100 ms 880 Hz beep every `period_us`, starting
    // at `epoch_us` (steady clock) — aligned with the video test pattern for A/V sync checks.
    bool start_test_tone(ChunkCallback on_chunk, uint64_t epoch_us, uint64_t period_us);
    void stop();
    const std::string& error() const { return error_; }
    const std::string& description() const { return description_; }

private:
    void run();
    void run_tone(uint64_t epoch_us, uint64_t period_us);

    ChunkCallback on_chunk_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<int> init_state_{0};  // 0 pending, 1 ok, -1 failed
    std::string error_, description_;
};

}  // namespace tcl
