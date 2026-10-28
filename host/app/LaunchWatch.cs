using System.IO;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// "Jinky Shell": a game started from the TV opens straight away (no Big Picture), and while it
/// opens the TV shows what Steam is doing with it — updating it, syncing the cloud, processing
/// shaders — read live from the Steam client ("Ler direto da Steam"). When Steam waits for the
/// player (launch options, an agreement, a cloud conflict) the engine shows that window on the TV
/// at once; while Steam works it keeps the TV waiting instead of giving up after a minute.
/// </summary>
public static class LaunchWatch
{
    /// <summary>
    /// What the TV shows: a phrase, a percent (-1 none), seconds left (-1 unknown); [Ask]: Steam's
    /// question for the player, answered on the TV ([Waiting] without it: the PC's window is shown);
    /// [CanSkip]: shader processing can be skipped.
    /// </summary>
    public sealed record State(string Text, int Percent, int Eta, bool Waiting, bool Running, Prompt? Ask = null, bool CanSkip = false);

    /// <summary>A question Steam asks before starting the game, as the TV shows it (one button per choice, and Cancelar).</summary>
    public sealed record Prompt(string Title, string Text, List<Choice> Choices);
    public sealed record Choice(string Label, string Value);

    static int _actionId;  // Steam's game action being followed (for the answers)

    // Read-only: Steam's active "game actions" (its launch steps) with their details, and which
    // apps it reports running.
    const string Query = """
        (async () => {
          const plain = o => { const x = {}; if (o) for (const k of Object.keys(o)) { const v = o[k];
            if (v === null || (typeof v !== 'object' && typeof v !== 'function')) x[k] = v; } return x; };
          let acts = [];
          try { acts = await SteamClient.Apps.GetActiveGameActions(); } catch (e) {}
          if (!Array.isArray(acts)) acts = acts ? Object.values(acts) : [];
          const out = [];
          for (const a of acts) {
            const id = a && (a.nGameActionID ?? a.gameActionID ?? a.nActionID);
            let d = null;
            try {
              d = await Promise.race([
                new Promise(res => { const r = SteamClient.Apps.GetGameActionDetails(id, res); if (r && r.then) r.then(res); }),
                new Promise(res => setTimeout(() => res(null), 400))]);
            } catch (e) {}
            out.push(Object.assign(plain(a), plain(d)));
          }
          return JSON.stringify({
            user: (typeof App !== 'undefined' && App.m_CurrentUser && App.m_CurrentUser.strSteamID) || '',
            actions: out,
            running: (SteamUIStore.RunningApps || []).map(r => r.appid) });
        })()
        """;

    static readonly object Lock = new();
    static Timer? _timer;
    static int _busy;
    static int _gameId;
    static DateTime _until;
    static Action<string>? _send;
    static State? _state;
    static int _runningTicks;
    static readonly HashSet<string> LoggedTasks = [];

    /// <summary>Starts following the launch of Steam game [gameId] (for up to 15 minutes).</summary>
    public static void Start(int gameId, Action<string>? sendToEngine)
    {
        if (gameId <= 0) return;  // non-Steam games (emulators) start without Steam's steps
        lock (Lock)
        {
            _gameId = gameId;
            _until = DateTime.UtcNow.AddMinutes(15);
            _send = sendToEngine;
            _state = new State("Abrindo o jogo", -1, -1, false, false);
            _runningTicks = 0;
            _timer ??= new Timer(_ => Tick(), null, TimeSpan.Zero, TimeSpan.FromSeconds(1));
        }
    }

    public static void Stop()
    {
        lock (Lock) _gameId = 0;
    }

    /// <summary>The launch state of [gameId], if it is the one being followed.</summary>
    public static State? Of(int gameId)
    {
        lock (Lock) return gameId == _gameId ? _state : null;
    }

