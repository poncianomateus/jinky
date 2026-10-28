using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace Jinky;

/// <summary>
/// "Monitor virtual": TV sessions play on a virtual screen at the TV's size and rate, and the PC's
/// monitors stay as they are (dark meanwhile). The screen comes from the Virtual Display Driver
/// (github.com/VirtualDrivers/Virtual-Display-Driver, MIT licence, signed by the SignPath
/// Foundation). Jinky downloads exactly the reviewed release (checked by its SHA-256) and its
/// engine installs it, under one administrator prompt.
/// </summary>
public static class VirtualScreen
{
    const string ZipUrl = "https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/download/25.7.23/VirtualDisplayDriver-x86.Driver.Only.zip";
    const string ZipSha256 = "e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a";

    [DllImport("user32.dll")]
    static extern int SetDisplayConfig(uint numPaths, IntPtr paths, uint numModes, IntPtr modes, uint flags);
    const uint SdcApply = 0x80, SdcUseDatabaseCurrent = 0x0F, SdcTopologyExtend = 0x04;

    /// <summary>
    /// The engine ended (crashed, was killed) while a TV session had the PC's monitors dark: brings
    /// them back right away, without waiting for the engine to start again (it may not).
    /// </summary>
    public static void RestoreIfLeft()
    {
        try
        {
            var marker = Path.Combine(Settings.Folder, "virtual-display.flag");
            if (!File.Exists(marker)) return;
            if (SetDisplayConfig(0, IntPtr.Zero, 0, IntPtr.Zero, SdcApply | SdcUseDatabaseCurrent) != 0)
                SetDisplayConfig(0, IntPtr.Zero, 0, IntPtr.Zero, SdcApply | SdcTopologyExtend);
            File.Delete(marker);
        }
        catch (Exception)
        {
        }
    }

    /// <summary>The driver is installed and its screen is there (asks the engine; no administrator).</summary>
    public static bool Installed()
    {
        var exe = Engine.FindExecutable();
        if (exe is null) return false;
        try
        {
            using var p = Process.Start(new ProcessStartInfo(exe, "--virtual-display-status") { UseShellExecute = false, CreateNoWindow = true });
            if (p is null || !p.WaitForExit(10_000)) return false;
            return p.ExitCode == 0;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Downloads and installs the driver (on) or removes it (off). Null when done, else what went wrong.</summary>
    public static async Task<string?> SetAsync(bool on, Action<string> progress)
    {
        var exe = Engine.FindExecutable();
        if (exe is null) return "jinky-engine.exe não foi encontrado";
        string? folder = null;
        string args = "--uninstall-virtual-display";
        if (on)
        {
            progress("Baixando o driver do monitor virtual…");
            folder = Path.Combine(Path.GetTempPath(), "Jinky", "virtual-display");
            try
            {
                if (Directory.Exists(folder)) Directory.Delete(folder, true);
                Directory.CreateDirectory(folder);
                using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromMinutes(3) };
                var zip = await http.GetByteArrayAsync(ZipUrl);
                // Exactly the release that was reviewed: anything else is refused.
                if (!Convert.ToHexString(SHA256.HashData(zip)).Equals(ZipSha256, StringComparison.OrdinalIgnoreCase))
                    return "o arquivo baixado não é o esperado; nada foi instalado";
                using (var archive = new ZipArchive(new MemoryStream(zip)))
                    foreach (var entry in archive.Entries)
                    {
                        if (entry.Name.Length == 0) continue;
                        // Flat: the INF, catalog, DLL and sample settings, never paths from the archive.
                        entry.ExtractToFile(Path.Combine(folder, entry.Name), overwrite: true);
                    }
                if (!File.Exists(Path.Combine(folder, "MttVDD.inf"))) return "o driver baixado está incompleto";
            }
            catch (Exception e)
            {
                return "não deu para baixar o driver (" + e.Message + ")";
            }
            args = $"--install-virtual-display \"{folder}\"";
            progress("Instalando o monitor virtual… (o Windows vai pedir permissão)");
        }
        else progress("Removendo o monitor virtual… (o Windows vai pedir permissão)");

        int code;
        try
        {
            using var p = Process.Start(new ProcessStartInfo(exe, args)
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            if (p is null) return "o Windows não deu permissão";
            await p.WaitForExitAsync().WaitAsync(TimeSpan.FromMinutes(3));
            code = p.ExitCode;
        }
        catch (Exception)
        {
            return "o Windows não deu permissão";  // UAC refused
        }
        finally
        {
            if (folder is not null) try { Directory.Delete(folder, true); } catch (Exception) { }
        }
        return code switch
        {
            0 => null,
            14 => "o Windows não aceitou o driver",
            15 => "o driver instalou, mas o monitor virtual não apareceu (reiniciar o PC pode resolver)",
            16 => "o monitor virtual não foi removido por completo",
            _ => $"algo deu errado (código {code})",
        };
    }
}
