#include "cursor.h"

#include <d3dcompiler.h>

#include <cstring>

namespace tcl {

namespace {

// Full-screen-quad style vertex shader: 4 vertices from SV_VertexID, placed by a rect in NDC.
constexpr char kShaders[] = R"(
cbuffer Rect : register(b0) { float4 rect; };  // left, top, right, bottom in NDC
struct VOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VOut vs_main(uint id : SV_VertexID) {
    float2 t = float2(id & 1, id >> 1);
    VOut o;
    o.pos = float4(lerp(rect.x, rect.z, t.x), lerp(rect.y, rect.w, t.y), 0, 1);
    o.uv = t;
    return o;
}
Texture2D tex : register(t0);
SamplerState samp : register(s0);
float4 ps_main(VOut i) : SV_Target { return tex.Sample(samp, i.uv); }
)";

ComPtr<ID3DBlob> compile(const char* entry, const char* target, std::string& error) {
    ComPtr<ID3DBlob> code, errors;
    if (FAILED(D3DCompile(kShaders, sizeof(kShaders) - 1, "cursor", nullptr, nullptr, entry, target, 0, 0, &code,
                          &errors))) {
        error = errors ? static_cast<const char*>(errors->GetBufferPointer()) : "shader compile failed";
        return nullptr;
    }
    return code;
}

}  // namespace

bool CursorOverlay::init(ID3D11Device* device, ID3D11DeviceContext* context) {
    device_ = device;
    context_ = context;
    auto vs = compile("vs_main", "vs_4_0", error_);
    auto ps = compile("ps_main", "ps_4_0", error_);
    if (!vs || !ps) return false;
    device->CreateVertexShader(vs->GetBufferPointer(), vs->GetBufferSize(), nullptr, &vs_);
    device->CreatePixelShader(ps->GetBufferPointer(), ps->GetBufferSize(), nullptr, &ps_);

    D3D11_BUFFER_DESC bd{};
    bd.ByteWidth = 16;
    bd.Usage = D3D11_USAGE_DEFAULT;
    bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    device->CreateBuffer(&bd, nullptr, &rect_cb_);

    D3D11_SAMPLER_DESC sd{};
    sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;  // cursor is drawn 1:1, keep it crisp
    sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    device->CreateSamplerState(&sd, &sampler_);

    D3D11_BLEND_DESC blend{};
    auto& rt = blend.RenderTarget[0];
    rt.BlendEnable = TRUE;
    rt.SrcBlend = D3D11_BLEND_SRC_ALPHA;
    rt.DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    rt.BlendOp = D3D11_BLEND_OP_ADD;
    rt.SrcBlendAlpha = D3D11_BLEND_ONE;
    rt.DestBlendAlpha = D3D11_BLEND_ZERO;
    rt.BlendOpAlpha = D3D11_BLEND_OP_ADD;
    rt.RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    device->CreateBlendState(&blend, &blend_);
    return vs_ && ps_ && rect_cb_ && sampler_ && blend_;
}

bool CursorOverlay::update(IDXGIOutputDuplication* dup, const DXGI_OUTDUPL_FRAME_INFO& info) {
    if (info.LastMouseUpdateTime.QuadPart == 0) return false;  // no pointer news in this frame
    bool changed = false;
    const bool vis = info.PointerPosition.Visible;
    if (vis != visible_ || (vis && (info.PointerPosition.Position.x != x_ || info.PointerPosition.Position.y != y_)))
        changed = true;
    visible_ = vis;
    if (vis) {
        x_ = info.PointerPosition.Position.x;
        y_ = info.PointerPosition.Position.y;
    }
    if (info.PointerShapeBufferSize > 0) {
        shape_buf_.resize(info.PointerShapeBufferSize);
        UINT needed = 0;
        DXGI_OUTDUPL_POINTER_SHAPE_INFO shape{};
        if (SUCCEEDED(dup->GetFramePointerShape(UINT(shape_buf_.size()), shape_buf_.data(), &needed, &shape)) &&
            upload_shape(shape, shape_buf_)) {
            // Position from DXGI is the shape's top-left already; the hot spot only matters for clicks.
            changed = true;
        }
    }
    return changed;
}

