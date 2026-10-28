using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace Jinky;

/// <summary>User preferences, stored in %LOCALAPPDATA%\Jinky\settings.json.</summary>
public sealed class Settings
{
    /// <summary>Big Picture + 60 Hz + sound on the TV while a TV is connected.</summary>
    public bool ConsoleMode { get; set; } = true;

    /// <summary>Big Picture stays open between games (games start sooner); it closes with Jinky.</summary>
    public bool KeepBigPicture { get; set; }

    /// <summary>A paired TV may put this PC to sleep or shut it down ("Suspender/Desligar o PC" on the TV).</summary>
    public bool AllowTvPower { get; set; } = true;

    /// <summary>"Jogar fora de casa (Tailscale)": TVs may reach this PC over Tailscale (100.64.0.0/10).</summary>
    public bool RemotePlay { get; set; }

    /// <summary>Games from the TV start inside Steam Big Picture, the way before 1.0 (off: they start straight away).</summary>
    public bool GamesInBigPicture { get; set; }

    /// <summary>"Monitor virtual": console sessions play on a virtual screen at the TV's size; the PC's monitors stay as they are.</summary>
    public bool VirtualDisplay { get; set; }

    /// <summary>Console sessions cap games at the stream's 60 fps (NVIDIA driver), leaving GPU room for the stream.</summary>
    public bool CapGames { get; set; } = true;

    /// <summary>
    /// Also offer test versions (pre-releases), to try a version before everyone gets it. Only on
    /// the developer's PC (see <see cref="Developer"/>); unset there means on.
    /// </summary>
    public bool? TestUpdates { get; set; }

    /// <summary>
    /// The developer's own PC: a file named "developer" in Jinky's data folder. Only there does
    /// Jinky show and use the test-versions channel; everyone else gets stable releases only.
    /// </summary>
    public static bool Developer => File.Exists(Path.Combine(Folder, "developer"));

    /// <summary>Whether this PC is offered test versions.</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public bool WantsTestUpdates => Developer && (TestUpdates ?? true);

    /// <summary>When console mode is off: still send the sound only to the TV.</summary>
    public bool SoundOnlyOnTv { get; set; } = true;

    /// <summary>Steam Web API key: lets the TV list (and install) the account's games.</summary>
    public string? SteamApiKey { get; set; }

    public static string Folder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Jinky");

    static string FilePath => Path.Combine(Folder, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch (Exception)
        {
            // A corrupt file must never stop the app from starting; fall back to defaults.
        }
        return new Settings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Folder);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    /// <summary>How TVs list this Jinky; empty = "<PC name> · <Windows user>".</summary>
    public string? TvName { get; set; }

    public static string DefaultTvName => $"{Environment.MachineName} · {Environment.UserName}";

    /// <summary>Engine arguments for these preferences.</summary>
    public string EngineArguments()
    {
        var name = string.IsNullOrWhiteSpace(TvName) ? DefaultTvName : TvName.Trim();
        var args = $"--events --stdin-control --port {Ports.Media} --name \"{name.Replace("\"", "'")}\"";
        // Offer the bundled TV app to TVs (they update from this PC).
        if (TvService.Available) args += $" --tv-update {TvService.VersionCode}:{TvService.Port}";
        if (ConsoleMode) args += (CapGames ? " --console --frame-cap" : " --console") + (KeepBigPicture ? " --keep-big-picture" : "") +
                                (VirtualDisplay ? " --virtual-display" : "") + (GamesInBigPicture ? " --games-in-big-picture" : "");
        else if (SoundOnlyOnTv) args += " --mute-pc";
        return args;
    }
}

/// <summary>"Start with Windows": a value under HKCU\...\Run (no admin rights needed).</summary>
public static class StartWithWindows
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string Name = "Jinky";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
            key.SetValue(Name, $"\"{Environment.ProcessPath}\" --tray");
        else
            key.DeleteValue(Name, throwOnMissingValue: false);
    }
}

/// <summary>
/// The ports of this Jinky. Several Jinkys can run on one PC at the same time (one per Windows
/// user or ASTER workplace), each a separate "PC" for the TVs: the first takes 47100 (streaming,
/// UDP) and 47101 (TV service, TCP), the next 47110/47111, and so on. TVs look on all of them
/// and learn each Jinky's port from its answer, so the pair may differ from one start to the next.
/// </summary>
public static class Ports
{
    public const int First = 47100, Step = 10, Count = 4;

    public static int Media { get; private set; } = First;
    public static int Service => Media + 1;

    /// <summary>Takes the first pair nobody is using. Returns the slot (0 = the usual ports).</summary>
    public static int Pick()
    {
        for (var slot = 0; slot < Count; slot++)
        {
            var media = First + slot * Step;
            if (Free(media, media + 1))
            {
                Media = media;
                return slot;
            }
        }
        Media = First;  // all taken: the engine reports it and keeps waiting
        return 0;
    }

    static bool Free(int udp, int tcp)
    {
        try
        {
            using (new System.Net.Sockets.UdpClient(udp)) { }
            var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Any, tcp);
            listener.Start();
            listener.Stop();
            return true;
        }
        catch (System.Net.Sockets.SocketException)
        {
            return false;
        }
    }
}
