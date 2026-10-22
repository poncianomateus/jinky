using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Jinky;

public enum GameState { Installed, Downloading, NotInstalled }

public sealed record SteamGame(int Id, string Name, long LastPlayed, string? CoverPath, string? HeroPath, string? LogoPath,
                               GameState State = GameState.Installed, double Progress = 1, bool Family = false);

/// <summary>
/// The games in Steam on this PC, for the TV's Biblioteca: names and install state from the
/// library manifests (with download progress), "last played" from the user's Steam config,
/// artwork from Steam's own local cache. With a Steam Web API key, also the games the account owns
/// but hasn't installed, and those of the other members of its Steam family (shared library).
/// </summary>
public static partial class SteamLibrary
{
    // Steam tools that are not games.
    static readonly HashSet<int> NotGames = [228980, 1070560, 1391110, 1493710, 1628350, 250820, 1826330];

    public static string? SteamPath()
    {
        var p = Registry.GetValue(@"HKEY_CURRENT_USER\Software\Valve\Steam", "SteamPath", null) as string;
        return p is not null && Directory.Exists(p) ? Path.GetFullPath(p) : null;
    }

    /// <summary>
    /// Installed and downloading games (most recently played first), then the games to install —
    /// as the Steam client lists them, or with an API key from the Web API (alphabetical). Empty if
    /// Steam is not installed.
    /// </summary>
    public static List<SteamGame> Games(string? apiKey = null)
    {
        var steam = SteamPath();
        if (steam is null) return [];
        var lastPlayed = LastPlayed(steam);
        var games = new Dictionary<int, SteamGame>();
        foreach (var library in LibraryFolders(steam))
        {
            var apps = Path.Combine(library, "steamapps");
            if (!Directory.Exists(apps)) continue;
            foreach (var manifest in Directory.EnumerateFiles(apps, "appmanifest_*.acf"))
            {
                string text;
                try { text = File.ReadAllText(manifest, Encoding.UTF8); }
                catch (IOException) { continue; }
                if (!int.TryParse(Value(text, "appid"), out var id) || NotGames.Contains(id)) continue;
                if (!int.TryParse(Value(text, "StateFlags"), out var state)) continue;
                var name = Value(text, "name") ?? $"App {id}";
                if (!IsGame(id, name)) continue;
                // StateFlags bit 2 (4) = fully installed; without it the game is still downloading.
                var installed = (state & 4) != 0;
                double progress = 1;
                if (!installed && long.TryParse(Value(text, "BytesToDownload"), out var total) && total > 0 &&
                    long.TryParse(Value(text, "BytesDownloaded"), out var done))
                    progress = Math.Clamp((double)done / total, 0, 1);
                else if (!installed) progress = 0;
                games[id] = new SteamGame(id, name, lastPlayed.GetValueOrDefault(id), Cover(steam, id),
                                          Art(steam, id, "library_hero.jpg"), Art(steam, id, "logo.png"),
                                          installed ? GameState.Installed : GameState.Downloading, progress);
            }
        }
        var list = games.Values.OrderByDescending(g => g.LastPlayed).ThenBy(g => g.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
        // Games to install: straight from the Steam client when its reading is on (exactly its
        // Library), else from the Web API with a key.
        var client = SteamClient.Library();
        IEnumerable<(int Id, string Name, long Last, bool Family)>? toInstall =
            client?.Where(a => !a.Installed).Select(a => (a.Id, a.Name, a.LastPlayed, a.Family));
        if (toInstall is null && !string.IsNullOrWhiteSpace(apiKey)) toInstall = Owned(apiKey!, steam);
        if (toInstall is not null)
        {
            // The account's own games first, then the family's (alphabetical within each).
            foreach (var (id, name, last, family) in toInstall
                         .OrderBy(o => o.Family).ThenBy(o => o.Name, StringComparer.CurrentCultureIgnoreCase))
            {
                if (games.ContainsKey(id) || !IsGame(id, name)) continue;
                games[id] = new SteamGame(id, name, lastPlayed.GetValueOrDefault(id, last), Cover(steam, id),
                                          Art(steam, id, "library_hero.jpg"), Art(steam, id, "logo.png"), GameState.NotInstalled, 0, family);
                list.Add(games[id]);
            }
        }
        return list;
    }

    static bool IsGame(int id, string name) =>
        !NotGames.Contains(id) && !name.Contains("Redistributable") && !name.StartsWith("Proton") &&
        !name.StartsWith("Steam Linux Runtime") && !name.StartsWith("Steamworks");

    /// <summary>Asks Steam to install a game (Steam shows its own confirmation).</summary>
    public static void Install(int id) =>
        Process.Start(new ProcessStartInfo($"steam://install/{id}") { UseShellExecute = true });

    // --- the account's games (Steam Web API) ---

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };
    static readonly object OwnedLock = new();
    static (string Key, DateTime At, List<(int Id, string Name, long Last, bool Family)> Games)? _owned;
    const long SteamId64Base = 76561197960265728;