bool CursorOverlay::upload_shape(const DXGI_OUTDUPL_POINTER_SHAPE_INFO& shape, const std::vector<uint8_t>& data) {
    // Convert every DXGI cursor type to straight-alpha BGRA. XOR ("invert screen") pixels cannot be
    // reproduced with blending; they are drawn as opaque white, which reads well on most content.
    const bool mono = shape.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME;
    const UINT w = shape.Width, h = mono ? shape.Height / 2 : shape.Height;
    if (w == 0 || h == 0) return false;
    std::vector<uint32_t> px(size_t(w) * h);
    for (UINT y = 0; y < h; ++y) {
        for (UINT x = 0; x < w; ++x) {
            uint32_t out = 0;
            if (mono) {
                const UINT byte = y * shape.Pitch + x / 8, bit = 0x80 >> (x % 8);
                const bool and_bit = data[byte] & bit;
                const bool xor_bit = data[byte + h * shape.Pitch] & bit;
                if (!and_bit) out = xor_bit ? 0xFFFFFFFF : 0xFF000000;  // white / black
                else if (xor_bit) out = 0xFFFFFFFF;                         // invert -> white
                // and=1, xor=0: transparent
            } else {
                const uint32_t v = *reinterpret_cast<const uint32_t*>(&data[y * shape.Pitch + x * 4]);
                if (shape.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_COLOR) {
                    out = v;
                } else {  // MASKED_COLOR: alpha 0 = replace pixel, 0xFF = XOR with screen
                    const uint32_t rgb = v & 0x00FFFFFF;
                    if ((v >> 24) == 0) out = 0xFF000000 | rgb;
                    else if (rgb) out = 0xFFFFFFFF;
                }
            }
            px[size_t(y) * w + x] = out;
        }
    }
    D3D11_TEXTURE2D_DESC td{};
    td.Width = w;
    td.Height = h;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    D3D11_SUBRESOURCE_DATA init{px.data(), w * 4, 0};
    texture_.Reset();
    srv_.Reset();
    if (FAILED(device_->CreateTexture2D(&td, &init, &texture_)) ||
        FAILED(device_->CreateShaderResourceView(texture_.Get(), nullptr, &srv_))) {
        texture_.Reset();
        return false;
    }
    w_ = w;
    h_ = h;
    return true;
}

void CursorOverlay::draw(ID3D11RenderTargetView* target, UINT target_w, UINT target_h) {
    if (!visible()) return;
    const float rect[4] = {
        2.0f * x_ / target_w - 1.0f,
        1.0f - 2.0f * y_ / target_h,
        2.0f * (x_ + LONG(w_)) / target_w - 1.0f,
        1.0f - 2.0f * (y_ + LONG(h_)) / target_h,
    };
    context_->UpdateSubresource(rect_cb_.Get(), 0, nullptr, rect, 0, 0);
    D3D11_VIEWPORT vp{0, 0, float(target_w), float(target_h), 0, 1};
    context_->RSSetViewports(1, &vp);
    context_->OMSetRenderTargets(1, &target, nullptr);
    context_->OMSetBlendState(blend_.Get(), nullptr, 0xFFFFFFFF);
    context_->IASetInputLayout(nullptr);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);
    context_->VSSetShader(vs_.Get(), nullptr, 0);
    context_->VSSetConstantBuffers(0, 1, rect_cb_.GetAddressOf());
    context_->PSSetShader(ps_.Get(), nullptr, 0);
    context_->PSSetShaderResources(0, 1, srv_.GetAddressOf());
    context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
    context_->Draw(4, 0);
    // Unbind so the texture can be used as video-processor input right after.
    ID3D11RenderTargetView* none = nullptr;
    context_->OMSetRenderTargets(1, &none, nullptr);
}

bool FrameCompositor::set_desktop(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* desktop) {
    D3D11_TEXTURE2D_DESC d;
    desktop->GetDesc(&d);
    if (!clean_ || d.Width != desc_.Width || d.Height != desc_.Height || d.Format != desc_.Format) {
        desc_ = d;
        D3D11_TEXTURE2D_DESC td = d;
        td.MipLevels = 1;
        td.ArraySize = 1;
        td.SampleDesc = {1, 0};
        td.Usage = D3D11_USAGE_DEFAULT;
        td.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        td.CPUAccessFlags = 0;
        td.MiscFlags = 0;
        clean_.Reset();
        composed_.Reset();
        composed_rtv_.Reset();
        if (FAILED(device->CreateTexture2D(&td, nullptr, &clean_)) ||
            FAILED(device->CreateTexture2D(&td, nullptr, &composed_)) ||
            FAILED(device->CreateRenderTargetView(composed_.Get(), nullptr, &composed_rtv_))) {
            clean_.Reset();
            return false;
        }
    }
    context->CopyResource(clean_.Get(), desktop);
    return true;
}

ID3D11Texture2D* FrameCompositor::compose(ID3D11DeviceContext* context, CursorOverlay& cursor) {
    if (!cursor.visible()) return clean_.Get();
    context->CopyResource(composed_.Get(), clean_.Get());
    cursor.draw(composed_rtv_.Get(), desc_.Width, desc_.Height);
    return composed_.Get();
}

}  // namespace tcl
