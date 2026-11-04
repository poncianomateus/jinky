using System.Diagnostics;
using System.IO;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// The TV's library: Steam's games plus the games Jinky finds and starts by itself (PS2 discs in
/// PCSX2's folders; the programs the "non-Steam game" shortcuts point to). Anything with its own
/// program starts straight from Jinky, so those games need no Steam at all; only Steam's own games
/// go through Steam.
/// </summary>
public static class Library
{
    /// <summary>Ids of Jinky's own games: far above Steam's app ids, never negative like shortcuts.</summary>
    public const int OwnIdBase = 2_000_000_000;

    public static bool IsOwn(int id) => id >= OwnIdBase;

    /// <summary>A stable id for a game file (the same every time, from its path).</summary>
    public static int OwnId(string path)
    {
        uint h = 2166136261;
        foreach (var c in path.ToLowerInvariant())
        {
            h ^= c;
            h *= 16777619;
        }
        return OwnIdBase + (int)(h % 100_000_000);
    }

    static readonly object Lock = new();
    static (DateTime At, List<SteamGame> Games)? _last;

    /// <summary>Every game, most recently played first.</summary>
    public static List<SteamGame> Games(string? apiKey = null)
    {
        var steam = SteamLibrary.Games(apiKey);
        var shortcuts = steam.Where(g => g.Id < 0 && g.Exe is { Length: > 0 }).ToList();
        var all = new List<SteamGame>(steam.Count + 8);
        foreach (var g in steam)
        {
            // Shortcuts started by Jinky: Steam no longer counts their time; Jinky does.
            if (g.Id < 0 && JinkyPlaytime.Minutes(g.Id) is var mine and > 0)
                all.Add(g with { Minutes = Math.Max(g.Minutes, mine), LastPlayed = Math.Max(g.LastPlayed, JinkyPlaytime.LastPlayed(g.Id)) });
            else all.Add(g);
        }
        if (Ps2Library.FindPcsx2(shortcuts.Select(s => s.Exe!)) is { } pcsx2)
            all.AddRange(Ps2Library.Games(pcsx2, shortcuts.Select(s => s.Args ?? "").ToList()));
        // Epic, GOG, Xbox / Game Pass.
        all.AddRange(StoreLibrary.Games(shortcuts.Select(s => s.Exe!).ToList()));
        var list = all.OrderByDescending(g => g.LastPlayed).ThenBy(g => g.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
        lock (Lock) _last = (DateTime.UtcNow, list);
        return list;
    }

    /// <summary>One game (from the last listing when recent, else a fresh one).</summary>
    public static SteamGame? Find(int id)
    {
        lock (Lock)
            if (_last is { } l && DateTime.UtcNow - l.At < TimeSpan.FromMinutes(2) && l.Games.FirstOrDefault(g => g.Id == id) is { } hit)
                return hit;
        return Games().FirstOrDefault(g => g.Id == id);
    }

    /// <summary>
    /// Starts a game: one with its own program (PS2, shortcuts) straight away, Steam's through
    /// Steam. Returns false when it couldn't.
    /// </summary>
    public static bool Launch(SteamGame g)
    {
        // Another store's game: started the way that store does it; its time counts once its
        // program shows up.
        if (g.Uri is { Length: > 0 } uri)
        {
            try
            {
                if (uri.StartsWith("shell:", StringComparison.OrdinalIgnoreCase))
                    Process.Start(new ProcessStartInfo("explorer.exe", uri) { UseShellExecute = false });
                else
                    Process.Start(new ProcessStartInfo(uri) { UseShellExecute = true });
                if (g.TrackExe is { } track) TrackWhenStarted(g.Id, track);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }
        if (g.Exe is { Length: > 0 } exe && File.Exists(exe))
        {
            try
            {
                var p = Process.Start(new ProcessStartInfo(exe, g.Args ?? "")
                {
                    UseShellExecute = false,
                    WorkingDirectory = Directory.Exists(g.StartDir) ? g.StartDir : Path.GetDirectoryName(exe) ?? "",
                });
                if (p is null) return false;
                JinkyPlaytime.Track(g.Id, p);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }
        if (g.Id > 0 && !IsOwn(g.Id))
        {
            // Steam's controller notices would wait for a click on the PC: seen already.
            LaunchWatch.PrepareSteamLaunch(g.Id);
            SteamLibrary.Launch(g.Id);
            return true;
        }
        if (g.Id < 0)
        {
            SteamLibrary.Launch(g.Id);  // a shortcut whose program moved: let Steam try
            return true;
        }
        return false;
    }

    /// <summary>Waits (up to 3 minutes) for [exe] to start, then counts its time as game [id]'s.</summary>
    static void TrackWhenStarted(int id, string exe)
    {
        var name = Path.GetFileNameWithoutExtension(exe);
        _ = Task.Run(async () =>
        {
            for (var i = 0; i < 90; i++)
            {
                await Task.Delay(2000);
                foreach (var p in Process.GetProcessesByName(name))
                {
                    string? path = null;
                    try { path = p.MainModule?.FileName; } catch (Exception) { }
                    if (path is null || path.Equals(exe, StringComparison.OrdinalIgnoreCase))
                    {
                        JinkyPlaytime.Track(id, p);
                        return;
                    }
                }
            }
        });
    }

    /// <summary>The game running now: one Jinky started, else what Steam reports (0 = none).</summary>
    public static int Running() => JinkyPlaytime.RunningId() is var mine and not 0 ? mine : SteamLibrary.RunningGame();
}

/// <summary>
/// Play time of the games Jinky starts itself (Steam counts only what it starts):
/// %LOCALAPPDATA%\Jinky\playtime-jinky.json, minutes and last played per game.
/// </summary>
public static class JinkyPlaytime
{
    sealed record Entry(int Minutes, long LastPlayed);

    static readonly object Lock = new();
    static Dictionary<int, Entry>? _map;
    static (int Id, Process P)? _running;
    static string FilePath => Path.Combine(Settings.Folder, "playtime-jinky.json");

    static Dictionary<int, Entry> Map()
    {
        if (_map is not null) return _map;
        try { _map = File.Exists(FilePath) ? JsonSerializer.Deserialize<Dictionary<int, Entry>>(File.ReadAllText(FilePath)) : null; }
        catch (Exception) { }
        return _map ??= [];
    }

    static void Save()
    {
        try { File.WriteAllText(FilePath, JsonSerializer.Serialize(_map)); }
        catch (Exception) { }
    }

    public static int Minutes(int id)
    {
        lock (Lock) return Map().TryGetValue(id, out var e) ? e.Minutes : 0;
    }

    public static long LastPlayed(int id)
    {
        lock (Lock) return Map().TryGetValue(id, out var e) ? e.LastPlayed : 0;
    }

    /// <summary>The game Jinky started that is still running (0 = none).</summary>
    public static int RunningId()
    {
        lock (Lock)
        {
            if (_running is not { } r) return 0;
            try { if (!r.P.HasExited) return r.Id; }
            catch (Exception) { }
            return 0;
        }
    }

    /// <summary>Counts [p]'s time as game [id]'s (a minute at a time, while it runs).</summary>
    public static void Track(int id, Process p)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        lock (Lock)
        {
            var map = Map();
            map[id] = new Entry(map.TryGetValue(id, out var e) ? e.Minutes : 0, now);
            Save();
            _running = (id, p);
        }
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try
                {
                    if (await Task.WhenAny(p.WaitForExitAsync(), Task.Delay(TimeSpan.FromMinutes(1))) is var done && p.HasExited) break;
                }
                catch (Exception) { break; }
                lock (Lock)
                {
                    var map = Map();
                    var e = map.TryGetValue(id, out var old) ? old : new Entry(0, 0);
                    map[id] = new Entry(e.Minutes + 1, DateTimeOffset.UtcNow.ToUnixTimeSeconds());
                    Save();
                }
            }
        });
    }
}
