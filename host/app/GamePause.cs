using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// "Pausar como no Xbox" (Quick Resume): leaving a game from the TV ("Início" in the game menu,
/// the TV's Home button, the TV switched off) freezes it on the PC, exactly where it is;
/// "Continuar" on the TV picks it up again. Up to two games stay paused at once.
///
/// Memory, the way a console does it: a frozen game hands its RAM back to Windows at once and its
/// pages are the first to go when the game in front needs room; its video memory moves to RAM by
/// itself (Windows' video memory manager) for the same reason. Continuing reads it all back
/// before the game runs again, so it doesn't stutter catching up. A new game with the PC's memory
/// short closes the oldest paused game first, so the game in front never runs out.
///
/// Games with anti-cheat (EasyAntiCheat, BattlEye...) or that live online are never frozen. The
/// list survives Jinky restarting and updating; a game that ended is dropped. A paused game
/// brought up on the PC itself (its taskbar button) simply continues there.
/// </summary>
public static class GamePause
{
    public const int MaxPaused = 2;

    /// <summary>Less free memory (RAM + page file) than this, and a new game closes the oldest paused one.</summary>
    const long MinFreeMemory = 10L << 30;

    /// <summary>[Started]: the process's start time (UTC file time), so a reused process id after a
    /// restart is never taken for the game (0 in lists saved by older versions).</summary>
    public sealed record Paused(int GameId, string Name, int Pid, DateTime Since, long Started = 0,
                                // A PS2 game paused into a save state (Pcsx2): nothing runs meanwhile;
                                // continuing starts [Exe] [Args] from it. Survives restarts, no limit.
                                string? StateFile = null, string? Exe = null, string? Args = null, string? StartDir = null)
    {
        public bool Saved => StateFile is not null;
    }

    /// <summary>An update is about to close Jinky: paused games stay paused (the new version picks them up).</summary>
    public static bool KeepFrozenOnExit { get; set; }

    /// <summary>A paused game was brought up on the PC itself and continues there (its name).</summary>
    public static event Action<string>? ResumedOnPc;

