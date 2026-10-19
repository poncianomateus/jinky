// Animated synthetic frames rendered on the GPU (slow moving bar over a drifting background), used to
// test the full pipeline at the target frame rate without depending on what is on the desktop.
#pragma once

#include <d3d11_1.h>
#include <wrl/client.h>

#include <cstdint>

namespace tcl {

class TestPattern {
public:
    bool init(ID3D11Device* device, ID3D11DeviceContext* context, UINT width, UINT height);
    void draw(uint64_t frame);
    ID3D11Texture2D* texture() const { return texture_.Get(); }

private:
    Microsoft::WRL::ComPtr<ID3D11DeviceContext1> context_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> texture_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> rtv_;
    UINT width_ = 0, height_ = 0;
};

}  // namespace tcl
