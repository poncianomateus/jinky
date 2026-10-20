using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Jinky;

public sealed record SteamGame(int Id, string Name, long LastPlayed, string? CoverPath);

/// <summary>
/// The games installed through Steam on this PC, for the TV's Biblioteca: names and install state
/// from the library manifests, "last played" from the user's Steam config, covers from Steam's own
/// local artwork cache (nothing is downloaded).
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

    /// <summary>Installed games, most recently played first. Empty if Steam is not installed.</summary>
    public static List<SteamGame> Games()
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
                // StateFlags bit 2 (4) = fully installed; skip downloads in progress.
                if (!int.TryParse(Value(text, "StateFlags"), out var state) || (state & 4) == 0) continue;
                var name = Value(text, "name") ?? $"App {id}";
                if (name.Contains("Redistributable") || name.StartsWith("Proton") || name.StartsWith("Steam Linux Runtime")) continue;
                games[id] = new SteamGame(id, name, lastPlayed.GetValueOrDefault(id), Cover(steam, id));
            }
        }
        return games.Values.OrderByDescending(g => g.LastPlayed).ThenBy(g => g.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
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

    /// <summary>Portrait cover from Steam's cache (older and newer cache layouts), else its banner.</summary>
    static string? Cover(string steam, int id)
    {
        var dir = Path.Combine(steam, "appcache", "librarycache", id.ToString());
        var flat = Path.Combine(steam, "appcache", "librarycache", $"{id}_library_600x900.jpg");
        if (File.Exists(flat)) return flat;
        if (!Directory.Exists(dir)) return null;
        foreach (var name in new[] { "library_600x900.jpg", "library_capsule.jpg", "library_header.jpg", "header.jpg" })
        {
            var found = Directory.EnumerateFiles(dir, name, SearchOption.AllDirectories).FirstOrDefault();
            if (found is not null) return found;
        }
        return null;
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
