using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Jinky;

/// <summary>A question of the "Ajuda" page; the answer opens under it when clicked.</summary>
public sealed class FaqItem(string question, string answer) : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    public string Question { get; } = question;
    public string Answer { get; } = answer;

    bool _open;
    public bool IsOpen { get => _open; set { _open = value; Notify(); } }

    void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed record FaqGroup(string Title, IReadOnlyList<FaqItem> Items);

/// <summary>A page of the PC tour: which page of the window it shows, and what it says.</summary>
public sealed record TourStep(string Page, string Eyebrow, string Title, string Text);

/// <summary>The texts of the PC's "Ajuda" page and tour.</summary>
public static class HelpContent
{
    public static IReadOnlyList<TourStep> Tour { get; } =
    [
        new("NavHome", "BEM-VINDO", "Este é o Jinky do PC",
            "Ele fica perto do relógio do Windows e cuida de tudo enquanto você joga na TV. Vamos passar rapidinho pelas páginas."),
        new("NavHome", "INÍCIO", "O que está acontecendo agora",
            "Quando uma TV está jogando, aparecem aqui a latência, a imagem e um gráfico ao vivo. Encerrar sessão manda a TV de volta ao início dela. Embaixo ficam as últimas sessões."),
        new("NavLibrary", "BIBLIOTECA", "Os jogos que a TV mostra",
            "Steam, PlayStation 2, Epic, GOG e Xbox, todos juntos. Desligue o interruptor para esconder um jogo da TV, marque a ★ para fixá-lo no início dela e passe o mouse num jogo para trocar a capa."),
        new("NavGames", "JOGAR", "Como o PC se comporta na sessão",
            "O Modo console deixa o PC com cara de console enquanto a TV joga: monitor a 60 Hz, jogos limitados a 60 fps e som só na TV. Tudo volta ao normal no fim. Aqui também fica a ligação com a Steam."),
        new("NavTvs", "TVS", "As suas TVs",
            "TVs pareadas, o endereço para instalar o Jinky numa TV nova e o teste de conexão, que diz o que melhorar na rede. Também é aqui que a TV ganha permissão para ligar, suspender ou desligar o PC."),
        new("NavSystem", "SISTEMA", "O Jinky neste PC",
            "Iniciar com o Windows, o gráfico da sessão, as atualizações (automáticas) e a pasta de diagnóstico, se algo der errado."),
        new("NavHelp", "AJUDA", "Ficou alguma dúvida?",
            "A página Ajuda responde as perguntas mais comuns, e este tour fica lá para quando você quiser rever. Bom jogo!"),
    ];

