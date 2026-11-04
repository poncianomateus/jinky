<div align="center">

<img src="branding/source/logo_lockup_dark.png" alt="Jinky" width="420">

### O seu PC vira um console na TV.

Jogue os jogos do seu PC com Windows na TV da sala, com o controle na mão e sem encostar no computador.<br>
Feito para **Google TV** e **Fire TV**, numa interface própria pensada para o controle.

[![Versão](https://img.shields.io/github/v/release/poncianomateus/jinky-releases?label=vers%C3%A3o&color=4F8BFF)](https://github.com/poncianomateus/jinky-releases/releases/latest)
[![Licença](https://img.shields.io/badge/licen%C3%A7a-Apache%202.0-4F8BFF)](LICENSE)
![Windows](https://img.shields.io/badge/PC-Windows%2010%2F11%20%2B%20NVIDIA-0b0e15)
![TV](https://img.shields.io/badge/TV-Google%20TV%20%7C%20Fire%20TV-0b0e15)
[![Apoie](https://img.shields.io/badge/%E2%98%95%20apoie%20o%20criador-Ko--fi-FFC84A)](https://ko-fi.com/poncianomateus)

<img src="docs/screenshots/tv-inicio.jpg" alt="Tela inicial do Jinky na TV" width="900">

</div>

---

## A ideia

Jogar o PC na TV costuma ser assim: você abre um app de streaming, aparece a área de trabalho do Windows, a Steam pede alguma coisa, você levanta para clicar no mouse… e a sensação de console vai embora.

O Jinky foi feito para que isso **nunca aconteça**. Na TV você vê uma tela de console de verdade, com as capas dos seus jogos, o que você jogou na semana e o que está baixando. Escolhe o jogo com o controle, e o PC faz o resto sozinho: abre o jogo, responde às perguntas da Steam pela TV e só mostra a imagem quando o jogo está na tela.

Tudo roda **na sua casa**: o PC transmite direto para a TV pela rede local, sem nuvem, sem conta e sem assinatura.

## Como funciona

```mermaid
flowchart LR
    subgraph PC["PC com Windows"]
        direction TB
        G["Jogo<br/>(Steam, PS2, Epic, GOG, Xbox)"]
        E["Motor do Jinky (C++)<br/>captura a tela • comprime na placa NVIDIA (NVENC)<br/>captura o som • controle virtual"]
        A["App do Jinky (C#)<br/>biblioteca • Steam • atualizações"]
        G --> E
        A <--> E
    end
    subgraph TV["TV (Google TV / Fire TV)"]
        direction TB
        R["App do Jinky (Kotlin + C++)<br/>interface de console<br/>decodifica o vídeo no hardware"]
    end
    E -- "vídeo + som (UDP, protocolo próprio,<br/>com proteção contra perdas)" --> R
    R -- "controle (botões, analógicos,<br/>touchpad e movimento)" --> E
    A <-- "biblioteca, capas, downloads,<br/>perguntas da Steam (HTTP local)" --> R
```

1. **O PC captura a tela** direto da placa de vídeo (DXGI) e a **comprime em H.264 ou HEVC na própria GPU NVIDIA** (NVENC), em poucos milissegundos.
2. **O vídeo e o som vão pela rede de casa** num protocolo próprio sobre UDP, com **correção de erros (Reed-Solomon)**: quando o Wi-Fi perde um pedaço, a TV reconstrói sem pedir de novo.
3. **A TV decodifica no hardware dela** (MediaCodec) e mostra a imagem com a menor espera possível.
4. **O controle conectado na TV vira um controle no PC**: Xbox 360, ou DualShock 4 para controles PlayStation (com touchpad e movimento), pelo driver ViGEmBus.
5. **O app do PC conversa com a Steam** para listar a biblioteca, mostrar o que está baixando, abrir jogos e responder às perguntas dela pela TV.

Nada de Sunshine nem Moonlight: o protocolo, o motor e as duas interfaces são do próprio Jinky.

## O que ele faz

### 🎮 Na TV, uma experiência de console

- **Tela inicial viva**: jogo pausado para continuar, fixados, recentes, o tempo jogado na semana e o que está baixando no PC.
- **Biblioteca única**: jogos da **Steam**, **PlayStation 2** (PCSX2), **Epic Games**, **GOG** e **Xbox / PC Game Pass**, todos juntos, com capa, fundo e logo.
- **Página de cada jogo** com descrição, conquistas, tempo de jogo e imagens.
- **Amigos da Steam**: quem está online, o que está jogando e "Jogar também".
- **Downloads em tempo real**, com velocidade, pico, disco e tempo restante.
- Abertura animada, sons de interface, temas e navegação só pelo controle.
- **Tour na primeira abertura** e **Ajuda** com perguntas e respostas, na TV e no PC.

### ⚡ Abrir um jogo sem levantar do sofá

- O jogo **abre direto**, sem passar pelo Big Picture, e a TV mostra uma **tela de carregamento** com o que a Steam está fazendo (atualizando, sincronizando saves, processando shaders), até o jogo aparecer.
- **As perguntas da Steam aparecem na TV** (saves diferentes na nuvem, jeito de abrir o jogo, jogo aberto em outro computador), com os botões do Jinky.
- Os avisos de controle da Steam são resolvidos sozinhos.
- **Quick Resume**: sair de um jogo congela ele no PC, e "Continuar" volta exatamente de onde parou. Nos jogos de PS2, o Jinky salva o estado do emulador, e dá para continuar até depois de reiniciar o PC.

### 📡 Imagem e rede

- **60 fps em 1080p** (ou 720p para TVs mais simples), em H.264 ou HEVC.
- **Proteção contra perda de pacotes** no vídeo e no som, ajustada sozinha conforme a rede.
- **Jinky Vision (beta)**: mais nitidez no centro da tela, onde está a ação, com a mesma banda.
- **Teste de rede** na TV e no PC, com conselhos do que melhorar.
- **Modo console**: durante a sessão, o PC muda o monitor para 60 Hz, limita os jogos a 60 fps e manda o som só para a TV. Tudo volta ao normal no fim.

### 🖥️ No PC, um app discreto

- Fica perto do relógio do Windows; o pareamento de uma TV nova chega como notificação.
- **Sessão ao vivo** com gráfico de latência e vídeo, e o histórico das últimas sessões.
- **Biblioteca no PC**: esconder jogos da TV, marcar favoritos (fixados na TV) e trocar capas.
- **Atualizações automáticas**: o PC se atualiza pelo GitHub, e as TVs se atualizam pelo PC.

## Galeria

### Na TV

| | |
|:---:|:---:|
| <img src="docs/screenshots/tv-inicio-jogo.jpg" alt="Tela inicial com um jogo em destaque"> | <img src="docs/screenshots/tv-biblioteca.jpg" alt="Biblioteca"> |
| **Tela inicial** | **Biblioteca** |
| <img src="docs/screenshots/tv-jogo.jpg" alt="Página do jogo"> | <img src="docs/screenshots/tv-carregando.jpg" alt="Carregando o jogo"> |
| **Página do jogo**, com conquistas e imagens | **Carregando**: a TV mostra o que a Steam está fazendo |
| <img src="docs/screenshots/tv-pergunta-steam.jpg" alt="Pergunta da Steam na TV"> | <img src="docs/screenshots/tv-menu.jpg" alt="Menu durante o jogo"> |
| **Perguntas da Steam** respondidas pela TV | **Menu durante o jogo** |
| <img src="docs/screenshots/tv-downloads.jpg" alt="Downloads"> | <img src="docs/screenshots/tv-amigos.jpg" alt="Amigos"> |
| **Downloads** em tempo real | **Amigos** da Steam |
| <img src="docs/screenshots/tv-configuracoes.jpg" alt="Configurações"> | <img src="docs/screenshots/tv-abertura.jpg" alt="Abertura"> |
| **Configurações** | **Abertura** |
| <img src="docs/screenshots/tv-tour.jpg" alt="Tour de primeira abertura"> | <img src="docs/screenshots/tv-ajuda.jpg" alt="Ajuda na TV"> |
| **Tour** na primeira abertura | **Ajuda**: perguntas e respostas |

### No PC

| | |
|:---:|:---:|
| <img src="docs/screenshots/pc-inicio.png" alt="Início do app do PC"> | <img src="docs/screenshots/pc-biblioteca.png" alt="Biblioteca no PC"> |
| **Início**: sessão ao vivo e histórico | **Biblioteca**: esconder, favoritar, trocar capa |
| <img src="docs/screenshots/pc-jogar.png" alt="Opções de jogo"> | <img src="docs/screenshots/pc-tvs.png" alt="TVs e rede"> |
| **Jogar**: modo console e Steam | **TVs**: pareamento e teste de conexão |
| <img src="docs/screenshots/pc-tour.png" alt="Tour do app do PC"> | <img src="docs/screenshots/pc-ajuda.png" alt="Ajuda no PC"> |
| **Tour**: um cartão apresenta cada página | **Ajuda**: perguntas e respostas |

> As imagens usam dados de exemplo. As capas e os fundos dos jogos pertencem aos seus donos.

## Requisitos

| | |
|---|---|
| **PC** | Windows 10 ou 11 (64 bits) com placa de vídeo **NVIDIA** (GeForce GTX 10 ou mais nova). O instalador traz o driver de controle virtual ViGEmBus. |
| **TV** | Google TV / Android TV 7.1 ou mais novo (TCL, Chromecast com Google TV etc.) ou **Fire TV** com Fire OS 6 ou mais novo. Não funciona em aparelhos com Vega OS. |
| **Rede** | PC e TV na mesma rede. **Cabo no PC** e **Wi-Fi de 5 GHz na TV** dão o melhor resultado. |
| **Controle** | Qualquer controle conectado na TV: Xbox, DualSense, DualShock 4 e outros. |

## Instalação

1. **No PC**: baixe o `Jinky-Setup-<versão>.exe` mais recente em **[Releases](https://github.com/poncianomateus/jinky-releases/releases/latest)** e instale. O Jinky abre um guia rápido na primeira vez.
2. **Na TV**: instale o app **Downloader** pela loja da TV, permita que ele instale apps ("Fontes desconhecidas") e digite o endereço que o Jinky do PC mostra em **TVs › Instalar numa TV nova** (algo como `http://192.168.0.10:47101/tv/jinky.apk`). Também dá para baixar o `Jinky-TV.apk` direto das [Releases](https://github.com/poncianomateus/jinky-releases/releases/latest).
3. **Abra o Jinky na TV**: ela encontra o PC sozinha e mostra um código. Confira o mesmo código no PC e clique em **Permitir**. Pronto.

Depois disso, tudo se atualiza sozinho: o PC pelo GitHub, e as TVs pelo PC.

## Compilar a partir do código

### Ferramentas

- **Visual Studio 2022** com "Desenvolvimento para desktop com C++" (já traz o CMake).
- **.NET 10 SDK**.
- **Android Studio** (para o JDK e o SDK do Android).
- **Inno Setup 6**, só para gerar o instalador. Ele também precisa do instalador oficial do [ViGEmBus 1.22.0](https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0) em `third_party/redist/ViGEmBus_1.22.0_x64_x86_arm64.exe` (não fica no repositório).

### Peça por peça

```powershell
# Protocolo (biblioteca compartilhada) e os testes dele
cmake -S proto -B build/proto
cmake --build build/proto --config Release
ctest --test-dir build/proto -C Release

# Motor do PC (C++, captura + NVENC + rede)
cmake -S host/engine -B build/host -G "Visual Studio 17 2022" -A x64
cmake --build build/host --config Release --target jinky-engine

# App do PC (C# / WPF)
dotnet build host/app -c Release

# App da TV (Kotlin + C++ via NDK); sem chave própria, usa a assinatura de depuração
cd tv; ./gradlew assembleRelease
```

### Tudo de uma vez

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1
```

Gera em `dist/` o instalador do Windows e o APK da TV. Para testar a TV sem uma TV, `scripts/tv-emulator.ps1` abre o app num emulador de Android TV no próprio PC.

> **Fazendo a sua versão?** O atualizador procura versões em `poncianomateus/jinky-releases` (`host/app/Updater.cs`). Troque pelo seu repositório para publicar as suas.

## Organização do código

| Pasta | O que tem |
|---|---|
| `proto/` | O protocolo do Jinky: pacotes, fragmentação, correção de erros (Reed-Solomon) e os testes. Usado pelo PC e pela TV. |
| `host/engine/` | O motor do PC em C++: captura (DXGI), codificação (NVENC), som (WASAPI), controle virtual (ViGEm), modo console e rede. |
| `host/app/` | O app do PC em C# / WPF: janela, ícone perto do relógio, biblioteca, Steam, serviço para a TV e atualizações. |
| `tv/` | O app da TV: interface em Kotlin e recepção / decodificação em C++ (NDK). |
| `installer/` | Script do instalador (Inno Setup). |
| `scripts/` | Compilar, publicar versões e abrir o emulador de TV. |
| `docs/` | [Arquitetura](docs/ARCHITECTURE.md), [como lançar versões](docs/RELEASING.md) e [componentes de terceiros](docs/THIRD_PARTY_LICENSES.md). |

## Contribuir

Ideias, correções e melhorias são bem-vindas:

- **Achou um problema?** Abra uma *issue* contando o que aconteceu, o modelo da TV e, se der, o diagnóstico (no Jinky da TV: Configurações › Sistema › Enviar diagnóstico ao PC; o arquivo vai para a pasta de diagnóstico do Jinky do PC).
- **Quer mudar algo?** Faça um *fork*, crie uma *branch* e mande um *pull request* explicando a mudança.

## Apoie o criador

O Jinky é gratuito e vai continuar sendo. Se ele deixou a sua sala mais divertida e você quiser agradecer, **um café simbólico** ajuda a manter o projeto vivo:

<a href="https://ko-fi.com/poncianomateus"><img src="https://img.shields.io/badge/%E2%98%95%20pague%20um%20caf%C3%A9-Ko--fi-FFC84A?style=for-the-badge" alt="Pague um café pelo Ko-fi"></a>

O Ko-fi aceita cartão e PayPal, de qualquer país.

## Licença

O Jinky é distribuído sob a **[Licença Apache 2.0](LICENSE)**: você pode usar, estudar, modificar e distribuir, inclusive em projetos próprios, desde que **mantenha os créditos ao criador** (arquivo [NOTICE](NOTICE)) e o aviso de licença.

Criado por **Mateus** ([@poncianomateus](https://github.com/poncianomateus)).

Componentes de terceiros (ViGEmClient, cabeçalhos da NVIDIA, fontes) mantêm as suas próprias licenças: veja [docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md). Steam, PlayStation, Xbox, Epic Games, GOG, NVIDIA, Google TV e Fire TV são marcas dos seus donos; o Jinky não tem ligação com eles.

---

<sub>🇺🇸 **English:** Jinky turns your Windows PC into a console on your TV. A native Google TV / Fire TV app with a controller-first interface streams your PC games (Steam, PS2, Epic, GOG, Xbox) over your home network with its own low-latency protocol (NVENC + UDP with Reed-Solomon FEC). Licensed under Apache 2.0.</sub>
