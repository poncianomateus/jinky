using System.Net.Http;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// The account's profile for the TV: Steam name, avatar and level (Web API), time played across
/// the library, and achievements gathered from the games on this PC (unlocked, by rarity, the most
/// recent ones, games at 100%). Cached for a couple of minutes.
/// </summary>
public static class SteamProfile
{
    public sealed record RecentUnlock(int GameId, string Game, string Id, string Name, string Icon, double Percent, long Time);
    /// <summary>A Steam friend who is online: name, avatar, and the game they play now ("" = none).</summary>
    public sealed record Friend(string Name, string Avatar, string Game, int State);
    public sealed record GameProgress(int Id, string Name, int Minutes, int Unlocked, int Total);
    public sealed record Profile(string Name, string Avatar, int Level, int Games, long Minutes,
                                 int Unlocked, int Total, int Legendary, int Epic, int Perfect,
                                 List<RecentUnlock> Recent, List<GameProgress> Played, List<Friend> Friends);

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };
    static readonly object Lock = new();
    static (DateTime At, Profile Value)? _cache;

    public static string? LastError { get; private set; }

    public static Profile? Get(string? apiKey)
    {
        lock (Lock)
            if (_cache is { } c && DateTime.UtcNow - c.At < TimeSpan.FromMinutes(2)) return c.Value;
        var steam = SteamLibrary.SteamPath();
        var me = steam is null ? null : SteamLibrary.SteamId(steam);
        if (me is null)
        {
            LastError = "nenhuma conta da Steam neste PC";
            return null;
        }
        var (name, avatar) = Summary(apiKey, me);
        var level = Level(apiKey, me);
        var games = SteamLibrary.Games(apiKey);
        // Achievements of the games on this PC (and recently played), most recent first.
        var here = games.Where(g => g.State == GameState.Installed && g.Id > 0)
                        .OrderByDescending(g => g.LastPlayed).Take(14).ToList();
        int unlocked = 0, total = 0, legendary = 0, epic = 0, perfect = 0;
        var recent = new List<RecentUnlock>();
        var played = new List<GameProgress>();
        foreach (var g in here)
        {
            var list = string.IsNullOrWhiteSpace(apiKey) ? null : SteamAchievements.Get(g.Id, apiKey);
            var mine = list?.Where(a => a.Achieved).ToList() ?? [];
            unlocked += mine.Count;
            total += list?.Count ?? 0;
            legendary += mine.Count(a => a.Percent is >= 0 and < 1);
            epic += mine.Count(a => a.Percent is >= 1 and < 5);
            if (list is { Count: > 0 } && mine.Count == list.Count) perfect++;
            recent.AddRange(mine.Select(a => new RecentUnlock(g.Id, g.Name, a.Id, a.Name, a.Icon, a.Percent, a.UnlockTime)));
            if (played.Count < 6) played.Add(new GameProgress(g.Id, g.Name, g.Minutes, mine.Count, list?.Count ?? 0));
        }
        var profile = new Profile(name, avatar, level, games.Count, games.Sum(g => (long)g.Minutes),
                                  unlocked, total, legendary, epic, perfect,
                                  recent.OrderByDescending(r => r.Time).Take(6).ToList(), played, Friends(apiKey, me));
        lock (Lock) _cache = (DateTime.UtcNow, profile);
        LastError = null;
        return profile;
    }

    static (string Name, string Avatar) Summary(string? apiKey, string steamId)
    {
        if (string.IsNullOrWhiteSpace(apiKey)) return (Environment.UserName, "");
        try
        {
            var json = Http.GetStringAsync(
                $"https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key={apiKey}&steamids={steamId}")
                .GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            var p = doc.RootElement.GetProperty("response").GetProperty("players")[0];
            return (p.GetProperty("personaname").GetString() ?? "", p.TryGetProperty("avatarfull", out var a) ? a.GetString() ?? "" : "");
        }
        catch (Exception)
        {
            return (Environment.UserName, "");
        }
    }

    /// <summary>
    /// Friends online now, the ones in a game first (at most 8). Empty when the friends list is
    /// private or there is no key: the profile just doesn't show the section.
    /// </summary>
    static List<Friend> Friends(string? apiKey, string steamId)
    {
        if (string.IsNullOrWhiteSpace(apiKey)) return [];
        try
        {
            var list = Http.GetStringAsync(
                $"https://api.steampowered.com/ISteamUser/GetFriendList/v1/?key={apiKey}&steamid={steamId}&relationship=friend")
                .GetAwaiter().GetResult();
            using var friends = JsonDocument.Parse(list);
            var ids = friends.RootElement.GetProperty("friendslist").GetProperty("friends").EnumerateArray()
                .Select(f => f.GetProperty("steamid").GetString()).OfType<string>().Take(100).ToList();
            if (ids.Count == 0) return [];
            var json = Http.GetStringAsync(
                $"https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key={apiKey}&steamids={string.Join(',', ids)}")
                .GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.GetProperty("response").GetProperty("players").EnumerateArray()
                .Select(p => new Friend(
                    p.TryGetProperty("personaname", out var n) ? n.GetString() ?? "" : "",
                    p.TryGetProperty("avatarmedium", out var a) ? a.GetString() ?? "" : "",
                    p.TryGetProperty("gameextrainfo", out var g) ? g.GetString() ?? "" : "",
                    p.TryGetProperty("personastate", out var s) ? s.GetInt32() : 0))
                .Where(f => f.State > 0 || f.Game.Length > 0)
                .OrderByDescending(f => f.Game.Length > 0).ThenBy(f => f.Name, StringComparer.CurrentCultureIgnoreCase)
                .Take(8).ToList();
        }
        catch (Exception)
        {
            return [];  // private friends list, offline...
        }
    }

    static int Level(string? apiKey, string steamId)
    {
        if (string.IsNullOrWhiteSpace(apiKey)) return 0;
        try
        {
            var json = Http.GetStringAsync(
                $"https://api.steampowered.com/IPlayerService/GetSteamLevel/v1/?key={apiKey}&steamid={steamId}")
                .GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.GetProperty("response").TryGetProperty("player_level", out var l) ? l.GetInt32() : 0;
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
