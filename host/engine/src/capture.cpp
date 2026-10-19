#include "capture.h"

#include <format>
#include <vector>

#include "log.h"

namespace tcl {

namespace {
constexpr UINT kNvidiaVendorId = 0x10DE;

std::string hr_str(HRESULT hr) { return std::format("0x{:08X}", static_cast<unsigned>(hr)); }
}  // namespace

bool DesktopCapture::init(int output_index) {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) {
        error_ = "CreateDXGIFactory1 failed";
        return false;
    }

    // Collect adapters that drive a monitor; NVIDIA first so NVENC shares the capture device.
    struct Candidate {
        ComPtr<IDXGIAdapter1> adapter;
        ComPtr<IDXGIOutput> output;
        DXGI_ADAPTER_DESC1 desc;
    };
    std::vector<Candidate> candidates;
    ComPtr<IDXGIAdapter1> adapter;
    for (UINT i = 0; factory->EnumAdapters1(i, &adapter) != DXGI_ERROR_NOT_FOUND; ++i) {
        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) continue;
        ComPtr<IDXGIOutput> output;
        if (adapter->EnumOutputs(UINT(output_index), &output) == DXGI_ERROR_NOT_FOUND) continue;
        Candidate c{adapter, output, desc};
        if (desc.VendorId == kNvidiaVendorId)
            candidates.insert(candidates.begin(), c);
        else
            candidates.push_back(c);
    }
    if (candidates.empty()) {
        error_ = std::format("no GPU has a monitor on output {}", output_index);
        return false;
    }

    auto& chosen = candidates.front();
    adapter_name_ = chosen.desc.Description;
    if (chosen.desc.VendorId != kNvidiaVendorId)
        TCL_WARN("capture", "monitor is not attached to an NVIDIA GPU; NVENC will not be available");

    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    // VIDEO_SUPPORT is required for the D3D11 video processor used in conversion.
    HRESULT hr = D3D11CreateDevice(chosen.adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr,
                                   D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                   levels, UINT(std::size(levels)), D3D11_SDK_VERSION, &device_, nullptr,
                                   &context_);
    if (FAILED(hr)) {
        error_ = "D3D11CreateDevice failed " + hr_str(hr);
        return false;
    }
    // NVENC and the capture loop run on different threads in later phases.
    ComPtr<ID3D10Multithread> mt;
    if (SUCCEEDED(context_.As(&mt))) mt->SetMultithreadProtected(TRUE);

    if (!cursor_.init(device_.Get(), context_.Get()))
        TCL_WARN("capture", "mouse cursor will not be shown: {}", cursor_.error());

    if (FAILED(chosen.output.As(&output_))) {
        error_ = "IDXGIOutput1 not supported";
        return false;
    }
    return open_duplication();
}

bool DesktopCapture::open_duplication() {
    release();
    dup_.Reset();
    HRESULT hr = output_->DuplicateOutput(device_.Get(), &dup_);
    if (FAILED(hr)) {
        // E_ACCESSDENIED happens on the secure desktop (UAC, lock screen); caller retries.
        error_ = "DuplicateOutput failed " + hr_str(hr);
        return false;
    }
    DXGI_OUTDUPL_DESC d;
    dup_->GetDesc(&d);
    width_ = d.ModeDesc.Width;
    height_ = d.ModeDesc.Height;
    return true;
}

bool DesktopCapture::reopen() { return open_duplication(); }

DesktopCapture::Result DesktopCapture::acquire(int timeout_ms, ComPtr<ID3D11Texture2D>& frame) {
    frame.Reset();
    if (!dup_) return Result::AccessLost;
    release();

    DXGI_OUTDUPL_FRAME_INFO info;
    ComPtr<IDXGIResource> resource;
    HRESULT hr = dup_->AcquireNextFrame(UINT(timeout_ms), &info, &resource);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return Result::Timeout;
    if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_INVALID_CALL) return Result::AccessLost;
    if (FAILED(hr)) {
        error_ = "AcquireNextFrame failed " + hr_str(hr);
        return Result::Error;
    }
    holding_frame_ = true;
    const bool cursor_changed = cursor_.update(dup_.Get(), info);

    // LastPresentTime == 0 means only the mouse moved; the image itself did not change.
    if (info.LastPresentTime.QuadPart == 0) return cursor_changed ? Result::CursorMoved : Result::Timeout;

    // LastPresentTime is a QueryPerformanceCounter value; MSVC's steady_clock uses the same counter.
    static const int64_t qpc_freq = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    const int64_t ticks = info.LastPresentTime.QuadPart;
    present_time_us_ = uint64_t(ticks / qpc_freq * 1'000'000 + ticks % qpc_freq * 1'000'000 / qpc_freq);

    if (FAILED(resource.As(&frame))) {
        error_ = "desktop resource is not a texture";
        return Result::Error;
    }
    return Result::NewFrame;
}

void DesktopCapture::release() {
    if (holding_frame_ && dup_) dup_->ReleaseFrame();
    holding_frame_ = false;
}

}  // namespace tcl
