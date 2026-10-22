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
    public string VersionText => $"versão {Updater.Current}";
    public string UpdateButtonText => _updateStatus ?? (_update is null ? "" : $"Atualizar para {_update.Version}");

    public void SetUpdate(UpdateInfo? update)
    {
        _update = update;
        NotifyUpdate();
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

    public enum State { Starting, Waiting, Streaming, Failed }

    State _state = State.Starting;
    public State Current { get => _state; private set { _state = value; Notify(); NotifyStatus(); } }
    public bool IsStreaming => _state == State.Streaming;

    public string StatusTitle => _state switch
    {
        State.Starting => "Iniciando…",
        State.Waiting => "Pronto para a TV",
        State.Streaming => "Jogando na TV",
        _ => "Algo deu errado",
    };

    string _detail = "Preparando o streaming…";
    public string StatusDetail { get => _detail; private set { _detail = value; Notify(); } }

    public string PillText => _state switch
    {
        State.Streaming => "Transmitindo",
        State.Waiting => "Aguardando a TV",
        State.Starting => "Iniciando",
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
        SteamKeyStatus = "Verificando com a Steam…";
        Task.Run(() =>
        {
            string status;
            if (direct && SteamClient.Library() is { } apps)
                status = $"✓ Lendo direto da Steam: {apps.Count} jogos (seus e da família), como na sua biblioteca.";
            else
            {
                var fallback = direct ? "Leitura direta ligada: reinicie a Steam para ativar. " : "";
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
    string? _tvName;

    /// <summary>Raised when a TV starts a session (the app hides its window in console mode).</summary>
    public event Action? SessionStarted;

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
                SessionStarted?.Invoke();
                Current = State.Streaming;
                StatusDetail = $"TV conectada · {Host(e.Str("tv"))}";
                Add("TV conectada · " + Host(e.Str("tv")));
                break;
            case "disconnected":
                Current = State.Waiting;
                StatusDetail = "Abra o Jinky na TV para começar.";
                Latency = Fps = Bitrate = "—";
                Add(e.Str("reason") == "silent" ? "A TV parou de responder · sessão encerrada" : "TV desconectou", e.Str("reason") == "silent");
                break;
            case "pair_request":
                Pairing = new PairingRequest(e.Str("id") ?? "", e.Str("name") ?? "TV", e.Str("code") ?? "000000");
                Add($"{Pairing.Name} pediu para parear");
                PairingRequested?.Invoke();
                break;
            case "paired":
                Pairing = null;
                Add($"{e.Str("name")} pareada · já pode jogar");
                break;
            case "pair_rejected":
                Pairing = null;
                Add($"Pareamento de {e.Str("name")} recusado", warning: true);
                break;
            case "pair_expired":
                Pairing = null;
                break;
            case "paired_list":
                PairedTvs.Clear();
                if (e.Data.TryGetProperty("tvs", out var tvs))
                    foreach (var tv in tvs.EnumerateArray())
                        PairedTvs.Add(new PairedTvItem(tv.GetProperty("id").GetString() ?? "", tv.GetProperty("name").GetString() ?? "TV"));
                Notify(nameof(NoPairedTvs));
                break;
            case "tv_name":
                _tvName = e.Str("name");
                if (IsStreaming) StatusDetail = $"TV conectada · {_tvName}";
                break;
            case "handoff":
                Add($"O jogo passou de {e.Str("from")} para outra TV");
                break;
            case "session_end":
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
                if (e.Bool("on") == true) { _activePads.Add(index); Add($"Controle {index + 1} conectado"); }
                else { _activePads.Remove(index); Add($"Controle {index + 1} desconectado"); }
                PadsConnected = _activePads.Count switch { 0 => "nenhum", 1 => "1 controle Xbox", var n => $"{n} controles Xbox" };
                break;
            case "console":
                Add(e.Bool("on") == true ? "Modo console ligado · Big Picture, 60 Hz, som na TV" : "Modo console desligado · tela e som restaurados");
                break;
            case "log":
                if (e.Str("level") == "ERROR") Add(e.Str("msg") ?? "erro", warning: true);
                break;
        }
    }

    public void OnGameLaunched(string name) => Add($"Abrindo {name} pela TV");

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
        if (_state != State.Failed) Current = State.Starting;
        _activePads.Clear();
        PadsConnected = "nenhum";
    }

    static string Host(string? endpoint) => endpoint?.Split(':')[0] ?? "?";

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
