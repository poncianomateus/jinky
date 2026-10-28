using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;

namespace Jinky;

public sealed record ActivityItem(string Time, string Text, bool IsWarning);
public sealed record PairingRequest(string Id, string Name, string Code);
public sealed record PairedTvItem(string Id, string Name);

/// <summary>What the window shows, built only from real engine events (nothing is simulated).</summary>
public sealed class MainViewModel : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    readonly Settings _settings;
    readonly Action _settingsChanged;
    static readonly CultureInfo PtBr = CultureInfo.GetCultureInfo("pt-BR");

    readonly Action<string> _sendCommand;

    public MainViewModel(Settings settings, Action settingsChanged, Action<string> sendCommand)
    {
        _settings = settings;
        _settingsChanged = settingsChanged;
        _sendCommand = sendCommand;
    }

    // --- pairing --------------------------------------------------------------------------------

    /// <summary>Raised when a TV asks to pair (the app brings its window forward).</summary>
    public event Action? PairingRequested;

    PairingRequest? _pairing;
    public PairingRequest? Pairing { get => _pairing; private set { _pairing = value; Notify(); Notify(nameof(HasPairing)); Notify(nameof(PairingCode)); } }
    public bool HasPairing => _pairing is not null;
    public string PairingCode => _pairing is null ? "" : $"{_pairing.Code[..3]} {_pairing.Code[3..]}";

    public ObservableCollection<PairedTvItem> PairedTvs { get; } = new();
    public bool NoPairedTvs => PairedTvs.Count == 0;

    public void AcceptPairing() { if (_pairing is { } p) _sendCommand("pair-accept " + p.Id); }
    public void RejectPairing() { if (_pairing is { } p) _sendCommand("pair-reject " + p.Id); }
    public void Unpair(string id) => _sendCommand("unpair " + id);

    // --- app updates ------------------------------------------------------------------------------

    UpdateInfo? _update;
    string? _updateStatus;
    bool _installing;

    public bool HasUpdate => _update is not null;
    public bool CanInstallUpdate => _update is not null && !_installing;
    public string UpdateNotes => _update?.Notes ?? "";
    public string VersionText => $"v{Updater.Current}";
    public string UpdateButtonText => _updateStatus ?? (_update is null ? "" : $"Atualizar para {_update.Version}" + (_update.Test ? " (teste)" : ""));
    public Version? OfferedVersion => _update?.Version;

    /// <summary>The update channel changed: check again now.</summary>
    public event Action? UpdateChannelChanged;

    /// <summary>The test-versions switch exists only on the developer's PC.</summary>
    public bool ShowTestUpdates => Settings.Developer;

    public bool TestUpdates
    {
        get => _settings.WantsTestUpdates;
        set
        {
            if (_settings.WantsTestUpdates == value) return;
            _settings.TestUpdates = value;
            _settings.Save();
            Notify();
            UpdateChannelChanged?.Invoke();
        }
    }

    /// <summary>How TVs list this Jinky (several can run on one PC: one per Windows user / ASTER workplace).</summary>
    public string TvName
    {
        get => _settings.TvName ?? "";
        set
        {
            var v = string.IsNullOrWhiteSpace(value) ? null : value.Trim();
            if (_settings.TvName == v) return;
            _settings.TvName = v;
            Notify();
            _settingsChanged();  // the engine announces the name: restart it
        }
    }

    public string TvNameHint => $"Vazio = \"{Settings.DefaultTvName}\"";
    public string PortsText => $"{Ports.Media} e {Ports.Service}";

    public void OnPortsChanged(int slot)
    {
        Notify(nameof(PortsText));
        Notify(nameof(TvInstallUrl));
        if (slot > 0) Add($"Outro Jinky usa as portas de sempre neste PC · este usa {PortsText}");
    }

    /// <summary>Where a TV without Jinky downloads it from this PC (Downloader app, or the TV's browser).</summary>
    public string TvInstallUrl => LocalAddress() is { } ip ? $"http://{ip}:{TvService.Port}/tv/jinky.apk" : "";

    static string? LocalAddress()
    {
        try
        {
            // The address this PC uses on the home network (the route a TV would take back to it).
            using var s = new System.Net.Sockets.Socket(System.Net.Sockets.AddressFamily.InterNetwork,
                                                        System.Net.Sockets.SocketType.Dgram, System.Net.Sockets.ProtocolType.Udp);
            s.Connect("192.168.0.1", 9);
            return (s.LocalEndPoint as System.Net.IPEndPoint)?.Address.ToString();
        }
        catch (Exception)
        {
            return null;
        }
    }

    public void SetUpdate(UpdateInfo? update)
    {
        _update = update;
        if (_checkingNow)
        {
            _checkingNow = false;
            UpdateCheckText = update is null ? "Você já tem a versão mais nova."
                : $"Versão {update.Version} disponível: clique em \"Atualizar\" lá em cima.";
        }
        NotifyUpdate();
    }

    bool _checkingNow;
    string _updateCheckText = "";
    public string UpdateCheckText { get => _updateCheckText; private set { _updateCheckText = value; Notify(); } }

    /// <summary>"Procurar atualização": checks GitHub now instead of waiting for the next 6-hour round.</summary>
    public void CheckForUpdatesNow()
    {
        _checkingNow = true;
        UpdateCheckText = "Procurando…";
        UpdateChannelChanged?.Invoke();
    }

    public async Task InstallUpdateAsync()
    {
        if (_update is null || _installing) return;
        _installing = true;
        var progress = new Progress<double>(p => { _updateStatus = $"Baixando… {p:P0}"; NotifyUpdate(); });
        _updateStatus = "Baixando…";
        NotifyUpdate();
        var error = await Updater.InstallAsync(_update, progress);
        _installing = false;
        _updateStatus = error is null ? "Instalando…" : null;
        if (error is not null) Add(error, warning: true);
        NotifyUpdate();
    }

    void NotifyUpdate()
    {
        Notify(nameof(HasUpdate));
        Notify(nameof(CanInstallUpdate));
        Notify(nameof(UpdateNotes));
        Notify(nameof(UpdateButtonText));
    }

    // --- state -------------------------------------------------------------------------------

    // Away: another Windows user has the screen; their Jinky answers the TVs meanwhile.
    public enum State { Starting, Waiting, Streaming, Failed, Away }

    State _state = State.Starting;
    public State Current { get => _state; private set { _state = value; Notify(); NotifyStatus(); } }
    public bool IsStreaming => _state == State.Streaming;

    public string StatusTitle => _state switch
    {
        State.Starting => "Iniciando…",
        State.Waiting => "Pronto para a TV",
        State.Streaming => "Jogando na TV",
        State.Away => "Em outro usuário",
        _ => "Algo deu errado",
    };

    string _detail = "Preparando o streaming…";
    public string StatusDetail { get => _detail; private set { _detail = value; Notify(); } }

    public string PillText => _state switch
    {
        State.Streaming => "Transmitindo",
        State.Waiting => "Aguardando a TV",
        State.Starting => "Iniciando",
        State.Away => "Em pausa",
        _ => "Parado",
    };

    string _latency = "—", _fps = "—", _bitrate = "—", _codec = "—";
    public string Latency { get => _latency; private set { _latency = value; Notify(); } }
    public string Fps { get => _fps; private set { _fps = value; Notify(); } }
    public string Bitrate { get => _bitrate; private set { _bitrate = value; Notify(); } }
    public string Codec { get => _codec; private set { _codec = value; Notify(); } }

    string _gpu = "—", _desktop = "—", _gamepads = "—", _pads = "nenhum";
    public string Gpu { get => _gpu; private set { _gpu = value; Notify(); } }
    public string Desktop { get => _desktop; private set { _desktop = value; Notify(); } }
    public string GamepadDriver { get => _gamepads; private set { _gamepads = value; Notify(); } }
    public string PadsConnected { get => _pads; private set { _pads = value; Notify(); } }

    public ObservableCollection<ActivityItem> Activity { get; } = new();

    // --- preferences (two-way) -----------------------------------------------------------------

    public bool ConsoleMode
    {
        get => _settings.ConsoleMode;
        set { if (_settings.ConsoleMode == value) return; _settings.ConsoleMode = value; Notify(); Notify(nameof(SoundToggleEnabled)); _settingsChanged(); }
    }

    public bool AllowTvPower
    {
        get => _settings.AllowTvPower;
        set { if (_settings.AllowTvPower == value) return; _settings.AllowTvPower = value; Notify(); _settings.Save(); }
    }

    /// <summary>"Jogar fora de casa (Tailscale)": firewall rules (administrator, once) and the address to give the TV.</summary>
    public bool RemotePlayOn
    {
        get => _settings.RemotePlay;
        set
        {
            if (_settings.RemotePlay == value || _remoteBusy is not null) return;
            _ = SetRemotePlay(value);
        }
    }

    string? _remoteBusy;  // what "Jogar fora de casa" is doing right now (downloading, installing...)
    System.Windows.Threading.DispatcherTimer? _remoteWatch;

    async Task SetRemotePlay(bool on)
    {
        void Show(string text) { _remoteBusy = text; Notify(nameof(RemotePlayText)); }
        Show(on ? "Preparando…" : "Desligando…");
        var error = await RemotePlay.SetupAsync(on, text => System.Windows.Application.Current?.Dispatcher.Invoke(() => Show(text)));
        _remoteBusy = null;
        if (error is not null)
        {
            Add("Jogar fora de casa: " + error, warning: true);
        }
        else
        {
            _settings.RemotePlay = on;
            TvService.AllowTailscale = on;
            _settings.Save();
            Add(on ? "Jogar fora de casa ligado" : "Jogar fora de casa desligado");
        }
        Notify(nameof(RemotePlayOn));
        Notify(nameof(RemotePlayText));
        // Until the user signs in to Tailscale there is no address to show: look again now and then.
        _remoteWatch?.Stop();
        if (_settings.RemotePlay && RemotePlay.TailscaleAddress() is null)
        {
            _remoteWatch = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            _remoteWatch.Tick += (_, _) =>
            {
                Notify(nameof(RemotePlayText));
                if (RemotePlay.TailscaleAddress() is not null || !_settings.RemotePlay) _remoteWatch?.Stop();
            };
            _remoteWatch.Start();
        }
    }

    // --- "Monitor virtual" ----------------------------------------------------------------------

    string? _virtualBusy;
    bool _virtualInstalled;

    /// <summary>Looks whether the virtual screen driver is there; off the UI thread.</summary>
    public void CheckVirtualScreen() => Task.Run(() =>
    {
        var installed = VirtualScreen.Installed();
        System.Windows.Application.Current?.Dispatcher.Invoke(() =>
        {
            _virtualInstalled = installed;
            // Removed outside Jinky: stop asking the engine for it.
            if (_settings.VirtualDisplay && !installed)
            {
                _settings.VirtualDisplay = false;
                _settingsChanged();
            }
            Notify(nameof(VirtualDisplayOn));
            Notify(nameof(VirtualDisplayText));
        });
    });

    public bool VirtualDisplayOn
    {
        get => _settings.VirtualDisplay && _virtualInstalled;
        set
        {
            if (VirtualDisplayOn == value || _virtualBusy is not null) return;
            _ = SetVirtualDisplay(value);
        }
    }

    async Task SetVirtualDisplay(bool on)
    {
        void Show(string text) { _virtualBusy = text; Notify(nameof(VirtualDisplayText)); }
        string? error = null;
        // Installed already (an earlier time): just start using it; off keeps nothing behind.
        if (!on || !_virtualInstalled)
            error = await VirtualScreen.SetAsync(on, text => System.Windows.Application.Current?.Dispatcher.Invoke(() => Show(text)));
        _virtualBusy = null;
        if (error is not null) Add("Monitor virtual: " + error, warning: true);
        else
        {
            _virtualInstalled = on;
            _settings.VirtualDisplay = on;
            _settingsChanged();  // the engine starts again with (or without) it
            Add(on ? "Monitor virtual ligado · na próxima sessão o jogo vai para ele" : "Monitor virtual desligado e removido");
        }
        Notify(nameof(VirtualDisplayOn));
        Notify(nameof(VirtualDisplayText));
    }

    public string VirtualDisplayText =>
        _virtualBusy ?? (VirtualDisplayOn ? "Ligado: jogando na TV, o PC usa a tela virtual; o monitor do PC fica apagado e volta como estava." : "");

    // --- "Ligar o PC pela TV" (Wake-on-LAN) ----------------------------------------------------

    bool? _wakeReady = null;   // the adapter may wake the PC (null = not checked yet / no network)
    string? _wakeBusy;

    /// <summary>Looks (without administrator) whether the TV can wake this PC; off the UI thread.</summary>
    public void CheckWakeFromTv() => Task.Run(() =>
    {
        var ready = WakeOnLan.Ready();
        System.Windows.Application.Current?.Dispatcher.Invoke(() =>
        {
            _wakeReady = ready;
            Notify(nameof(WakeFromTv));
            Notify(nameof(WakeFromTvText));
        });
    });

    public bool WakeFromTv
    {
        get => _wakeReady == true;
        set
        {
            if ((_wakeReady == true) == value || _wakeBusy is not null) return;
            _ = SetWakeFromTv(value);
        }
    }

    async Task SetWakeFromTv(bool on)
    {
        _wakeBusy = on ? "Liberando a placa de rede… (o Windows vai pedir permissão; o Wi-Fi pode piscar)" : "Desfazendo…";
        Notify(nameof(WakeFromTvText));
        var error = await WakeOnLan.SetAsync(on);
        _wakeBusy = null;
        if (error is not null) Add("Ligar o PC pela TV: " + error, warning: true);
        else Add(on ? "A TV agora liga este PC (quando ele estiver suspenso)" : "Ligar o PC pela TV desligado");
        _wakeReady = WakeOnLan.Ready();
        Notify(nameof(WakeFromTv));
        Notify(nameof(WakeFromTvText));
    }

    public string WakeFromTvText =>
        _wakeBusy ?? _wakeReady switch
        {
            true => "Pronto: abrir o Jinky na TV acorda este PC suspenso.",
            false => "Desligado: a placa de rede ainda não tem permissão para acordar o PC.",
            _ => "",
        };

    public string RemotePlayText =>
        _remoteBusy ?? (!_settings.RemotePlay ? "" :
        RemotePlay.TailscaleAddress() is { } ip
            ? $"Pronto. Na TV: Configurações › Sistema › PC pela internet, e digite {ip}"
            : RemotePlay.TailscaleInstalled
                ? "Entre na sua conta do Tailscale (a página abriu no navegador; ou o ícone do Tailscale perto do relógio). Use a mesma conta na TV."
                : "O Tailscale não está instalado. Desligue e ligue de novo para instalar.");

    public bool KeepBigPicture
    {
        get => _settings.KeepBigPicture;
        set { if (_settings.KeepBigPicture == value) return; _settings.KeepBigPicture = value; Notify(); _settingsChanged(); }
    }

    public bool CapGames
    {
        get => _settings.CapGames;
        set { if (_settings.CapGames == value) return; _settings.CapGames = value; Notify(); _settingsChanged(); }
    }

    public bool SoundOnlyOnTv
    {
        get => _settings.ConsoleMode || _settings.SoundOnlyOnTv;
        set { if (_settings.SoundOnlyOnTv == value) return; _settings.SoundOnlyOnTv = value; Notify(); _settingsChanged(); }
    }

    /// <summary>Console mode always sends the sound to the TV, so the separate switch is locked on.</summary>
    public bool SoundToggleEnabled => !_settings.ConsoleMode;

    public bool StartWithWindows
    {
        get => Jinky.StartWithWindows.IsEnabled;
        set { Jinky.StartWithWindows.Set(value); Notify(); }
    }

    // --- Steam library (games to install from the TV) ---

    string _steamKeyStatus = "";
    public string SteamKeyStatus { get => _steamKeyStatus; private set { _steamKeyStatus = value; Notify(); } }

    /// <summary>Saved as soon as a full key (32 characters) is pasted; no engine restart needed.</summary>
    public string SteamApiKey
    {
        get => _settings.SteamApiKey ?? "";
        set
        {
            var key = value.Trim();
            if (key == (_settings.SteamApiKey ?? "")) return;
            if (key.Length is not (0 or 32))
            {
                SteamKeyStatus = "A chave tem 32 caracteres; cole ela inteira.";
                return;
            }
            _settings.SteamApiKey = key.Length == 0 ? null : key;
            _settings.Save();
            Notify();
            CheckSteamKey();
        }
    }

    /// <summary>
    /// Read the library straight from the Steam client (exactly its Library) instead of the Web API.
    /// Turning it on writes Steam's developer flag file; it takes effect after Steam restarts.
    /// </summary>
    public bool SteamDirect
    {
        get => SteamLibrary.SteamPath() is { } s && SteamClient.Enabled(s);
        set
        {
            if (SteamLibrary.SteamPath() is not { } steam) return;
            var error = SteamClient.SetEnabled(steam, value);
            Notify();
            if (error is not null) SteamKeyStatus = error;
            else CheckSteamKey();
        }
    }

    /// <summary>Shows where the TV's list of games to install comes from, checked in the background.</summary>
    public void CheckSteamKey()
    {
        var key = _settings.SteamApiKey;
        var direct = SteamDirect;
        var blocked = SteamLibrary.SteamPath() is { } steam && SteamClient.Blocked(steam);
        SteamKeyStatus = "Verificando com a Steam…";
        Task.Run(() =>
        {
            string status;
            if (direct && SteamClient.Library() is { } apps)
                status = $"✓ Lendo direto da Steam: {apps.Count} jogos (seus e da família), como na sua biblioteca.";
            else
            {
                var fallback = direct ? "Leitura direta ligada: reinicie a Steam para ativar. "
                    : blocked ? "Outro complemento instalado na Steam bloqueou a leitura direta; ligue de novo para liberar. " : "";
                if (string.IsNullOrEmpty(key))
                    status = fallback + "Sem chave: a TV mostra só os jogos instalados.";
                else
                {
                    var (count, error) = SteamLibrary.CheckKey(key);
                    status = fallback + (error ?? $"✓ Pela API: {count} jogos (seus e da família) na Biblioteca da TV.");
                }
            }
            System.Windows.Application.Current?.Dispatcher.Invoke(() => SteamKeyStatus = status);
        });
    }

    // --- engine events --------------------------------------------------------------------------

    readonly HashSet<int> _activePads = new();
    readonly HashSet<int> _ds4Pads = [];
    string? _tvName;

    /// <summary>Raised when a TV starts a session (the app hides its window in console mode).</summary>
    public event Action? SessionStarted;

    /// <summary>The engine is ready for the game the TV asked for (Big Picture is up, in console mode).</summary>
    public event Action? ReadyToLaunch;
    public bool LaunchReady { get; private set; }

    public void OnEngineEvent(EngineEvent e)
    {
        switch (e.Kind)
        {
            case "ready":
                Gpu = e.Str("gpu") ?? "—";
                Desktop = e.Str("desktop") ?? "—";
                Current = State.Waiting;
                StatusDetail = "Abra o Jinky na TV para começar.";
                Add("Streaming pronto · " + Gpu);
                break;
            case "gamepads":
                GamepadDriver = e.Bool("available") == true ? "Pronto (ViGEmBus)" : "Driver ViGEmBus não encontrado";
                break;
            case "connected":
                _tvName = null;
                LaunchReady = false;
                SessionStarted?.Invoke();
                Current = State.Streaming;
                StatusDetail = $"TV conectada · {Host(e.Str("tv"))}";
                Add("TV conectada · " + Host(e.Str("tv")));
                break;
            case "launch_ready":
                LaunchReady = true;
                ReadyToLaunch?.Invoke();
                break;
            case "disconnected":
                LaunchReady = false;
                Current = State.Waiting;
                StatusDetail = "Abra o Jinky na TV para começar.";
                Latency = Fps = Bitrate = "—";
                Add(e.Str("reason") == "silent" ? "A TV parou de responder · sessão encerrada" : "TV desconectou", e.Str("reason") == "silent");
                break;
            case "pair_request":
                Pairing = new PairingRequest(e.Str("id") ?? "", CleanName(e.Str("name")), e.Str("code") ?? "000000");
                Add($"{Pairing.Name} pediu para parear");
                PairingRequested?.Invoke();
                break;
            case "paired":
                Pairing = null;
                Add($"{CleanName(e.Str("name"))} pareada · já pode jogar");
                break;
            case "pair_rejected":
                Pairing = null;
                Add($"Pareamento de {CleanName(e.Str("name"))} recusado", warning: true);
                break;
            case "pair_expired":
                Pairing = null;
                break;
            case "paired_list":
                PairedTvs.Clear();
                if (e.Data.TryGetProperty("tvs", out var tvs))
                    foreach (var tv in tvs.EnumerateArray())
                        PairedTvs.Add(new PairedTvItem(tv.GetProperty("id").GetString() ?? "", CleanName(tv.GetProperty("name").GetString())));
                Notify(nameof(NoPairedTvs));
                break;
            case "tv_name":
                _tvName = CleanName(e.Str("name"));
                if (IsStreaming) StatusDetail = $"TV conectada · {_tvName}";
                break;
            case "handoff":
                Add($"O jogo passou de {e.Str("from")} para outra TV");
                break;
            case "session_end":
                LaunchReady = false;
                Add("Big Picture fechado · sessão encerrada");
                break;
            case "codec":
                Codec = e.Str("codec") ?? "—";
                break;
            case "latency":
                if (e.Num("ms") is { } ms) Latency = ms.ToString("0", PtBr) + " ms";
                break;
            case "stats":
                if (!IsStreaming) break;
                if (e.Num("fps") is { } fps) Fps = fps.ToString("0", PtBr) + " fps";
                if (e.Num("mbps") is { } mbps) Bitrate = mbps.ToString("0.0", PtBr) + " Mbit/s";
                break;
            case "pad":
                var index = (int)(e.Num("index") ?? 0);
                var ds4 = e.Bool("ds4") == true;
                if (e.Bool("on") == true)
                {
                    _activePads.Add(index);
                    if (ds4) _ds4Pads.Add(index); else _ds4Pads.Remove(index);
                    Add($"Controle {index + 1} conectado" + (ds4 ? " · como DualShock 4" : ""));
                }
                else { _activePads.Remove(index); _ds4Pads.Remove(index); Add($"Controle {index + 1} desconectado"); }
                var kind = _ds4Pads.Count == _activePads.Count && _ds4Pads.Count > 0 ? "PlayStation" : "Xbox";
                PadsConnected = _activePads.Count switch { 0 => "nenhum", 1 => $"1 controle {kind}", var n => $"{n} controles" };
                break;
            case "virtual_display":
                Add(e.Bool("on") == true ? "Monitor virtual ligado · a TV joga na tela virtual, o monitor do PC descansa"
                                         : "Monitor virtual desligado · monitor do PC de volta");
                break;
            case "console":
                Add(e.Bool("on") == true ? "Modo console ligado · Big Picture, 60 Hz, som na TV" : "Modo console desligado · tela e som restaurados");
                break;
            case "frame_cap":
                Add(e.Bool("on") == true ? $"Jogos limitados a {e.Num("fps") ?? 60:0} fps · mais folga para a transmissão"
                                         : "Limite de fps dos jogos desfeito");
                break;
            case "port_busy":
                if (_state != State.Away)
                {
                    Current = State.Away;
                    StatusDetail = "Outro Jinky está atendendo a TV agora (de outro usuário do Windows ou de outra estação do ASTER). " +
                                   "Feche-o lá (ícone perto do relógio → Sair) e este assume sozinho.";
                    Add("Outro Jinky está atendendo a TV · este espera a vez");
                }
                break;
            case "power":
                var off = e.Str("action") == "shutdown";
                if (!_settings.AllowTvPower)
                {
                    Add(off ? "A TV pediu para desligar o PC · não permitido nas configurações" : "A TV pediu para suspender o PC · não permitido nas configurações", warning: true);
                    break;
                }
                Add(off ? "A TV pediu para desligar o PC · desligando" : "A TV pediu para suspender o PC · suspendendo");
                // A moment for the log line, then sleep / shut down (off the UI thread).
                var action = off ? "shutdown" : "sleep";
                _ = Task.Run(async () =>
                {
                    await Task.Delay(1500);
                    if (!Power.Run(action)) System.Windows.Application.Current?.Dispatcher.Invoke(() => Add("Não deu para " + (off ? "desligar" : "suspender") + " o PC", warning: true));
                });
                break;
            case "pause":
                // "Início" on the TV: freeze the game to continue later (off the UI thread: it waits
                // a moment for the game to minimise).
                if (e.Num("pid") is { } pid && GamePausing is { } pausing)
                {
                    var (id, name) = pausing();
                    _ = Task.Run(() =>
                    {
                        var why = GamePause.Pause((int)pid, id, name);
                        System.Windows.Application.Current?.Dispatcher.Invoke(() =>
                            Add(why is null ? $"{name} pausado · continua de onde parou pela TV" : $"{name} não foi pausado: {why}", why is not null));
                    });
                }
                break;
            case "log":
                if (e.Str("level") == "ERROR") Add(e.Str("msg") ?? "erro", warning: true);
                break;
        }
    }

    public void OnGameLaunched(string name) => Add($"Abrindo {name} pela TV");

    public void OnNotice(string text) => Add(text);

    /// <summary>The game being paused: its Steam id and name (from the TV service).</summary>
    public Func<(int Id, string Name)>? GamePausing { get; set; }

    /// <summary>Another Windows user took the screen: this Jinky stepped aside.</summary>
    public void OnAway()
    {
        Current = State.Away;
        StatusDetail = "Outro usuário do Windows está na tela: o Jinky dele atende a TV. Este volta sozinho quando você voltar.";
        Add("Outro usuário do Windows na tela · Jinky em pausa");
    }

    /// <summary>This Windows user has the screen again.</summary>
    public void OnBack()
    {
        Current = State.Starting;
        StatusDetail = "Preparando o streaming…";
        Add("De volta à tela · Jinky retomando");
    }

    public void OnTvDiagnostics(string tv, string path) =>
        Add($"Diagnóstico recebido de {tv} ({System.IO.Path.GetFileName(path)})");

    public void OnEngineFailed(string message)
    {
        Current = State.Failed;
        StatusDetail = message;
        Add(message, warning: true);
    }

    public void OnEngineExited()
    {
        if (_state is not (State.Failed or State.Away)) Current = State.Starting;
        _activePads.Clear();
        PadsConnected = "nenhum";
    }

    static string Host(string? endpoint) => endpoint?.Split(':')[0] ?? "?";

    /// <summary>A TV's name for display: no invisible marks (some devices put U+200E in their name).</summary>
    static string CleanName(string? name)
    {
        var clean = new string((name ?? "").Where(c => char.GetUnicodeCategory(c) is not
            (UnicodeCategory.Format or UnicodeCategory.Control)).ToArray()).Trim();
        return clean.Length > 0 ? clean : "TV";
    }

    void Add(string text, bool warning = false)
    {
        Activity.Insert(0, new ActivityItem(DateTime.Now.ToString("HH:mm", PtBr), text, warning));
        while (Activity.Count > 8) Activity.RemoveAt(Activity.Count - 1);
    }

    void NotifyStatus()
    {
        Notify(nameof(StatusTitle));
        Notify(nameof(PillText));
        Notify(nameof(IsStreaming));
    }

    void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
