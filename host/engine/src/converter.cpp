#include "converter.h"

#include <d3dcompiler.h>

#include <algorithm>
#include <format>

namespace tcl {

namespace {

// HDR10 from the HDR desktop. Windows composes HDR in scRGB: linear light, BT.709 primaries,
// 1.0 = 80 nits. Each output pixel: to BT.2020 primaries, to the PQ curve (SMPTE ST 2084,
// 1.0 = 10000 nits), to Y'CbCr BT.2020 limited range, as 10-bit codes in the top bits of P010.
constexpr char kHdrShaders[] = R"(
cbuffer Rect : register(b0) { float4 rect; };  // the picture in NDC: left, top, right, bottom
struct VOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VOut vs_main(uint id : SV_VertexID) {
    float2 t = float2(id & 1, id >> 1);
    VOut o;
    o.pos = float4(lerp(rect.x, rect.z, t.x), lerp(rect.y, rect.w, t.y), 0, 1);
    o.uv = t;
    return o;
}
Texture2D src : register(t0);
SamplerState samp : register(s0);
static const float3x3 to2020 = { 0.6274, 0.3293, 0.0433,
                                 0.0691, 0.9195, 0.0114,
                                 0.0164, 0.0880, 0.8956 };
float3 pq(float3 l) {
    const float m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
    float3 p = pow(max(l, 0), m1);
    return pow((c1 + c2 * p) / (1 + c3 * p), m2);
}
float3 rgb_pq(float2 uv) {
    float3 lin = src.Sample(samp, uv).rgb * (80.0 / 10000.0);
    return pq(saturate(mul(to2020, lin)));
}
static const float k = 64.0 / 65535.0;  // a 10-bit code in the top bits of a 16-bit sample
static const float3 kY = float3(0.2627, 0.6780, 0.0593);
float ps_y(VOut i) : SV_Target {
    float y = dot(rgb_pq(i.uv), kY);
    return (64.0 + 876.0 * y) * k;
}
float2 ps_uv(VOut i) : SV_Target {
    // Half resolution: the bilinear sample at the centre of each 2x2 block averages it.
    float3 c = rgb_pq(i.uv);
    float y = dot(c, kY);
    return float2(512.0 + 896.0 * (c.b - y) / 1.8814, 512.0 + 896.0 * (c.r - y) / 1.4746) * k;
}
)";

ComPtr<ID3DBlob> compile_hdr(const char* entry, const char* target, std::string& error) {
    ComPtr<ID3DBlob> code, errors;
    if (FAILED(D3DCompile(kHdrShaders, sizeof(kHdrShaders) - 1, "hdr10", nullptr, nullptr, entry, target,
                          D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &code, &errors))) {
        error = errors ? static_cast<const char*>(errors->GetBufferPointer()) : "HDR shader compile failed";
        return nullptr;
    }
    return code;
}

}  // namespace