    /// <summary>The Steam account most recently signed in on this PC (SteamID64).</summary>
    public static string? SteamId(string steam)
    {
        var file = Path.Combine(steam, "config", "loginusers.vdf");
        if (!File.Exists(file)) return null;
        var text = File.ReadAllText(file, Encoding.UTF8);
        var recent = Regex.Match(text, "\"(\\d{17})\"\\s*\\{[^}]*?\"MostRecent\"\\s*\"1\"", RegexOptions.IgnoreCase);
        if (recent.Success) return recent.Groups[1].Value;
        var any = Regex.Match(text, "\"(\\d{17})\"\\s*\\{");
        return any.Success ? any.Groups[1].Value : null;
    }

    /// <summary>Account ids of the other members of this account's Steam family (from Steam's own config).</summary>
    static List<long> FamilyMembers(string steam, string mySteamId)
    {
        var result = new List<long>();
        var me = long.Parse(mySteamId) - SteamId64Base;
        var config = Path.Combine(steam, "userdata", me.ToString(), "config", "localconfig.vdf");
        if (!File.Exists(config)) return result;
        var text = File.ReadAllText(config, Encoding.UTF8);
        var at = text.IndexOf("\"FamilyGroup\"", StringComparison.Ordinal);
        if (at < 0) return result;
        // The block runs to its closing brace; members are its "accountid" entries.
        int depth = 0, end = at;
        for (var i = text.IndexOf('{', at); i >= 0 && i < text.Length; i++)
        {
            if (text[i] == '{') depth++;
            else if (text[i] == '}' && --depth == 0) { end = i; break; }
        }
        foreach (Match m in Regex.Matches(text[at..end], "\"accountid\"\\s+\"(\\d+)\""))
            if (long.TryParse(m.Groups[1].Value, out var id) && id != me && !result.Contains(id)) result.Add(id);
        return result;
    }

    /// <summary>The account's games and its family's (cached for 10 minutes). Empty on any error.</summary>
    static List<(int Id, string Name, long Last, bool Family)> Owned(string apiKey, string steam)
    {
        lock (OwnedLock)
        {
            if (_owned is { } c && c.Key == apiKey && DateTime.UtcNow - c.At < TimeSpan.FromMinutes(10)) return c.Games;
        }
        var (games, _) = FetchOwned(apiKey, steam);
        lock (OwnedLock) _owned = (apiKey, DateTime.UtcNow, games ?? []);
        return games ?? [];
    }

    /// <summary>Checks a key: the number of games on the account, or an error to show.</summary>
    public static (int Count, string? Error) CheckKey(string apiKey)
    {
        var steam = SteamPath();
        if (steam is null) return (0, "A Steam não está instalada neste PC.");
        var (games, error) = FetchOwned(apiKey, steam);
        if (games is not null) lock (OwnedLock) _owned = (apiKey, DateTime.UtcNow, games);
        return (games?.Count ?? 0, error);
    }

    static (List<(int, string, long, bool)>? Games, string? Error) FetchOwned(string apiKey, string steam)
    {
        var id = SteamId(steam);
        if (id is null) return (null, "Entre na sua conta na Steam deste PC.");
        try
        {
            var mine = OwnedBy(apiKey, id, out var refused);
            if (refused) return (null, "A Steam recusou a chave. Confira se ela foi copiada inteira.");
            var list = mine.Select(g => (g.Id, g.Name, g.Last, false)).ToList();
            // Family members' games are shared with this account (Steam Families). A member whose
            // game list is private simply adds nothing.
            var seen = list.Select(g => g.Item1).ToHashSet();
            foreach (var member in FamilyMembers(steam, id))
                foreach (var g in OwnedBy(apiKey, (member + SteamId64Base).ToString(), out _))
                    if (seen.Add(g.Id)) list.Add((g.Id, g.Name, 0, true));
            return (list, null);
        }
        catch (Exception ex)
        {
            return (null, $"Não foi possível falar com a Steam: {ex.Message}");
        }
    }