    public static IReadOnlyList<FaqGroup> Faq { get; } =
    [
        new("Começando",
        [
            new("Como coloco o Jinky na TV?",
                "Em TVs › Instalar numa TV nova aparece um endereço. Na TV (Google TV ou Fire TV), instale o app Downloader pela loja, permita que ele instale apps (\"Fontes desconhecidas\") e digite o endereço. Depois disso a TV se atualiza sozinha por este PC."),
            new("Como pareio a TV com este PC?",
                "Abra o Jinky na TV: ela encontra o PC sozinha e mostra um código de 6 números. O mesmo código aparece aqui; confira e clique em Permitir. Só é preciso fazer isso uma vez por TV."),
            new("A TV não encontra o PC",
                "O PC e a TV precisam estar na mesma rede, e o Jinky precisa estar aberto aqui (ícone perto do relógio). Se o Windows perguntou sobre o firewall na instalação, permita o Jinky nas redes privadas. Uma rede marcada como Pública no Windows também bloqueia: troque para Privada."),
            new("Posso usar mais de uma TV?",
                "Sim. Pareie quantas quiser; uma joga por vez. Se outra TV abrir o mesmo jogo, ela pode trazer a partida para si (Jogar aqui)."),
        ]),
        new("Jogar",
        [
            new("O jogo não abre na TV",
                "Confira se o jogo abre normalmente no PC. Se a Steam estiver atualizando o jogo, a TV mostra o progresso e abre quando terminar. Se a TV mostrar a tela do PC com uma janela esperando, use o controle (no menu do jogo, Mouse) para responder, e mande o diagnóstico se acontecer sempre."),
            new("Para que serve o Modo console?",
                "Enquanto a TV joga, o PC muda o monitor para 60 Hz, limita os jogos a 60 fps (pelo driver da NVIDIA) e manda o som só para a TV. A imagem fica mais estável e a placa de vídeo sobra para a transmissão. No fim da sessão tudo volta exatamente como estava."),
            new("Como funciona o Quick Resume (continuar de onde parou)?",
                "Quando você volta ao Início da TV no meio do jogo, o Jinky congela o jogo no PC. Ao escolher Continuar, ele volta na hora. Até 2 jogos ficam pausados; jogos online ou com anti-cheat não são pausados. Nos jogos de PS2 (PCSX2), o Jinky salva o estado do emulador, e dá para continuar até depois de reiniciar o PC."),
            new("Jogos de PS2: como aparecem na TV?",
                "O Jinky lê as pastas de jogos que você configurou no PCSX2 e mostra cada jogo com capa. Eles abrem direto, sem a Steam. O Quick Resume do PS2 liga sozinho."),
            new("Jogos da Epic, GOG e Xbox / Game Pass",
                "Os jogos instalados por essas lojas aparecem sozinhos na biblioteca da TV. Os da Epic abrem pelo launcher dela; os da GOG e do Xbox abrem direto. O tempo de jogo é contado pelo Jinky."),
            new("Como escondo um jogo, fixo um favorito ou troco uma capa?",
                "Na página Biblioteca: o interruptor de cada jogo mostra ou esconde o jogo na TV, a ★ fixa o jogo no início da TV e, passando o mouse, aparece Trocar capa (uma imagem PNG ou JPG, de preferência em pé)."),
        ]),
        new("Controles",
        [
            new("O controle não funciona no jogo",
                "O Jinky cria um controle virtual no PC com o driver ViGEmBus, que o instalador coloca sozinho. Em Sistema › Este PC, Controle virtual deve dizer Pronto. Se o jogo usar a Steam, deixe o Steam Input como a Steam sugere."),
            new("Meu controle PlayStation aparece como Xbox",
                "Na TV, ligue Configurações › Sistema › Controle PlayStation no PC. O PC passa a ver um controle de PS4 (DualShock 4), com os botões △ ○ ✕ □ nos jogos. O Windows não tem como criar um controle de PS5 virtual, mas os botões são os mesmos."),
        ]),
        new("Imagem e rede",
        [
            new("A imagem trava, atrasa ou fica borrada",
                "Use TVs › Testar conexão: ele mede a rede até cada TV e diz o que fazer. O que mais ajuda: cabo de rede no PC, Wi-Fi de 5 GHz na TV e a TV perto do roteador. Na TV, Configurações › Imagem e qualidade permite baixar a qualidade ou a resolução."),
            new("O que é o Jinky Vision?",
                "Uma forma de comprimir a imagem que gasta mais detalhe no centro da tela, onde está a ação, e um pouco menos nas bordas, com a mesma banda. Ajuda em Wi-Fi mais fraco. Está em teste: ligue em Jogar e compare."),
            new("O que é o Monitor virtual?",
                "Durante a sessão, o jogo vai para uma tela virtual do tamanho da TV, e o seu monitor não muda de resolução nem de Hz. Instala um driver de código aberto na primeira vez. Não aparece em PCs com o ASTER (várias pessoas no mesmo PC)."),
        ]),
        new("PC e TV",
        [
            new("Como ligo o PC pela TV?",
                "Ligue Ligar o PC pela TV, em TVs. Com o PC suspenso, abrir o Jinky na TV acorda o PC. Com o PC desligado de vez não funciona pelo Wi-Fi."),
            new("Como suspendo ou desligo o PC pela TV?",
                "Deixe ligada a opção A TV pode suspender ou desligar o PC, em TVs. Na TV, isso fica em Configurações › Sistema."),
            new("Como jogo fora de casa?",
                "Ligue Jogar fora de casa, em TVs. O Jinky instala o Tailscale (grátis) e abre o login. Entre com a mesma conta no PC e na TV, e na TV digite o endereço que aparece aqui em Configurações › Sistema › PC pela internet. Pareie a TV em casa primeiro."),
            new("Como removo uma TV?",
                "Em TVs, clique em Remover ao lado dela. Para jogar de novo ela vai precisar parear outra vez."),
            new("Como faço um lugar do ASTER só para a TV?",
                "Assim, alguém usa o PC enquanto outra pessoa joga na TV, sem um atrapalhar o outro. Instale um driver de monitor virtual e, no painel do ASTER, dê esse monitor a um lugar, com login automático de um usuário do Windows. Com o Jinky instalado para todos os usuários, ele abre sozinho nesse lugar. Em TVs › Outros lugares deste PC você muda o nome e o modo console dele, e os pedidos de pareamento das TVs para esse lugar aparecem aqui para você permitir."),
            new("Duas pessoas usam este PC. Funciona?",
                "Sim. Cada usuário do Windows tem o seu Jinky, e cada um aparece como um PC diferente na TV. Dê um nome para cada em TVs › Nome deste PC na TV."),
            new("Como o Jinky se atualiza?",
                "Sozinho: ele procura versões novas ao abrir e a cada 6 horas e avisa com um botão no menu. A versão nova da TV vem junto, e a TV mostra Atualizar o Jinky no início dela."),
            new("Algo deu errado. Como peço ajuda?",
                "Na TV, Configurações › Sistema › Enviar diagnóstico ao PC manda um relatório para a pasta de diagnóstico (Sistema › Diagnóstico › Abrir pasta). Abra uma issue no GitHub do Jinky contando o que aconteceu e anexe esses arquivos."),
        ]),
    ];
}
