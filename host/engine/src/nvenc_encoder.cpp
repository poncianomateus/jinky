#include "nvenc_encoder.h"

#include <cstring>
#include <format>

namespace tcl {

namespace {
using CreateInstanceFn = NVENCSTATUS(NVENCAPI*)(NV_ENCODE_API_FUNCTION_LIST*);
using MaxVersionFn = NVENCSTATUS(NVENCAPI*)(uint32_t*);
}  // namespace

NvencEncoder::~NvencEncoder() { close(); }

bool NvencEncoder::check(NVENCSTATUS st, const char* what) {
    if (st == NV_ENC_SUCCESS) return true;
    const char* detail = (encoder_ && api_.nvEncGetLastErrorString) ? api_.nvEncGetLastErrorString(encoder_) : "";
    error_ = std::format("{} failed (NVENCSTATUS {}) {}", what, int(st), detail ? detail : "");
    return false;
}

bool NvencEncoder::open(ID3D11Device* device, ID3D11Texture2D* input, const EncoderConfig& cfg) {
    cfg_ = cfg;
    lib_ = LoadLibraryW(L"nvEncodeAPI64.dll");
    if (!lib_) {
        error_ = "nvEncodeAPI64.dll not found (NVIDIA driver missing?)";
        return false;
    }
    auto max_version = reinterpret_cast<MaxVersionFn>(GetProcAddress(lib_, "NvEncodeAPIGetMaxSupportedVersion"));
    auto create = reinterpret_cast<CreateInstanceFn>(GetProcAddress(lib_, "NvEncodeAPICreateInstance"));
    if (!max_version || !create) {
        error_ = "NVENC entry points missing";
        return false;
    }
    uint32_t driver_version = 0;
    max_version(&driver_version);
    const uint32_t needed = (NVENCAPI_MAJOR_VERSION << 4) | NVENCAPI_MINOR_VERSION;
    if (driver_version < needed) {
        error_ = std::format("driver supports NVENC API {}.{}, need {}.{} — update the NVIDIA driver",
                             driver_version >> 4, driver_version & 15, NVENCAPI_MAJOR_VERSION,
                             NVENCAPI_MINOR_VERSION);
        return false;
    }

    api_.version = NV_ENCODE_API_FUNCTION_LIST_VER;
    if (!check(create(&api_), "NvEncodeAPICreateInstance")) return false;

    NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS session{};
    session.version = NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;
    session.deviceType = NV_ENC_DEVICE_TYPE_DIRECTX;
    session.device = device;
    session.apiVersion = NVENCAPI_VERSION;
    if (!check(api_.nvEncOpenEncodeSessionEx(&session, &encoder_), "nvEncOpenEncodeSessionEx")) return false;

    const bool hevc = cfg.codec == VideoCodec::HEVC;
    const GUID codec_guid = hevc ? NV_ENC_CODEC_HEVC_GUID : NV_ENC_CODEC_H264_GUID;
    // P4 instead of P1: noticeably cleaner detail (foliage, fine edges) for ~1 ms more on an RTX 50.
    const GUID preset_guid = NV_ENC_PRESET_P4_GUID;

    NV_ENC_PRESET_CONFIG preset{};
    preset.version = NV_ENC_PRESET_CONFIG_VER;
    preset.presetCfg.version = NV_ENC_CONFIG_VER;
    if (!check(api_.nvEncGetEncodePresetConfigEx(encoder_, codec_guid, preset_guid,
                                                 NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY, &preset),
               "nvEncGetEncodePresetConfigEx"))
        return false;

    NV_ENC_CONFIG enc = preset.presetCfg;
    enc.profileGUID = hevc ? NV_ENC_HEVC_PROFILE_MAIN_GUID : NV_ENC_H264_PROFILE_HIGH_GUID;
    enc.gopLength = NVENC_INFINITE_GOPLENGTH;
    enc.frameIntervalP = 1;  // IPPP, no B-frames
    enc.rcParams.rateControlMode = NV_ENC_PARAMS_RC_CBR;
    enc.rcParams.averageBitRate = cfg.bitrate_kbps * 1000;
    enc.rcParams.maxBitRate = cfg.bitrate_kbps * 1000;
    // One frame worth of VBV: frame sizes stay even, so no frame takes much longer to send.
    enc.rcParams.vbvBufferSize = cfg.bitrate_kbps * 1000 / cfg.fps;
    enc.rcParams.vbvInitialDelay = enc.rcParams.vbvBufferSize;

    // Both codecs share the VUI layout; fill it once and copy it where it belongs.
    NV_ENC_CONFIG_H264_VUI_PARAMETERS vui{};
    vui.videoSignalTypePresentFlag = 1;
    vui.videoFormat = NV_ENC_VUI_VIDEO_FORMAT_UNSPECIFIED;
    vui.videoFullRangeFlag = 0;  // matches the converter's 16-235 output
    vui.colourDescriptionPresentFlag = 1;
    vui.colourPrimaries = NV_ENC_VUI_COLOR_PRIMARIES_BT709;
    vui.transferCharacteristics = NV_ENC_VUI_TRANSFER_CHARACTERISTIC_BT709;
    vui.colourMatrix = NV_ENC_VUI_MATRIX_COEFFS_BT709;
    // Tells the decoder there is no frame reordering (max_num_reorder_frames = 0). Without it many
    // TV decoders hold several frames in the DPB "just in case", adding 50+ ms of latency.
    vui.bitstreamRestrictionFlag = 1;

    if (hevc) {
        auto& h = enc.encodeCodecConfig.hevcConfig;
        h.idrPeriod = NVENC_INFINITE_GOPLENGTH;
        h.repeatSPSPPS = 1;  // VPS/SPS/PPS with every IDR: a decoder can join at any keyframe
        h.level = NV_ENC_LEVEL_AUTOSELECT;
        h.chromaFormatIDC = 1;
        h.maxNumRefFramesInDPB = cfg.max_ref_frames;
        h.hevcVUIParameters = vui;
    } else {
        auto& h = enc.encodeCodecConfig.h264Config;
        h.idrPeriod = NVENC_INFINITE_GOPLENGTH;
        h.repeatSPSPPS = 1;
        h.level = NV_ENC_LEVEL_AUTOSELECT;
        h.chromaFormatIDC = 1;
        h.maxNumRefFrames = cfg.max_ref_frames;
        h.h264VUIParameters = vui;
    }

    NV_ENC_INITIALIZE_PARAMS init{};
    init.version = NV_ENC_INITIALIZE_PARAMS_VER;
    init.encodeGUID = codec_guid;
    init.presetGUID = preset_guid;
    init.tuningInfo = NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY;
    init.encodeWidth = init.darWidth = init.maxEncodeWidth = cfg.width;
    init.encodeHeight = init.darHeight = init.maxEncodeHeight = cfg.height;
    init.frameRateNum = cfg.fps;
    init.frameRateDen = 1;
    init.enablePTD = 1;
    init.encodeConfig = &enc;
    if (!check(api_.nvEncInitializeEncoder(encoder_, &init), "nvEncInitializeEncoder")) return false;

    NV_ENC_REGISTER_RESOURCE reg{};
    reg.version = NV_ENC_REGISTER_RESOURCE_VER;
    reg.resourceType = NV_ENC_INPUT_RESOURCE_TYPE_DIRECTX;
    reg.width = cfg.width;
    reg.height = cfg.height;
    reg.resourceToRegister = input;
    reg.bufferFormat = NV_ENC_BUFFER_FORMAT_NV12;
    reg.bufferUsage = NV_ENC_INPUT_IMAGE;
    if (!check(api_.nvEncRegisterResource(encoder_, &reg), "nvEncRegisterResource")) return false;
    registered_ = reg.registeredResource;

    NV_ENC_CREATE_BITSTREAM_BUFFER bs{};
    bs.version = NV_ENC_CREATE_BITSTREAM_BUFFER_VER;
    if (!check(api_.nvEncCreateBitstreamBuffer(encoder_, &bs), "nvEncCreateBitstreamBuffer")) return false;
    bitstream_ = bs.bitstreamBuffer;
    return true;
}

bool NvencEncoder::encode(bool force_idr, uint64_t timestamp, std::vector<uint8_t>& out, bool& is_keyframe) {
    NV_ENC_MAP_INPUT_RESOURCE map{};
    map.version = NV_ENC_MAP_INPUT_RESOURCE_VER;
    map.registeredResource = registered_;
    if (!check(api_.nvEncMapInputResource(encoder_, &map), "nvEncMapInputResource")) return false;

    NV_ENC_PIC_PARAMS pic{};
    pic.version = NV_ENC_PIC_PARAMS_VER;
    pic.inputWidth = cfg_.width;
    pic.inputHeight = cfg_.height;
    pic.inputBuffer = map.mappedResource;
    pic.bufferFmt = map.mappedBufferFmt;
    pic.outputBitstream = bitstream_;
    pic.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
    pic.inputTimeStamp = timestamp;
    if (force_idr) pic.encodePicFlags = NV_ENC_PIC_FLAG_FORCEIDR | NV_ENC_PIC_FLAG_OUTPUT_SPSPPS;
    bool ok = check(api_.nvEncEncodePicture(encoder_, &pic), "nvEncEncodePicture");

    if (ok) {
        NV_ENC_LOCK_BITSTREAM lock{};
        lock.version = NV_ENC_LOCK_BITSTREAM_VER;
        lock.outputBitstream = bitstream_;
        ok = check(api_.nvEncLockBitstream(encoder_, &lock), "nvEncLockBitstream");
        if (ok) {
            const auto* p = static_cast<const uint8_t*>(lock.bitstreamBufferPtr);
            out.assign(p, p + lock.bitstreamSizeInBytes);
            is_keyframe = lock.pictureType == NV_ENC_PIC_TYPE_IDR;
            api_.nvEncUnlockBitstream(encoder_, bitstream_);
        }
    }
    api_.nvEncUnmapInputResource(encoder_, map.mappedResource);
    return ok;
}

std::string NvencEncoder::description() const {
    return std::format("NVENC {} {}x{}@{} CBR {} kbit/s, P4 ultra-low-latency",
                       cfg_.codec == VideoCodec::HEVC ? "HEVC Main" : "H.264 High", cfg_.width, cfg_.height,
                       cfg_.fps, cfg_.bitrate_kbps);
}

void NvencEncoder::close() {
    if (encoder_) {
        if (bitstream_) api_.nvEncDestroyBitstreamBuffer(encoder_, bitstream_);
        if (registered_) api_.nvEncUnregisterResource(encoder_, registered_);
        api_.nvEncDestroyEncoder(encoder_);
    }
    encoder_ = nullptr;
    bitstream_ = nullptr;
    registered_ = nullptr;
    if (lib_) FreeLibrary(lib_);
    lib_ = nullptr;
}

}  // namespace tcl
