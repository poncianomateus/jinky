#include "converter.h"

#include <algorithm>
#include <format>

namespace tcl {

bool Nv12Converter::init(ID3D11Device* device, ID3D11DeviceContext* context, UINT src_w, UINT src_h,
                         UINT dst_w, UINT dst_h) {
    device_ = device;
    src_w_ = src_w;
    src_h_ = src_h;
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
    return true;
}

}  // namespace tcl
