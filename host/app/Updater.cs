using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace Jinky;

/// <summary>An update on offer; [Test] = a pre-release, only offered to PCs that asked for them.</summary>
public sealed record UpdateInfo(Version Version, string SetupUrl, string ChecksumUrl, string Notes, bool Test = false);

/// <summary>
/// Checks GitHub Releases for a newer Jinky, downloads the installer, verifies its SHA-256 against
/// the checksum file published with it, and runs it silently. The installer closes Jinky cleanly,
/// updates it (and the TV app it serves), and reopens it in the tray.
/// </summary>
public sealed class Updater
{
    /// <summary>Public repository that holds only the release installers (the code stays private).</summary>
    public const string Repository = "poncianomateus/jinky-releases";

    static readonly HttpClient Http = CreateClient();

    static HttpClient CreateClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd($"Jinky/{Current}");
        return c;
    }

    public static Version Current => typeof(Updater).Assembly.GetName().Version is { } v ? new Version(v.Major, v.Minor, Math.Max(v.Build, 0)) : new Version(0, 0, 0);

    public static bool Configured => !Repository.StartsWith("OWNER/");

    /// <summary>
    /// The newest release if it is newer than this Jinky, else null. Never throws. Releases are
    /// published as pre-releases ("teste") first and promoted once tested: everyone gets the
    /// latest stable one; with [tests] (the developer's own PC) pre-releases count too.
    /// </summary>
    public static async Task<UpdateInfo?> CheckAsync(bool tests = false)
    {
        if (!Configured) return null;
        try
        {
            if (!tests)
            {
                using var latest = JsonDocument.Parse(await Http.GetStringAsync($"https://api.github.com/repos/{Repository}/releases/latest"));
                return Offer(latest.RootElement);
            }
            using var all = JsonDocument.Parse(await Http.GetStringAsync($"https://api.github.com/repos/{Repository}/releases?per_page=20"));
            return all.RootElement.EnumerateArray()
                      .Where(r => !(r.TryGetProperty("draft", out var d) && d.GetBoolean()))
                      .Select(Offer).OfType<UpdateInfo>()
                      .MaxBy(u => u.Version);
        }
        catch (Exception)
        {
            return null;  // offline, rate-limited, etc.: try again later
        }
    }

    // A release as an update for this PC: newer than this Jinky and complete, else null.
    static UpdateInfo? Offer(JsonElement release)
    {
        var tag = release.GetProperty("tag_name").GetString()?.TrimStart('v') ?? "";
        if (!Version.TryParse(tag, out var version) || version <= Current) return null;
        string? setup = null, sum = null;
        foreach (var a in release.GetProperty("assets").EnumerateArray())
        {
            var name = a.GetProperty("name").GetString() ?? "";
            var url = a.GetProperty("browser_download_url").GetString();
            if (name.EndsWith(".exe.sha256", StringComparison.OrdinalIgnoreCase)) sum = url;
            else if (name.StartsWith("Jinky-Setup-", StringComparison.OrdinalIgnoreCase) && name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) setup = url;
        }
        if (setup is null || sum is null) return null;  // incomplete release: ignore it
        var notes = release.TryGetProperty("body", out var b) ? b.GetString() ?? "" : "";
        var test = release.TryGetProperty("prerelease", out var p) && p.GetBoolean();
        return new UpdateInfo(version, setup, sum, notes, test);
    }

    /// <summary>Downloads, verifies and launches the installer. Progress 0..1. Returns an error or null.</summary>
    public static async Task<string?> InstallAsync(UpdateInfo update, IProgress<double>? progress = null)
    {
        try
        {
            var expected = (await Http.GetStringAsync(update.ChecksumUrl)).Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries)[0].ToLowerInvariant();
            var path = Path.Combine(Path.GetTempPath(), $"Jinky-Setup-{update.Version}.exe");
            using (var response = await Http.GetAsync(update.SetupUrl, HttpCompletionOption.ResponseHeadersRead))
            {
                response.EnsureSuccessStatusCode();
                var total = response.Content.Headers.ContentLength ?? 0;
                await using var input = await response.Content.ReadAsStreamAsync();
                await using var output = File.Create(path);
                var buffer = new byte[81920];
                long done = 0;
                int n;
                while ((n = await input.ReadAsync(buffer)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, n));
                    done += n;
                    if (total > 0) progress?.Report((double)done / total);
                }
            }
            string actual;
            await using (var f = File.OpenRead(path)) actual = Convert.ToHexString(await SHA256.HashDataAsync(f)).ToLowerInvariant();
            if (actual != expected)
            {
                File.Delete(path);
                return "O arquivo baixado não confere com a assinatura da versão. Nada foi instalado.";
            }
            // The installer asks Windows for administrator rights (firewall rules, driver), closes
            // this Jinky cleanly, updates it and reopens it in the tray.
            Process.Start(new ProcessStartInfo(path, "/SILENT /SUPPRESSMSGBOXES /NORESTART /SP-") { UseShellExecute = true, Verb = "runas" });
            return null;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return "A atualização precisa da permissão de administrador do Windows.";
        }
        catch (Exception ex)
        {
            return $"Não foi possível baixar a atualização: {ex.Message}";
        }
    }
}
