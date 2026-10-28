using System.IO;
using System.Text;

namespace Jinky;

/// <summary>
/// The "non-Steam games" the user added to the Steam library (shortcuts.vdf), with the artwork set
/// for them in Steam (userdata\...\config\grid). Steam gives each a 32-bit id with the top bit set;
/// as a signed int it is negative, which is how Jinky tells them apart from Steam apps.
/// </summary>
public static class SteamShortcuts
{
    /// <summary>[Exe] (unquoted), [Args] and [StartDir]: how Steam starts it (to start it again ourselves, e.g. a PS2 game from its save state).</summary>
    public sealed record Shortcut(int Id, string Name, long LastPlayed, string? Cover, string? Hero, string? Logo,
                                  string Exe = "", string Args = "", string StartDir = "");

    /// <summary>The shortcuts of the Steam account [steamId64] (empty if none or unreadable).</summary>
    public static List<Shortcut> Read(string steam, string steamId64)
    {
        var account = long.Parse(steamId64) - 76561197960265728;
        var config = Path.Combine(steam, "userdata", account.ToString(), "config");
        var file = Path.Combine(config, "shortcuts.vdf");
        var result = new List<Shortcut>();
        if (!File.Exists(file)) return result;
        try
        {
            var root = new BinaryVdf(File.ReadAllBytes(file)).ReadMap();
            if (root.GetValueOrDefault("shortcuts") is not Dictionary<string, object> list) return result;
            foreach (var entry in list.Values.OfType<Dictionary<string, object>>())
            {
                if (Get(entry, "appid") is not int id) continue;
                var name = Get(entry, "AppName") as string ?? Get(entry, "appname") as string;
                if (string.IsNullOrWhiteSpace(name)) continue;
                var last = Get(entry, "LastPlayTime") is int t ? (uint)t : 0u;
                var grid = Path.Combine(config, "grid");
                var u = (uint)id;
                result.Add(new Shortcut(id, name, last,
                    Image(grid, $"{u}p") ?? Image(grid, $"{u}"), Image(grid, $"{u}_hero"), Image(grid, $"{u}_logo"),
                    (Get(entry, "Exe") as string ?? Get(entry, "exe") as string ?? "").Trim().Trim('"'),
                    Get(entry, "LaunchOptions") as string ?? "",
                    (Get(entry, "StartDir") as string ?? "").Trim().Trim('"')));
            }
        }
        catch (Exception)
        {
            // A file being rewritten by Steam: no shortcuts this time.
        }
        return result;
    }

    /// <summary>The steam://rungameid/ id of a shortcut: its 32-bit id in the high half, 0x02000000 in the low.</summary>
    public static ulong GameId(int id) => ((ulong)(uint)id << 32) | 0x02000000;

    static object? Get(Dictionary<string, object> map, string key) =>
        map.FirstOrDefault(kv => string.Equals(kv.Key, key, StringComparison.OrdinalIgnoreCase)).Value;

    static string? Image(string grid, string name) =>
        new[] { ".png", ".jpg", ".jpeg", ".webp" }.Select(ext => Path.Combine(grid, name + ext)).FirstOrDefault(File.Exists);

    /// <summary>Steam's binary KeyValues: type byte, NUL-terminated key, value; 0x08 ends a map.</summary>
    sealed class BinaryVdf(byte[] data)
    {
        int _pos;

        public Dictionary<string, object> ReadMap()
        {
            var map = new Dictionary<string, object>();
            while (_pos < data.Length)
            {
                var type = data[_pos++];
                if (type == 0x08) break;
                var key = ReadString();
                map[key] = type switch
                {
                    0x00 => ReadMap(),
                    0x01 => ReadString(),
                    0x02 => ReadInt32(),
                    0x07 => ReadUInt64(),
                    _ => throw new InvalidDataException($"unknown VDF type {type}"),
                };
            }
            return map;
        }

        string ReadString()
        {
            var end = Array.IndexOf(data, (byte)0, _pos);
            if (end < 0) throw new InvalidDataException("unterminated VDF string");
            var s = Encoding.UTF8.GetString(data, _pos, end - _pos);
            _pos = end + 1;
            return s;
        }

        int ReadInt32()
        {
            var v = BitConverter.ToInt32(data, _pos);
            _pos += 4;
            return v;
        }

        ulong ReadUInt64()
        {
            var v = BitConverter.ToUInt64(data, _pos);
            _pos += 8;
            return v;
        }
    }
}
