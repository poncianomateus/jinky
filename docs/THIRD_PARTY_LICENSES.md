# Componentes de terceiros

| Componente | Onde | Origem | Licença |
|---|---|---|---|
| ViGEmClient (fonte) | `third_party/vigemclient/` | [nefarius/ViGEmClient](https://github.com/nefarius/ViGEmClient) commit `b66d02d` (2023-09-08); removidos só arquivos de projeto VS (.vcxproj/.rc) | MIT (`third_party/vigemclient/LICENSE`) |
| `nvEncodeAPI.h` (NVENC API 13.0) | `third_party/nvenc/` | NVIDIA, via [FFmpeg/nv-codec-headers](https://github.com/FFmpeg/nv-codec-headers) tag `n13.0.19.1`, sem modificações | MIT (aviso no próprio arquivo) |

Bibliotecas do sistema usadas em tempo de execução (não redistribuídas): driver NVIDIA (`nvEncodeAPI64.dll`), Direct3D 11/DXGI, WASAPI, Winsock, Android MediaCodec/AudioTrack.

Driver instalado separadamente no PC (não redistribuído pelo projeto): **ViGEmBus 1.22.0** (nefarius, BSD-3-Clause), controle virtual Xbox 360. Projeto arquivado pelo autor em 2023; funciona no Windows 11. Isolado atrás de `host/engine/src/gamepad.*` para poder ser trocado.
