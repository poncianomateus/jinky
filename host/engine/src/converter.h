// GPU conversion of the BGRA desktop image into an NV12 texture at the stream resolution,
// using the D3D11 video processor (scaling, letterboxing and RGB->BT.709 limited range).
#pragma once

#include <d3d11.h>
#include <wrl/client.h>

#include <string>

namespace tcl {

using Microsoft::WRL::ComPtr;

class Nv12Converter {
public:
    bool init(ID3D11Device* device, ID3D11DeviceContext* context, UINT src_w, UINT src_h, UINT dst_w,
              UINT dst_h);
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
    UINT src_w_ = 0, src_h_ = 0;
    std::string error_;
};

}  // namespace tcl
