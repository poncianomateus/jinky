using System.IO;
using System.Text.Json;
using System.Xml.Linq;
using Microsoft.Win32;

namespace Jinky;

/// <summary>
/// Games from the other PC stores, for the universal library: Epic Games (its install manifests,
/// started through the Epic launcher, which most of its games need), GOG (DRM-free: the game's own
/// program) and Xbox / PC Game Pass (the Windows game packages, started like the Start menu does).
/// Read-only: nothing here changes a store.
/// </summary>
public static class StoreLibrary
{
    /// <summary>Every store game installed on this PC, except those a Steam shortcut already starts ([shortcutExes]).</summary>
    public static List<SteamGame> Games(IReadOnlyCollection<string> shortcutExes)
    {
        var all = new List<SteamGame>();
        try { all.AddRange(Epic()); } catch (Exception) { }
        try { all.AddRange(Gog()); } catch (Exception) { }
        try { all.AddRange(Xbox()); } catch (Exception) { }
        // A game the player also added to Steam as a shortcut stays the shortcut (no duplicate).
        return all.Where(g => g.Exe is null || !shortcutExes.Contains(g.Exe, StringComparer.OrdinalIgnoreCase))
                  .Where(g => g.TrackExe is null || !shortcutExes.Any(s => s.Equals(g.TrackExe, StringComparison.OrdinalIgnoreCase)))
                  .ToList();
    }

    static SteamGame Game(string key, string name, string platform, string? exe = null, string? args = null, string? dir = null,
                          string? uri = null, string? track = null, string? cover = null) =>
        new(Library.OwnId(platform + ":" + key), name, JinkyPlaytime.LastPlayed(Library.OwnId(platform + ":" + key)), cover, cover, null,
            Minutes: JinkyPlaytime.Minutes(Library.OwnId(platform + ":" + key)), Platform: platform,
            Exe: exe, Args: args, StartDir: dir, Uri: uri, TrackExe: track);

    /// <summary>Epic Games: C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests\*.item (JSON).</summary>
    public static IEnumerable<SteamGame> Epic(string? manifests = null)
    {
        var folder = manifests ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Epic", "EpicGamesLauncher", "Data", "Manifests");
        if (!Directory.Exists(folder)) yield break;
        foreach (var file in Directory.EnumerateFiles(folder, "*.item"))
        {
            JsonDocument doc;
            try { doc = JsonDocument.Parse(File.ReadAllText(file)); }
            catch (Exception) { continue; }
            using (doc)
            {
                var r = doc.RootElement;
                string S(string k) => r.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
                if (r.TryGetProperty("bIsIncompleteInstall", out var inc) && inc.ValueKind == JsonValueKind.True) continue;
                var name = S("DisplayName");
                var app = S("AppName");
                var location = S("InstallLocation");
                if (name.Length == 0 || app.Length == 0 || !Directory.Exists(location)) continue;
                // Not games: Unreal Engine and the like.
                if (r.TryGetProperty("AppCategories", out var cats) && cats.ValueKind == JsonValueKind.Array &&
                    !cats.EnumerateArray().Any(c => c.GetString() is "games" or "public")) continue;
                var exe = S("LaunchExecutable");
                var track = exe.Length > 0 ? Path.Combine(location, exe) : null;
                var uri = $"com.epicgames.launcher://apps/{Uri.EscapeDataString(S("CatalogNamespace"))}%3A{Uri.EscapeDataString(S("CatalogItemId"))}%3A{Uri.EscapeDataString(app)}?action=launch&silent=true";
                yield return Game(app, name, "epic", uri: uri, track: track);
            }
        }
    }

    /// <summary>GOG: HKLM\SOFTWARE\WOW6432Node\GOG.com\Games\(id) (what GOG Galaxy and the GOG installers write).</summary>
    static IEnumerable<SteamGame> Gog()
    {
        using var root = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\WOW6432Node\GOG.com\Games");
        if (root is null) yield break;
        foreach (var id in root.GetSubKeyNames())
        {
            using var k = root.OpenSubKey(id);
            if (k is null) continue;
            var name = k.GetValue("gameName") as string ?? k.GetValue("GAMENAME") as string ?? "";
            var exe = k.GetValue("exe") as string ?? k.GetValue("EXE") as string ?? "";
            if (name.Length == 0 || !File.Exists(exe)) continue;
            var args = k.GetValue("launchParam") as string ?? "";
            var dir = k.GetValue("workingDir") as string ?? k.GetValue("path") as string ?? Path.GetDirectoryName(exe) ?? "";
            yield return Game(id, name, "gog", exe: exe, args: args, dir: dir);
        }
    }

    /// <summary>
    /// Xbox / PC Game Pass: Windows packages that carry a MicrosoftGame.config (only games do),
    /// found through the user's package registry; started as shell:AppsFolder\(family)!(app).
    /// </summary>
    public static IEnumerable<SteamGame> Xbox()
    {
        using var packages = Registry.CurrentUser.OpenSubKey(
            @"Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\Repository\Packages");
        if (packages is null) yield break;
        var seen = new HashSet<string>();
        foreach (var full in packages.GetSubKeyNames())
        {
            using var k = packages.OpenSubKey(full);
            var folder = k?.GetValue("PackageRootFolder") as string;
            if (folder is null || !File.Exists(Path.Combine(folder, "MicrosoftGame.config"))) continue;
            SteamGame? game = null;
            try
            {
                var config = XDocument.Load(Path.Combine(folder, "MicrosoftGame.config")).Root!;
                var manifest = XDocument.Load(Path.Combine(folder, "AppxManifest.xml")).Root!;
                var identity = manifest.Descendants().FirstOrDefault(e => e.Name.LocalName == "Identity");
                var app = manifest.Descendants().FirstOrDefault(e => e.Name.LocalName == "Application")?.Attribute("Id")?.Value;
                var visuals = config.Descendants().FirstOrDefault(e => e.Name.LocalName == "ShellVisuals");
                var name = visuals?.Attribute("DefaultDisplayName")?.Value ?? identity?.Attribute("Name")?.Value ?? "";
                // "Name_Version_Arch_Resource_PublisherId" -> family "Name_PublisherId".
                var parts = full.Split('_');
                var family = parts.Length >= 5 ? $"{parts[0]}_{parts[^1]}" : null;
                if (family is null || app is null || name.Length == 0 || name.StartsWith("ms-resource:") || !seen.Add(family)) continue;
                var exe = config.Descendants().FirstOrDefault(e => e.Name.LocalName == "Executable")?.Attribute("Name")?.Value;
                var logo = visuals?.Attribute("Square480x480Logo")?.Value ?? visuals?.Attribute("Square150x150Logo")?.Value;
                var cover = logo is null ? null : Path.Combine(folder, logo);
                game = Game(family, name, "xbox", uri: $"shell:AppsFolder\\{family}!{app}",
                            track: exe is null ? null : Path.Combine(folder, exe), cover: cover is not null && File.Exists(cover) ? cover : null);
            }
            catch (Exception) { }
            if (game is not null) yield return game;
        }
    }
}