    [DllImport("ntdll.dll")] static extern int NtSuspendProcess(IntPtr process);
    [DllImport("ntdll.dll")] static extern int NtResumeProcess(IntPtr process);
    [DllImport("psapi.dll")] static extern bool EmptyWorkingSet(IntPtr process);
    [DllImport("kernel32.dll")] static extern bool SetProcessInformation(IntPtr process, int infoClass, ref uint info, uint size);
    [DllImport("kernel32.dll")] static extern UIntPtr VirtualQueryEx(IntPtr process, IntPtr address, out MemoryInfo info, UIntPtr length);
    [DllImport("kernel32.dll")] static extern bool PrefetchVirtualMemory(IntPtr process, UIntPtr count, MemoryRange[] ranges, uint flags);
    [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc f, IntPtr lParam);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassNameW(IntPtr hWnd, StringBuilder name, int max);
    [DllImport("user32.dll")] static extern IntPtr HungWindowFromGhostWindow(IntPtr ghost);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    struct MemoryInfo
    {
        public IntPtr BaseAddress, AllocationBase;
        public uint AllocationProtect;
        public ushort PartitionId;
        public UIntPtr RegionSize;
        public uint State, Protect, Type;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MemoryRange
    {
        public IntPtr Address;
        public UIntPtr Size;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MemoryStatus
    {
        public uint Length, MemoryLoad;
        public ulong TotalPhys, AvailPhys, TotalPageFile, AvailPageFile, TotalVirtual, AvailVirtual, AvailExtendedVirtual;
    }

    const int SwMinimize = 6, SwRestore = 9;
    const int ProcessMemoryPriority = 0;
    const uint MemoryPriorityVeryLow = 1, MemoryPriorityNormal = 5;
    static readonly object Lock = new();
    static List<Paused>? _list;
    static Timer? _watch;
    static IntPtr _lastFront;
    static string FilePath => Path.Combine(Settings.Folder, "paused-games.json");
    static string SnapshotFolder => Path.Combine(Settings.Folder, "paused");

    /// <summary>Jinky started: watch the games left paused by the last run (if any).</summary>
    public static void Start()
    {
        if (List().Count > 0) StartWatch();
        CleanSnapshots();
    }

    /// <summary>The paused games, oldest first (games that ended in the meantime are dropped).</summary>
    public static List<Paused> List()
    {
        lock (Lock)
        {
            Load();
            var before = _list!.Count;
            _list.RemoveAll(p => !Alive(p));
            if (_list.Count != before) Save();
            return [.. _list];
        }
    }

    public static bool IsPaused(int gameId) => List().Any(p => p.GameId == gameId);

    /// <summary>The paused game's last picture (taken by the engine as it was paused), if any.</summary>
    public static string? SnapshotOf(int gameId)
    {
        var entry = List().FirstOrDefault(p => p.GameId == gameId);
        if (entry is null) return null;
        var path = Path.Combine(SnapshotFolder, $"{entry.Pid}.jpg");
        return File.Exists(path) ? path : null;
    }

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
        // A PS2 game: a real save state instead of a frozen emulator (falls back to freezing if
        // PCSX2 can't be asked, e.g. PINE still off in the copy that is running).
        if (Pcsx2.IsPcsx2(path) && PauseIntoState(pid, gameId, name, path)) return null;
        lock (Lock)
        {
            Load();
            _list!.RemoveAll(p => p.Pid == pid || p.GameId == gameId || !Alive(p));
            if (_list.Count(p => !p.Saved) >= MaxPaused) return "já há dois jogos pausados";
            // Minimised first (the game notices it lost the screen: most pause themselves and
            // go quiet), then frozen.
            foreach (var w in Windows(pid)) ShowWindow(w, SwMinimize);
            Thread.Sleep(800);
            using var p = Process.GetProcessById(pid);
            if (NtSuspendProcess(p.Handle) != 0) return "o Windows não deixou pausar o jogo";
            // Its memory goes back to Windows: still cached (instant to come back) until the game
            // in front needs the room - and then its pages are the first to go.
            var priority = MemoryPriorityVeryLow;
            SetProcessInformation(p.Handle, ProcessMemoryPriority, ref priority, 4);
            EmptyWorkingSet(p.Handle);
            _list.Add(new Paused(gameId, name, pid, DateTime.Now, StartTime(p)));
            Save();
            _lastFront = GetForegroundWindow();
        }
        StartWatch();
        return null;
    }

    /// <summary>The PS2 game in front: saved into Jinky's slot and PCSX2 closed. False if it couldn't.</summary>
    static bool PauseIntoState(int pid, int gameId, string name, string exe)
    {
        // How to start it again: the library's entry for it (its disc image is in the arguments).
        var shortcut = Library.Find(gameId);
        if (shortcut is null || !Pcsx2.IsPcsx2(shortcut.Exe)) return false;
        var file = Pcsx2.SaveAndClose(pid, exe);
        if (file is null) return false;
        lock (Lock)
        {
            Load();
            _list!.RemoveAll(p => p.GameId == gameId || !Alive(p));
            _list.Add(new Paused(gameId, name, pid, DateTime.Now, 0, file, shortcut.Exe, shortcut.Args ?? "", shortcut.StartDir ?? ""));
            Save();
        }
        return true;
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
        if (!Alive(entry)) return false;
        // A PS2 game: started again straight from its save state.
        if (entry.Saved)
        {
            if (Pcsx2.StartFromState(entry.Exe!, entry.Args ?? "", entry.StartDir ?? "") is not { } started) return false;
            try { JinkyPlaytime.Track(gameId, Process.GetProcessById(started)); } catch (Exception) { }
            return true;
        }
        try
        {
            using var p = Process.GetProcessById(entry.Pid);
            var priority = MemoryPriorityNormal;
            SetProcessInformation(p.Handle, ProcessMemoryPriority, ref priority, 4);
            // Its memory back in RAM before it runs (a few seconds at most): otherwise the first
            // seconds stutter while it pages everything back in.
            var handle = p.Handle;
            Task.Run(() => Prefetch(handle)).Wait(TimeSpan.FromSeconds(4));
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
        if (!Alive(entry)) return false;
        if (entry.Saved) return true;  // nothing runs: dropping it from the list is all
        try
        {
            using var p = Process.GetProcessById(entry.Pid);
            p.Kill();
            p.WaitForExit(5000);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
        finally
        {
            CleanSnapshots();
        }
    }

    /// <summary>
    /// A new game is about to start: with two games paused, or the PC's memory short (RAM + page
    /// file), the oldest paused game closes first. Returns the names of the games closed.
    /// </summary>
    public static List<string> MakeRoom()
    {
        var closed = new List<string>();
        // (Saved PS2 games take no memory: they never need to make room.)
        for (var list = List().Where(p => !p.Saved).ToList(); list.Count > 0; list = List().Where(p => !p.Saved).ToList())
        {
            if (list.Count < MaxPaused && FreeMemory() >= MinFreeMemory) break;
            if (!Close(list[0].GameId)) break;
            closed.Add(list[0].Name);
        }
        return closed;
    }

    /// <summary>Thaws everything (Jinky closing): a frozen game must never be left behind for good.</summary>
    public static void ResumeAllSilently()
    {
        // An update closes Jinky for a moment: the new version keeps them paused.
        if (KeepFrozenOnExit) return;
        foreach (var p in List())
            try
            {
                if (p.Saved || !Alive(p)) continue;
                using var proc = Process.GetProcessById(p.Pid);
                var priority = MemoryPriorityNormal;
                SetProcessInformation(proc.Handle, ProcessMemoryPriority, ref priority, 4);
                NtResumeProcess(proc.Handle);
            }
            catch (Exception) { }
        lock (Lock)
        {
            // PS2 games saved into a state stay paused: that is the point (they need nothing running).
            _list = _list?.Where(p => p.Saved).ToList() ?? [];
            Save();
        }
        CleanSnapshots();
    }

    static void StartWatch()
    {
        lock (Lock) _watch ??= new Timer(_ => Watch(), null, 1000, 1000);
    }

    /// <summary>
    /// Someone at the PC brought a paused game up (its taskbar button): it continues there. A
    /// frozen window can't answer, so Windows may show a "ghost" of it instead; that counts too.
    /// </summary>
    static int _watching;

    static void Watch()
    {
        if (Interlocked.Exchange(ref _watching, 1) == 1) return;
        try { WatchOnce(); }
        finally { _watching = 0; }
    }

    static void WatchOnce()
    {
        List<Paused> list;
        lock (Lock)
        {
            // Only frozen games can be brought up on the PC (a saved PS2 game has no window).
            if (_list is null || !_list.Any(p => !p.Saved))
            {
                _watch?.Dispose();
                _watch = null;
                return;
            }
            list = _list.Where(p => !p.Saved).ToList();
        }
        var front = GetForegroundWindow();
        if (front == IntPtr.Zero || front == _lastFront) return;
        _lastFront = front;
        var window = front;
        var cls = new StringBuilder(64);
        if (GetClassNameW(front, cls, cls.Capacity) > 0 && cls.ToString() == "Ghost" && HungWindowFromGhostWindow(front) is var hung && hung != IntPtr.Zero)
            window = hung;
        GetWindowThreadProcessId(window, out var pid);
        var game = list.FirstOrDefault(p => p.Pid == pid);
        if (game is not null && Resume(game.GameId)) ResumedOnPc?.Invoke(game.Name);
    }

    /// <summary>Reads a paused game's memory back into RAM (it was handed to Windows when paused).</summary>
    static void Prefetch(IntPtr process)
    {
        try
        {
            var ranges = new List<MemoryRange>();
            var address = IntPtr.Zero;
            var size = (UIntPtr)Marshal.SizeOf<MemoryInfo>();
            while (VirtualQueryEx(process, address, out var m, size) != UIntPtr.Zero)
            {
                // Committed private memory that can be read (never-touched memory isn't read: verified).
                const uint MemCommit = 0x1000, MemPrivate = 0x20000, NoAccessOrGuard = 0x101;
                if (m.State == MemCommit && m.Type == MemPrivate && (m.Protect & NoAccessOrGuard) == 0)
                    ranges.Add(new MemoryRange { Address = m.BaseAddress, Size = m.RegionSize });
                var next = (long)m.BaseAddress + (long)m.RegionSize.ToUInt64();
                if (next <= (long)address) break;
                address = (IntPtr)next;
            }
            if (ranges.Count > 0) PrefetchVirtualMemory(process, (UIntPtr)ranges.Count, [.. ranges], 0);
        }
        catch (Exception)
        {
        }
    }

    /// <summary>Memory still free to hand out (RAM + page file), in bytes.</summary>
    static long FreeMemory()
    {
        var status = new MemoryStatus { Length = (uint)Marshal.SizeOf<MemoryStatus>() };
        return GlobalMemoryStatusEx(ref status) ? (long)status.AvailPageFile : long.MaxValue;
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
        _lastFront = GetForegroundWindow();
    }

    /// <summary>Pictures of games no longer paused are deleted.</summary>
    static void CleanSnapshots()
    {
        try
        {
            if (!Directory.Exists(SnapshotFolder)) return;
            var keep = List().Select(p => $"{p.Pid}.jpg").ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var f in Directory.EnumerateFiles(SnapshotFolder, "*.jpg"))
                // A minute of grace: the engine writes the picture just before the game is paused.
                if (!keep.Contains(Path.GetFileName(f)) && DateTime.Now - File.GetLastWriteTime(f) > TimeSpan.FromMinutes(1))
                    File.Delete(f);
        }
        catch (Exception)
        {
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

    /// <summary>The paused game's process is still there (the same process, not a reused id).</summary>
    static bool Alive(Paused p)
    {
        if (p.Saved) return File.Exists(p.StateFile);
        if (!Alive(p.Pid)) return false;
        if (p.Started == 0) return true;
        try
        {
            using var proc = Process.GetProcessById(p.Pid);
            return StartTime(proc) == p.Started;
        }
        catch (Exception)
        {
            return false;
        }
    }

    static long StartTime(Process p)
    {
        try { return p.StartTime.ToFileTimeUtc(); }
        catch (Exception) { return 0; }
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
