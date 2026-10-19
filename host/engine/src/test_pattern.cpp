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
    const float t = float(frame % 240) / 240.0f;
    const float bg[4] = {0.05f + 0.1f * t, 0.08f, 0.15f + 0.2f * (1.0f - t), 1.0f};
    context_->ClearView(rtv_.Get(), bg, nullptr, 0);

    // A white bar sweeping left to right once per second at 60 fps; easy to judge smoothness by eye.
    const LONG bar_w = LONG(width_ / 16);
    const LONG x = LONG((frame % 60) * (width_ - UINT(bar_w)) / 59);
    const D3D11_RECT bar{x, 0, x + bar_w, LONG(height_)};
    const float white[4] = {1, 1, 1, 1};
    context_->ClearView(rtv_.Get(), white, &bar, 1);

    // A row of 8 blocks showing the frame counter in binary: lets you spot dropped/repeated frames.
    const LONG block = LONG(height_ / 18);
    for (int bit = 0; bit < 8; ++bit) {
        const D3D11_RECT r{block * (1 + 2 * bit), block, block * (2 + 2 * bit), block * 2};
        const float on[4] = {1.0f, 0.75f, 0.1f, 1}, off[4] = {0.15f, 0.15f, 0.15f, 1};
        context_->ClearView(rtv_.Get(), (frame >> bit) & 1 ? on : off, &r, 1);
    }
}

}  // namespace tcl
