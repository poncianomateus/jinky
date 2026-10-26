// GPU conversion of the desktop image into the encoder's input at the stream resolution, using
// the D3D11 video processor (scaling, letterboxing and color conversion):
//   SDR: 8-bit BGRA -> NV12, BT.709 limited range.
//   HDR: the HDR desktop (16-bit float, scRGB) -> P010, BT.2020 with the PQ curve (HDR10).
#pragma once

#include <d3d11_1.h>
#include <wrl/client.h>

#include <string>

namespace tcl {

using Microsoft::WRL::ComPtr;

class Nv12Converter {
public:
    bool init(ID3D11Device* device, ID3D11DeviceContext* context, UINT src_w, UINT src_h, UINT dst_w,
              UINT dst_h, bool hdr = false);
    bool hdr() const { return hdr_; }
    // Converts `src` into output(). The source must match the size passed to init().
    bool convert(ID3D11Texture2D* src);

    ID3D11Texture2D* output() const { return output_.Get(); }
    UINT src_width() const { return src_w_; }
    UINT src_height() const { return src_h_; }
    const std::string& error() const { return error_; }

private:
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<ID3D11VideoDevice> vdevice_;
    ComPtr<ID3D11VideoContext> vcontext_;
    ComPtr<ID3D11VideoProcessorEnumerator> enumerator_;
    ComPtr<ID3D11VideoProcessor> processor_;
    ComPtr<ID3D11Texture2D> output_;
    ComPtr<ID3D11VideoProcessorOutputView> output_view_;
    // HDR10: pixel shaders write the P010 planes (the video processor can't make PQ from scRGB).
    bool init_hdr_shaders(UINT dst_w, UINT dst_h);
    bool convert_hdr(ID3D11Texture2D* src);
    ComPtr<ID3D11VertexShader> vs_;
    ComPtr<ID3D11PixelShader> ps_y_, ps_uv_;
    ComPtr<ID3D11RenderTargetView> rtv_y_, rtv_uv_;
    ComPtr<ID3D11SamplerState> sampler_;
    ComPtr<ID3D11Buffer> rect_cb_;
    ComPtr<ID3D11ShaderResourceView> src_srv_;
    ID3D11Texture2D* src_srv_for_ = nullptr;
    UINT dst_w_ = 0, dst_h_ = 0;
    UINT src_w_ = 0, src_h_ = 0;
    bool hdr_ = false;
    std::string error_;
};

}  // namespace tcl