    static void Tick()
    {
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        try
        {
            int id;
            lock (Lock)
            {
                if (_gameId != 0 && DateTime.UtcNow > _until) _gameId = 0;
                id = _gameId;
            }
            if (id == 0) return;
            var json = SteamClient.Run(Query, awaitPromise: true);
            if (json is null) return;  // reading off: the TV keeps its own "Abrindo o jogo"
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var running = root.GetProperty("running").EnumerateArray().Any(r => r.ValueKind == JsonValueKind.Number && r.GetInt64() == id);
            JsonElement? mine = null;
            foreach (var a in root.GetProperty("actions").EnumerateArray())
                if (AppOf(a) == id) mine = a;
            State next;
            bool waiting = false, working = false, canSkip = false;
            Prompt? ask = null;
            if (mine is { } act)
            {
                var task = Str(act, "strTaskName", "taskName", "strTask") ?? "";
                var details = Str(act, "strTaskDetails", "taskDetails") ?? "";
                waiting = Bool(act, "bWaitingForUI", "waitingForUI", "bWaitingForUser") || Waits(task);
                working = !waiting;
                Learn(task, act);
                var text = Describe(task, waiting);
                var percent = -1;
                // ("nSecondsRemaing": Valve's own spelling.)
                var eta = Int(act, "nSecondsRemaing", "nSecondsRemaining", "secondsRemaining") ?? -1;
                lock (Lock) _actionId = Int(act, "nGameActionID", "gameActionID") ?? 0;
                // Steam asks something: the TV asks it with its own buttons when it knows how to
                // answer; the PC's window shows only for the rest.
                ask = waiting ? PromptFor(task, details, id) : null;
                canSkip = task == "ProcessingShaderCache";
                if (Long(act, "strNumDone", "nNumDone") is { } done && Long(act, "strNumTotal", "nNumTotal") is { } total && total > 0)
                    percent = (int)Math.Clamp(done * 100 / total, 0, 100);
                // Updating before it starts: the live download has the real numbers.
                if (SteamDownloads.Now() is { } d && d.Id == id && d.State is not ("" or "None"))
                {
                    text = "Atualizando o jogo";
                    percent = d.Percent;
                    eta = d.Paused ? -1 : d.EtaSeconds;
                }
                next = new State(text, percent, eta, waiting, running, ask, canSkip);
            }
            else
            {
                next = new State(running ? "Abrindo o jogo" : "Iniciando no PC", -1, -1, false, running);
            }
            lock (Lock)
            {
                if (_gameId != id) return;
                _state = next;
                // On screen soon: the engine takes it from here.
                if (running && mine is null && ++_runningTicks >= 5) _gameId = 0;
            }
            // A question the TV asks itself keeps the launch screen up; any other shows the PC.
            if (waiting && ask is null) _send?.Invoke("launch-waiting");
            else if (working || ask is not null) _send?.Invoke("launch-busy");
        }
        catch (Exception)
        {
        }
        finally
        {
            Interlocked.Exchange(ref _busy, 0);
        }
    }

    static long AppOf(JsonElement a)
    {
        foreach (var name in new[] { "nAppID", "unAppID", "appid", "appID", "nAppId" })
            if (a.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number) return v.GetInt64();
        // Steam's GameAction carries "gameid" ("730", or a 64-bit game id with the app in its low bits).
        if (Str(a, "gameid", "strGameID", "strAppID") is { } s && ulong.TryParse(s, out var g)) return (long)(g & 0xFFFFFF);
        return 0;
    }

    /// <summary>
    /// The question for Steam's request [task] ([details] tells the cloud cases apart), with the
    /// value Steam's own dialogs answer each choice with; null when the TV can't ask it (the
    /// license agreement, which has to be read: shown on the PC).
    /// </summary>
    static Prompt? PromptFor(string task, string details, int appId)
    {
        switch (task)
        {
            case "ShowLaunchOption":
            {
                var json = SteamClient.Run($"(async () => JSON.stringify(await SteamClient.Apps.GetLaunchOptionsForApp({appId})))()", awaitPromise: true);
                if (json is null) return null;
                var choices = new List<Choice>();
                using (var doc = JsonDocument.Parse(json))
                    foreach (var o in doc.RootElement.EnumerateArray())
                    {
                        var label = o.TryGetProperty("strDescription", out var d) ? d.GetString() ?? "" : "";
                        var index = o.TryGetProperty("nIndex", out var n) ? n.GetInt32() : choices.Count;
                        choices.Add(new Choice(OptionLabel(label), index.ToString()));
                    }
                return choices.Count == 0 ? null : new Prompt("Como abrir o jogo?", "A Steam oferece mais de um jeito de abrir este jogo.", choices);
            }
            case "SynchronizingCloud" when details == "cloudconflict":
                return new Prompt("Saves diferentes na nuvem",
                    "Os saves deste PC e os da Steam Cloud são diferentes. Qual você quer usar?",
                    [new("Usar os deste PC", "KeepLocal"), new("Usar os da nuvem", "KeepRemote")]);
            case "SynchronizingCloud" when details == "syncfailed":
                return new Prompt("A nuvem não respondeu",
                    "A Steam não conseguiu sincronizar os saves. Dá para jogar mesmo assim e sincronizar depois.",
                    [new("Jogar mesmo assim", "IgnoreCloud")]);
            case "SynchronizingCloud" when details == "pendingcloudsessions":
                return new Prompt("Saves de outra sessão",
                    "Outro aparelho ainda está enviando saves deste jogo para a nuvem.",
                    [new("Jogar mesmo assim", "IgnorePendingCloudSessions")]);
            case "KickingOtherSession":
                return new Prompt("Jogo aberto em outro lugar",
                    "Esta conta está jogando em outro computador. Jogar aqui fecha a outra sessão.",
                    [new("Jogar aqui", "KickOtherSession")]);
            case "RunningInstallScript":
                return new Prompt("Instalação de componentes falhou",
                    "Um componente do jogo não instalou. Ele pode abrir mesmo assim.",
                    [new("Abrir mesmo assim", "IgnoreInstallError")]);
            case "ShowGameArgs":
                return new Prompt("Abrir com opções extras?",
                    "Este jogo vai abrir com opções de inicialização diferentes das normais.",
                    [new("Continuar", "ShowGameArgs")]);
            case "ShowCDKey":
                return new Prompt("Chave do jogo", "A Steam mostra a chave deste jogo (você pode vê-la depois na Steam).",
                    [new("Continuar", "")]);
            case "ShowDurationControl":
                return new Prompt("Aviso de tempo de jogo", "A Steam avisa sobre o tempo de jogo nesta conta.",
                    [new("Continuar", "")]);
            default:
                return null;
        }
    }

