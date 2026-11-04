# Jinky — Arquitetura

> O projeto nasceu com o nome provisório "TclStream" (ainda presente em alguns nomes internos, como o pacote `com.tclstream.tv`). As seções abaixo registram as decisões desde o início; as mais recentes estão no fim de cada uma.

## 1. Objetivo

Jogar jogos do PC Windows na TV, **somente na rede local de casa**, com:

1. Imagem que **sempre aparece** — e, quando não aparece, o sistema diz exatamente onde falhou e tenta se recuperar.
2. Latência baixa.
3. Interface própria, bonita e navegável só pelo controle.

Aparelhos-alvo:

| Papel | Aparelho | Sistema |
|---|---|---|
| Transmissor (host) | PC Windows 11, NVIDIA GeForce RTX 5060 | Windows 10/11 x64 |
| Receptor | TV TCL com Google TV | Android TV (versão a confirmar) |
| Receptor | Amazon Fire TV Stick (modelo a confirmar) | Fire OS (baseado em Android) |

Um **único APK** atende Google TV e Fire TV. Diferenças de hardware são resolvidas em tempo de execução pelo diagnóstico de decodificador (seção 6), não por builds separados.

> ⚠️ Fire TV Sticks com **Vega OS** (ex.: Fire TV Stick 4K Select) não rodam APK Android. Confirmar o modelo.

## 2. Decisão principal

**Pilha própria, sem Sunshine/Moonlight como base.** Motivo: controle total de latência, funcionalidades, diagnóstico e licença.

"Própria" não significa reinventar codecs. Usamos componentes de hardware/SO comprovados e escrevemos a integração e o protocolo:

| Função | Componente usado | O que escrevemos |
|---|---|---|
| Captura de tela | DXGI Desktop Duplication (D3D11) | Gerência da captura, recuperação de perda de acesso |
| Codificação | NVIDIA Video Codec SDK (NVENC) | Configuração de baixa latência, pedido de quadro-chave |
| Transporte | UDP + sockets do SO | **Protocolo TclStream** (seção 4) |
| Decodificação | Android MediaCodec (hardware) | Pipeline de baixa latência, detecção de travamento |
| Áudio | WASAPI loopback + Opus (libopus) | Sincronia e buffer mínimo |
| Controle | ViGEmBus (controle virtual Xbox 360) | Mapeamento e canal de entrada |
| Descoberta | mDNS / DNS-SD | Anúncio e busca do host |

Moonlight/Sunshine podem ser **lidos como referência de ideias**; nenhum código deles é copiado.

## 3. Linguagens — "o melhor dos dois mundos"

A peça central é uma **biblioteca C++ compartilhada** com o protocolo, compilada para Windows e para Android (via NDK). Os dois lados falam exatamente o mesmo protocolo porque é o mesmo código.

```
                  ┌────────────────────────────┐
                  │  libtclproto (C++20)       │
                  │  pacotes, FEC, sessão,     │
                  │  estatísticas, log         │
                  └──────┬──────────────┬──────┘
                         │              │
       ┌─────────────────┴───┐     ┌────┴────────────────────────┐
       │ PC — TclStream Host │     │ TV — TclStream (APK)        │
       │                     │     │                             │
       │ Motor (C++20)       │     │ Núcleo nativo (C++/NDK)     │
       │  captura, NVENC,    │     │  recepção UDP, remontagem,  │
       │  áudio, entrada     │     │  AMediaCodec, áudio         │
       │        ▲ IPC local  │     │        ▲ JNI                │
       │ Interface (C# .NET  │     │ Interface (Kotlin +         │
       │  8, WPF)            │     │  Jetpack Compose for TV)    │
       └─────────────────────┘     └─────────────────────────────┘
```

- **C++ nos caminhos de vídeo/áudio/rede**: sem coletor de lixo, acesso direto a NVENC, DXGI, AMediaCodec. É onde a latência se decide.
- **C# WPF na interface do PC**: produtiva, madura, permite visual premium. Conversa com o motor por *named pipe* local. Se a interface travar, o streaming não para.
- **Kotlin + Compose for TV na interface da TV**: padrão atual do Android TV, ótimo suporte a foco/D-pad, funciona no Fire OS.

