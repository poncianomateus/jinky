// Desktop capture via DXGI Desktop Duplication. Frames stay on the GPU.
#pragma once

#include <d3d11.h>
#include <dxgi1_6.h>
#include <wrl/client.h>

#include <string>

#include "cursor.h"

namespace tcl {

using Microsoft::WRL::ComPtr;

class DesktopCapture {
public:
    // CursorMoved: the desktop image is unchanged but the mouse cursor moved or changed shape.
    enum class Result { NewFrame, CursorMoved, Timeout, AccessLost, Error };

    // Picks the first adapter that has a desktop output attached (preferring NVIDIA) and
    // creates a D3D11 device on it. The same device must be used for conversion and NVENC.
    bool init(int output_index = 0);

    // Re-creates the duplication after AccessLost (mode change, UAC prompt, fullscreen switch...).
    bool reopen();

    // Captures another monitor of the same GPU from the next reopen(): the one Windows calls
    // `gdi_name` (e.g. DISPLAY3 with its \\.\ prefix), or with an empty name the main one. False if it isn't in the
    // desktop (yet) or belongs to another GPU.
    bool retarget(const std::wstring& gdi_name);

    // HDR sessions: capture the desktop as Windows composes it in HDR (16-bit float scRGB)
    // instead of the 8-bit SDR copy. Takes effect at the next (re)open; only when Windows HDR is
    // on for this monitor.
    void want_hdr(bool on) { want_hdr_ = on; }
    // Frames come in HDR (16-bit float) right now.
    bool hdr() const { return hdr_; }
    // Windows HDR is on for the captured monitor.
    bool display_hdr_on();
    // The monitor's luminance (nits) as Windows reports it: max, min, max full-frame.
    void luminance(float& max_nits, float& min_nits, float& max_fall) const;

    // Waits up to timeout_ms for a new desktop image. On NewFrame, `frame` is valid until release().
    // The mouse cursor is not part of the image (DXGI delivers it separately; not drawn yet).
    Result acquire(int timeout_ms, ComPtr<ID3D11Texture2D>& frame);
    void release();

    ID3D11Device* device() const { return device_.Get(); }
    ID3D11DeviceContext* context() const { return context_.Get(); }
    UINT width() const { return width_; }
    UINT height() const { return height_; }
    const std::wstring& adapter_name() const { return adapter_name_; }
    // GDI name of the captured monitor, e.g. \\.\DISPLAY1 (for display mode changes).
    const std::wstring& display_name() const { return display_name_; }
    CursorOverlay& cursor() { return cursor_; }
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
    bool want_hdr_ = false, hdr_ = false;
    CursorOverlay cursor_;
    uint64_t present_time_us_ = 0;
    UINT width_ = 0, height_ = 0;
    std::wstring adapter_name_, display_name_;
    std::string error_;
};

}  // namespace tcl
