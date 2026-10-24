using System.IO;
using System.Text;

namespace Jinky;

/// <summary>
/// The account's achievements as Steam keeps them on this PC (Steam\appcache\stats), written the
/// moment an achievement unlocks. The Web API takes minutes to show a new unlock (measured: still
/// "0 unlocked" 3 min after Resident Evil 7 gave one), too late for the notice on the TV, and it
/// hides progress of private profiles; this file has neither problem for games played here.
/// The Web API still gives the Portuguese names, icons and rarity.
/// </summary>
public static class SteamLocalStats
{
    /// <summary>An achievement as the local schema describes it.</summary>
    public sealed record Entry(string Id, string Name, string Description, bool Hidden);

    /// <summary>
    /// Unlocked achievements of [appId] for the PC's Steam account (API name -> unix unlock time),
    /// or null if Steam has no local data for that game.
    /// </summary>
    public static Dictionary<string, long>? Unlocked(int appId)
    {
        var (schemaFile, userFile) = Files(appId);
        if (schemaFile is null || userFile is null || !File.Exists(schemaFile) || !File.Exists(userFile)) return null;
        try
        {
            var bits = Bits(appId, schemaFile);
            var cache = BinaryVdf.Parse(File.ReadAllBytes(userFile)).Map("cache");
            if (cache is null) return null;
            var unlocked = new Dictionary<string, long>();
            foreach (var (statId, value) in cache)
            {
                if (value is not Dictionary<string, object> stat || stat.GetValueOrDefault("data") is not int data) continue;
                var times = stat.Map("AchievementTimes");
                for (var bit = 0; bit < 32; bit++)
                {
                    if ((data & (1 << bit)) == 0 || !bits.TryGetValue((statId, bit), out var entry)) continue;
                    unlocked[entry.Id] = times?.GetValueOrDefault(bit.ToString()) is int t ? (uint)t : 0;
                }
            }
            return unlocked;
        }
        catch (Exception)
        {
            return null;  // being written right now, or a format this reader doesn't know
        }
    }

    /// <summary>When Steam last wrote the account's stats for [appId] (cheap: no parsing).</summary>
    public static DateTime? LastWrite(int appId)
    {
        var (_, userFile) = Files(appId);
        return userFile is not null && File.Exists(userFile) ? File.GetLastWriteTimeUtc(userFile) : null;
    }

    /// <summary>All of [appId]'s achievements from the local schema (fallback names, no icons).</summary>
    public static List<Entry> Schema(int appId)
    {
        var (schemaFile, _) = Files(appId);
        if (schemaFile is null || !File.Exists(schemaFile)) return [];
        try { return Bits(appId, schemaFile).Values.ToList(); }
        catch (Exception) { return []; }
    }

    static (string? Schema, string? User) Files(int appId)
    {
        var steam = SteamLibrary.SteamPath();
        var me = steam is null ? null : SteamLibrary.SteamId(steam);
        if (steam is null || me is null || !long.TryParse(me, out var id64)) return (null, null);
        var folder = Path.Combine(steam, "appcache", "stats");
        return (Path.Combine(folder, $"UserGameStatsSchema_{appId}.bin"),
                Path.Combine(folder, $"UserGameStats_{id64 - 76561197960265728}_{appId}.bin"));
    }

    // (stat id, bit) -> achievement, per game; the schema only changes with game updates.
    static readonly Dictionary<int, (DateTime Stamp, Dictionary<(string, int), Entry> Bits)> SchemaCache = new();

    static Dictionary<(string, int), Entry> Bits(int appId, string schemaFile)
    {
        var stamp = File.GetLastWriteTimeUtc(schemaFile);
        lock (SchemaCache)
            if (SchemaCache.TryGetValue(appId, out var c) && c.Stamp == stamp) return c.Bits;
        var bits = new Dictionary<(string, int), Entry>();
        var stats = BinaryVdf.Parse(File.ReadAllBytes(schemaFile)).Map(appId.ToString())?.Map("stats");
        foreach (var (statId, value) in stats ?? new Dictionary<string, object>())
        {
            if (value is not Dictionary<string, object> stat) continue;
            foreach (var (key, b) in stat.Map("bits") ?? new Dictionary<string, object>())
            {
                if (b is not Dictionary<string, object> bit || bit.GetValueOrDefault("name") is not string name) continue;
                var index = bit.GetValueOrDefault("bit") is int n ? n : int.TryParse(key, out var k) ? k : -1;
                if (index < 0) continue;
                var display = bit.Map("display");
                bits[(statId, index)] = new Entry(name, Text(display?.Map("name")) ?? name, Text(display?.Map("desc")) ?? "",
                                                  display?.GetValueOrDefault("hidden") is "1" or 1);
            }
        }
        lock (SchemaCache) SchemaCache[appId] = (stamp, bits);
        return bits;
    }

    static string? Text(Dictionary<string, object>? byLanguage) =>
        byLanguage?.GetValueOrDefault("brazilian") as string ?? byLanguage?.GetValueOrDefault("english") as string;

    static Dictionary<string, object>? Map(this Dictionary<string, object> map, string key) =>
        map.GetValueOrDefault(key) as Dictionary<string, object>;
}

/// <summary>Steam's binary KeyValues ("binary VDF") as nested dictionaries.</summary>
static class BinaryVdf
{
    public static Dictionary<string, object> Parse(byte[] data)
    {
        var i = 0;
        return ReadMap(data, ref i);
    }

    static Dictionary<string, object> ReadMap(byte[] b, ref int i)
    {
        var map = new Dictionary<string, object>();
        while (i < b.Length)
        {
            var type = b[i++];
            if (type is 0x08 or 0x0B) return map;  // end of this map
            var key = ReadString(b, ref i);
            switch (type)
            {
                case 0x00: map[key] = ReadMap(b, ref i); break;
                case 0x01: map[key] = ReadString(b, ref i); break;
                case 0x02 or 0x04 or 0x06: map[key] = BitConverter.ToInt32(b, i); i += 4; break;
                case 0x03: map[key] = BitConverter.ToSingle(b, i); i += 4; break;
                case 0x07 or 0x0A: map[key] = BitConverter.ToInt64(b, i); i += 8; break;
                case 0x05: map[key] = ReadWide(b, ref i); break;
                default: throw new InvalidDataException($"binary VDF type 0x{type:X2}");
            }
        }
        return map;
    }

    static string ReadString(byte[] b, ref int i)
    {
        var end = Array.IndexOf(b, (byte)0, i);
        if (end < 0) throw new InvalidDataException("unterminated string");
        var s = Encoding.UTF8.GetString(b, i, end - i);
        i = end + 1;
        return s;
    }

    static string ReadWide(byte[] b, ref int i)
    {
        var start = i;
        while (i + 1 < b.Length && (b[i] != 0 || b[i + 1] != 0)) i += 2;
        var s = Encoding.Unicode.GetString(b, start, i - start);
        i += 2;
        return s;
    }
}
