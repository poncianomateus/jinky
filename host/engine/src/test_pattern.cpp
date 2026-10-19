#include "test_pattern.h"

namespace tcl {

bool TestPattern::init(ID3D11Device* device, ID3D11DeviceContext* context, UINT width, UINT height) {
    width_ = width;
    height_ = height;
    if (FAILED(context->QueryInterface(IID_PPV_ARGS(&context_)))) return false;
    D3D11_TEXTURE2D_DESC td{};
    td.Width = width;
    td.Height = height;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;  // same format as the desktop
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    return SUCCEEDED(device->CreateTexture2D(&td, nullptr, &texture_)) &&
           SUCCEEDED(device->CreateRenderTargetView(texture_.Get(), nullptr, &rtv_));
}

void TestPattern::draw(uint64_t frame) {
    // Dark background with a slow colour drift, so every frame still differs from the previous one.
    const float t = float(frame % 600) / 600.0f;
    const float bg[4] = {0.04f + 0.04f * t, 0.06f, 0.12f + 0.04f * (1.0f - t), 1.0f};
    context_->ClearView(rtv_.Get(), bg, nullptr, 0);

    // A white bar sweeping left to right every 3 s (moves ~10 px per frame at 1080p60). Slow enough
    // to judge smoothness by eye: any stutter means frames were dropped or shown twice.
    constexpr uint64_t kSweepFrames = 180;
    const LONG bar_w = LONG(width_ / 24);
    const LONG x = LONG((frame % kSweepFrames) * (width_ - UINT(bar_w)) / (kSweepFrames - 1));
    const D3D11_RECT bar{x, 0, x + bar_w, LONG(height_)};
    const float white[4] = {1, 1, 1, 1};
    context_->ClearView(rtv_.Get(), white, &bar, 1);
}

}  // namespace tcl
