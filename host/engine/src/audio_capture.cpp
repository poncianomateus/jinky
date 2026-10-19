#include "audio_capture.h"

#include <windows.h>
#include <audioclient.h>
#include <endpointvolume.h>
#include <avrt.h>
#include <ksmedia.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <format>

#include "log.h"

namespace tcl {

using Microsoft::WRL::ComPtr;

namespace {

uint64_t now_us() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

enum class SampleType { Float32, Int16, Int32, Unsupported };

SampleType sample_type(const WAVEFORMATEX* wf) {
    WORD tag = wf->wFormatTag;
    if (tag == WAVE_FORMAT_EXTENSIBLE) {
        auto* ext = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(wf);
        if (IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT)) tag = WAVE_FORMAT_IEEE_FLOAT;
        else if (IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_PCM)) tag = WAVE_FORMAT_PCM;
    }
    if (tag == WAVE_FORMAT_IEEE_FLOAT && wf->wBitsPerSample == 32) return SampleType::Float32;
    if (tag == WAVE_FORMAT_PCM && wf->wBitsPerSample == 16) return SampleType::Int16;
    if (tag == WAVE_FORMAT_PCM && wf->wBitsPerSample == 32) return SampleType::Int32;
    return SampleType::Unsupported;
}

float read_sample(const BYTE* p, SampleType t) {
    switch (t) {
    case SampleType::Float32: return *reinterpret_cast<const float*>(p);
    case SampleType::Int16: return *reinterpret_cast<const int16_t*>(p) / 32768.0f;
    case SampleType::Int32: return float(*reinterpret_cast<const int32_t*>(p) / 2147483648.0);
    default: return 0;
    }
}

}  // namespace

AudioCapture::~AudioCapture() { stop(); }

double AudioCapture::take_peak_dbfs() {
    const int p = peak_.exchange(0);
    return p > 0 ? 20.0 * std::log10(p / 32767.0) : -INFINITY;
}

namespace {

ComPtr<IAudioEndpointVolume> default_endpoint_volume() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMDevice> device;
    ComPtr<IAudioEndpointVolume> volume;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator))) ||
        FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device)) ||
        FAILED(device->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr, &volume)))
        return nullptr;
    return volume;
}

// %LOCALAPPDATA%\TclStream\pc-muted.flag exists while TclStream holds the PC muted.
std::wstring mute_marker_path() {
    wchar_t base[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    std::wstring dir = std::wstring(base) + L"\\TclStream";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir + L"\\pc-muted.flag";
}

}  // namespace

bool AudioCapture::set_pc_muted(bool muted) {
    auto volume = default_endpoint_volume();
    if (!volume) return false;
    const std::wstring marker = mute_marker_path();
    if (muted) {
        BOOL was = FALSE;
        volume->GetMute(&was);
        if (was) return true;  // the user had it muted already: not ours to restore later
        if (FAILED(volume->SetMute(TRUE, nullptr))) return false;
        if (!marker.empty()) CloseHandle(CreateFileW(marker.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr));
        return true;
    }
    // Only undo a mute we did ourselves.
    if (marker.empty() || GetFileAttributesW(marker.c_str()) == INVALID_FILE_ATTRIBUTES) return true;
    const bool ok = SUCCEEDED(volume->SetMute(FALSE, nullptr));
    if (ok) DeleteFileW(marker.c_str());
    return ok;
}

bool AudioCapture::restore_if_left_muted() {
    const std::wstring marker = mute_marker_path();
    if (marker.empty() || GetFileAttributesW(marker.c_str()) == INVALID_FILE_ATTRIBUTES) return false;
    return set_pc_muted(false);
}

bool AudioCapture::start(ChunkCallback on_chunk) {
    on_chunk_ = std::move(on_chunk);
    running_ = true;
    init_state_ = 0;
    thread_ = std::thread(&AudioCapture::run, this);
    while (init_state_ == 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (init_state_ < 0) {
        stop();
        return false;
    }
    return true;
}

bool AudioCapture::start_test_tone(ChunkCallback on_chunk, uint64_t epoch_us, uint64_t period_us) {
    on_chunk_ = std::move(on_chunk);
    running_ = true;
    description_ = "test tone: 880 Hz beep every " + std::to_string(period_us / 1000) + " ms, synced to the test pattern";
    thread_ = std::thread(&AudioCapture::run_tone, this, epoch_us, period_us);
    return true;
}

void AudioCapture::run_tone(uint64_t epoch_us, uint64_t period_us) {
    DWORD task = 0;
    HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task);
    std::vector<int16_t> chunk(kChunkFrames * kChannels);
    constexpr uint64_t kChunkUs = 1'000'000 * kChunkFrames / kSampleRate;
    constexpr uint64_t kBeepUs = 100'000;
    uint64_t t = now_us();
    while (running_) {
        while (now_us() < t + kChunkUs) Sleep(1);
        for (uint32_t i = 0; i < kChunkFrames; ++i) {
            const uint64_t ts = t + uint64_t(i) * 1'000'000 / kSampleRate;
            int16_t v = 0;
            if (ts >= epoch_us) {
                const uint64_t phase = (ts - epoch_us) % period_us;
                if (phase < kBeepUs) v = int16_t(8000 * std::sin(2 * 3.14159265358979 * 880.0 * double(phase) / 1e6));
            }
            chunk[i * 2] = chunk[i * 2 + 1] = v;
        }
        on_chunk_(chunk.data(), t);
        t += kChunkUs;
    }
    if (mmcss) AvRevertMmThreadCharacteristics(mmcss);
}

