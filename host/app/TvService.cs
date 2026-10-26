using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Jinky;

/// <summary>
/// The PC's small web service for the TVs on the home network (LAN addresses only):
/// GET /tv/version -> "versionCode versionName"; GET /tv/jinky.apk -> the TV app that ships with
/// this Jinky, so TVs update from the PC (Android only installs it over the existing app if it
/// carries the same signature, so a tampered file is refused by the TV itself);
/// POST /tv/diagnostics?tv=Name -> a TV's diagnostic report, saved next to the PC's logs;
/// GET /library -> installed Steam games (JSON); GET /library/art?id=N&kind=cover|hero|logo -> artwork;
/// POST /library/launch?id=N -> starts that game when a TV streams from this PC (right away, or as
/// soon as the session the TV is opening starts); GET /profile -> the Steam account;
/// GET /friends -> the Steam friends list with status; GET /friends/games?id=STEAMID -> what that
/// friend played lately; GET /captures, GET /captures/file?name=, POST /captures/delete?name= ->
/// the screenshots taken from the TV (deleted ones go to the Recycle Bin).
/// </summary>
public sealed class TvService : IDisposable
{
    /// <summary>
    /// This Jinky's HTTP port. 47101 for the first Jinky on the PC; each Windows user / ASTER
    /// workplace running Jinky at the same time takes the next pair (see <see cref="Ports"/>).
    /// </summary>
    public static int Port => Ports.Service;
    const int MaxBody = 8 * 1024 * 1024;
    // Replaced on every Start(): the service stops while another Windows user has the screen
    // (that user's Jinky answers the TVs) and starts again when this one is back.
    TcpListener _listener = new(IPAddress.Any, Port);
    CancellationTokenSource _stop = new();

    /// <summary>Listening for TVs now.</summary>
    public bool Running { get; private set; }

    /// <summary>A TV sent its diagnostic report (TV name, saved file).</summary>
    public event Action<string, string>? DiagnosticsReceived;

    /// <summary>A TV started a game from its Biblioteca (game name).</summary>
    public event Action<string>? GameLaunched;

    /// <summary>Whether a TV is streaming right now; games can only be started from a TV then.</summary>
    public Func<bool>? Streaming { get; set; }

    /// <summary>A session is on and ready for a game (in console mode: Big Picture is already up).</summary>
    public Func<bool>? LaunchReady { get; set; }

    /// <summary>The Steam Web API key (lists the account's games that aren't installed), if set.</summary>
    public Func<string?>? SteamApiKey { get; set; }

    // What a TV asked for while its session was still opening (start or install a game).
    (Action Run, DateTime At)? _pending;

    /// <summary>The TV's session is ready for a game: do what it asked for, if anything (and if recent).</summary>
    public void OnSessionStarted()
    {
        if (_pending is not { } p) return;
        _pending = null;
        if (DateTime.UtcNow - p.At <= TimeSpan.FromSeconds(30)) p.Run();
    }

    /// <summary>Runs [action] now if a TV session is ready for it, else as soon as it is.</summary>
    bool RunInSession(Action action)
    {
        if (LaunchReady?.Invoke() == true)
        {
            action();
            return true;
        }
        _pending = (action, DateTime.UtcNow);
        return false;
    }

    // Games the last library listing returned (installs are only accepted for these).
    Dictionary<int, string> _known = [];

    /// <summary>A game's artwork: Steam's local copy, else downloaded once from Steam's servers.</summary>
    string? ArtFor(int id, string kind)
    {
        if (_art.TryGetValue((id, kind), out var path) && File.Exists(path)) return path;
        if (!_known.ContainsKey(id) || kind is not ("cover" or "hero" or "logo")) return null;
        var downloaded = SteamLibrary.DownloadArt(id, kind);
        if (downloaded is not null) _art[(id, kind)] = downloaded;
        return downloaded;
    }

    // Artwork is served only from the files the last library listing found (never arbitrary paths).
    readonly System.Collections.Concurrent.ConcurrentDictionary<(int, string), string> _art = new();

    public static string ApkPath => Path.Combine(AppContext.BaseDirectory, "tv", "jinky-tv.apk");
    public static bool Available => File.Exists(ApkPath);

    /// <summary>Same formula as the TV build: "0.3.0" -> 300.</summary>
    public static int VersionCode
    {
        get
        {
            var v = typeof(TvService).Assembly.GetName().Version ?? new Version(0, 0, 0);
            return v.Major * 10000 + v.Minor * 100 + Math.Max(v.Build, 0);
        }
    }

    public bool Start()
    {
        if (Running) return true;
        var listener = new TcpListener(IPAddress.Any, Port);
        try
        {
            listener.Start();
        }
        catch (SocketException)
        {
            return false;  // port busy (another user's Jinky still has it): try again later
        }
        var stop = new CancellationTokenSource();
        _listener = listener;
        _stop = stop;
        Running = true;
        _ = Task.Run(() => AcceptLoop(listener, stop.Token));
        _ = Task.Run(() => WatchAchievements(stop.Token));
        return true;
    }

