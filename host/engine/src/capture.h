// Desktop capture via DXGI Desktop Duplication. Frames stay on the GPU.
#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <string>

namespace tcl {

using Microsoft::WRL::ComPtr;

class DesktopCapture {
public:
    enum class Result { NewFrame, Timeout, AccessLost, Error };

    // Picks the first adapter that has a desktop output attached (preferring NVIDIA) and
    // creates a D3D11 device on it. The same device must be used for conversion and NVENC.
    bool init(int output_index = 0);

    // Re-creates the duplication after AccessLost (mode change, UAC prompt, fullscreen switch...).
    bool reopen();

    // Waits up to timeout_ms for a new desktop image. On NewFrame, `frame` is valid until release().
    // The mouse cursor is not part of the image (DXGI delivers it separately; not drawn yet).
    Result acquire(int timeout_ms, ComPtr<ID3D11Texture2D>& frame);
    void release();

    ID3D11Device* device() const { return device_.Get(); }
    ID3D11DeviceContext* context() const { return context_.Get(); }
    UINT width() const { return width_; }
    UINT height() const { return height_; }
    const std::wstring& adapter_name() const { return adapter_name_; }
    // When Windows presented the last acquired frame, in steady_clock microseconds.
    uint64_t present_time_us() const { return present_time_us_; }
    const std::string& error() const { return error_; }

private:
    bool open_duplication();

    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<IDXGIOutput1> output_;
    ComPtr<IDXGIOutputDuplication> dup_;
    bool holding_frame_ = false;
    uint64_t present_time_us_ = 0;
    UINT width_ = 0, height_ = 0;
    std::wstring adapter_name_;
    std::string error_;
};

}  // namespace tcl
