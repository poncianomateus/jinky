# Prova de vida — PC → TV

Objetivo: provar que a parte difícil funciona (captura → NVENC → rede → decodificador da TV → imagem na tela) antes de qualquer interface. Ver ARCHITECTURE.md §9.

## O que existe

| Parte | Onde | O que faz |
|---|---|---|
| `libtclproto` | `proto/` | Formato dos pacotes, fragmentação/remontagem, UDP. Mesmo código no PC e na TV. |
| `tclstream-host.exe` | `host/engine/` | Captura a tela (DXGI), converte para NV12 1080p na GPU, codifica com NVENC H.264 e envia. |
| APK de teste | `tv/` | Acha o PC sozinho (broadcast), recebe, decodifica com MediaCodec por hardware e mostra os marcos na tela. |

Ainda **não** tem: áudio, controle, pareamento/criptografia, interface final. Use só na rede de casa.

## Compilar

PC (no "Developer PowerShell for VS 2022"):

```powershell
cmake -S host/engine -B build/host -G "Visual Studio 17 2022" -A x64
cmake --build build/host --config Release
# testes do protocolo
cmake -S proto -B build/proto -G "Visual Studio 17 2022" -A x64
cmake --build build/proto --config Release
ctest --test-dir build/proto -C Release --output-on-failure
```

TV:

```powershell
cd tv
.\gradlew.bat assembleRelease
# APK: tv/app/build/outputs/apk/release/app-release.apk
```

## Instalar na TV / Fire Stick

1. Ativar **Opções do desenvolvedor** e **Depuração USB/ADB** (e "Apps de fontes desconhecidas" no Fire TV).
2. `adb connect <ip-da-tv>:5555` (aceitar o aviso na TV)
3. `adb install -r app-release.apk`

## Rodar

1. No PC: `build\host\Release\tclstream-host.exe`
   - Na primeira vez o Windows pergunta sobre o firewall: permitir em **rede privada**.
2. Na TV: abrir **TclStream**.
3. A TV deve achar o PC em ~1 s e mostrar a área de trabalho.

Opções do host: `--bitrate 20000` (kbit/s) · `--fps 60` · `--size 1920x1080` · `--output 0` (monitor) · `--port 47100`.

## Lendo o diagnóstico

O painel da TV (botão OK mostra/oculta) mostra a etapa atual:

| Etapa na TV | Significa | Onde olhar |
|---|---|---|
| PC: procurando na rede... | Nenhum pacote do PC chegou | Host rodando? Firewall? TV e PC na mesma rede? |
| aguardando vídeo do PC | PC encontrado, nenhum quadro completo ainda | Log do host: "first frame sent"? |
| decodificando (exibição ainda não confirmada) | Decodificador produz quadros, mas a TV não confirmou que exibiu | Possível problema de superfície/decodificador — anotar modelo |
| ✔ imagem sendo exibida | Funcionando, com confirmação de exibição | — |
| ⚠ travado — recuperando | Vídeo chegando, decodificador parado; recriando | Contar quantas vezes acontece |

O host imprime cada marco (`capture`, `encoder`, `session`) e estatísticas a cada 2 s.

Logs da TV: `adb logcat -s TclStream`

## Resultados

Preencher após cada teste em hardware real.

| Data | Aparelho | Resultado | Observações |
|---|---|---|---|
| 2026-10-18 | TCL 55P7K (Amlogic T963D4, Android 12/API 31) | ✔ imagem exibida, confirmada por OnFrameRenderedListener | Decodificador `OMX.amlogic.avc.decoder.awesome2` com KEY_LOW_LATENCY. ~1 s até o 1º quadro (3 pedidos de keyframe na partida). Sem perdas em ~20 s de área de trabalho. Encode às vezes sobe para 5–12 ms com a tela parada (a investigar: GPU em economia de energia?). Latência ainda não medida. |
| 2026-10-18 | TCL 55P7K — latência (padrão de teste 60 fps, Wi-Fi) | ✔ 60 fps estáveis | Total PC→TV (composição) **~61 ms**: PC+rede ~9 ms (NVENC ~2,7 ms), TV decodificação+exibição ~52 ms. O decodificador entrega na hora (queued = decoded); o atraso está no caminho de exibição da Amlogic. Testado: `vdec-lowlatency` + `releaseOutputBuffer(idx, now)` = melhor (−3 ms); 120 Hz e `setFrameRate` pioraram. Não inclui o atraso do painel (por isso pedimos Modo Jogo/ALLM). |
| 2026-10-18 | TCL 55P7K — fluidez | ✔ **~59 fps na tela (97–98%)**, latência **~48 ms** | Medido no SurfaceFlinger e nos dois lados. Causas encontradas e corrigidas: (1) driver segurava a conversão D3D11 → picos de 42 ms no NVENC → `Flush()` após a conversão; (2) laço único na TV só liberava o quadro N quando chegava o N+1 → threads separadas de entrada/saída; (3) dois quadros no mesmo vsync → ritmo alinhado ao vsync (Choreographer); (4) modo de teste desenhava quadros extras → timer de alta precisão. Restante: atraso ocasional do Wi-Fi (~1 quadro a cada poucos segundos). |
| 2026-10-19 | Fire TV Stick 3ª ger. (AFTSS "sheldon", MediaTek MT8695, Fire OS 7.7 / Android 9, API 28, 32 bits, 1 GB) | ✔ imagem, áudio e menu | Só H.264 por hardware (`OMX.MTK.VIDEO.DECODER.AVC`; sem HEVC, sem KEY_LOW_LATENCY). Latência inicial **126 ms**: o decodificador MediaTek segurava 3 quadros. NVENC com 1 quadro de referência (`maxNumRefFrames=1`) → **89 ms** (segura 1); chave `vdec-lowlatency` (não documentada, também aceita pela MTK) → **~73 ms**, enviados = decodificados. Restante ~63 ms é o caminho de exibição do Fire (pacing/vsync não mudam). Áudio sem perdas, saída ~80 ms. Wi-Fi: 0 quadros atrasados. |