    /// <summary>Stops answering TVs (frees the port); Start() again resumes.</summary>
    public void Stop()
    {
        if (!Running) return;
        Running = false;
        _stop.Cancel();
        _listener.Stop();
    }

    /// <summary>While a TV plays, looks for newly unlocked achievements every few seconds.</summary>
    async Task WatchAchievements(CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            // Cheap: only Steam's local stats file is checked this often (the Web API fallback
            // throttles itself to every 10 s).
            try { await Task.Delay(TimeSpan.FromSeconds(2), stop); }
            catch (OperationCanceledException) { return; }
            if (Streaming?.Invoke() != true) continue;
            try
            {
                var known = _known;
                await Task.Run(() => SteamAchievements.Watch(SteamApiKey?.Invoke(), id => known.GetValueOrDefault(id, "")));
            }
            catch (Exception)
            {
                // Offline or Steam refused: try again next round.
            }
        }
    }

    async Task AcceptLoop(TcpListener listener, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(stop);
            }
            catch (Exception)
            {
                return;
            }
            _ = Task.Run(() => Handle(client));
        }
    }

    static bool IsHomeNetwork(IPAddress a)
    {
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        var b = a.GetAddressBytes();
        return b.Length == 4 && (b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
                                 (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) || b[0] == 127);
    }

    async Task Handle(TcpClient client)
    {
        using (client)
        {
            try
            {
                if (client.Client.RemoteEndPoint is not IPEndPoint remote || !IsHomeNetwork(remote.Address)) return;
                client.ReceiveTimeout = client.SendTimeout = 15000;
                var stream = client.GetStream();
                var request = await Request.ReadAsync(stream);
                if (request is null) return;

                if (request.Is("GET", "/tv/version"))
                {
                    await RespondText(stream, $"{VersionCode} {typeof(TvService).Assembly.GetName().Version?.ToString(3)}");
                }
                else if (request.Is("GET", "/tv/jinky.apk") && Available)
                {
                    await using var file = File.OpenRead(ApkPath);
                    await Respond(stream, "200 OK", "application/vnd.android.package-archive", file.Length);
                    await file.CopyToAsync(stream);
                }
                else if (request.Is("POST", "/tv/diagnostics") && request.ContentLength is > 0 and <= MaxBody)
                {
                    var body = await request.ReadBodyAsync(stream);
                    var tv = request.Query("tv") ?? remote.Address.ToString();
                    var safe = string.Concat(tv.Select(c => char.IsLetterOrDigit(c) ? c : '-')).Trim('-');
                    var folder = Path.Combine(Settings.Folder, "logs");
                    Directory.CreateDirectory(folder);
                    var path = Path.Combine(folder, $"tv-{safe}-{DateTime.Now:yyyyMMdd-HHmmss}.txt");
                    await File.WriteAllBytesAsync(path, body);
                    await RespondText(stream, "ok");
                    DiagnosticsReceived?.Invoke(tv, path);
                }
                else if (request.Is("GET", "/library"))
                {
                    var games = SteamLibrary.Games(SteamApiKey?.Invoke());
                    var running = SteamLibrary.RunningGame();
                    _known = games.ToDictionary(g => g.Id, g => g.Name);
                    foreach (var g in games)
                    {
                        if (g.CoverPath is not null) _art[(g.Id, "cover")] = g.CoverPath;
                        if (g.HeroPath is not null) _art[(g.Id, "hero")] = g.HeroPath;
                        if (g.LogoPath is not null) _art[(g.Id, "logo")] = g.LogoPath;
                    }
                    var json = System.Text.Json.JsonSerializer.Serialize(new
                    {
                        games = games.Select(g => new
                        {
                            id = g.Id, name = g.Name, lastPlayed = g.LastPlayed,
                            // Games without a local picture get one from Steam's servers on request.
                            cover = g.CoverPath is not null || g.State != GameState.Installed,
                            hero = g.HeroPath is not null || g.State != GameState.Installed,
                            logo = g.LogoPath is not null || g.State != GameState.Installed,
                            state = g.State switch { GameState.Downloading => "downloading", GameState.NotInstalled => "notInstalled", _ => "installed" },
                            progress = g.Progress,
                            family = g.Family,
                            minutes = g.Minutes,
                            running = running != 0 && g.Id == running,
                        }),
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if ((request.Is("GET", "/library/art") || request.Is("GET", "/library/cover")) &&
                         int.TryParse(request.Query("id"), out var artId) && ArtFor(artId, request.Query("kind") ?? "cover") is { } artPath)
                {
                    await using var file = File.OpenRead(artPath);
                    await Respond(stream, "200 OK", artPath.EndsWith(".png") ? "image/png" : "image/jpeg", file.Length);
                    await file.CopyToAsync(stream);
                }
                else if (request.Is("POST", "/library/launch") && int.TryParse(request.Query("id"), out var gameId))
                {
                    // The TV asks for the game together with its connection: it starts as soon as
                    // that session begins (never without a TV session).
                    var game = SteamLibrary.Games().FirstOrDefault(g => g.Id == gameId);
                    if (game is null) await Respond(stream, "404 Not Found", "text/plain", 0);
                    else await RespondText(stream, RunInSession(() =>
                    {
                        SteamLibrary.Launch(game.Id);
                        GameLaunched?.Invoke(game.Name);
                    }) ? "ok" : "pending");
                }
                else if (request.Is("GET", "/library/details") && int.TryParse(request.Query("id"), out var detailsId))
                {
                    // The game page: store description and media, plus this PC's numbers for it.
                    var store = SteamStore.Get(detailsId);
                    var local = SteamClient.Library()?.FirstOrDefault(a => a.Id == detailsId);
                    var json = System.Text.Json.JsonSerializer.Serialize(new
                    {
                        description = store?.Description ?? "",
                        genres = store?.Genres ?? [],
                        developer = store?.Developer ?? "",
                        released = store?.Released ?? "",
                        metacritic = store?.Metacritic ?? 0,
                        screenshots = store?.Screenshots.Select(m => new { thumb = m.Thumb, full = m.Full }) ?? [],
                        trailers = store?.Trailers.Select(t => new { name = t.Name, thumb = t.Thumb, hls = t.Hls }) ?? [],
                        minutes = local?.Minutes ?? 0,
                        sizeBytes = local?.SizeBytes ?? 0,
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("GET", "/achievements") && int.TryParse(request.Query("id"), out var achId))
                {
                    var result = await Task.Run(() => SteamAchievements.Read(achId, SteamApiKey?.Invoke()));
                    var list = result?.Items;
                    var json = System.Text.Json.JsonSerializer.Serialize(new
                    {
                        error = list is null ? SteamAchievements.LastError ?? "erro" : null,
                        progressHidden = result?.ProgressHidden ?? false,
                        total = list?.Count ?? 0,
                        unlocked = list?.Count(a => a.Achieved) ?? 0,
                        items = (list ?? []).Select(a => new
                        {
                            id = a.Id, name = a.Name, desc = a.Description, icon = a.Icon, iconGray = a.IconGray,
                            hidden = a.Hidden, achieved = a.Achieved, time = a.UnlockTime, percent = a.Percent,
                        }),
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("GET", "/profile"))
                {
                    var p = await Task.Run(() => SteamProfile.Get(SteamApiKey?.Invoke()));
                    var json = p is null
                        ? System.Text.Json.JsonSerializer.Serialize(new { error = SteamProfile.LastError ?? "erro" })
                        : System.Text.Json.JsonSerializer.Serialize(new
                        {
                            name = p.Name, avatar = p.Avatar, level = p.Level, games = p.Games, minutes = p.Minutes,
                            unlocked = p.Unlocked, total = p.Total, legendary = p.Legendary, epic = p.Epic, perfect = p.Perfect,
                            recent = p.Recent.Select(r => new { gameId = r.GameId, game = r.Game, id = r.Id, name = r.Name, icon = r.Icon, percent = r.Percent, time = r.Time }),
                            friends = p.Friends.Select(f => new { name = f.Name, avatar = f.Avatar, game = f.Game, state = f.State }),
                            played = p.Played.Select(g => new { id = g.Id, name = g.Name, minutes = g.Minutes, unlocked = g.Unlocked, total = g.Total }),
                        });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("GET", "/friends"))
                {
                    var list = await Task.Run(() => SteamProfile.AllFriends(SteamApiKey?.Invoke()));
                    var json = list is null
                        ? System.Text.Json.JsonSerializer.Serialize(new { error = SteamProfile.LastError ?? "erro" })
                        : System.Text.Json.JsonSerializer.Serialize(new
                        {
                            now = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                            friends = list.Select(f => new
                            {
                                id = f.Id, name = f.Name, avatar = f.Avatar, gameId = f.GameId, game = f.Game,
                                state = f.State, lastOff = f.LastOff, since = f.Since,
                            }),
                        });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("GET", "/friends/games") && request.Query("id") is { } friendId)
                {
                    var games = await Task.Run(() => SteamProfile.FriendRecentGames(SteamApiKey?.Invoke(), friendId));
                    var json = System.Text.Json.JsonSerializer.Serialize(new
                    {
                        games = games.Select(g => new { id = g.Id, name = g.Name, recent = g.Minutes2Weeks, total = g.MinutesTotal }),
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("GET", "/captures"))
                {
                    var names = _known;
                    var json = System.Text.Json.JsonSerializer.Serialize(new
                    {
                        captures = Captures.List().Select(c => new
                        {
                            name = c.Name, time = c.Time, gameId = c.GameId,
                            game = c.GameId != 0 && names.TryGetValue(c.GameId, out var g) ? g : "",
                        }),
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("GET", "/captures/file") && Captures.PathOf(request.Query("name")) is { } capturePath)
                {
                    await using var file = File.OpenRead(capturePath);
                    await Respond(stream, "200 OK", "image/jpeg", file.Length);
                    await file.CopyToAsync(stream);
                }
                else if (request.Is("POST", "/captures/delete"))
                {
                    await RespondText(stream, Captures.Delete(request.Query("name")) ? "ok" : "not found");
                }
                else if (request.Is("GET", "/achievements/unlocks"))
                {
                    // after=-1: just learn the PC's clock (nothing from before this TV started asking).
                    var after = long.TryParse(request.Query("after"), out var a) ? a : -1;
                    var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    var unlocks = after < 0 ? [] : SteamAchievements.UnlocksAfter(after);
                    var json = System.Text.Json.JsonSerializer.Serialize(new
                    {
                        now,
                        unlocks = unlocks.Select(u => new
                        {
                            at = u.At, appId = u.AppId, game = u.Game, id = u.Item.Id, name = u.Item.Name,
                            desc = u.Item.Description, icon = u.Item.Icon, percent = u.Item.Percent,
                            unlocked = u.Unlocked, total = u.Total,
                        }),
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if (request.Is("POST", "/library/install") && int.TryParse(request.Query("id"), out var installId) &&
                         _known.TryGetValue(installId, out var installName))
                {
                    // Steam asks for confirmation in its own window (Big Picture, on the TV).
                    await RespondText(stream, RunInSession(() =>
                    {
                        SteamLibrary.Install(installId);
                        GameLaunched?.Invoke($"a instalação de {installName}");
                    }) ? "ok" : "pending");
                }
                else
                {
                    await Respond(stream, "404 Not Found", "text/plain", 0);
                }
            }
            catch (Exception)
            {
                // A TV that went away mid-request; nothing to do.
            }
        }
    }

    /// <summary>Minimal HTTP/1.1 request: request line, headers we care about, raw body.</summary>
    sealed class Request
    {
        public string Method = "", Path = "", QueryString = "";
        public long? ContentLength;

        public bool Is(string method, string path) => Method == method && Path == path;

        public string? Query(string name)
        {
            foreach (var pair in QueryString.Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                var kv = pair.Split('=', 2);
                if (kv[0] == name) return Uri.UnescapeDataString(kv.Length > 1 ? kv[1].Replace('+', ' ') : "");
            }
            return null;
        }

        public static async Task<Request?> ReadAsync(Stream s)
        {
            // Headers byte by byte up to the blank line, so the body stays unread in the stream.
            var head = new List<byte>();
            var one = new byte[1];
            while (head.Count < 16 * 1024)
            {
                if (await s.ReadAsync(one) == 0) return null;
                head.Add(one[0]);
                if (head.Count >= 4 && head[^4] == '\r' && head[^3] == '\n' && head[^2] == '\r' && head[^1] == '\n') break;
            }
            var lines = Encoding.ASCII.GetString(head.ToArray()).Split("\r\n");
            var first = lines[0].Split(' ');
            if (first.Length < 2) return null;
            var target = first[1].Split('?', 2);
            var r = new Request { Method = first[0], Path = target[0], QueryString = target.Length > 1 ? target[1] : "" };
            foreach (var line in lines.Skip(1))
            {
                var colon = line.IndexOf(':');
                if (colon > 0 && line[..colon].Trim().Equals("Content-Length", StringComparison.OrdinalIgnoreCase) &&
                    long.TryParse(line[(colon + 1)..].Trim(), out var n))
                    r.ContentLength = n;
            }
            return r;
        }

        public async Task<byte[]> ReadBodyAsync(Stream s)
        {
            var body = new byte[ContentLength ?? 0];
            await s.ReadExactlyAsync(body);
            return body;
        }
    }

    static async Task RespondText(Stream s, string text, string type = "text/plain; charset=utf-8")
    {
        var body = Encoding.UTF8.GetBytes(text);
        await Respond(s, "200 OK", type, body.Length);
        await s.WriteAsync(body);
    }

    static Task Respond(Stream s, string status, string type, long length)
    {
        var head = $"HTTP/1.1 {status}\r\nContent-Type: {type}\r\nContent-Length: {length}\r\nConnection: close\r\n\r\n";
        return s.WriteAsync(Encoding.ASCII.GetBytes(head)).AsTask();
    }

    public void Dispose() => Stop();
}
