using System.Diagnostics;
using System.IO;
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
    // The official installer (always the latest stable), straight from Tailscale.
    const string MsiUrl = "https://pkgs.tailscale.com/stable/tailscale-setup-latest-amd64.msi";

    static string TailscaleFolder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Tailscale");

    /// <summary>Tailscale is installed on this PC.</summary>
    public static bool TailscaleInstalled => File.Exists(Path.Combine(TailscaleFolder, "tailscale.exe"));

    /// <summary>
    /// Turns "Jogar fora de casa" on: downloads and installs Tailscale if it isn't here (only if
    /// the file is signed by Tailscale Inc.) and lets Tailscale devices through the firewall, all
    /// under one administrator prompt; then opens Tailscale so the user signs in. Off: removes the
    /// firewall rules (Tailscale stays; it is the user's to keep or uninstall). Null when done,
    /// else what went wrong.
    /// </summary>
    public static async Task<string?> SetupAsync(bool on, Action<string> progress)
    {
        string? msi = null;
        if (on && !TailscaleInstalled)
        {
            progress("Baixando o Tailscale…");
            msi = Path.Combine(Path.GetTempPath(), "Jinky", "tailscale-setup.msi");
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(msi)!);
                using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromMinutes(5) };
                await using var body = await http.GetStreamAsync(MsiUrl);
                await using var file = File.Create(msi);
                await body.CopyToAsync(file);
            }
            catch (Exception e)
            {
                return "não deu para baixar o Tailscale (" + e.Message + ")";
            }
            progress("Instalando o Tailscale… (o Windows vai pedir permissão)");
        }
        else if (on) progress("Liberando o firewall… (o Windows vai pedir permissão)");

        // One elevated script: firewall rules, and Tailscale itself when needed (ASCII only).
        var lines = new List<string>
        {
            "$ErrorActionPreference = 'Continue'",
            $"netsh advfirewall firewall delete rule name=\"{RuleUdp}\" | Out-Null",
            $"netsh advfirewall firewall delete rule name=\"{RuleTcp}\" | Out-Null",
        };
        if (on)
        {
            if (msi is not null)
            {
                lines.Add($"$msi = '{msi.Replace("'", "''")}'");
                lines.Add("$sig = Get-AuthenticodeSignature $msi");
                lines.Add("if ($sig.Status -ne 'Valid' -or $sig.SignerCertificate.Subject -notmatch 'Tailscale') { exit 5 }");
                lines.Add("$p = Start-Process msiexec.exe -ArgumentList @('/i', ('\"' + $msi + '\"'), '/quiet', '/norestart') -Wait -PassThru");
                lines.Add("if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { exit 6 }");
            }
            lines.Add($"netsh advfirewall firewall add rule name=\"{RuleUdp}\" dir=in action=allow protocol=UDP localport=47100-47139 remoteip=100.64.0.0/10 profile=any | Out-Null");
            lines.Add($"netsh advfirewall firewall add rule name=\"{RuleTcp}\" dir=in action=allow protocol=TCP localport=47100-47139 remoteip=100.64.0.0/10 profile=any | Out-Null");
        }
        lines.Add("exit 0");
        var script = Path.Combine(Path.GetTempPath(), "Jinky", "remote-play.ps1");
        Directory.CreateDirectory(Path.GetDirectoryName(script)!);
        await File.WriteAllLinesAsync(script, lines, System.Text.Encoding.ASCII);

        int code;
        try
        {
            using var p = Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -ExecutionPolicy Bypass -File \"{script}\"")
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            if (p is null) return "o Windows não deu permissão";
            await p.WaitForExitAsync().WaitAsync(TimeSpan.FromMinutes(5));
            code = p.ExitCode;
        }
        catch (Exception)
        {
            return "o Windows não deu permissão";  // UAC refused
        }
        finally
        {
            try { File.Delete(script); } catch (Exception) { }
            if (msi is not null) try { File.Delete(msi); } catch (Exception) { }
        }
        if (code == 5) return "o arquivo baixado não tem a assinatura da Tailscale; não foi instalado";
        if (code == 6) return "a instalação do Tailscale não terminou";
        if (code != 0) return $"algo deu errado (código {code})";

        // Tailscale open, for the sign-in (its own window/browser page).
        if (on && TailscaleAddress() is null) OpenTailscale();
        return null;
    }

    /// <summary>Opens Tailscale (its tray app; on a first run it asks the user to sign in).</summary>
    public static void OpenTailscale()
    {
        try
        {
            var ipn = Path.Combine(TailscaleFolder, "tailscale-ipn.exe");
            if (File.Exists(ipn)) Process.Start(new ProcessStartInfo(ipn) { UseShellExecute = true });
            var cli = Path.Combine(TailscaleFolder, "tailscale.exe");
            // "tailscale login" opens the sign-in page in the browser.
            if (File.Exists(cli)) Process.Start(new ProcessStartInfo(cli, "login") { UseShellExecute = false, CreateNoWindow = true });
        }
        catch (Exception)
        {
        }
    }
}
