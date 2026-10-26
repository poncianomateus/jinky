#include "screenshot.h"

#include <knownfolders.h>
#include <shlobj.h>
#include <wincodec.h>
#include <wrl/client.h>

#include <ctime>
#include <filesystem>
#include <string>
#include <thread>
#include <vector>

#include "log.h"

namespace tcl {

using Microsoft::WRL::ComPtr;

namespace {

std::filesystem::path pictures_folder() {
    PWSTR path = nullptr;
    std::filesystem::path out;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Pictures, 0, nullptr, &path))) out = path;
    CoTaskMemFree(path);
    return out;
}

bool write_jpeg(const std::wstring& path, const std::vector<uint8_t>& bgra, UINT width, UINT height, UINT stride) {
    const HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool ok = false;
    {
        ComPtr<IWICImagingFactory> wic;
        ComPtr<IWICStream> stream;
        ComPtr<IWICBitmapEncoder> encoder;
        ComPtr<IWICBitmapFrameEncode> frame;
        ComPtr<IPropertyBag2> options;
        if (SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&wic))) &&
            SUCCEEDED(wic->CreateStream(&stream)) && SUCCEEDED(stream->InitializeFromFilename(path.c_str(), GENERIC_WRITE)) &&
            SUCCEEDED(wic->CreateEncoder(GUID_ContainerFormatJpeg, nullptr, &encoder)) &&
            SUCCEEDED(encoder->Initialize(stream.Get(), WICBitmapEncoderNoCache)) &&
            SUCCEEDED(encoder->CreateNewFrame(&frame, &options))) {
            PROPBAG2 name{};
            name.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
            VARIANT quality{};
            quality.vt = VT_R4;
            quality.fltVal = 0.92f;
            options->Write(1, &name, &quality);
            WICPixelFormatGUID format = GUID_WICPixelFormat32bppBGRA;
            ComPtr<IWICBitmap> bitmap;
            ComPtr<IWICFormatConverter> to24;
            // JPEG has no alpha: hand the encoder 24-bit BGR.
            ok = SUCCEEDED(frame->Initialize(options.Get())) && SUCCEEDED(frame->SetSize(width, height)) &&
                 SUCCEEDED(wic->CreateBitmapFromMemory(width, height, format, stride, UINT(bgra.size()),
                                                       const_cast<BYTE*>(bgra.data()), &bitmap)) &&
                 SUCCEEDED(wic->CreateFormatConverter(&to24)) &&
                 SUCCEEDED(to24->Initialize(bitmap.Get(), GUID_WICPixelFormat24bppBGR, WICBitmapDitherTypeNone, nullptr, 0,
                                            WICBitmapPaletteTypeCustom));
            if (ok) {
                format = GUID_WICPixelFormat24bppBGR;
                ok = SUCCEEDED(frame->SetPixelFormat(&format)) && format == GUID_WICPixelFormat24bppBGR &&
                     SUCCEEDED(frame->WriteSource(to24.Get(), nullptr)) && SUCCEEDED(frame->Commit()) &&
                     SUCCEEDED(encoder->Commit());
            }
        }
    }
    if (SUCCEEDED(init)) CoUninitialize();
    return ok;
}

}  // namespace

bool save_screenshot(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* image, uint32_t app_id) {
    if (!device || !context || !image) return false;
    D3D11_TEXTURE2D_DESC d;
    image->GetDesc(&d);
    if (d.Format != DXGI_FORMAT_B8G8R8A8_UNORM && d.Format != DXGI_FORMAT_B8G8R8A8_UNORM_SRGB) {
        TCL_WARN("capture", "screenshot: the desktop format ({}) isn't 8-bit BGRA (HDR on?)", int(d.Format));
        return false;
    }
    D3D11_TEXTURE2D_DESC sd = d;
    sd.Usage = D3D11_USAGE_STAGING;
    sd.BindFlags = 0;
    sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    sd.MiscFlags = 0;
    sd.MipLevels = sd.ArraySize = 1;
    ComPtr<ID3D11Texture2D> staging;
    if (FAILED(device->CreateTexture2D(&sd, nullptr, &staging))) return false;
    context->CopyResource(staging.Get(), image);
    D3D11_MAPPED_SUBRESOURCE m{};
    if (FAILED(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &m))) return false;
    const UINT stride = d.Width * 4;
    std::vector<uint8_t> pixels(size_t(stride) * d.Height);
    for (UINT y = 0; y < d.Height; ++y)
        memcpy(pixels.data() + size_t(y) * stride, static_cast<const uint8_t*>(m.pData) + size_t(y) * m.RowPitch, stride);
    context->Unmap(staging.Get(), 0);

    // Pictures\Jinky\2026-10-25 14-03-22_1817070.jpg (the app id tells Jinky which game it was).
    const auto folder = pictures_folder() / L"Jinky";
    const std::time_t t = std::time(nullptr);
    std::tm local{};
    localtime_s(&local, &t);
    wchar_t stamp[64];
    wcsftime(stamp, 64, L"%Y-%m-%d %H-%M-%S", &local);
    const std::wstring file = std::wstring(stamp) + L"_" + std::to_wstring(app_id) + L".jpg";
    const UINT w = d.Width, h = d.Height;
    std::thread([folder, file, pixels = std::move(pixels), w, h, stride]() {
        std::error_code ec;
        std::filesystem::create_directories(folder, ec);
        const auto path = folder / file;
        if (write_jpeg(path.wstring(), pixels, w, h, stride)) {
            std::string name;
            for (wchar_t c : file) name.push_back(c < 128 ? char(c) : '?');
            TCL_INFO("capture", "screenshot saved: {}", name);
            const auto u8 = path.u8string();
            const std::string utf8(u8.begin(), u8.end());
            tcl::emit_event("screenshot", "\"file\":" + tcl::json_str(utf8));
        } else {
            TCL_WARN("capture", "screenshot: could not write the JPEG");
        }
    }).detach();
    return true;
}

}  // namespace tcl