bool Nv12Converter::init_hdr_shaders(UINT dst_w, UINT dst_h) {
    auto vs = compile_hdr("vs_main", "vs_4_0", error_);
    auto psy = compile_hdr("ps_y", "ps_4_0", error_);
    auto psuv = compile_hdr("ps_uv", "ps_4_0", error_);
    if (!vs || !psy || !psuv) return false;
    if (FAILED(device_->CreateVertexShader(vs->GetBufferPointer(), vs->GetBufferSize(), nullptr, &vs_)) ||
        FAILED(device_->CreatePixelShader(psy->GetBufferPointer(), psy->GetBufferSize(), nullptr, &ps_y_)) ||
        FAILED(device_->CreatePixelShader(psuv->GetBufferPointer(), psuv->GetBufferSize(), nullptr, &ps_uv_))) {
        error_ = "HDR shaders could not be created";
        return false;
    }
    // The P010 output, written plane by plane: Y as R16, CbCr as R16G16 at half size.
    D3D11_TEXTURE2D_DESC td{};
    td.Width = dst_w;
    td.Height = dst_h;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_P010;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_RENDER_TARGET;
    D3D11_RENDER_TARGET_VIEW_DESC y{}, uv{};
    y.ViewDimension = uv.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D;
    y.Format = DXGI_FORMAT_R16_UNORM;
    uv.Format = DXGI_FORMAT_R16G16_UNORM;
    if (FAILED(device_->CreateTexture2D(&td, nullptr, &output_)) ||
        FAILED(device_->CreateRenderTargetView(output_.Get(), &y, &rtv_y_)) ||
        FAILED(device_->CreateRenderTargetView(output_.Get(), &uv, &rtv_uv_))) {
        error_ = "this GPU can't draw into a P010 texture (HDR10)";
        return false;
    }
    D3D11_SAMPLER_DESC sd{};
    sd.Filter = D3D11_FILTER_MIN_MAG_LINEAR_MIP_POINT;
    sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    D3D11_BUFFER_DESC bd{};
    bd.ByteWidth = 16;
    bd.Usage = D3D11_USAGE_DEFAULT;
    bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    if (FAILED(device_->CreateSamplerState(&sd, &sampler_)) || FAILED(device_->CreateBuffer(&bd, nullptr, &rect_cb_))) {
        error_ = "HDR sampler/constants could not be created";
        return false;
    }
    // Letterbox/pillarbox like the SDR path: the picture's place in NDC.
    const double scale = std::min(double(dst_w) / src_w_, double(dst_h) / src_h_);
    const double w = (LONG(src_w_ * scale) & ~1L), h = (LONG(src_h_ * scale) & ~1L);
    const double left = (dst_w - w) / 2, top = (dst_h - h) / 2;
    const float rect[4] = {float(2 * left / dst_w - 1), float(1 - 2 * top / dst_h), float(2 * (left + w) / dst_w - 1),
                           float(1 - 2 * (top + h) / dst_h)};
    context_->UpdateSubresource(rect_cb_.Get(), 0, nullptr, rect, 0, 0);
    dst_w_ = dst_w;
    dst_h_ = dst_h;
    hdr_ = true;
    return true;
}

bool Nv12Converter::convert_hdr(ID3D11Texture2D* src) {
    if (src != src_srv_for_) {
        src_srv_.Reset();
        if (FAILED(device_->CreateShaderResourceView(src, nullptr, &src_srv_))) {
            error_ = "HDR: the desktop image can't be read by a shader";
            src_srv_for_ = nullptr;
            return false;
        }
        src_srv_for_ = src;
    }
    // Black around the picture (limited range: Y 64, CbCr 512).
    const float k = 64.0f / 65535.0f;
    const float black_y[4] = {64 * k, 0, 0, 0}, black_uv[4] = {512 * k, 512 * k, 0, 0};
    context_->ClearRenderTargetView(rtv_y_.Get(), black_y);
    context_->ClearRenderTargetView(rtv_uv_.Get(), black_uv);

    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);
    context_->IASetInputLayout(nullptr);
    context_->VSSetShader(vs_.Get(), nullptr, 0);
    context_->VSSetConstantBuffers(0, 1, rect_cb_.GetAddressOf());
    context_->PSSetShaderResources(0, 1, src_srv_.GetAddressOf());
    context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
    context_->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
    context_->RSSetState(nullptr);

    D3D11_VIEWPORT vp{0, 0, float(dst_w_), float(dst_h_), 0, 1};
    context_->RSSetViewports(1, &vp);
    context_->OMSetRenderTargets(1, rtv_y_.GetAddressOf(), nullptr);
    context_->PSSetShader(ps_y_.Get(), nullptr, 0);
    context_->Draw(4, 0);

    vp.Width = float(dst_w_ / 2);
    vp.Height = float(dst_h_ / 2);
    context_->RSSetViewports(1, &vp);
    context_->OMSetRenderTargets(1, rtv_uv_.GetAddressOf(), nullptr);
    context_->PSSetShader(ps_uv_.Get(), nullptr, 0);
    context_->Draw(4, 0);

    ID3D11RenderTargetView* none = nullptr;
    context_->OMSetRenderTargets(1, &none, nullptr);
    ID3D11ShaderResourceView* no_srv = nullptr;
    context_->PSSetShaderResources(0, 1, &no_srv);
    context_->Flush();  // as in the SDR path: don't let NVENC wait on a held command buffer
    return true;
}

