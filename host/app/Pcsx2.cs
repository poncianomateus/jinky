using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Text;

namespace Jinky;

/// <summary>
/// PS2 games (PCSX2): real Quick Resume. Pausing asks PCSX2 to save the game's state to Jinky's
/// own slot over PINE (PCSX2's local control channel, 127.0.0.1) and closes the emulator, so the
/// pause takes no memory, has no limit and survives the PC being turned off; continuing starts
/// the same game straight from that state ("-state"). PINE has to be on in PCSX2's settings: Jinky
/// turns it on (EmuCore/EnablePINE) while PCSX2 is closed.
/// </summary>
public static class Pcsx2
{
    /// <summary>Jinky's save slot: the player's own quick saves (F1) live in the other slots.</summary>
    public const int Slot = 10;

    public static bool IsPcsx2(string? exe) =>
        exe is not null && Path.GetFileName(exe).StartsWith("pcsx2", StringComparison.OrdinalIgnoreCase);

    /// <summary>PCSX2's data folder: next to the program when it is portable, else Documents\PCSX2.</summary>
    public static string DataFolder(string exe)
    {
        var dir = Path.GetDirectoryName(exe) ?? "";
        if (File.Exists(Path.Combine(dir, "portable.ini")) || File.Exists(Path.Combine(dir, "portable.txt"))) return dir;
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "PCSX2");
    }

    static string IniPath(string exe) => Path.Combine(DataFolder(exe), "inis", "PCSX2.ini");

    static bool Running(string exe) =>
        Process.GetProcessesByName(Path.GetFileNameWithoutExtension(exe)).Length > 0;

    /// <summary>
    /// Turns PINE on in PCSX2's settings if it is off (only while PCSX2 is closed: it rewrites the
    /// file when it exits). Returns true when it changed the setting.
    /// </summary>
    public static bool EnsurePine(string exe)
    {
        var ini = IniPath(exe);
        if (!File.Exists(ini) || Running(exe)) return false;
        try
        {
            var lines = File.ReadAllLines(ini).ToList();
            var i = lines.FindIndex(l => l.TrimStart().StartsWith("EnablePINE", StringComparison.OrdinalIgnoreCase));
            if (i >= 0)
            {
                if (lines[i].Contains("true", StringComparison.OrdinalIgnoreCase)) return false;
                lines[i] = "EnablePINE = true";
            }
            else
            {
                var core = lines.FindIndex(l => l.Trim().Equals("[EmuCore]", StringComparison.OrdinalIgnoreCase));
                if (core < 0) { lines.Add("[EmuCore]"); core = lines.Count - 1; }
                lines.Insert(core + 1, "EnablePINE = true");
            }
            File.WriteAllLines(ini, lines);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    static int PinePort(string exe)
    {
        try
        {
            var line = File.ReadLines(IniPath(exe)).FirstOrDefault(l => l.TrimStart().StartsWith("PINESlot", StringComparison.OrdinalIgnoreCase));
            if (line is not null && int.TryParse(line.Split('=')[1].Trim(), out var port) && port is > 0 and < 65536) return port;
        }
        catch (Exception) { }
        return 28011;
    }

    /// <summary>One PINE request: [u32 size][u8 opcode][args]; reply [u32 size][u8 result][data]. Null on failure.</summary>
    static byte[]? Pine(int port, byte opcode, params byte[] args)
    {
        try
        {
            using var client = new TcpClient();
            if (!client.ConnectAsync("127.0.0.1", port).Wait(1500)) return null;
            using var s = client.GetStream();
            s.ReadTimeout = s.WriteTimeout = 3000;
            var msg = new byte[5 + args.Length];
            BitConverter.GetBytes(msg.Length).CopyTo(msg, 0);
            msg[4] = opcode;
            args.CopyTo(msg, 5);
            s.Write(msg);
            var head = ReadExactly(s, 4);
            var size = BitConverter.ToInt32(head, 0);
            if (size < 5 || size > 450_000) return null;
            var rest = ReadExactly(s, size - 4);
            return rest[0] == 0 ? rest[1..] : null;  // 0 = IPC_OK, 0xFF = IPC_FAIL
        }
        catch (Exception)
        {
            return null;
        }
    }

    static byte[] ReadExactly(NetworkStream s, int n)
    {
        var buf = new byte[n];
        for (var got = 0; got < n;)
        {
            var r = s.Read(buf, got, n - got);
            if (r <= 0) throw new IOException("closed");
            got += r;
        }
        return buf;
    }

    /// <summary>The running game's serial (e.g. "SCUS-97399"), from PINE; null when not reachable.</summary>
    static string? Serial(int port)
    {
        var data = Pine(port, 0x0C);  // MsgID: u32 length, then the text
        if (data is null || data.Length < 4) return null;
        var len = Math.Min(BitConverter.ToInt32(data, 0), data.Length - 4);
        return len <= 0 ? null : Encoding.UTF8.GetString(data, 4, len).TrimEnd('\0').Trim();
    }

    /// <summary>
    /// Saves the running game's state to Jinky's slot and closes PCSX2 ([pid]). Returns the state
    /// file, or null (PINE off or unreachable, the save didn't finish): the caller falls back to
    /// freezing the emulator.
    /// </summary>
    public static string? SaveAndClose(int pid, string exe)
    {
        var port = PinePort(exe);
        var serial = Serial(port);
        if (serial is null) return null;
        var states = Path.Combine(DataFolder(exe), "sstates");
        var started = DateTime.Now.AddSeconds(-1);
        if (Pine(port, 0x09, (byte)Slot) is null) return null;  // MsgSaveState
        // PCSX2 writes "<serial> (<crc>).10.p2s" on its own thread: wait until it is there and done.
        string? file = null;
        long lastSize = -1;
        for (var i = 0; i < 60; i++)
        {
            Thread.Sleep(250);
            file = Directory.Exists(states)
                ? Directory.EnumerateFiles(states, $"{serial}*.{Slot:00}.p2s").Concat(Directory.EnumerateFiles(states, $"{serial}*.{Slot}.p2s"))
                    .Select(f => new FileInfo(f)).Where(f => f.LastWriteTime >= started).OrderByDescending(f => f.LastWriteTime)
                    .FirstOrDefault()?.FullName
                : null;
            if (file is null) continue;
            var size = new FileInfo(file).Length;
            if (size > 0 && size == lastSize) break;  // same size twice in a row: written
            lastSize = size;
        }
        if (file is null) return null;
        // A moment for the memory card: an in-game save made just before is written through first.
        Thread.Sleep(1000);
        try
        {
            using var p = Process.GetProcessById(pid);
            p.Kill();
            p.WaitForExit(5000);
        }
        catch (Exception) { }
        return file;
    }

    /// <summary>Starts the game again from Jinky's slot ([args]: the shortcut's own, with the disc image).</summary>
    public static int? StartFromState(string exe, string args, string startDir)
    {
        try
        {
            var p = Process.Start(new ProcessStartInfo(exe, $"{args} -state {Slot}")
            {
                UseShellExecute = false,
                WorkingDirectory = Directory.Exists(startDir) ? startDir : Path.GetDirectoryName(exe) ?? "",
            });
            return p?.Id;
        }
        catch (Exception)
        {
            return null;
        }
    }
}
