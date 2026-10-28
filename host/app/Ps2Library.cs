using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;

namespace Jinky;

/// <summary>
/// PS2 games without Steam: every disc image in PCSX2's game folders (its [GameList] paths) is a
/// game in Jinky's library, started straight with PCSX2. A new image dropped into the folder just
/// shows up on the TV. Covers: PCSX2's own covers folder, else the same cover collection PCSX2's
/// "Download covers" uses (xlenore/ps2-covers), by the disc's serial read from the image.
/// </summary>
public static partial class Ps2Library
{
    static readonly string[] Extensions = [".iso", ".chd", ".cso", ".zso", ".cue", ".gz"];
    const string CoverUrl = "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/{0}.jpg";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };
    static readonly Dictionary<string, string?> Serials = [];  // "path|size|time" -> serial
    static readonly HashSet<string> Downloading = [];

    static string RememberedPath => Path.Combine(Settings.Folder, "pcsx2-path.txt");

    /// <summary>
    /// PCSX2's program: the one the Steam shortcuts start (remembered, so it still works without
    /// Steam), else the usual install places.
    /// </summary>
    public static string? FindPcsx2(IEnumerable<string> shortcutExes)
    {
        var fromSteam = shortcutExes.FirstOrDefault(e => Pcsx2.IsPcsx2(e) && File.Exists(e));
        if (fromSteam is not null)
        {
            try
            {
                Directory.CreateDirectory(Settings.Folder);
                if (!File.Exists(RememberedPath) || File.ReadAllText(RememberedPath).Trim() != fromSteam)
                    File.WriteAllText(RememberedPath, fromSteam);
            }
            catch (Exception) { }
            return fromSteam;
        }
        try
        {
            if (File.Exists(RememberedPath) && File.ReadAllText(RememberedPath).Trim() is { } saved && File.Exists(saved)) return saved;
        }
        catch (Exception) { }
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        foreach (var candidate in new[]
                 {
                     Path.Combine(local, "Programs", "PCSX2", "pcsx2-qt.exe"),
                     Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "PCSX2", "pcsx2-qt.exe"),
                 })
            if (File.Exists(candidate)) return candidate;
        return null;
    }

    /// <summary>The folders PCSX2 lists games from (recursive ones flagged).</summary>
    static List<(string Folder, bool Recursive)> GameFolders(string exe)
    {
        var list = new List<(string, bool)>();
        var ini = Path.Combine(Pcsx2.DataFolder(exe), "inis", "PCSX2.ini");
        if (!File.Exists(ini)) return list;
        var inList = false;
        foreach (var raw in File.ReadLines(ini))
        {
            var line = raw.Trim();
            if (line.StartsWith('[')) { inList = line.Equals("[GameList]", StringComparison.OrdinalIgnoreCase); continue; }
            if (!inList) continue;
            var eq = line.IndexOf('=');
            if (eq < 0) continue;
            var key = line[..eq].Trim();
            var value = line[(eq + 1)..].Trim();
            if (value.Length > 0 && Directory.Exists(value) && key is "RecursivePaths" or "Paths")
                list.Add((value, key == "RecursivePaths"));
        }
        return list;
    }

    /// <summary>
    /// The PS2 games found in PCSX2's folders, except those a Steam shortcut already starts
    /// ([inShortcuts]: the shortcuts' arguments, where their disc image is named).
    /// </summary>
    public static List<SteamGame> Games(string exe, IReadOnlyCollection<string> inShortcuts)
    {
        var games = new List<SteamGame>();
        foreach (var (folder, recursive) in GameFolders(exe))
        {
            IEnumerable<string> files;
            try
            {
                files = Directory.EnumerateFiles(folder, "*", new EnumerationOptions
                {
                    RecurseSubdirectories = recursive,
                    MaxRecursionDepth = 4,
                    IgnoreInaccessible = true,
                });
            }
            catch (Exception) { continue; }
            foreach (var file in files)
            {
                if (!Extensions.Contains(Path.GetExtension(file), StringComparer.OrdinalIgnoreCase)) continue;
                if (inShortcuts.Any(a => a.Contains(file, StringComparison.OrdinalIgnoreCase))) continue;
                var id = Library.OwnId(file);
                var serial = Serial(file);
                var cover = Cover(exe, serial, CleanName(file));
                games.Add(new SteamGame(id, CleanName(file), JinkyPlaytime.LastPlayed(id), cover, cover, null,
                                        Minutes: JinkyPlaytime.Minutes(id), Platform: "ps2",
                                        Exe: exe, Args: $"\"{file}\" -batch -fullscreen -nogui", StartDir: Path.GetDirectoryName(exe) ?? ""));
            }
        }
        return games;
    }

    /// <summary>"God of War (BR) (DUB-IA) (www.site.com).iso" -> "God of War".</summary>
    public static string CleanName(string file)
    {
        var name = Path.GetFileNameWithoutExtension(file);
        name = Brackets().Replace(name, " ");
        name = Spaces().Replace(name, " ").Trim(' ', '-', '_', '.');
        return name.Length > 0 ? name : Path.GetFileNameWithoutExtension(file);
    }

    [GeneratedRegex(@"\([^)]*\)|\[[^\]]*\]")]
    private static partial Regex Brackets();

    [GeneratedRegex(@"\s{2,}")]
    private static partial Regex Spaces();

    /// <summary>The disc's serial ("SCUS-97399") from SYSTEM.CNF inside an ISO image; null otherwise.</summary>
    public static string? Serial(string file)
    {
        if (!file.EndsWith(".iso", StringComparison.OrdinalIgnoreCase)) return null;
        string key;
        try
        {
            var info = new FileInfo(file);
            key = $"{file}|{info.Length}|{info.LastWriteTimeUtc.Ticks}";
        }
        catch (Exception) { return null; }
        lock (Serials)
            if (Serials.TryGetValue(key, out var known)) return known;
        var serial = ReadSerial(file);
        lock (Serials) Serials[key] = serial;
        return serial;
    }

    /// <summary>ISO 9660: the root directory (from the primary volume descriptor), then SYSTEM.CNF's "BOOT2" line.</summary>
    static string? ReadSerial(string file)
    {
        try
        {
            using var f = File.OpenRead(file);
            var sector = new byte[2048];
            byte[] Read(long lba, int length)
            {
                var buf = new byte[length];
                f.Position = lba * 2048;
                var got = 0;
                while (got < length)
                {
                    var r = f.Read(buf, got, length - got);
                    if (r <= 0) break;
                    got += r;
                }
                return buf;
            }
            sector = Read(16, 2048);
            if (sector[0] != 1 || Encoding.ASCII.GetString(sector, 1, 5) != "CD001") return null;
            var rootLba = BitConverter.ToUInt32(sector, 156 + 2);
            var rootLen = (int)Math.Min(BitConverter.ToUInt32(sector, 156 + 10), 64 * 2048);
            var dir = Read(rootLba, rootLen);
            for (var pos = 0; pos < dir.Length;)
            {
                int len = dir[pos];
                if (len == 0) { pos = (pos / 2048 + 1) * 2048; continue; }  // records don't cross sectors
                if (pos + 33 > dir.Length) break;
                var nameLen = dir[pos + 32];
                var name = Encoding.ASCII.GetString(dir, pos + 33, Math.Min(nameLen, dir.Length - pos - 33));
                if (name.StartsWith("SYSTEM.CNF", StringComparison.OrdinalIgnoreCase))
                {
                    var lba = BitConverter.ToUInt32(dir, pos + 2);
                    var size = (int)Math.Min(BitConverter.ToUInt32(dir, pos + 10), 4096);
                    var text = Encoding.ASCII.GetString(Read(lba, size));
                    // BOOT2 = cdrom0:\SCUS_973.99;1
                    var m = Regex.Match(text, @"BOOT2\s*=\s*cdrom0:\\?([A-Z]{4})[_-](\d{3})\.(\d{2})", RegexOptions.IgnoreCase);
                    return m.Success ? $"{m.Groups[1].Value.ToUpperInvariant()}-{m.Groups[2].Value}{m.Groups[3].Value}" : null;
                }
                pos += len;
            }
        }
        catch (Exception) { }
        return null;
    }

    /// <summary>
    /// The game's cover: PCSX2's covers folder (by serial or title), else Jinky's copy downloaded
    /// once from the PS2 cover collection (in the background: the next listing has it).
    /// </summary>
    static string? Cover(string exe, string? serial, string title)
    {
        var covers = Path.Combine(Pcsx2.DataFolder(exe), "covers");
        foreach (var name in new[] { serial, title }.Where(n => !string.IsNullOrEmpty(n)))
            foreach (var ext in new[] { ".jpg", ".png", ".webp" })
                if (Path.Combine(covers, name + ext) is var p && File.Exists(p)) return p;
        if (serial is null) return null;
        var mine = Path.Combine(Settings.Folder, "art", "ps2", serial + ".jpg");
        if (File.Exists(mine)) return mine;
        var missing = mine + ".missing";
        if (File.Exists(missing) && DateTime.Now - File.GetLastWriteTime(missing) < TimeSpan.FromDays(7)) return null;
        lock (Downloading)
            if (!Downloading.Add(serial)) return null;
        _ = Task.Run(async () =>
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(mine)!);
                using var r = await Http.GetAsync(string.Format(CoverUrl, serial));
                if (r.IsSuccessStatusCode) await File.WriteAllBytesAsync(mine, await r.Content.ReadAsByteArrayAsync());
                else await File.WriteAllTextAsync(missing, "");
            }
            catch (Exception) { }
            finally
            {
                lock (Downloading) Downloading.Remove(serial);
            }
        });
        return null;
    }
}
