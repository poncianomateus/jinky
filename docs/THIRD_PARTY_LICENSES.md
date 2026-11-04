# Componentes de terceiros

| Componente | Onde | Origem | Licença |
|---|---|---|---|
| ViGEmClient (fonte) | `third_party/vigemclient/` | [nefarius/ViGEmClient](https://github.com/nefarius/ViGEmClient) commit `b66d02d` (2023-09-08); removidos só arquivos de projeto VS (.vcxproj/.rc) | MIT (`third_party/vigemclient/LICENSE`) |
| `nvEncodeAPI.h` (NVENC API 13.0) | `third_party/nvenc/` | NVIDIA, via [FFmpeg/nv-codec-headers](https://github.com/FFmpeg/nv-codec-headers) tag `n13.0.19.1`, sem modificações | MIT (aviso no próprio arquivo) |
| NVAPI (cabeçalhos e `nvapi64.lib`) | `third_party/nvapi/` | [NVIDIA/nvapi](https://github.com/NVIDIA/nvapi) | MIT (`third_party/nvapi/License.txt`) |
| Fontes Montserrat e JetBrains Mono | `host/app/Assets/Fonts/`, `tv/app/src/main/assets/fonts/` | Google Fonts / JetBrains | SIL Open Font License 1.1 (`host/app/Assets/Fonts/OFL-*.txt`) |

Bibliotecas do sistema usadas em tempo de execução (não redistribuídas): driver NVIDIA (`nvEncodeAPI64.dll`), Direct3D 11/DXGI, WASAPI, Winsock, Android MediaCodec/AudioTrack.

Driver do controle virtual: **ViGEmBus 1.22.0** (nefarius, BSD-3-Clause), controle virtual Xbox 360 e DualShock 4. O instalador do Jinky leva o instalador oficial dele e só o instala quando falta; o arquivo não fica no repositório (baixe em [nefarius/ViGEmBus](https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0) para `third_party/redist/` antes de gerar o instalador). Projeto arquivado pelo autor em 2023; funciona no Windows 11. Isolado atrás de `host/engine/src/gamepad.*` para poder ser trocado.