bool Nv12Converter::init(ID3D11Device* device, ID3D11DeviceContext* context, UINT src_w, UINT src_h,
                         UINT dst_w, UINT dst_h, bool hdr) {
    hdr_ = false;
    output_.Reset();
    output_view_.Reset();
    processor_.Reset();
    enumerator_.Reset();
    rtv_y_.Reset();
    rtv_uv_.Reset();
    src_srv_.Reset();
    src_srv_for_ = nullptr;
    device_ = device;
    context_ = context;
    src_w_ = src_w;
    src_h_ = src_h;
    // HDR10: our own shaders (the GPU's video processor doesn't make PQ from the scRGB desktop).
    if (hdr) return init_hdr_shaders(dst_w, dst_h);
    if (FAILED(device->QueryInterface(IID_PPV_ARGS(&vdevice_))) ||
        FAILED(context->QueryInterface(IID_PPV_ARGS(&vcontext_)))) {
        error_ = "D3D11 video API unavailable";
        return false;
    }

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC cd{};
    cd.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    cd.InputWidth = src_w;
    cd.InputHeight = src_h;
    cd.OutputWidth = dst_w;
    cd.OutputHeight = dst_h;
    cd.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
    HRESULT hr = vdevice_->CreateVideoProcessorEnumerator(&cd, &enumerator_);
    if (SUCCEEDED(hr)) hr = vdevice_->CreateVideoProcessor(enumerator_.Get(), 0, &processor_);
    if (FAILED(hr)) {
        error_ = std::format("video processor creation failed 0x{:08X}", unsigned(hr));
        return false;
    }

    D3D11_TEXTURE2D_DESC td{};
    td.Width = dst_w;
    td.Height = dst_h;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_NV12;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_RENDER_TARGET;
    hr = device->CreateTexture2D(&td, nullptr, &output_);
    if (SUCCEEDED(hr)) {
        D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC ovd{};
        ovd.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
        hr = vdevice_->CreateVideoProcessorOutputView(output_.Get(), enumerator_.Get(), &ovd, &output_view_);
    }
    if (FAILED(hr)) {
        error_ = std::format("NV12 output creation failed 0x{:08X}", unsigned(hr));
        return false;
    }

    // Input: full-range RGB. Output: BT.709 limited range — must match the encoder's VUI.
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE in_cs{};
    in_cs.RGB_Range = 0;  // 0-255
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE out_cs{};
    out_cs.YCbCr_Matrix = 1;  // BT.709
    out_cs.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    vcontext_->VideoProcessorSetStreamColorSpace(processor_.Get(), 0, &in_cs);
    vcontext_->VideoProcessorSetOutputColorSpace(processor_.Get(), &out_cs);

    // Letterbox/pillarbox if the desktop is not 16:9, instead of stretching.
    const double scale = std::min(double(dst_w) / src_w, double(dst_h) / src_h);
    const LONG w = LONG(src_w * scale) & ~1L, h = LONG(src_h * scale) & ~1L;
    RECT dst{LONG(dst_w - w) / 2, LONG(dst_h - h) / 2, 0, 0};
    dst.right = dst.left + w;
    dst.bottom = dst.top + h;
    RECT src{0, 0, LONG(src_w), LONG(src_h)};
    vcontext_->VideoProcessorSetStreamSourceRect(processor_.Get(), 0, TRUE, &src);
    vcontext_->VideoProcessorSetStreamDestRect(processor_.Get(), 0, TRUE, &dst);
    D3D11_VIDEO_COLOR black{};
    black.YCbCr = {16.0f / 255, 128.0f / 255, 128.0f / 255, 1.0f};
    vcontext_->VideoProcessorSetOutputBackgroundColor(processor_.Get(), TRUE, &black);
    return true;
}

bool Nv12Converter::convert(ID3D11Texture2D* src) {
    if (hdr_) return convert_hdr(src);
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC ivd{};
    ivd.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    ComPtr<ID3D11VideoProcessorInputView> input_view;
    HRESULT hr = vdevice_->CreateVideoProcessorInputView(src, enumerator_.Get(), &ivd, &input_view);
    if (FAILED(hr)) {
        error_ = std::format("input view creation failed 0x{:08X}", unsigned(hr));
        return false;
    }
    D3D11_VIDEO_PROCESSOR_STREAM stream{};
    stream.Enable = TRUE;
    stream.pInputSurface = input_view.Get();
    hr = vcontext_->VideoProcessorBlt(processor_.Get(), output_view_.Get(), 0, 1, &stream);
    if (FAILED(hr)) {
        error_ = std::format("VideoProcessorBlt failed 0x{:08X}", unsigned(hr));
        return false;
    }
    // Submit now. Otherwise the driver may hold the command buffer for tens of ms, and NVENC,
    // which waits for this conversion, stalls with it (measured: 40+ ms encode spikes).
    context_->Flush();
    return true;
}

}  // namespace tcl