void AudioCapture::stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
}

void AudioCapture::run() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    DWORD task = 0;
    HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task);

    std::vector<float> staging;  // interleaved stereo at 48 kHz, waiting to fill a chunk
    std::vector<int16_t> chunk(kChunkFrames * kChannels);
    bool first_open = true;

    // Outer loop: (re)open the default device; the user may switch outputs while streaming.
    while (running_) {
        ComPtr<IMMDeviceEnumerator> enumerator;
        ComPtr<IMMDevice> device;
        ComPtr<IAudioClient> client;
        ComPtr<IAudioCaptureClient> capture;
        WAVEFORMATEX* wf = nullptr;
        HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator));
        if (SUCCEEDED(hr)) hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
        if (SUCCEEDED(hr)) hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &client);
        if (SUCCEEDED(hr)) hr = client->GetMixFormat(&wf);
        const SampleType type = SUCCEEDED(hr) ? sample_type(wf) : SampleType::Unsupported;
        if (SUCCEEDED(hr) && type == SampleType::Unsupported) hr = E_NOTIMPL;
        if (SUCCEEDED(hr))
            hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 20 * 10000, 0, wf, nullptr);
        if (SUCCEEDED(hr)) hr = client->GetService(IID_PPV_ARGS(&capture));
        if (SUCCEEDED(hr)) hr = client->Start();

        if (FAILED(hr)) {
            if (wf) CoTaskMemFree(wf);
            error_ = std::format("WASAPI loopback init failed 0x{:08X}", unsigned(hr));
            if (first_open) {
                init_state_ = -1;
                break;
            }
            std::this_thread::sleep_for(std::chrono::seconds(1));
            continue;
        }

        const uint32_t in_rate = wf->nSamplesPerSec, in_ch = wf->nChannels, frame_bytes = wf->nBlockAlign,
                       sample_bytes = wf->wBitsPerSample / 8;
        description_ = std::format("WASAPI loopback {} Hz, {} ch, {}-bit -> PCM 48 kHz stereo 16-bit, 5 ms packets",
                                   in_rate, in_ch, wf->wBitsPerSample);
        CoTaskMemFree(wf);
        if (first_open) {
            init_state_ = 1;
            first_open = false;
        } else {
            TCL_INFO("audio", "output device reopened: {}", description_);
        }

        // Linear resampler state (only used when the mix rate is not 48 kHz).
        const double step = double(in_rate) / kSampleRate;
        double pos = 0;
        float prev_l = 0, prev_r = 0;

        bool device_ok = true;
        while (running_ && device_ok) {
            Sleep(2);
            UINT32 packet = 0;
            while (SUCCEEDED(hr = capture->GetNextPacketSize(&packet)) && packet > 0) {
                BYTE* data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                hr = capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
                if (FAILED(hr)) break;
                const bool silent = flags & AUDCLNT_BUFFERFLAGS_SILENT;
                for (UINT32 i = 0; i < frames; ++i) {
                    float l = 0, r = 0;
                    if (!silent) {
                        const BYTE* f = data + size_t(i) * frame_bytes;
                        auto s = [&](uint32_t ch) { return read_sample(f + ch * sample_bytes, type); };
                        if (in_ch == 1) {
                            l = r = s(0);
                        } else {
                            l = s(0);
                            r = s(1);
                            if (in_ch >= 6) {  // 5.1/7.1: FL FR FC LFE BL BR [SL SR] -> stereo downmix
                                const float c = s(2) * 0.707f;
                                float sl = s(4) * 0.707f, sr = s(5) * 0.707f;
                                if (in_ch >= 8) {
                                    sl += s(6) * 0.707f;
                                    sr += s(7) * 0.707f;
                                }
                                l = (l + c + sl) * 0.5f;
                                r = (r + c + sr) * 0.5f;
                            }
                        }
                    }
                    if (in_rate == kSampleRate) {
                        staging.push_back(l);
                        staging.push_back(r);
                    } else {
                        // Emit output samples that fall between the previous and this input frame.
                        while (pos <= 1.0) {
                            staging.push_back(prev_l + float(pos) * (l - prev_l));
                            staging.push_back(prev_r + float(pos) * (r - prev_r));
                            pos += step;
                        }
                        pos -= 1.0;
                        prev_l = l;
                        prev_r = r;
                    }
                }
                capture->ReleaseBuffer(frames);
            }
            if (hr == AUDCLNT_E_DEVICE_INVALIDATED || FAILED(hr)) {
                TCL_WARN("audio", "output device changed or lost (0x{:08X}), reopening", unsigned(hr));
                device_ok = false;
            }

            const size_t chunk_values = chunk.size();
            size_t used = 0;
            while (staging.size() - used >= chunk_values) {
                for (size_t i = 0; i < chunk_values; ++i)
                    chunk[i] = int16_t(std::lround(std::clamp(staging[used + i], -1.0f, 1.0f) * 32767.0f));
                int peak = 0;
                for (int16_t v : chunk) peak = std::max(peak, std::abs(int(v)));
                if (peak > peak_) peak_ = peak;
                on_chunk_(chunk.data(), now_us());
                used += chunk_values;
            }
            staging.erase(staging.begin(), staging.begin() + ptrdiff_t(used));
        }
        client->Stop();
    }

    if (mmcss) AvRevertMmThreadCharacteristics(mmcss);
    CoUninitialize();
}

}  // namespace tcl
