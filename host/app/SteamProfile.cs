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

    /// <summary>
    /// A friend on the Amigos page: Steam id, name, big avatar, the game they play now (id and
    /// name, 0/"" = none), Steam status (0 offline, 1 online, 2 busy, 3 away, 4 snooze, 5 trade,
    /// 6 looking to play), last time online and friends since (unix seconds, 0 = unknown).
    /// </summary>
    public sealed record FriendInfo(string Id, string Name, string Avatar, int GameId, string Game, int State, long LastOff, long Since);
    /// <summary>A game a friend played in the last two weeks.</summary>
    public sealed record FriendGame(int Id, string Name, int Minutes2Weeks, int MinutesTotal);

    static readonly Dictionary<string, (DateTime At, List<FriendGame> Games)> _friendGames = [];
    static (DateTime At, List<FriendInfo> Value)? _friends;

    /// <summary>
    /// The whole friends list (playing now first, then online, then the rest by last time online).
    /// Null with [LastError] when it can't be read (no key, private list, no internet).
    /// Cached for 30 s so moving around the page doesn't ask Steam again.
    /// </summary>
    public static List<FriendInfo>? AllFriends(string? apiKey)
    {
        lock (Lock)
            if (_friends is { } c && DateTime.UtcNow - c.At < TimeSpan.FromSeconds(30)) return c.Value;
        if (string.IsNullOrWhiteSpace(apiKey))
        {
            LastError = "falta a chave da Steam API (no app do PC, em Configurações)";
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
            using var response = Http.GetAsync(
                $"https://api.steampowered.com/ISteamUser/GetFriendList/v1/?key={apiKey}&steamid={me}&relationship=friend")
                .GetAwaiter().GetResult();
            if ((int)response.StatusCode is 401 or 403)
            {
                LastError = "a lista de amigos está privada na Steam (Perfil > Privacidade > Lista de amigos: Pública)";
                return null;
            }
            using var friends = JsonDocument.Parse(response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
            var since = new Dictionary<string, long>();
            foreach (var f in friends.RootElement.GetProperty("friendslist").GetProperty("friends").EnumerateArray())
                if (f.GetProperty("steamid").GetString() is { } id)
                    since[id] = f.TryGetProperty("friend_since", out var s) ? s.GetInt64() : 0;
            var list = new List<FriendInfo>();
            // GetPlayerSummaries takes at most 100 ids at a time.
            foreach (var chunk in since.Keys.Chunk(100))
            {
                var json = Http.GetStringAsync(
                    $"https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key={apiKey}&steamids={string.Join(',', chunk)}")
                    .GetAwaiter().GetResult();
                using var doc = JsonDocument.Parse(json);
                foreach (var p in doc.RootElement.GetProperty("response").GetProperty("players").EnumerateArray())
                {
                    var id = p.GetProperty("steamid").GetString() ?? "";
                    list.Add(new FriendInfo(id,
                        p.TryGetProperty("personaname", out var n) ? n.GetString() ?? "" : "",
                        p.TryGetProperty("avatarfull", out var a) ? a.GetString() ?? "" : "",
                        p.TryGetProperty("gameid", out var gi) && int.TryParse(gi.GetString(), out var gid) ? gid : 0,
                        p.TryGetProperty("gameextrainfo", out var g) ? g.GetString() ?? "" : "",
                        p.TryGetProperty("personastate", out var st) ? st.GetInt32() : 0,
                        p.TryGetProperty("lastlogoff", out var l) ? l.GetInt64() : 0,
                        since.GetValueOrDefault(id)));
                }
            }
            var sorted = list
                .OrderByDescending(f => f.Game.Length > 0)
                .ThenByDescending(f => f.State > 0)
                .ThenByDescending(f => f.State > 0 ? 0 : f.LastOff)
                .ThenBy(f => f.Name, StringComparer.CurrentCultureIgnoreCase)
                .ToList();
            lock (Lock) _friends = (DateTime.UtcNow, sorted);
            LastError = null;
            return sorted;
        }
        catch (Exception)
        {
            LastError = "não deu para falar com a Steam agora";
            return null;
        }
    }

    /// <summary>
    /// What a friend played in the last two weeks (empty when their game details are private).
    /// Cached for 5 minutes per friend.
    /// </summary>
    public static List<FriendGame> FriendRecentGames(string? apiKey, string steamId)
    {
        lock (Lock)
            if (_friendGames.TryGetValue(steamId, out var c) && DateTime.UtcNow - c.At < TimeSpan.FromMinutes(5)) return c.Games;
        if (string.IsNullOrWhiteSpace(apiKey) || !steamId.All(char.IsAsciiDigit)) return [];
        List<FriendGame> games;
        try
        {
            var json = Http.GetStringAsync(
                $"https://api.steampowered.com/IPlayerService/GetRecentlyPlayedGames/v1/?key={apiKey}&steamid={steamId}&count=6")
                .GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            games = doc.RootElement.GetProperty("response").TryGetProperty("games", out var list)
                ? list.EnumerateArray().Select(g => new FriendGame(
                    g.GetProperty("appid").GetInt32(),
                    g.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "",
                    g.TryGetProperty("playtime_2weeks", out var w) ? w.GetInt32() : 0,
                    g.TryGetProperty("playtime_forever", out var f) ? f.GetInt32() : 0)).ToList()
                : [];
        }
        catch (Exception)
        {
            games = [];
        }
        lock (Lock) _friendGames[steamId] = (DateTime.UtcNow, games);
        return games;
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
