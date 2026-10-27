// "Capturar tela" from the TV: saves the picture being streamed (the PC's screen, without the
// mouse cursor) as a JPEG in the user's Pictures\Jinky folder. The GPU copy happens on the
// calling thread (a couple of milliseconds); encoding and writing happen on a thread of their own,
// so the stream doesn't stutter.
#pragma once

#include <d3d11.h>

#include <cstdint>

namespace tcl {

// [app_id]: the Steam game running (0 = none), kept in the file name so Jinky can show which game
// a capture is from. Returns false if the picture couldn't be read (nothing is saved then).
bool save_screenshot(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* image, uint32_t app_id);

// A game paused from the TV: its last picture, %LOCALAPPDATA%\Jinky\paused\<pid>.jpg (replaced if
// there). The TV shows it on the paused game's card, like a console's Quick Resume.
bool save_pause_snapshot(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* image, uint32_t pid);

}  // namespace tcl