## 4. Protocolo TclStream (rede local)

Três canais separados:

| Canal | Transporte | Uso |
|---|---|---|
| Controle | TCP + TLS 1.3 (certificados gerados no pareamento) | Pareamento, lista de jogos, iniciar/parar sessão, pedir quadro-chave, estatísticas, diagnóstico |
| Vídeo/Áudio | UDP | Quadros H.264/HEVC fragmentados, com reenvio seletivo (NACK); áudio PCM |
| Entrada | UDP (com reenvio do estado completo) | Botões, analógicos, gatilhos |

Pontos-chave do canal de vídeo:

- Cada quadro tem `frame_id`, carimbo de tempo de captura e é fragmentado em pacotes de tamanho ≤ MTU (~1400 B).
- **Reenvio seletivo (NACK)**: a TV entrega os quadros sempre em ordem; um quadro com buracos segura os seguintes enquanto ela pede só os fragmentos que faltam (`Nack`, após ~3 ms de tolerância a pacotes fora de ordem, até 3 vezes, no ritmo do RTT medido). O PC guarda ~1 s de quadros enviados e responde na thread de recepção. Em LAN isso custa um RTT (poucos ms), bem menos que um quadro-chave.
- **Proteção contra perda (FEC, 0.8.6)**: com TVs que anunciam `kHelloFec`, cada quadro leva também pacotes de paridade Reed–Solomon (matriz de Cauchy em GF(2^8), blocos de até 128 fragmentos intercalados, `fec.h`), enviados logo após os dados. A TV reconstrói na hora até tantos fragmentos perdidos por bloco quantos pacotes de paridade chegaram, sem ida e volta; só pede reenvio (NACK) do que a paridade não cobre. A quantidade (5–50%, começa em 10%) segue a perda bruta relatada no `NetReport` (que conta também o que foi reconstruído): sobe na hora, desce devagar. TVs antigas descartam os pacotes de paridade (índice ≥ `frag_count`). Custo no PC: < 0,1 ms por quadro comum (SSSE3). **Áudio (0.8.9)**: com `kHelloAudioFec`, a cada grupo de 4 pedaços de som (20 ms) vão 1 ou 2 pacotes de paridade (2 quando a proteção do vídeo passa de 20%). A TV guarda os últimos grupos; um pedaço perdido entra reconstruído no lugar, ou, se a paridade chega depois, substitui o silêncio que ocupou o lugar dele na parte que ainda não tocou (um pedaço atrasado também).
- Quadro irrecuperável (tempo esgotado, ~60–150 ms) → receptor descarta até o próximo quadro de referência e pede **quadro-chave**. Um quadro-chave completo dispensa a espera pelos anteriores.
- **Qualidade automática**: a TV envia `NetReport` a cada 1 s (fração de pacotes pedidos de novo, quadros perdidos, pacotes recebidos). O PC baixa o bitrate via `nvEncReconfigureEncoder` (sem quadro-chave) — até 60% do escolhido com perda moderada, até 8 Mbit/s com quadros perdidos — e sobe 15% a cada 5 s após 10 s de rede calma, nunca acima do escolhido.
- **Prioridade na rede e na GPU** (0.5.2): vídeo/áudio do PC marcados como tráfego de vídeo (qWAVE `QOSTrafficTypeAudioVideo` → fila WMM de vídeo / DSCP CS5); pacotes da TV (controle, NACK) com DSCP EF (fila de voz); enquanto há TV conectada, o Wi-Fi do PC desliga as buscas de rede em segundo plano e entra em *media streaming mode* (`WlanSetInterface`); processo do motor com prioridade de agendamento de GPU "alta" (`D3DKMTSetProcessSchedulingPriorityClass`) para captura/encode não esperarem atrás do jogo.
- **Limite de fps dos jogos** (opção "Limitar jogos a 60 fps", ligada por padrão): em sessão de jogo o motor põe o "Max Frame Rate" global do driver da NVIDIA (NVAPI DRS, `FRL_FPS`) na taxa do stream e devolve o valor do usuário ao sair (marcador `frame-cap.flag` restaura se o motor morrer). Motivo medido no Fire (out/2026): com o jogo acima de 60 fps a GPU fica a 100%, o encode vai de ~3 ms para 11–20 ms e 60% dos intervalos têm quadros atrasados, que chegam juntos na TV e um é descartado (~55 fps na tela). Vale para jogos abertos depois que a sessão começa.
- **Fire TV Stick (medido)**: decodificar + exibir custa ~65 ms no MT8695 em qualquer ajuste; 59,94 × 60 Hz, 20 × 40 Mbit/s e marcador AUD não mudam o resultado; o ritmo por vsync (padrão) é o que mais mostra quadros.
- **Monitor virtual (0.8.6, opcional)**: com `--virtual-display`, sessões de console usam uma tela virtual (Virtual Display Driver, MIT, assinado pela SignPath Foundation; monitor "MTT1337") no tamanho e taxa da TV; os monitores do PC ficam apagados e voltam exatamente como estavam (`SetDisplayConfig` com a configuração salva antes; o Windows guarda essa como a configuração dele, e um marcador `virtual-display.flag` faz o motor ou o app do PC devolver tudo se o motor cair). Fora das sessões a tela virtual nunca faz parte da área de trabalho. Não usado com HDR nem em "Área de trabalho", nem em PCs com ASTER (multiusuário): o driver dele (`mutenx.sys`) bloqueia drivers de vídeo por política, esconde adaptadores novos, e trocar de monitor apagaria os outros lugares. Instalação pelo próprio motor (`--install-virtual-display`, como administrador): cria o dispositivo `Root\MttVDD` e instala o INF baixado (SHA-256 fixo); resoluções em `%ProgramData%\Jinky\VirtualDisplay\vdd_settings.xml` (via `VDDPATH`).
- **Queda de Wi-Fi**: a TV não esquece o PC; continua chamando-o (direto + broadcast) e mostra "Reconectando". O PC mantém o modo console (Big Picture, 60 Hz, som) por 90 s esperando a volta.
- Autenticação: só dispositivos pareados (PIN mostrado na TV, digitado/confirmado no PC) abrem sessão. Pacotes UDP carregam um identificador de sessão negociado no canal TLS.

