using System.IO;
using System.Net.Http;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// Reads the game library straight from the Steam client — exactly what its Library shows: the
/// account's games and those shared by its Steam family, with their install state. Uses Steam's
/// own local developer interface (127.0.0.1:8080), which is only there when the user turned it on
/// (an empty ".cef-enable-remote-debugging" file in the Steam folder, then a Steam restart).
/// Read-only: it only evaluates a query in Steam's library page.
/// </summary>
public static class SteamClient
{
    public sealed record App(int Id, string Name, long LastPlayed, bool Installed, bool Family, long Minutes, long SizeBytes);

    const string DebugUrl = "http://127.0.0.1:8080/json";
    const string FlagFile = ".cef-enable-remote-debugging";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(3) };
    static readonly object CacheLock = new();
    static (DateTime At, List<App> Apps)? _cache;

    // Steam's own view: every game in the library it considers owned (bought, free or shared by
    // the family). owner_account_id is set only for games another family member owns.
    const string Query = """
        JSON.stringify(collectionStore.allGamesCollection.allApps
          .filter(a => a.BIsOwned() && a.appid > 0 && a.appid <= 2147483647)  // not "non-Steam game" shortcuts
          .map(a => ({ id: a.appid, name: a.display_name, last: a.rt_last_time_played || 0,
                       minutes: a.minutes_playtime_forever || 0, size: Number(a.size_on_disk || 0),
                       installed: !!(a.per_client_data && a.per_client_data[0] && a.per_client_data[0].installed),
                       family: !!a.owner_account_id })))
        """;

    /// <summary>Why the last reading failed (for the status line), else null.</summary>
    public static string? LastError { get; private set; }

    /// <summary>Whether the user turned the reading on (the flag file exists).</summary>
    public static bool Enabled(string steam) => File.Exists(Path.Combine(steam, FlagFile));

    /// <summary>Turns the reading on or off. Takes effect when Steam restarts.</summary>
    public static string? SetEnabled(string steam, bool on)
    {
        var flag = Path.Combine(steam, FlagFile);
        try
        {
            if (on && !File.Exists(flag)) File.WriteAllBytes(flag, []);
            if (!on && File.Exists(flag)) File.Delete(flag);
            return null;
        }
        catch (Exception ex)
        {
            return $"Não foi possível alterar a pasta da Steam: {ex.Message}";
        }
    }

    /// <summary>The library as Steam shows it (cached for a minute), or null if Steam can't be read.</summary>
    public static List<App>? Library()
    {
        lock (CacheLock)
        {
            if (_cache is { } c && DateTime.UtcNow - c.At < TimeSpan.FromMinutes(1)) return c.Apps;
        }
        var apps = Fetch();
        if (apps is not null) lock (CacheLock) _cache = (DateTime.UtcNow, apps);
        return apps;
    }

    static List<App>? Fetch()
    {
        try
        {
            using var pages = JsonDocument.Parse(Http.GetStringAsync(DebugUrl).GetAwaiter().GetResult());
            var ws = pages.RootElement.EnumerateArray()
                .Where(p => p.TryGetProperty("title", out var t) && t.GetString() == "SharedJSContext")
                .Select(p => p.GetProperty("webSocketDebuggerUrl").GetString())
                .FirstOrDefault();
            if (ws is null) throw new InvalidOperationException("a página da biblioteca da Steam ainda não abriu");
            var json = Evaluate(new Uri(ws), Query) ?? throw new InvalidOperationException("a Steam não respondeu à consulta da biblioteca");
            using var doc = JsonDocument.Parse(json);
            LastError = null;
            return doc.RootElement.EnumerateArray().Select(a => new App(
                a.GetProperty("id").GetInt32(),
                a.GetProperty("name").GetString() ?? "",
                a.GetProperty("last").GetInt64(),
                a.GetProperty("installed").GetBoolean(),
                a.GetProperty("family").GetBoolean(),
                a.GetProperty("minutes").GetInt64(),
                (long)a.GetProperty("size").GetDouble())).ToList();
        }
        catch (Exception ex)
        {
            // Reading off, Steam closed, or Steam changed: the caller falls back to the API.
            LastError = ex.Message;
            try
            {
                File.AppendAllText(Path.Combine(Settings.Folder, "logs", "steam-client.log"),
                                   $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {ex}{Environment.NewLine}");
            }
            catch (IOException) { }
            return null;
        }
    }

    /// <summary>Runs one expression in the page and returns its string result.</summary>
    static string? Evaluate(Uri ws, string expression)
    {
        using var socket = new ClientWebSocket();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        socket.ConnectAsync(ws, timeout.Token).GetAwaiter().GetResult();
        var request = JsonSerializer.SerializeToUtf8Bytes(new
        {
            id = 1,
            method = "Runtime.evaluate",
            @params = new { expression, returnByValue = true },
        });
        socket.SendAsync(request, WebSocketMessageType.Text, true, timeout.Token).GetAwaiter().GetResult();
        var buffer = new byte[64 * 1024];
        while (true)
        {
            using var message = new MemoryStream();
            WebSocketReceiveResult r;
            do
            {
                r = socket.ReceiveAsync(buffer, timeout.Token).GetAwaiter().GetResult();
                message.Write(buffer, 0, r.Count);
            } while (!r.EndOfMessage);
            using var doc = JsonDocument.Parse(message.ToArray());
            if (!doc.RootElement.TryGetProperty("id", out var id) || id.GetInt32() != 1) continue;  // an event
            var result = doc.RootElement.GetProperty("result");
            if (result.TryGetProperty("exceptionDetails", out _)) return null;
            return result.GetProperty("result").TryGetProperty("value", out var v) ? v.GetString() : null;
        }
    }
}
