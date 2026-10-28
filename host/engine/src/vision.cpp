#include "vision.h"

#include <d3dcompiler.h>

#include <algorithm>
#include <cmath>
#include <format>

namespace tcl {

namespace {

// One thread group per encoder block: every thread sums the gradients and the brightness of its
// pixels (1 for 16x16 blocks, 2x2 for 32x32), the group adds them up, and thread 0 writes the
// block's detail, brightness and how much they changed since the last frame.
constexpr char kShader[] = R"(
Texture2D<float> Luma : register(t0);
RWStructuredBuffer<uint> Stats : register(u0);
RWStructuredBuffer<uint> Prev : register(u1);
cbuffer Params : register(b0) { uint blocksX; uint blocksY; uint block; uint width; uint height; uint3 pad; };
groupshared float gEdge[256];
groupshared float gMean[256];
[numthreads(16, 16, 1)]
void main(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID, uint gi : SV_GroupIndex) {
    uint step = block / 16;
    float e = 0, m = 0;
    for (uint dy = 0; dy < step; dy++)
        for (uint dx = 0; dx < step; dx++) {
            int2 p = int2(gid.xy * block + tid.xy * step + uint2(dx, dy));
            p = min(p, int2(width - 2, height - 2));
            float c = Luma.Load(int3(p, 0));
            float r = Luma.Load(int3(p + int2(1, 0), 0));
            float d = Luma.Load(int3(p + int2(0, 1), 0));
            e += abs(c - r) + abs(c - d);
            m += c;
        }
    gEdge[gi] = e / (step * step);
    gMean[gi] = m / (step * step);
    GroupMemoryBarrierWithGroupSync();
    for (uint s = 128; s > 0; s >>= 1) {
        if (gi < s) { gEdge[gi] += gEdge[gi + s]; gMean[gi] += gMean[gi + s]; }
        GroupMemoryBarrierWithGroupSync();
    }
    if (gi == 0) {
        uint idx = gid.y * blocksX + gid.x;
        uint edge = (uint)min(gEdge[0] / 256.0 * 255.0 * 4.0, 255.0);
        uint mean = (uint)min(gMean[0] / 256.0 * 255.0, 255.0);
        uint prev = Prev[idx];
        int pe = (int)(prev & 0xFF), pm = (int)((prev >> 8) & 0xFF);
        uint change = (uint)min(abs((int)mean - pm) * 4 + abs((int)edge - pe), 255);
        Stats[idx] = edge | (mean << 8) | (change << 16);
        Prev[idx] = edge | (mean << 8);
    }
}
)";

}  // namespace

bool Vision::init(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* picture, uint32_t width,
                  uint32_t height, uint32_t block) {
    ok_ = false;
    device_ = device;
    context_ = context;
    picture_ = picture;
    block_ = block;
    bx_ = (width + block - 1) / block;
    by_ = (height + block - 1) / block;
    map_.clear();
    stable_.assign(blocks(), 0);
    frame_ = 0;

    D3D11_TEXTURE2D_DESC td{};
    picture->GetDesc(&td);
    if (!(td.BindFlags & D3D11_BIND_SHADER_RESOURCE)) {
        error_ = "the encoder's picture can't be read by shaders";
        return false;
    }
    // The luma plane: R8 of NV12, R16 of P010.
    D3D11_SHADER_RESOURCE_VIEW_DESC sd{};
    sd.Format = td.Format == DXGI_FORMAT_P010 ? DXGI_FORMAT_R16_UNORM : DXGI_FORMAT_R8_UNORM;
    sd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
    sd.Texture2D.MipLevels = 1;
    HRESULT hr = device->CreateShaderResourceView(picture, &sd, luma_.ReleaseAndGetAddressOf());
    if (FAILED(hr)) {
        error_ = std::format("no luma view (0x{:08X})", unsigned(hr));
        return false;
    }
    ComPtr<ID3DBlob> code, errors;
    hr = D3DCompile(kShader, sizeof kShader - 1, "vision", nullptr, nullptr, "main", "cs_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0,
                    &code, &errors);
    if (FAILED(hr)) {
        error_ = errors ? std::string(static_cast<const char*>(errors->GetBufferPointer()), errors->GetBufferSize()) : "shader";
        return false;
    }
    if (FAILED(device->CreateComputeShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, cs_.ReleaseAndGetAddressOf()))) {
        error_ = "no compute shader";
        return false;
    }
    struct Params {
        uint32_t bx, by, block, width, height, pad[3];
    } params{bx_, by_, block, width, height, {}};
    D3D11_BUFFER_DESC cb{};
    cb.ByteWidth = sizeof params;
    cb.Usage = D3D11_USAGE_IMMUTABLE;
    cb.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    D3D11_SUBRESOURCE_DATA init{&params, 0, 0};
    if (FAILED(device->CreateBuffer(&cb, &init, params_.ReleaseAndGetAddressOf()))) {
        error_ = "no constant buffer";
        return false;
    }
    D3D11_BUFFER_DESC bd{};
    bd.ByteWidth = uint32_t(blocks() * 4);
    bd.Usage = D3D11_USAGE_DEFAULT;
    bd.BindFlags = D3D11_BIND_UNORDERED_ACCESS;
    bd.MiscFlags = D3D11_RESOURCE_MISC_BUFFER_STRUCTURED;
    bd.StructureByteStride = 4;
    std::vector<uint32_t> zeros(blocks(), 0);
    D3D11_SUBRESOURCE_DATA z{zeros.data(), 0, 0};
    if (FAILED(device->CreateBuffer(&bd, &z, stats_.ReleaseAndGetAddressOf())) ||
        FAILED(device->CreateBuffer(&bd, &z, prev_.ReleaseAndGetAddressOf())) ||
        FAILED(device->CreateUnorderedAccessView(stats_.Get(), nullptr, stats_uav_.ReleaseAndGetAddressOf())) ||
        FAILED(device->CreateUnorderedAccessView(prev_.Get(), nullptr, prev_uav_.ReleaseAndGetAddressOf()))) {
        error_ = "no block buffers";
        return false;
    }
    D3D11_BUFFER_DESC st{};
    st.ByteWidth = uint32_t(blocks() * 4);
    st.Usage = D3D11_USAGE_STAGING;
    st.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    for (auto& s : staging_)
        if (FAILED(device->CreateBuffer(&st, nullptr, s.ReleaseAndGetAddressOf()))) {
            error_ = "no readback buffers";
            return false;
        }
    ok_ = true;
    return true;
}

