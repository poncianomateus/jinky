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

enum class VideoCodec { H264, HEVC };

struct EncoderConfig {
    VideoCodec codec = VideoCodec::H264;
    uint32_t width = 1920;
    uint32_t height = 1080;
    uint32_t fps = 60;
    // Reference frames in the decoder's buffer (DPB). 1 = the stream tells decoders they never need
    // to hold frames back (MediaTek on the Fire TV otherwise keeps 3 queued). 0 = driver default.
    uint32_t max_ref_frames = 1;
    uint32_t bitrate_kbps = 40000;  // measured on the TCL over Wi-Fi: 0 loss up to 50 Mbit/s
    // HDR10 (HEVC only): 10-bit P010 input in BT.2020 / PQ, Main10 profile, HDR metadata taken
    // from the PC's monitor (the picture is "mastered" on it).
    bool hdr = false;
    float hdr_max_nits = 1000, hdr_min_nits = 0.005f, hdr_max_fall_nits = 400;
    // Jinky Vision: every picture comes with a QP delta map (one value per macroblock / CTB).
    bool qp_map = false;
    // The QP each block was encoded with (Vision's self-test), see block_qp().
    bool block_stats = false;
};

class NvencEncoder {
public:
    ~NvencEncoder();

    // `input` is the NV12 texture that will be passed to every encode() call.
    bool open(ID3D11Device* device, ID3D11Texture2D* input, const EncoderConfig& cfg);
    void close();

    // Encodes the current content of the input texture. `out` receives one Annex-B access unit.
    bool encode(bool force_idr, uint64_t timestamp, std::vector<uint8_t>& out, bool& is_keyframe,
                const std::vector<int8_t>* qp_map = nullptr);

    // The encoder's block size (16: H.264 macroblock, 32: HEVC CTB) and how many blocks a picture has.
    uint32_t block_size() const { return cfg_.codec == VideoCodec::HEVC ? 32 : 16; }
    size_t block_count() const {
        const uint32_t b = block_size();
        return size_t((cfg_.width + b - 1) / b) * ((cfg_.height + b - 1) / b);
    }
    // With block_stats: the QP of every block of the last picture.
    const std::vector<uint8_t>& block_qp() const { return block_qp_; }

    // Changes the rate on the fly (automatic quality); no keyframe, no new session.
    bool set_bitrate(uint32_t kbps);
    uint32_t bitrate_kbps() const { return cfg_.bitrate_kbps; }
    bool is_open() const { return encoder_ != nullptr; }

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
    NV_ENC_INITIALIZE_PARAMS init_{};  // kept for nvEncReconfigureEncoder
    NV_ENC_CONFIG config_{};
    MASTERING_DISPLAY_INFO mastering_{};  // HDR10 SEI, sent with every keyframe
    CONTENT_LIGHT_LEVEL light_{};
    std::string error_;
    std::vector<NV_ENC_OUTPUT_STATS_BLOCK> stats_;
    std::vector<uint8_t> block_qp_;
};

}  // namespace tcl
