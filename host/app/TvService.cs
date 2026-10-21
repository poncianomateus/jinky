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
/// POST /library/launch?id=N -> starts that game, only while a TV is streaming from this PC.
/// </summary>
public sealed class TvService : IDisposable
{
    public const int Port = 47101;
    const int MaxBody = 8 * 1024 * 1024;
    readonly TcpListener _listener = new(IPAddress.Any, Port);
    readonly CancellationTokenSource _stop = new();

    /// <summary>A TV sent its diagnostic report (TV name, saved file).</summary>
    public event Action<string, string>? DiagnosticsReceived;

    /// <summary>A TV started a game from its Biblioteca (game name).</summary>
    public event Action<string>? GameLaunched;

    /// <summary>Whether a TV is streaming right now; games can only be started from a TV then.</summary>
    public Func<bool>? Streaming { get; set; }

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
        try
        {
            _listener.Start();
        }
        catch (SocketException)
        {
            return false;  // port busy: TVs just won't see updates or the library
        }
        _ = Task.Run(AcceptLoop);
        return true;
    }

    async Task AcceptLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token);
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
                    var games = SteamLibrary.Games();
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
                            cover = g.CoverPath is not null, hero = g.HeroPath is not null, logo = g.LogoPath is not null,
                        }),
                    });
                    await RespondText(stream, json, "application/json; charset=utf-8");
                }
                else if ((request.Is("GET", "/library/art") || request.Is("GET", "/library/cover")) &&
                         int.TryParse(request.Query("id"), out var artId) &&
                         _art.TryGetValue((artId, request.Query("kind") ?? "cover"), out var artPath) && File.Exists(artPath))
                {
                    await using var file = File.OpenRead(artPath);
                    await Respond(stream, "200 OK", artPath.EndsWith(".png") ? "image/png" : "image/jpeg", file.Length);
                    await file.CopyToAsync(stream);
                }
                else if (request.Is("POST", "/library/launch") && int.TryParse(request.Query("id"), out var gameId))
                {
                    var game = SteamLibrary.Games().FirstOrDefault(g => g.Id == gameId);
                    if (game is null) await Respond(stream, "404 Not Found", "text/plain", 0);
                    else if (Streaming?.Invoke() != true) await Respond(stream, "409 Conflict", "text/plain", 0);
                    else
                    {
                        SteamLibrary.Launch(game.Id);
                        await RespondText(stream, "ok");
                        GameLaunched?.Invoke(game.Name);
                    }
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

    public void Dispose()
    {
        _stop.Cancel();
        _listener.Stop();
    }
}
