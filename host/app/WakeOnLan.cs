using System.Diagnostics;
using System.IO;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Jinky;

/// <summary>
/// "Ligar o PC pela TV": opening Jinky on a TV sends a Wake-on-LAN packet to this PC. For it to
/// wake, the network adapter facing the TVs must be allowed to wake the PC (administrator, once).
/// Over Wi-Fi that works from sleep ("Suspender"), not from a full shutdown.
/// </summary>
public static class WakeOnLan
{
    /// <summary>The adapters that carry the home network (physical, connected, with an IPv4 address).</summary>
    static List<string> HomeAdapters()
    {
        var list = new List<string>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.GigabitEthernet))
                    continue;
                var d = nic.Description;
                // Tunnels and virtual switches can't wake anything.
                if (d.Contains("Tailscale", StringComparison.OrdinalIgnoreCase) || d.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) ||
                    d.Contains("Virtual", StringComparison.OrdinalIgnoreCase) || d.Contains("VPN", StringComparison.OrdinalIgnoreCase) ||
                    d.Contains("Loopback", StringComparison.OrdinalIgnoreCase))
                    continue;
                if (!nic.GetIPProperties().UnicastAddresses.Any(u => u.Address.AddressFamily == AddressFamily.InterNetwork)) continue;
                list.Add(d);
            }
        }
        catch (Exception)
        {
        }
        return list;
    }

    /// <summary>Devices Windows lets wake the PC right now (no administrator needed to ask).</summary>
    static HashSet<string> Armed()
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            using var p = Process.Start(new ProcessStartInfo("powercfg.exe", "/devicequery wake_armed")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
            });
            if (p is null) return set;
            var output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(5000);
            foreach (var line in output.Split('\n')) if (line.Trim() is { Length: > 0 } name) set.Add(name);
        }
        catch (Exception)
        {
        }
        return set;
    }

    /// <summary>
    /// True when every adapter facing the TVs may wake the PC; null when there is none to check
    /// (no network).
    /// </summary>
    public static bool? Ready()
    {
        var adapters = HomeAdapters();
        if (adapters.Count == 0) return null;
        var armed = Armed();
        return adapters.All(armed.Contains);
    }

    /// <summary>
    /// Allows (or stops allowing) the home network adapters to wake the PC with a magic packet,
    /// under one administrator prompt. Null when done, else what went wrong.
    /// </summary>
    public static async Task<string?> SetAsync(bool on)
    {
        var adapters = HomeAdapters();
        if (adapters.Count == 0) return "nenhuma placa de rede conectada";
        static string Quote(string s) => "'" + s.Replace("'", "''") + "'";
        var lines = new List<string> { "$ErrorActionPreference = 'Continue'" };
        foreach (var d in adapters)
        {
            if (on)
            {
                // The adapter answers magic packets, and Windows lets it wake the PC.
                lines.Add($"Get-NetAdapter -InterfaceDescription {Quote(d)} | Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue");
                lines.Add($"powercfg /deviceenablewake {Quote(d)} | Out-Null");
            }
            else
            {
                lines.Add($"powercfg /devicedisablewake {Quote(d)} | Out-Null");
            }
        }
        lines.Add("exit 0");
        var script = Path.Combine(Path.GetTempPath(), "Jinky", "wake-on-lan.ps1");
        Directory.CreateDirectory(Path.GetDirectoryName(script)!);
        // UTF-8 with a BOM: Windows PowerShell 5.1 reads adapter names with accents right.
        await File.WriteAllLinesAsync(script, lines, new System.Text.UTF8Encoding(true));
        try
        {
            using var p = Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -ExecutionPolicy Bypass -File \"{script}\"")
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            if (p is null) return "o Windows não deu permissão";
            await p.WaitForExitAsync().WaitAsync(TimeSpan.FromMinutes(2));
        }
        catch (Exception)
        {
            return "o Windows não deu permissão";  // UAC refused
        }
        finally
        {
            try { File.Delete(script); } catch (Exception) { }
        }
        // The Wi-Fi may restart for a moment after the change; check once it settled.
        for (int i = 0; i < 10; i++)
        {
            if (Ready() == on) return null;
            await Task.Delay(1000);
        }
        return on ? "a placa de rede deste PC não aceitou acordar o PC pela rede" : "não deu para desfazer";
    }
}
