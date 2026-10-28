// "Jinky Vision": the stream's bits go where the eye goes. Like the foveated streaming of Valve's
// Steam Frame, but without eye tracking: a compute shader looks at every block of the picture the
// encoder gets (detail and how much it changed since the last frame) and the encoder receives a
// QP delta map: sharper where it matters (text, HUD, crosshair, the centre where the action is),
// a little coarser where nobody looks closely (fast-moving detail near the edges, where motion
// already blurs it). The total stays the same: the rate control spends the same bits, better.
#pragma once

#include <d3d11.h>
#include <wrl/client.h>

#include <cstdint>
#include <string>
#include <vector>

namespace tcl {

using Microsoft::WRL::ComPtr;

class Vision {
public:
    // `picture` is the encoder's input (NV12 or P010, with shader-resource binding); `block` the
    // encoder's block size (16 for H.264 macroblocks, 32 for HEVC CTBs).
    bool init(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* picture, uint32_t width,
              uint32_t height, uint32_t block);
    bool ready_for(ID3D11Texture2D* picture, uint32_t block) const { return picture_ == picture && block_ == block && ok_; }

    // Measures the current picture on the GPU (no waiting) and returns the delta map to encode it
    // with, built from the newest measurement already back on the CPU (a frame or two old: the
    // picture barely changes in 30 ms). Empty until the first measurement arrives.
    const std::vector<int8_t>& analyse();

    size_t blocks() const { return size_t(bx_) * by_; }
    const std::string& error() const { return error_; }

    // How the last map was spread (for the log): blocks made sharper / coarser.
    uint32_t sharper() const { return sharper_; }
    uint32_t coarser() const { return coarser_; }
    const std::vector<int8_t>& last_map() const { return map_; }
    std::string debug(size_t block) const;

private:
    void build_map(const uint32_t* stats);

    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ID3D11Texture2D* picture_ = nullptr;
    ComPtr<ID3D11ShaderResourceView> luma_;
    ComPtr<ID3D11ComputeShader> cs_;
    ComPtr<ID3D11Buffer> params_, stats_, prev_;
    ComPtr<ID3D11UnorderedAccessView> stats_uav_, prev_uav_;
    static constexpr int kRing = 3;
    ComPtr<ID3D11Buffer> staging_[kRing];
    int frame_ = 0;
    uint32_t block_ = 0, bx_ = 0, by_ = 0;
    std::vector<uint8_t> stable_;  // frames each block has stayed still (HUD/text detection)
    std::vector<int8_t> map_;
    std::vector<uint32_t> last_stats_;
    uint32_t sharper_ = 0, coarser_ = 0;
    bool ok_ = false;
    std::string error_;
};

}  // namespace tcl
