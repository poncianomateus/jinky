using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace Jinky;

/// <summary>One machine-readable event from the engine (a JSON line on its stdout).</summary>
public sealed record EngineEvent(string Kind, JsonElement Data)
{
    public string? Str(string name) => Data.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    public double? Num(string name) => Data.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;
    public bool? Bool(string name) => Data.TryGetProperty(name, out var v) && v.ValueKind is JsonValueKind.True or JsonValueKind.False ? v.GetBoolean() : null;
}

/// <summary>
/// Runs jinky-engine.exe without a window, turns its stdout into <see cref="EngineEvent"/>s and its
/// stderr into a log file, restarts it if it crashes (bounded), and stops it cleanly ("quit" on
/// stdin) so console mode always gets undone. A job object kills it if this app dies.
/// </summary>
public sealed class Engine : IDisposable
{
    public event Action<EngineEvent>? EventReceived;
    public event Action<string>? Failed;  // could not start / keeps crashing
    public event Action? Exited;
    public event Action? PortBusy;  // its port belongs to another Jinky: the app picks ports and starts it again

    readonly SafeJobHandle _job = SafeJobHandle.CreateKillOnClose();
    Process? _process;
    string _arguments = "";
    bool _stopping;
    readonly Queue<DateTime> _crashes = new();
    StreamWriter? _log;

    public bool IsRunning => _process is { HasExited: false };

    public static string? FindExecutable()
    {
        var here = Path.Combine(AppContext.BaseDirectory, "jinky-engine.exe");
        return File.Exists(here) ? here : null;
    }

    public void Start(string arguments)
    {
        _arguments = arguments;
        _stopping = false;
        Launch();
    }

    void Launch()
    {
        var exe = FindExecutable();
        if (exe is null)
        {
            Failed?.Invoke("jinky-engine.exe não foi encontrado ao lado do Jinky.exe.");
            return;
        }
        Directory.CreateDirectory(Path.Combine(Settings.Folder, "logs"));
        _log?.Dispose();
        _log = new StreamWriter(Path.Combine(Settings.Folder, "logs", "engine.log"), append: false) { AutoFlush = true };

        var psi = new ProcessStartInfo(exe, _arguments)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            // The engine prints UTF-8 (TV names can have accents); the default would be the ANSI code page.
            StandardOutputEncoding = new System.Text.UTF8Encoding(false),
            StandardErrorEncoding = new System.Text.UTF8Encoding(false),
            WorkingDirectory = Path.GetDirectoryName(exe)!,
        };
        var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
        p.OutputDataReceived += (_, e) => { if (e.Data is not null) Parse(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data is not null) lock (this) _log?.WriteLine(e.Data); };
        p.Exited += (_, _) => OnExited(p);
        try
        {
            p.Start();
        }
        catch (Exception ex)
        {
            Failed?.Invoke($"Não foi possível iniciar o motor: {ex.Message}");
            return;
        }
        _job.Assign(p);
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        _process = p;
    }

    void Parse(string line)
    {
        if (!line.StartsWith('{')) return;
        try
        {
            using var doc = JsonDocument.Parse(line);
            var root = doc.RootElement.Clone();
            if (root.TryGetProperty("ev", out var ev) && ev.GetString() is { } kind)
                EventReceived?.Invoke(new EngineEvent(kind, root));
        }
        catch (JsonException)
        {
            // Not an event line; ignore.
        }
    }

    void OnExited(Process p)
    {
        if (p != _process) return;
        Exited?.Invoke();
        if (_stopping) return;

        // 3 = the port belongs to another Jinky (another Windows user or ASTER workplace): not a
        // crash; wait for it to be free.
        if (p.ExitCode == 3)
        {
            _stopping = true;
            PortBusy?.Invoke();
            return;
        }

        // Unexpected exit: restart, but give up if it keeps dying (e.g. no NVIDIA GPU).
        var now = DateTime.UtcNow;
        _crashes.Enqueue(now);
        while (_crashes.Count > 0 && now - _crashes.Peek() > TimeSpan.FromMinutes(1)) _crashes.Dequeue();
        if (_crashes.Count > 3)
        {
            Failed?.Invoke("O motor de streaming parou várias vezes seguidas. Veja o diagnóstico.");
            return;
        }
        Task.Delay(2000).ContinueWith(_ => { if (!_stopping) Launch(); });
    }

    /// <summary>Asks the engine to quit and waits for its cleanup; kills it as a last resort.</summary>
    public void Stop()
    {
        _stopping = true;
        var p = _process;
        if (p is null || p.HasExited) return;
        try
        {
            p.StandardInput.WriteLine("quit");
            p.StandardInput.Flush();
        }
        catch (IOException)
        {
        }
        if (!p.WaitForExit(6000)) p.Kill();
    }

    /// <summary>Sends a command line to the engine (e.g. "pair-accept &lt;id&gt;").</summary>
    public void Send(string line)
    {
        try
        {
            var p = _process;
            if (p is null || p.HasExited) return;
            p.StandardInput.WriteLine(line);
            p.StandardInput.Flush();
        }
        catch (IOException)
        {
        }
    }

    public void Restart(string arguments)
    {
        Stop();
        Start(arguments);
    }

    public void Dispose()
    {
        Stop();
        _log?.Dispose();
        _job.Dispose();
    }
}

/// <summary>Job object with KILL_ON_JOB_CLOSE: if Jinky.exe dies, Windows ends the engine too.</summary>
sealed class SafeJobHandle : SafeHandle
{
    SafeJobHandle() : base(IntPtr.Zero, true) { }
    public override bool IsInvalid => handle == IntPtr.Zero;
    protected override bool ReleaseHandle() => CloseHandle(handle);

    public static SafeJobHandle CreateKillOnClose()
    {
        var job = CreateJobObjectW(IntPtr.Zero, null);
        var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        info.BasicLimitInformation.LimitFlags = 0x2000;  // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        var size = Marshal.SizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>();
        var ptr = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(info, ptr, false);
            SetInformationJobObject(job, 9 /* JobObjectExtendedLimitInformation */, ptr, (uint)size);
        }
        finally
        {
            Marshal.FreeHGlobal(ptr);
        }
        return job;
    }

    public void Assign(Process p) => AssignProcessToJobObject(this, p.Handle);

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct IO_COUNTERS
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount,
            WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [DllImport("kernel32", CharSet = CharSet.Unicode)]
    static extern SafeJobHandle CreateJobObjectW(IntPtr attributes, string? name);

    [DllImport("kernel32")]
    static extern bool SetInformationJobObject(SafeJobHandle job, int infoClass, IntPtr info, uint length);

    [DllImport("kernel32")]
    static extern bool AssignProcessToJobObject(SafeJobHandle job, IntPtr process);

    [DllImport("kernel32")]
    static extern bool CloseHandle(IntPtr handle);
}