    static string OptionLabel(string description)
    {
        if (!description.StartsWith('#')) return description.Length > 0 ? description : "Jogar";
        return description switch
        {
            "#Steam_LaunchOption_Game" => "Jogar",
            "#Steam_LaunchOption_SafeMode" => "Modo de segurança",
            "#Steam_LaunchOption_Editor" => "Editor",
            "#Steam_LaunchOption_Manual" => "Manual",
            "#Steam_LaunchOption_Benchmark" => "Teste de desempenho",
            "#Steam_LaunchOption_Config" => "Configurações",
            "#Steam_LaunchOption_Server" => "Servidor",
            "#Steam_LaunchOption_OtherVR" => "Realidade virtual",
            _ => description.TrimStart('#').Replace("Steam_LaunchOption_", "").Replace('_', ' '),
        };
    }

    /// <summary>
    /// The player answered on the TV: [value] for Steam's request (one of the prompt's choices,
    /// or "SkipShaders"), or cancel the launch. False when there is nothing to answer.
    /// </summary>
    public static bool Answer(int gameId, string? value, bool cancel)
    {
        int action;
        lock (Lock)
        {
            if (gameId != _gameId || _actionId == 0) return false;
            action = _actionId;
        }
        var call = cancel
            ? $"SteamClient.Apps.CancelGameAction({action})"
            : $"SteamClient.Apps.ContinueGameAction({action}, {JsonSerializer.Serialize(value ?? "")})";
        return SteamClient.Run($"(() => {{ {call}; return 'ok'; }})()") == "ok";
    }

    static string? Str(JsonElement a, params string[] names)
    {
        foreach (var n in names)
            if (a.TryGetProperty(n, out var v))
                return v.ValueKind == JsonValueKind.String ? v.GetString() : v.ToString();
        return null;
    }

    static bool Bool(JsonElement a, params string[] names) =>
        names.Any(n => a.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.True);

    static int? Int(JsonElement a, params string[] names) => Long(a, names) is { } l ? (int)Math.Clamp(l, -1, int.MaxValue) : null;

    static long? Long(JsonElement a, params string[] names)
    {
        foreach (var n in names)
        {
            if (!a.TryGetProperty(n, out var v)) continue;
            if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var l)) return l;
            if (v.ValueKind == JsonValueKind.String && long.TryParse(v.GetString(), out var p)) return p;
        }
        return null;
    }

    static bool Waits(string task) =>
        task.Contains("Eula", StringComparison.OrdinalIgnoreCase) || task.Contains("Show", StringComparison.OrdinalIgnoreCase) ||
        task.Contains("Agreement", StringComparison.OrdinalIgnoreCase) || task.Contains("Conflict", StringComparison.OrdinalIgnoreCase);

    /// <summary>Steam's launch step in Portuguese.</summary>
    static string Describe(string task, bool waiting)
    {
        bool Has(string s) => task.Contains(s, StringComparison.OrdinalIgnoreCase);
        if (Has("Eula") || Has("Agreement")) return "Aceite os termos do jogo para continuar";
        if (Has("LaunchOption")) return "Escolha como abrir o jogo";
        if (Has("CDKey") || Has("Key")) return "A Steam pede uma chave do jogo";
        if (Has("Conflict")) return "A Steam encontrou saves diferentes na nuvem";
        if (waiting) return "A Steam está esperando você";
        if (Has("Cloud")) return "Sincronizando os saves com a nuvem";
        if (Has("Shader")) return "Processando shaders";
        if (Has("Download") || Has("Depot") || Has("Updat")) return "Atualizando o jogo";
        if (Has("Script") || Has("Install") || Has("Redist") || Has("Dependen")) return "Instalando componentes do jogo";
        if (Has("Check") || Has("Version") || Has("Info")) return "Verificando o jogo";
        if (Has("Process") || Has("Window") || Has("Creat") || Has("Launch") || Has("Complet") || Has("Running")) return "Abrindo o jogo";
        return "Preparando o jogo";
    }

    /// <summary>Notes each new kind of Steam launch step (to name them better later).</summary>
    static void Learn(string task, JsonElement act)
    {
        lock (LoggedTasks)
            if (!LoggedTasks.Add(task)) return;
        try
        {
            Directory.CreateDirectory(Path.Combine(Settings.Folder, "logs"));
            File.AppendAllText(Path.Combine(Settings.Folder, "logs", "steam-launch.log"),
                               $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {task}: {act.GetRawText()}{Environment.NewLine}");
        }
        catch (Exception)
        {
        }
    }
}