const std::vector<int8_t>& Vision::analyse() {
    if (!ok_) return map_;
    // Measure this frame...
    ID3D11ShaderResourceView* srv = luma_.Get();
    ID3D11UnorderedAccessView* uavs[2] = {stats_uav_.Get(), prev_uav_.Get()};
    ID3D11Buffer* cb = params_.Get();
    context_->CSSetShader(cs_.Get(), nullptr, 0);
    context_->CSSetShaderResources(0, 1, &srv);
    context_->CSSetUnorderedAccessViews(0, 2, uavs, nullptr);
    context_->CSSetConstantBuffers(0, 1, &cb);
    context_->Dispatch(bx_, by_, 1);
    ID3D11ShaderResourceView* no_srv = nullptr;
    ID3D11UnorderedAccessView* no_uav[2] = {};
    context_->CSSetShaderResources(0, 1, &no_srv);
    context_->CSSetUnorderedAccessViews(0, 2, no_uav, nullptr);
    context_->CSSetShader(nullptr, nullptr, 0);
    context_->CopyResource(staging_[frame_ % kRing].Get(), stats_.Get());
    // ...and use the oldest measurement in the ring, which the GPU has finished by now (never
    // waits: if it isn't ready, the previous map stays).
    if (frame_ >= kRing - 1) {
        ID3D11Buffer* old = staging_[(frame_ + 1) % kRing].Get();
        D3D11_MAPPED_SUBRESOURCE m{};
        if (SUCCEEDED(context_->Map(old, 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &m))) {
            build_map(static_cast<const uint32_t*>(m.pData));
            context_->Unmap(old, 0);
        }
    }
    frame_++;
    return map_;
}

std::string Vision::debug(size_t block) const {
    if (block >= last_stats_.size()) return "-";
    const uint32_t s = last_stats_[block];
    return std::format("edge {} mean {} change {} stable {}", s & 0xFF, (s >> 8) & 0xFF, (s >> 16) & 0xFF, stable_[block]);
}

void Vision::build_map(const uint32_t* stats) {
    last_stats_.assign(stats, stats + blocks());
    map_.resize(blocks());
    const float cx = (bx_ - 1) / 2.0f, cy = (by_ - 1) / 2.0f;
    int sum = 0;
    sharper_ = coarser_ = 0;
    for (uint32_t y = 0; y < by_; ++y)
        for (uint32_t x = 0; x < bx_; ++x) {
            const size_t i = size_t(y) * bx_ + x;
            const uint32_t s = stats[i];
            const int edge = int(s & 0xFF), change = int((s >> 16) & 0xFF);
            // Frames the block has stayed still: HUDs, subtitles, menus, a crosshair.
            stable_[i] = change < 4 ? uint8_t(std::min(255, stable_[i] + 1)) : 0;
            // Distance from the centre (0) to the corners (about 2), on an ellipse.
            const float dx = cx > 0 ? (x - cx) / cx : 0, dy = cy > 0 ? (y - cy) / cy : 0;
            const float r2 = dx * dx + dy * dy;
            int d = r2 < 0.2f ? -2 : r2 < 0.6f ? -1 : r2 > 1.3f ? 1 : 0;
            // Still detail (text, HUD): kept sharp, it is read.
            if (edge >= 36 && stable_[i] >= 20) d -= 4;
            // Busy detail moving fast near the edges: motion hides it.
            else if (change > 48 && edge > 28 && r2 > 0.7f) d += 2;
            d = std::clamp(d, -6, 4);
            map_[i] = int8_t(d);
            sum += d;
        }
    // Centred on zero: the map moves bits around, it doesn't change how many there are.
    const int shift = int(std::lround(double(sum) / double(blocks())));
    for (auto& v : map_) {
        v = int8_t(std::clamp(int(v) - shift, -6, 4));
        if (v < 0) sharper_++;
        else if (v > 0) coarser_++;
    }
}

}  // namespace tcl
