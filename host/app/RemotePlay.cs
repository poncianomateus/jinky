using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Jinky;

/// <summary>
/// "Jogar fora de casa (Tailscale)": the TV reaches this PC over Tailscale, a private network
/// between the user's own devices (no router ports opened). Tailscale addresses are 100.64.0.0/10.
/// Pairing still happens at home; away, the TV finds the PC by its Tailscale address.
/// </summary>
public static class RemotePlay
{
    /// <summary>A Tailscale address (100.64.0.0/10).</summary>
    public static bool IsTailscale(IPAddress a)
    {
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        var b = a.GetAddressBytes();
        return b.Length == 4 && b[0] == 100 && (b[1] & 0xC0) == 64;
    }

    /// <summary>This PC's Tailscale address, if Tailscale is running here.</summary>
    public static string? TailscaleAddress()
    {
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var u in nic.GetIPProperties().UnicastAddresses)
                    if (u.Address.AddressFamily == AddressFamily.InterNetwork && IsTailscale(u.Address))
                        return u.Address.ToString();
            }
        }
        catch (Exception)
        {
        }
        return null;
    }

    const string RuleUdp = "Jinky (fora de casa, Tailscale)";
    const string RuleTcp = "Jinky (fora de casa, Tailscale, TV)";

    /// <summary>
    /// Lets Tailscale devices through the Windows firewall to Jinky's ports (asks for administrator
    /// once). False if the user said no.
    /// </summary>
    public static bool AllowThroughFirewall(bool allow)
    {
        var script = allow
            ? $"netsh advfirewall firewall delete rule name=\"{RuleUdp}\" | Out-Null; netsh advfirewall firewall delete rule name=\"{RuleTcp}\" | Out-Null; " +
              $"netsh advfirewall firewall add rule name=\"{RuleUdp}\" dir=in action=allow protocol=UDP localport=47100-47139 remoteip=100.64.0.0/10 profile=any | Out-Null; " +
              $"netsh advfirewall firewall add rule name=\"{RuleTcp}\" dir=in action=allow protocol=TCP localport=47100-47139 remoteip=100.64.0.0/10 profile=any | Out-Null"
            : $"netsh advfirewall firewall delete rule name=\"{RuleUdp}\" | Out-Null; netsh advfirewall firewall delete rule name=\"{RuleTcp}\" | Out-Null";
        try
        {
            using var p = Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -ExecutionPolicy Bypass -Command \"{script.Replace("\"", "\\\"")}\"")
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            p?.WaitForExit(20000);
            return p is not null && p.ExitCode == 0;
        }
        catch (Exception)
        {
            return false;  // UAC refused
        }
    }
}
