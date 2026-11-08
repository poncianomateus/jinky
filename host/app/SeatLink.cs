using System.IO;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// The Jinkys of one PC (one per Windows user / ASTER workplace) see each other through a shared
/// folder, so a workplace without a screen of its own (a "TV" workplace on a virtual monitor) can
/// be run from another one: its pairing requests show up there to be allowed, and its name and
/// console mode can be changed there. %ProgramData%\Jinky\lugares: each Jinky writes
/// (user).json with what it is doing; commands for a Jinky are files (user).cmd.(ticks).json.
/// </summary>
public static class SeatLink
{
    public sealed record PairingAsk(string Id, string Name, string Code);

    /// <summary>What a Jinky tells the others (refreshed every few seconds).</summary>
    public sealed record SeatInfo(string User, int Session, string Name, bool ConsoleMode, bool Streaming,
                                  string Status, PairingAsk? Pairing, string Version, long Seen);

    /// <summary>Something one Jinky asks another to do.</summary>
    public sealed record Command(string Kind, string? Value);

    public static string Folder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Jinky", "lugares");

    public static string Me { get; } = Environment.UserName;
    static readonly int MySession = System.Diagnostics.Process.GetCurrentProcess().SessionId;
    static long _lastCommand;

    static string Safe(string user) => string.Concat(user.Where(c => char.IsLetterOrDigit(c) || c is '-' or '_' or '.'));

    /// <summary>Writes this Jinky's state for the others. Quiet on failure (no shared folder = alone).</summary>
    public static void Publish(SeatInfo info)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var path = Path.Combine(Folder, Safe(Me) + ".json");
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(info with { User = Me, Session = MySession }));
            File.Move(tmp, path, overwrite: true);
        }
        catch (Exception)
        {
        }
    }

    /// <summary>The other Jinkys of this PC seen in the last half minute.</summary>
    public static List<SeatInfo> Others()
    {
        var list = new List<SeatInfo>();
        try
        {
            if (!Directory.Exists(Folder)) return list;
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            foreach (var file in Directory.EnumerateFiles(Folder, "*.json"))
            {
                if (file.Contains(".cmd.")) continue;
                try
                {
                    var info = JsonSerializer.Deserialize<SeatInfo>(File.ReadAllText(file));
                    if (info is null || info.User.Equals(Me, StringComparison.OrdinalIgnoreCase)) continue;
                    if (now - info.Seen > 30) continue;
                    list.Add(info);
                }
                catch (Exception)
                {
                }
            }
        }
        catch (Exception)
        {
        }
        return list.OrderBy(s => s.Session).ToList();
    }

    /// <summary>Asks [user]'s Jinky to do [command].</summary>
    public static bool Send(string user, Command command)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var path = Path.Combine(Folder, $"{Safe(user)}.cmd.{DateTime.UtcNow.Ticks}.json");
            File.WriteAllText(path, JsonSerializer.Serialize(command));
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>The commands left for this Jinky since the last look (each one once).</summary>
    public static List<Command> Take()
    {
        var list = new List<Command>();
        try
        {
            if (!Directory.Exists(Folder)) return list;
            var prefix = Safe(Me) + ".cmd.";
            var files = Directory.EnumerateFiles(Folder, prefix + "*.json")
                .Select(f => (File: f, Ticks: long.TryParse(Path.GetFileNameWithoutExtension(f)[prefix.Length..], out var t) ? t : 0))
                .Where(f => f.Ticks > _lastCommand)
                .OrderBy(f => f.Ticks)
                .ToList();
            foreach (var (file, ticks) in files)
            {
                _lastCommand = ticks;
                try
                {
                    // Older than a few minutes: from a past run, not for now.
                    if (DateTime.UtcNow.Ticks - ticks < TimeSpan.FromMinutes(3).Ticks &&
                        JsonSerializer.Deserialize<Command>(File.ReadAllText(file)) is { } c)
                        list.Add(c);
                }
                catch (Exception)
                {
                }
                try { File.Delete(file); } catch (Exception) { }
            }
        }
        catch (Exception)
        {
        }
        return list;
    }

    /// <summary>On start: commands from before are ignored (only from now on).</summary>
    public static void Start() => _lastCommand = DateTime.UtcNow.Ticks;
}
