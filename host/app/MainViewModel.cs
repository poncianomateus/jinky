using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;

namespace Jinky;

public sealed record ActivityItem(string Time, string Text, bool IsWarning);

/// <summary>What the window shows, built only from real engine events (nothing is simulated).</summary>
public sealed class MainViewModel : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    readonly Settings _settings;
    readonly Action _settingsChanged;
    static readonly CultureInfo PtBr = CultureInfo.GetCultureInfo("pt-BR");

    public MainViewModel(Settings settings, Action settingsChanged)
    {
        _settings = settings;
        _settingsChanged = settingsChanged;
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