## 5. Máquina de estados da sessão (receptor)

```
IDLE → DISCOVERING_HOST → CONNECTING → AUTHENTICATING → STARTING_STREAM
     → WAITING_FOR_VIDEO → STREAMING ⇄ RECOVERING → STOPPED | ERROR
```

Cada transição é disparada por um **evento real** (ex.: "primeiro quadro apresentado"), nunca por temporizador sozinho. Temporizadores só disparam falhas.

## 6. Anti-tela-preta: marcos verificáveis

Cada quadro passa por marcos medidos dos dois lados:

| # | Marco | Lado | Como se prova |
|---|---|---|---|
| 1 | Captura funcionando | PC | DXGI entregou quadro novo |
| 2 | Codificador ativo | PC | NVENC devolveu bitstream; codec/perfil conhecidos |
| 3 | Enviado | PC | Contador de pacotes |
| 4 | Recebido e remontado | TV | Quadro completo (ou recuperado via reenvio) |
| 5 | Decodificador criado | TV | `MediaCodec.configure/start` sem erro, codec de hardware confirmado |
| 6 | Quadro decodificado | TV | Buffer de saída liberado |
| 7 | **Quadro apresentado** | TV | `OnFrameRenderedListener` / *frame callback* da Surface |

A tela de diagnóstico mostra o último marco atingido. Exemplo: "Recebendo vídeo, mas o decodificador da TV não produz imagem" → recria só o decodificador e pede quadro-chave.

