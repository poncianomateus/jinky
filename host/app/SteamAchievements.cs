using System.IO;
using System.Net.Http;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// A game's Steam achievements for the TV: names and descriptions (in Portuguese), icons, whether
/// this account unlocked each one and when, and how many players worldwide have it (rarity). From
/// the Steam Web API with the user's key. The list itself is cached on disk for a day, the global
/// percentages for a few hours, the account's progress for a few seconds.
/// </summary>
public static class SteamAchievements
{
    public sealed record Item(string Id, string Name, string Description, string Icon, string IconGray, bool Hidden,
                              bool Achieved, long UnlockTime, double Percent);

    public sealed record Unlock(long At, int AppId, string Game, Item Item, int Unlocked, int Total);

    /// <summary>The list, and whether Steam hid this account's progress (private profile or game).</summary>
    public sealed record Result(List<Item> Items, bool ProgressHidden);

    sealed class ProgressHiddenException() : Exception("progresso privado na Steam");

    sealed record SchemaEntry(string Id, string Name, string Description, string Icon, string IconGray, bool Hidden);

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(12) };
    static readonly object Lock = new();
    static readonly Dictionary<int, (DateTime At, Dictionary<string, double> Percent)> Globals = new();
    static readonly Dictionary<int, (DateTime At, Dictionary<string, (bool Achieved, long Time)> Mine)> Players = new();
    static string CacheDir => Path.Combine(Settings.Folder, "achievements");

    /// <summary>Why the last reading failed (no key, private profile...), else null.</summary>
    public static string? LastError { get; private set; }

    /// <summary>
    /// The game's achievements (empty if it has none), or null if they can't be read. [fresh] skips
    /// the short cache of the account's progress (the in-game watcher).
    /// </summary>
    public static List<Item>? Get(int appId, string? apiKey, bool fresh = false) => Read(appId, apiKey, fresh)?.Items;

    /// <summary>Like Get, also telling whether the account's progress was hidden by Steam.</summary>
    public static Result? Read(int appId, string? apiKey, bool fresh = false)
    {
        if (appId <= 0) return new([], false);  // non-Steam games have no achievements
        if (string.IsNullOrWhiteSpace(apiKey))
        {
            LastError = "sem chave da API da Steam no Jinky do PC";
            return null;
        }
        var steam = SteamLibrary.SteamPath();
        var me = steam is null ? null : SteamLibrary.SteamId(steam);
        if (me is null)
        {
            LastError = "nenhuma conta da Steam neste PC";
            return null;
        }
        try
        {
            var schema = Schema(appId, apiKey);
            if (schema.Count == 0) return new([], false);
            var percent = Percentages(appId);
            // A private profile or game (or one shared by the family) hides the progress, not the list.
            var hidden = false;
            Dictionary<string, (bool, long)> mine;
            try { mine = new(Mine(appId, apiKey, me, fresh)); }
            catch (ProgressHiddenException) { mine = []; hidden = true; }
            // Steam's own copy on this PC: up to date the moment an achievement unlocks (the Web
            // API lags by minutes) and readable even when the profile is private.
            if (SteamLocalStats.Unlocked(appId) is { } local)
            {
                foreach (var (id, time) in local) mine[id] = (true, time);
                hidden = false;
            }
            LastError = null;
            return new(schema.Select(s =>
            {
                var (achieved, time) = mine.GetValueOrDefault(s.Id);
                return new Item(s.Id, s.Name, s.Description, s.Icon, s.IconGray, s.Hidden, achieved, time,
                                percent.GetValueOrDefault(s.Id, -1));
            }).ToList(), hidden);
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            return null;
        }
    }

    static List<SchemaEntry> Schema(int appId, string apiKey)
    {
        var file = Path.Combine(CacheDir, $"{appId}-schema.json");
        try
        {
            if (File.Exists(file) && DateTime.UtcNow - File.GetLastWriteTimeUtc(file) < TimeSpan.FromDays(1))
                return JsonSerializer.Deserialize<List<SchemaEntry>>(File.ReadAllText(file)) ?? [];
        }
        catch (Exception)
        {
            // Unreadable cache: fetch again.
        }
        var json = Http.GetStringAsync(
            $"https://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key={apiKey}&appid={appId}&l=brazilian")
            .GetAwaiter().GetResult();
        using var doc = JsonDocument.Parse(json);
        var list = new List<SchemaEntry>();
        if (doc.RootElement.TryGetProperty("game", out var game) &&
            game.TryGetProperty("availableGameStats", out var stats) &&
            stats.TryGetProperty("achievements", out var items))
        {
            foreach (var a in items.EnumerateArray())
                list.Add(new SchemaEntry(Str(a, "name"), Str(a, "displayName"), Str(a, "description"), Str(a, "icon"),
                                         Str(a, "icongray"), a.TryGetProperty("hidden", out var h) && h.ValueKind == JsonValueKind.Number && h.GetInt32() == 1));
        }
        Directory.CreateDirectory(CacheDir);
        File.WriteAllText(file, JsonSerializer.Serialize(list));
        return list;
    }

    static Dictionary<string, double> Percentages(int appId)
    {
        lock (Lock)
            if (Globals.TryGetValue(appId, out var c) && DateTime.UtcNow - c.At < TimeSpan.FromHours(6)) return c.Percent;
        var result = new Dictionary<string, double>();
        try
        {
            var json = Http.GetStringAsync(
                $"https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/?gameid={appId}")
                .GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            foreach (var a in doc.RootElement.GetProperty("achievementpercentages").GetProperty("achievements").EnumerateArray())
            {
                var p = a.GetProperty("percent");
                var value = p.ValueKind == JsonValueKind.Number ? p.GetDouble()
                    : double.TryParse(p.GetString(), System.Globalization.NumberStyles.Float,
                                      System.Globalization.CultureInfo.InvariantCulture, out var d) ? d : -1;
                result[Str(a, "name")] = value;
            }
        }
        catch (Exception)
        {
            // Rarity is a nicety: without it the list still shows.
        }
        lock (Lock) Globals[appId] = (DateTime.UtcNow, result);
        return result;
    }

    static Dictionary<string, (bool, long)> Mine(int appId, string apiKey, string steamId, bool fresh)
    {
        lock (Lock)
            if (!fresh && Players.TryGetValue(appId, out var c) && DateTime.UtcNow - c.At < TimeSpan.FromSeconds(20)) return c.Mine;
        using var response = Http.GetAsync(
            $"https://api.steampowered.com/ISteamUserStats/GetPlayerAchievements/v1/?key={apiKey}&steamid={steamId}&appid={appId}")
            .GetAwaiter().GetResult();
        if (response.StatusCode == System.Net.HttpStatusCode.Forbidden) throw new ProgressHiddenException();
        response.EnsureSuccessStatusCode();
        var json = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
        using var doc = JsonDocument.Parse(json);
        var stats = doc.RootElement.GetProperty("playerstats");
        if (stats.TryGetProperty("success", out var ok) && ok.ValueKind == JsonValueKind.False)
            throw new InvalidOperationException(Str(stats, "error") is { Length: > 0 } e ? e : "a Steam não mostrou o progresso");
        var result = new Dictionary<string, (bool, long)>();
        if (stats.TryGetProperty("achievements", out var items))
            foreach (var a in items.EnumerateArray())
                result[Str(a, "apiname")] = (a.GetProperty("achieved").GetInt32() == 1,
                                             a.TryGetProperty("unlocktime", out var t) ? t.GetInt64() : 0);
        lock (Lock) Players[appId] = (DateTime.UtcNow, result);
        return result;
    }

    static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    // --- unlocks while playing on a TV ---

    static readonly List<Unlock> Recent = [];
    static readonly Dictionary<int, HashSet<string>> Seen = new();

    static readonly Dictionary<int, DateTime> LocalStamp = new();
    static DateTime _lastWebWatch;

    /// <summary>
    /// Checks the game running on the PC for newly unlocked achievements (call every couple of
    /// seconds while a TV plays). The first look at a game only learns what was already unlocked.
    /// Steam's local stats file shows an unlock at once; without it, the Web API (every 10 s, and
    /// it lags) is the fallback.
    /// </summary>
    public static void Watch(string? apiKey, Func<int, string> gameName)
    {
        var app = SteamLibrary.RunningGame();
        if (app <= 0) return;
        HashSet<string> unlocked;
        List<Item> list;
        if (SteamLocalStats.LastWrite(app) is { } stamp)
        {
            lock (Lock)
                if (LocalStamp.TryGetValue(app, out var seen) && seen == stamp && Seen.ContainsKey(app)) return;
            if (SteamLocalStats.Unlocked(app) is not { } local) return;  // being written: next round
            lock (Lock) LocalStamp[app] = stamp;
            unlocked = local.Keys.ToHashSet();
            // Names, icons and rarity from the Web API when there is a key, else the local schema.
            list = (string.IsNullOrWhiteSpace(apiKey) ? null : Get(app, apiKey))
                   ?? SteamLocalStats.Schema(app).Select(e => new Item(e.Id, e.Name, e.Description, "", "", e.Hidden,
                                                                     local.ContainsKey(e.Id), local.GetValueOrDefault(e.Id), -1)).ToList();
            if (list.Count == 0) return;
        }
        else
        {
            if (string.IsNullOrWhiteSpace(apiKey) || DateTime.UtcNow - _lastWebWatch < TimeSpan.FromSeconds(10)) return;
            _lastWebWatch = DateTime.UtcNow;
            var web = Get(app, apiKey, fresh: true);
            if (web is null || web.Count == 0) return;
            list = web;
            unlocked = list.Where(a => a.Achieved).Select(a => a.Id).ToHashSet();
        }
        lock (Lock)
        {
            if (Seen.TryGetValue(app, out var before))
            {
                var count = unlocked.Count;
                foreach (var a in list.Where(a => unlocked.Contains(a.Id) && !before.Contains(a.Id)))
                    Recent.Add(new Unlock(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), app, gameName(app), a, count, list.Count));
                if (Recent.Count > 50) Recent.RemoveRange(0, Recent.Count - 50);
            }
            Seen[app] = unlocked;
        }
    }

    /// <summary>Unlocks noticed after [afterMs] (unix milliseconds).</summary>
    public static List<Unlock> UnlocksAfter(long afterMs)
    {
        lock (Lock) return Recent.Where(u => u.At > afterMs).ToList();
    }
}
