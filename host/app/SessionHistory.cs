using System.Globalization;
using System.IO;
using System.Text.Json;

namespace Jinky;

/// <summary>One finished TV session, as the PC's home page lists it.</summary>
public sealed record SessionRecord(DateTime Start, int Minutes, string Tv, string Game, double Latency, double Mbps,
                                   double WorstLoss, int FramesLost)
{
    static readonly CultureInfo PtBr = CultureInfo.GetCultureInfo("pt-BR");

    public string Title => Game.Length > 0 ? Game : "Sem jogo aberto pela TV";
    public string When => (Start.Date == DateTime.Today ? "Hoje" : Start.Date == DateTime.Today.AddDays(-1) ? "Ontem" : Start.ToString("dd/MM", PtBr)) +
                          " às " + Start.ToString("HH:mm", PtBr) + " · " + Tv;
    public string Length => Minutes < 60 ? $"{Math.Max(Minutes, 1)} min" : $"{Minutes / 60} h {Minutes % 60:00}";
    public string Quality => (Latency > 0 ? Latency.ToString("0", PtBr) + " ms" : "—") + " · " +
                             (Mbps > 0 ? Mbps.ToString("0", PtBr) + " Mbit/s" : "—") +
                             (WorstLoss >= 1 ? " · perdas até " + WorstLoss.ToString("0.#", PtBr) + "%" : "");
    /// <summary>Good / so-so / bad, for the dot next to it.</summary>
    public string Grade => FramesLost > 20 || WorstLoss >= 5 || Latency > 120 ? "bad" : FramesLost > 0 || WorstLoss >= 2 || Latency > 90 ? "ok" : "good";
}

/// <summary>The last sessions (%LOCALAPPDATA%\Jinky\sessions.json, 20 at most).</summary>
public static class SessionHistory
{
    const int Max = 20;
    static string FilePath => Path.Combine(Settings.Folder, "sessions.json");

    public static List<SessionRecord> Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<List<SessionRecord>>(File.ReadAllText(FilePath)) ?? [];
        }
        catch (Exception)
        {
        }
        return [];
    }

    public static List<SessionRecord> Add(SessionRecord s)
    {
        var list = Load();
        list.Insert(0, s);
        if (list.Count > Max) list.RemoveRange(Max, list.Count - Max);
        try
        {
            Directory.CreateDirectory(Settings.Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(list));
        }
        catch (Exception)
        {
        }
        return list;
    }
}

/// <summary>The live session's last minutes, for the chart: latency and video rate, one point per report.</summary>
public sealed class SessionSamples
{
    public const int Capacity = 90;
    readonly Queue<double> _latency = new(), _mbps = new();

    public event Action? Changed;

    public IReadOnlyCollection<double> Latency => _latency;
    public IReadOnlyCollection<double> Mbps => _mbps;

    public void AddLatency(double ms) => Push(_latency, ms);
    public void AddMbps(double mbps) => Push(_mbps, mbps);

    void Push(Queue<double> q, double v)
    {
        q.Enqueue(v);
        while (q.Count > Capacity) q.Dequeue();
        Changed?.Invoke();
    }

    public void Clear()
    {
        _latency.Clear();
        _mbps.Clear();
        Changed?.Invoke();
    }
}