Diagnóstico de hardware na primeira execução: modelo, versão do Android/Fire OS, decodificadores de hardware, perfis/níveis H.264 e HEVC, resolução e FPS máximos anunciados, suporte a `KEY_LOW_LATENCY`. O perfil inicial é escolhido a partir disso.

## 7. Perfil inicial

1080p · 60 FPS · H.264 High · sem HDR · NVENC P1/ultra-low-latency · bitrate inicial conservador (~20 Mbit/s, a validar) · sem B-frames · GOP infinito com quadro-chave sob demanda.

HEVC, 4K, 120 FPS e HDR só depois de 1080p60 estável na TCL **e** no Fire Stick.

## 8. Estrutura do repositório

```
TclStream/
  proto/            libtclproto (C++20, CMake) — compartilhada
  host/
    engine/         motor C++ (captura, NVENC, áudio, entrada)
    app/            interface C# WPF
  tv/               projeto Android (Kotlin + NDK, Gradle)
  tools/            ferramentas de teste (ex.: simulador de perda)
  docs/
```

## 9. Primeiro marco técnico (prova de vida)

Antes de qualquer interface:

> **O PC captura a tela, codifica em H.264 com NVENC e envia por UDP; um APK mínimo na TV recebe, decodifica e mostra a imagem — medindo cada marco da seção 6.**

Só depois disso começam interface, pareamento, áudio e controle.

## 10. Riscos conhecidos

| Risco | Mitigação |
|---|---|
| Transporte de baixa latência é a parte mais difícil | Começar simples (LAN, FEC fixo), medir, só então refinar |
| Decodificadores de TV/Fire Stick com comportamentos próprios | Diagnóstico de hardware + tabela de ajustes por aparelho |
| ViGEmBus sem manutenção ativa | Funciona hoje; isolar atrás de interface para trocar depois |
| Jogos em tela cheia exclusiva / telas protegidas (UAC) | Tratar perda de acesso da Desktop Duplication e recriar |
| Wi-Fi da TV instável | Recomendar cabo/5 GHz; reenvio seletivo e bitrate adaptativo (feitos na 0.5.0) |

## 11. Ferramentas de desenvolvimento necessárias

- Visual Studio 2022 (C++ desktop + .NET desktop) e CMake
- NVIDIA Video Codec SDK (headers)
- Android Studio (JDK, SDK, NDK, adb)
- Depuração ADB habilitada na TCL e no Fire Stick

## 12. Pareamento e segurança (implementado)

- **Pareamento** (uma vez por TV): a TV e o PC trocam chaves públicas P-256 (ECDH; Android `KeyAgreement`, Windows CNG). Os dois derivam a mesma chave de par (`SHA-256("jinky-pair-v1" || segredo || pub_tv || pub_pc)`) e mostram um código de 6 dígitos tirado dela. O usuário confere e clica em **Permitir** no app do PC. Um intermediário acabaria com chaves e códigos diferentes (mesmo princípio da "comparação numérica" do Bluetooth).
- **Conexão:** o PC responde a qualquer Hello com `AuthRequired` (id do PC + nonce aleatório ligado ao endereço da TV). A TV pareada responde com `AuthHello` assinado (HMAC-SHA256, 128 bits) incluindo o nonce: uma mensagem capturada não serve de outro endereço e não depende de relógio.
- **Controle:** cada estado do controle vai assinado; o PC descarta estados sem assinatura válida.
- **O PC só transmite a tela para uma TV autenticada.** Aparelho não pareado não recebe nenhum pacote de mídia.
- Chaves: no PC em `%LOCALAPPDATA%\Jinky\paired.txt` (perfil do usuário); na TV em `SharedPreferences` privadas do app.
- "Remover" uma TV no app do PC encerra a sessão dela e a faz voltar à tela de pareamento.
- `--insecure` desliga tudo isso (apenas para as ferramentas de teste).
- Limitação conhecida: o vídeo/áudio não é cifrado (só vai para a TV autenticada, na rede local). Cifrar o fluxo é um passo futuro.
