using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// "Testar conexão": measures the home network between this PC and each TV (two seconds of
/// pings the size of video packets) and says in plain words what would make it better.
/// </summary>
public static class NetTest
{
    static readonly CultureInfo PtBr = CultureInfo.GetCultureInfo("pt-BR");
    static readonly object Lock = new();
    static Dictionary<string, (string Name, DateTime Seen)>? _tvs;
    static string FilePath => Path.Combine(Settings.Folder, "tv-addresses.json");

    sealed record Saved(string Name, DateTime Seen);

    static Dictionary<string, (string Name, DateTime Seen)> Tvs()
    {
        if (_tvs is not null) return _tvs;
        _tvs = [];
        try
        {
            if (File.Exists(FilePath) && JsonSerializer.Deserialize<Dictionary<string, Saved>>(File.ReadAllText(FilePath)) is { } saved)
                foreach (var (ip, s) in saved) _tvs[ip] = (s.Name, s.Seen);
        }
        catch (Exception)
        {
        }
        return _tvs;
    }

    /// <summary>A TV was seen at [ip] (its name when known: sessions tell it, the library requests don't).</summary>
    public static void Seen(string ip, string? name = null)
    {
        if (!IPAddress.TryParse(ip, out var a) || IPAddress.IsLoopback(a)) return;
        lock (Lock)
        {
            var tvs = Tvs();
            var known = tvs.TryGetValue(ip, out var old) ? old.Name : "";
            var changed = !tvs.ContainsKey(ip) || (name is { Length: > 0 } && name != known) || DateTime.Now - old.Seen > TimeSpan.FromHours(1);
            tvs[ip] = (name is { Length: > 0 } ? name : known, DateTime.Now);
            if (!changed) return;
            try
            {
                Directory.CreateDirectory(Settings.Folder);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(tvs.ToDictionary(t => t.Key, t => new Saved(t.Value.Name, t.Value.Seen))));
            }
            catch (Exception)
            {
            }
        }
    }

    /// <summary>The TVs seen over the last two weeks.</summary>
    public static List<(string Ip, string Name)> Known()
    {
        lock (Lock)
            return Tvs().Where(t => DateTime.Now - t.Value.Seen < TimeSpan.FromDays(14))
                        .OrderByDescending(t => t.Value.Seen)
                        .Select(t => (t.Key, t.Value.Name.Length > 0 ? t.Value.Name : "TV em " + t.Key))
                        .ToList();
    }

    public sealed record Result(string Tv, string Summary, string Advice, bool Good);

    /// <summary>Tests every known TV (seconds; call it off the UI thread).</summary>
    public static List<Result> Run()
    {
        var results = new List<Result>();
        var tvs = Known();
        if (tvs.Count == 0)
        {
            results.Add(new Result("Nenhuma TV encontrada", "O Jinky ainda não viu nenhuma TV na rede.",
                                   "Abra o Jinky na TV (com ela na mesma rede do PC) e teste de novo.", false));
            return results;
        }
        var (wifi, band, signal) = PcLink(tvs[0].Ip);
        foreach (var (ip, name) in tvs) results.Add(Test(ip, name, wifi, band, signal));
        return results;
    }

    static Result Test(string ip, string name, bool pcWifi, string band, int signal)
    {
        var times = new List<long>();
        int sent = 0, lost = 0;
        var payload = new byte[1200];  // about one video packet
        using (var ping = new Ping())
        {
            var clock = Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < 2500 && sent < 50)
            {
                sent++;
                try
                {
                    var reply = ping.Send(ip, 400, payload);
                    if (reply.Status == IPStatus.Success) times.Add(reply.RoundtripTime);
                    else lost++;
                }
                catch (Exception)
                {
                    lost++;
                }
                Thread.Sleep(30);
            }
        }
        if (times.Count == 0)
            return new Result(name, "A TV não respondeu.",
                              "Ela pode estar desligada, em outra rede, ou não responder a testes de rede. Abra o Jinky nela e tente de novo.", false);
        var avg = times.Average();
        var jitter = times.Count > 1 ? times.Zip(times.Skip(1), (a, b) => Math.Abs(a - b)).Average() : 0;
        var lossPct = lost * 100.0 / sent;
        var summary = $"{avg.ToString("0", PtBr)} ms de ida e volta · variação {jitter.ToString("0", PtBr)} ms · " +
                      (lossPct < 0.5 ? "sem perdas" : $"{lossPct.ToString("0.#", PtBr)}% de perdas");
        var advice = new List<string>();
        if (lossPct >= 2) advice.Add("Muitos pacotes se perdem: aproxime a TV do roteador (ou o roteador dela), tire obstáculos do caminho, ou use cabo de rede na TV.");
        else if (lossPct > 0) advice.Add("Algumas perdas: o Jinky corrige, mas a TV mais perto do roteador ajuda.");
        if (avg > 15 || jitter > 8) advice.Add("A rede está lenta ou instável: outros aparelhos baixando coisas atrapalham; prefira a rede de 5 GHz na TV.");
        if (pcWifi)
        {
            advice.Add(band == "2.4"
                ? "O PC está no Wi-Fi de 2,4 GHz: ligue o PC por cabo no roteador, ou pelo menos use a rede de 5 GHz."
                : "O PC está no Wi-Fi: um cabo de rede do PC até o roteador deixa a imagem mais estável.");
            if (signal is > 0 and < 60) advice.Add($"O sinal do Wi-Fi do PC está fraco ({signal}%).");
        }
        var good = lossPct < 2 && avg <= 15 && jitter <= 8;
        if (advice.Count == 0) advice.Add("Conexão ótima para jogar.");
        else if (good) advice.Insert(0, "Boa para jogar.");
        return new Result(name, summary, string.Join(" ", advice), good);
    }

    /// <summary>How this PC reaches [tvIp]: over Wi-Fi or cable, and the Wi-Fi band and signal.</summary>
    static (bool Wifi, string Band, int Signal) PcLink(string tvIp)
    {
        try
        {
            var target = IPAddress.Parse(tvIp);
            NetworkInterface? nic = null;
            foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (n.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var u in n.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask is null) continue;
                    var mask = BitConverter.ToUInt32(u.IPv4Mask.GetAddressBytes());
                    if ((BitConverter.ToUInt32(u.Address.GetAddressBytes()) & mask) == (BitConverter.ToUInt32(target.GetAddressBytes()) & mask)) nic = n;
                }
            }
            if (nic is null || nic.NetworkInterfaceType != NetworkInterfaceType.Wireless80211) return (false, "", 0);
            // Without Windows' location permission netsh says nothing: the link speed is a hint
            // (2.4 GHz links rarely pass 150 Mbit/s).
            var slow = nic.Speed is > 0 and <= 150_000_000;
            // "netsh wlan show interfaces": channel (above 14 = 5 GHz) and signal, in any language.
            var psi = new ProcessStartInfo("netsh", "wlan show interfaces") { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
            using var p = Process.Start(psi)!;
            var text = p.StandardOutput.ReadToEnd();
            p.WaitForExit(3000);
            int channel = 0, signal = 0;
            foreach (var line in text.Split('\n'))
            {
                var parts = line.Split(':', 2);
                if (parts.Length < 2) continue;
                var key = parts[0].Trim().ToLowerInvariant();
                var value = parts[1].Trim();
                if (key is "canal" or "channel" && int.TryParse(value, out var c)) channel = c;
                if (key is "sinal" or "signal" && int.TryParse(value.TrimEnd('%', ' '), out var sg)) signal = sg;
            }
            return (true, channel is > 0 and <= 14 ? "2.4" : channel > 14 ? "5" : slow ? "2.4" : "", signal);
        }
        catch (Exception)
        {
            return (false, "", 0);
        }
    }
}