    static List<(int Id, string Name, long Last)> OwnedBy(string apiKey, string steamId, out bool refused)
    {
        var url = $"https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key={Uri.EscapeDataString(apiKey.Trim())}" +
                  $"&steamid={steamId}&include_appinfo=1&include_played_free_games=1&format=json";
        using var response = Http.GetAsync(url).GetAwaiter().GetResult();
        refused = response.StatusCode is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized;
        if (!response.IsSuccessStatusCode) return [];
        using var doc = JsonDocument.Parse(response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
        var list = new List<(int, string, long)>();
        if (doc.RootElement.GetProperty("response").TryGetProperty("games", out var games))
            foreach (var g in games.EnumerateArray())
                list.Add((g.GetProperty("appid").GetInt32(),
                          g.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "",
                          g.TryGetProperty("rtime_last_played", out var t) ? t.GetInt64() : 0));
        return list;
    }

    // --- artwork for games without a local copy ---

    /// <summary>Downloads a game's artwork from Steam's image servers into Jinky's folder (once).</summary>
    public static string? DownloadArt(int id, string kind)
    {
        var file = kind switch { "hero" => "library_hero.jpg", "logo" => "logo.png", _ => "library_600x900.jpg" };
        var folder = Path.Combine(Settings.Folder, "art");
        var path = Path.Combine(folder, $"{id}_{file}");
        var missing = path + ".missing";
        if (File.Exists(path)) return path;
        if (File.Exists(missing) && DateTime.UtcNow - File.GetLastWriteTimeUtc(missing) < TimeSpan.FromDays(7)) return null;
        Directory.CreateDirectory(folder);
        foreach (var url in new[]
                 {
                     $"https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/{id}/{file}",
                     $"https://cdn.akamai.steamstatic.com/steam/apps/{id}/{file}",
                 })
        {
            try
            {
                using var response = Http.GetAsync(url).GetAwaiter().GetResult();
                if (!response.IsSuccessStatusCode) continue;
                File.WriteAllBytes(path, response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult());
                return path;
            }
            catch (Exception)
            {
                // try the next server
            }
        }
        File.WriteAllText(missing, "");
        return null;
    }

    /// <summary>Starts a game the way the Steam library does (inside Big Picture when it is open).</summary>
    public static void Launch(int id) =>
        Process.Start(new ProcessStartInfo($"steam://rungameid/{id}") { UseShellExecute = true });

    static IEnumerable<string> LibraryFolders(string steam)
    {
        yield return steam;
        var vdf = Path.Combine(steam, "steamapps", "libraryfolders.vdf");
        if (!File.Exists(vdf)) yield break;
        foreach (Match m in PathEntry().Matches(File.ReadAllText(vdf, Encoding.UTF8)))
        {
            var p = m.Groups[1].Value.Replace(@"\\", @"\");
            if (!string.Equals(Path.GetFullPath(p).TrimEnd('\\'), steam.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                yield return p;
        }
    }

    /// <summary>appid -> unix time last played, from the most recently used Steam account.</summary>
    static Dictionary<int, long> LastPlayed(string steam)
    {
        var result = new Dictionary<int, long>();
        try
        {
            var config = Directory.EnumerateDirectories(Path.Combine(steam, "userdata"))
                .Select(d => Path.Combine(d, "config", "localconfig.vdf"))
                .Where(File.Exists)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (config is null) return result;
            foreach (Match m in AppLastPlayed().Matches(File.ReadAllText(config, Encoding.UTF8)))
                if (int.TryParse(m.Groups[1].Value, out var id) && long.TryParse(m.Groups[2].Value, out var t))
                    result[id] = Math.Max(t, result.GetValueOrDefault(id));
        }
        catch (Exception)
        {
            // No Steam account data: the list is just alphabetical.
        }
        return result;
    }

    /// <summary>Portrait cover from Steam's cache, else its banner.</summary>
    static string? Cover(string steam, int id) =>
        Art(steam, id, "library_600x900.jpg") ?? Art(steam, id, "library_capsule.jpg") ??
        Art(steam, id, "library_header.jpg") ?? Art(steam, id, "header.jpg");

    /// <summary>One of a game's artwork files in Steam's cache (older flat and newer per-game layouts).</summary>
    static string? Art(string steam, int id, string name)
    {
        var cache = Path.Combine(steam, "appcache", "librarycache");
        var flat = Path.Combine(cache, $"{id}_{name}");
        if (File.Exists(flat)) return flat;
        var dir = Path.Combine(cache, id.ToString());
        return Directory.Exists(dir) ? Directory.EnumerateFiles(dir, name, SearchOption.AllDirectories).FirstOrDefault() : null;
    }

    static string? Value(string vdf, string key)
    {
        var m = Regex.Match(vdf, $"\"{Regex.Escape(key)}\"\\s+\"([^\"]*)\"", RegexOptions.IgnoreCase);
        return m.Success ? m.Groups[1].Value : null;
    }

    [GeneratedRegex("\"path\"\\s+\"([^\"]+)\"")]
    private static partial Regex PathEntry();

    // "730" { ... "LastPlayed" "1700000000" ... } (no nested braces before LastPlayed)
    [GeneratedRegex("\"(\\d+)\"\\s*\\{[^{}]*?\"LastPlayed\"\\s+\"(\\d+)\"", RegexOptions.IgnoreCase)]
    private static partial Regex AppLastPlayed();
}
