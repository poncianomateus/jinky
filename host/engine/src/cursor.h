// Mouse cursor for the stream. Desktop Duplication delivers the desktop image without the
// cursor, plus the cursor's position and shape separately; this draws it back on top.
#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <string>
#include <vector>

namespace tcl {

using Microsoft::WRL::ComPtr;

class CursorOverlay {
public:
    bool init(ID3D11Device* device, ID3D11DeviceContext* context);

    // Call while the duplication frame is held. Returns true if position, visibility or shape changed.
    bool update(IDXGIOutputDuplication* dup, const DXGI_OUTDUPL_FRAME_INFO& info);

    bool visible() const { return visible_ && texture_; }
    // Alpha-blends the cursor onto `target` (a render-target view of a desktop-sized texture).
    void draw(ID3D11RenderTargetView* target, UINT target_w, UINT target_h);

    const std::string& error() const { return error_; }

private:
    bool upload_shape(const DXGI_OUTDUPL_POINTER_SHAPE_INFO& shape, const std::vector<uint8_t>& data);

    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<ID3D11VertexShader> vs_;
    ComPtr<ID3D11PixelShader> ps_;
    ComPtr<ID3D11Buffer> rect_cb_;
    ComPtr<ID3D11SamplerState> sampler_;
    ComPtr<ID3D11BlendState> blend_;
    ComPtr<ID3D11Texture2D> texture_;
    ComPtr<ID3D11ShaderResourceView> srv_;
    bool visible_ = false;
    LONG x_ = 0, y_ = 0;
    UINT w_ = 0, h_ = 0;
    std::vector<uint8_t> shape_buf_;
    std::string error_;
};

// Keeps a clean copy of the last desktop image so the cursor can be redrawn when only the mouse
// moved, and produces the composed (desktop + cursor) texture fed to the encoder path.
class FrameCompositor {
public:
    // (Re)creates the textures to match `desktop` (size/format); call on every new desktop frame.
    bool set_desktop(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* desktop);
    // Returns the texture to convert: the clean desktop, or a copy with the cursor drawn on it.
    ID3D11Texture2D* compose(ID3D11DeviceContext* context, CursorOverlay& cursor);
    bool has_desktop() const { return clean_ != nullptr; }
    // The clean desktop image (no cursor), for screenshots. Null before the first frame.
    ID3D11Texture2D* desktop() const { return clean_.Get(); }

private:
    ComPtr<ID3D11Texture2D> clean_, composed_;
    ComPtr<ID3D11RenderTargetView> composed_rtv_;
    D3D11_TEXTURE2D_DESC desc_{};
};

}  // namespace tcl
