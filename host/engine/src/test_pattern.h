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
    // `stress`: also fill the screen with random colour blocks that change every frame, so the
    // encoder needs its full bitrate (worst case, like fast-moving game scenes).
    void draw(uint64_t frame, bool stress = false);
    ID3D11Texture2D* texture() const { return texture_.Get(); }

private:
    static constexpr UINT kNoiseExtra = 256;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext1> context_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> texture_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> rtv_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> noise_;  // larger than the frame; random per-pixel colours
    UINT width_ = 0, height_ = 0;
};

}  // namespace tcl
