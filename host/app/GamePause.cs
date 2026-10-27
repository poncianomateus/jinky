using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// "Pausar como no Xbox": leaving a game from the TV ("Início" in the game menu) freezes it on the
/// PC, exactly where it is; "Continuar" on the TV picks it up again. Up to two games stay paused at
/// once (the GPU's memory is the limit). Games with anti-cheat (EasyAntiCheat, BattlEye...) are
/// never frozen: their servers or protection could take it badly; they keep running as before.
/// The list survives Jinky restarting (saved next to the settings); a game that ended is dropped.
/// </summary>
public static class GamePause
{
    public const int MaxPaused = 2;

    public sealed record Paused(int GameId, string Name, int Pid, DateTime Since);

    [DllImport("ntdll.dll")] static extern int NtSuspendProcess(IntPtr process);
    [DllImport("ntdll.dll")] static extern int NtResumeProcess(IntPtr process);
    [DllImport("psapi.dll")] static extern bool EmptyWorkingSet(IntPtr process);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc f, IntPtr lParam);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    const int SwMinimize = 6, SwRestore = 9;
    static readonly object Lock = new();
    static List<Paused>? _list;
    static string FilePath => Path.Combine(Settings.Folder, "paused-games.json");

    /// <summary>The paused games, oldest first (games that ended in the meantime are dropped).</summary>
    public static List<Paused> List()
    {
        lock (Lock)
        {
            Load();
            var before = _list!.Count;
            _list.RemoveAll(p => !Alive(p.Pid));
            if (_list.Count != before) Save();
            return [.. _list];
        }
    }

    public static bool IsPaused(int gameId) => List().Any(p => p.GameId == gameId);

    /// <summary>
    /// Freezes [pid] (the game in front, as the engine saw it) as game [gameId]. Returns null when
    /// paused, else why not (anti-cheat, already two paused...).
    /// </summary>
    public static string? Pause(int pid, int gameId, string name)
    {
        string? path;
        try { path = Process.GetProcessById(pid).MainModule?.FileName; }
        catch (Exception) { return "o jogo já tinha fechado"; }
        if (path is null) return "não deu para ler o jogo";
        if (OnlineGames.Contains(gameId) || HasAntiCheat(path)) return "este jogo é online ou tem anti-cheat; ele continua rodando, sem pausa";
        lock (Lock)
        {
            Load();
            _list!.RemoveAll(p => p.Pid == pid || p.GameId == gameId || !Alive(p.Pid));
            if (_list.Count >= MaxPaused) return "já há dois jogos pausados";
            // Minimized first (the game notices it lost the screen), then frozen.
            foreach (var w in Windows(pid)) ShowWindow(w, SwMinimize);
            Thread.Sleep(800);
            using var p = Process.GetProcessById(pid);
            if (NtSuspendProcess(p.Handle) != 0) return "o Windows não deixou pausar o jogo";
            // "Hibernates" its memory: handed back to Windows. It stays cached (instant to come back)
            // until the game in front needs the room; only then it goes to the page file.
            EmptyWorkingSet(p.Handle);
            _list.Add(new Paused(gameId, name, pid, DateTime.Now));
            Save();
        }
        return null;
    }

    /// <summary>Unfreezes game [gameId] and brings it back to the front. False if it isn't paused.</summary>
    public static bool Resume(int gameId)
    {
        Paused? entry;
        lock (Lock)
        {
            Load();
            entry = _list!.FirstOrDefault(p => p.GameId == gameId);
            if (entry is null) return false;
            _list.Remove(entry);
            Save();
        }
        if (!Alive(entry.Pid)) return false;
        try
        {
            using var p = Process.GetProcessById(entry.Pid);
            NtResumeProcess(p.Handle);
        }
        catch (Exception)
        {
            return false;
        }
        Thread.Sleep(200);
        BringToFront(entry.Pid);
        // Big Picture (or Steam) can still take the screen a moment later, leaving the TV on Steam
        // with the game behind it: keep the game in front until it stays there for 2 s.
        var pid = entry.Pid;
        _ = Task.Run(async () =>
        {
            var steady = 0;
            for (var i = 0; i < 25 && steady < 5 && Alive(pid); i++)
            {
                await Task.Delay(400);
                GetWindowThreadProcessId(GetForegroundWindow(), out var front);
                if (front == pid) steady++;
                else
                {
                    steady = 0;
                    BringToFront(pid);
                }
            }
        });
        return true;
    }

    static void BringToFront(int pid)
    {
        foreach (var w in Windows(pid))
        {
            ShowWindow(w, SwRestore);
            // Windows only lets the active program take the front: borrowing its input queue (and a
            // tap on Alt, which counts as user input) lets the game come forward.
            var fg = GetForegroundWindow();
            var fgThread = GetWindowThreadProcessId(fg, out _);
            var me = GetCurrentThreadId();
            var attached = fgThread != 0 && fgThread != me && AttachThreadInput(me, fgThread, true);
            keybd_event(0x12, 0, 0, UIntPtr.Zero);
            keybd_event(0x12, 0, 2, UIntPtr.Zero);
            BringWindowToTop(w);
            SetForegroundWindow(w);
            if (attached) AttachThreadInput(me, fgThread, false);
        }
    }

    /// <summary>Closes a paused game (to make room for another).</summary>
    public static bool Close(int gameId)
    {
        Paused? entry;
        lock (Lock)
        {
            Load();
            entry = _list!.FirstOrDefault(p => p.GameId == gameId);
            if (entry is null) return false;
            _list.Remove(entry);
            Save();
        }
        try
        {
            using var p = Process.GetProcessById(entry.Pid);
            p.Kill();
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Thaws everything (Jinky closing): a frozen game must never be left behind for good.</summary>
    public static void ResumeAllSilently()
    {
        foreach (var p in List())
            try
            {
                using var proc = Process.GetProcessById(p.Pid);
                NtResumeProcess(proc.Handle);
            }
            catch (Exception) { }
        lock (Lock)
        {
            _list = [];
            Save();
        }
    }

    /// <summary>
    /// Online games whose anti-cheat doesn't live in the game's folder (or that just drop you from
    /// the match): Call of Duty, Counter-Strike, Apex, PUBG, Rust, Rainbow Six, Destiny, Dota, TF2...
    /// </summary>
    static readonly HashSet<int> OnlineGames =
    [
        730, 570, 440, 1938090, 2000950, 1962663, 2519060, 2933620, 1172470, 578080, 252490, 359550, 1085660,
        381210, 1203220, 2507950, 2357570, 1623730, 553850, 1599340, 236390, 304930,
    ];

    /// <summary>EasyAntiCheat, BattlEye and the like ship next to the game: never freeze those.</summary>
    static bool HasAntiCheat(string exePath)
    {
        try
        {
            // The game's own folder (steamapps\common\Game) and the exe's folder.
            var dir = Path.GetDirectoryName(exePath)!;
            var common = dir.IndexOf(@"\steamapps\common\", StringComparison.OrdinalIgnoreCase);
            var root = dir;
            if (common >= 0)
            {
                var rest = dir[(common + @"\steamapps\common\".Length)..];
                var first = rest.Split('\\')[0];
                root = dir[..(common + @"\steamapps\common\".Length)] + first;
            }
            // Whole folder names only ("EAC" inside "beacon" is not an anti-cheat).
            string[] folders = ["EasyAntiCheat", "EasyAntiCheat_EOS", "BattlEye", "BattlEyeLauncher", "PunkBuster", "nProtect", "XIGNCODE", "XIGNCODE3", "AntiCheat", "EAC"];
            var options = new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 2, IgnoreInaccessible = true };
            foreach (var d in Directory.EnumerateDirectories(root, "*", options))
                if (folders.Any(m => Path.GetFileName(d).Equals(m, StringComparison.OrdinalIgnoreCase))) return true;
            foreach (var f in Directory.EnumerateFiles(root, "*", options))
            {
                var n = Path.GetFileName(f);
                if (n.StartsWith("EasyAntiCheat", StringComparison.OrdinalIgnoreCase) || n.StartsWith("BEService", StringComparison.OrdinalIgnoreCase) ||
                    n.StartsWith("BEClient", StringComparison.OrdinalIgnoreCase) || n.Equals("start_protected_game.exe", StringComparison.OrdinalIgnoreCase) ||
                    n.EndsWith(".sys", StringComparison.OrdinalIgnoreCase))  // a kernel driver next to a game: anti-cheat
                    return true;
            }
        }
        catch (Exception)
        {
        }
        return false;
    }

    static List<IntPtr> Windows(int pid)
    {
        var found = new List<IntPtr>();
        EnumWindows((h, _) =>
        {
            GetWindowThreadProcessId(h, out var owner);
            if (owner == pid && IsWindowVisible(h)) found.Add(h);
            return true;
        }, IntPtr.Zero);
        return found;
    }

    static bool Alive(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            return !p.HasExited;
        }
        catch (Exception)
        {
            return false;
        }
    }

    static void Load()
    {
        if (_list is not null) return;
        try { _list = File.Exists(FilePath) ? JsonSerializer.Deserialize<List<Paused>>(File.ReadAllText(FilePath)) ?? [] : []; }
        catch (Exception) { _list = []; }
    }

    static void Save()
    {
        try { File.WriteAllText(FilePath, JsonSerializer.Serialize(_list)); }
        catch (Exception) { }
    }
}
