// H.264 encoding on NVIDIA hardware (NVENC), configured for ultra-low-latency streaming:
// no B-frames, no automatic keyframes (sent only on request), single-frame VBV.
#pragma once

#include <d3d11.h>
#include <windows.h>

#include <cstdint>
#include <string>
#include <vector>

#include "nvEncodeAPI.h"

namespace tcl {

struct EncoderConfig {
    uint32_t width = 1920;
    uint32_t height = 1080;
    uint32_t fps = 60;
    uint32_t bitrate_kbps = 20000;
};

class NvencEncoder {
public:
    ~NvencEncoder();

    // `input` is the NV12 texture that will be passed to every encode() call.
    bool open(ID3D11Device* device, ID3D11Texture2D* input, const EncoderConfig& cfg);
    void close();

    // Encodes the current content of the input texture. `out` receives one Annex-B access unit.
    bool encode(bool force_idr, uint64_t timestamp, std::vector<uint8_t>& out, bool& is_keyframe);

    std::string description() const;
    const std::string& error() const { return error_; }

private:
    bool check(NVENCSTATUS st, const char* what);

    HMODULE lib_ = nullptr;
    NV_ENCODE_API_FUNCTION_LIST api_{};
    void* encoder_ = nullptr;
    NV_ENC_REGISTERED_PTR registered_ = nullptr;
    NV_ENC_OUTPUT_PTR bitstream_ = nullptr;
    EncoderConfig cfg_;
    std::string error_;
};

}  // namespace tcl
