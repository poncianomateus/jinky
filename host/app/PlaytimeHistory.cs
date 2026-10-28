using System.IO;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// "Tempo de jogo da semana": Steam only keeps each game's total play time, so Jinky writes those
/// totals down once a day (%LOCALAPPDATA%\Jinky\playtime.json, the last 15 days) and the week is
/// today's total minus the one from 7 days ago (or the oldest it has, in the first days).
/// </summary>
public static class PlaytimeHistory
{
    static readonly object Lock = new();
    static Dictionary<string, Dictionary<int, int>>? _days;  // "yyyy-MM-dd" -> game id -> minutes
    static string FilePath => Path.Combine(Settings.Folder, "playtime.json");

    static Dictionary<string, Dictionary<int, int>> Days()
    {
        if (_days is not null) return _days;
        try
        {
            if (File.Exists(FilePath))
                _days = JsonSerializer.Deserialize<Dictionary<string, Dictionary<int, int>>>(File.ReadAllText(FilePath));
        }
        catch (Exception)
        {
        }
        return _days ??= [];
    }

    static string Key(DateTime d) => d.ToString("yyyy-MM-dd");

    /// <summary>Writes down today's totals (overwriting earlier ones of today).</summary>
    public static void Record(IEnumerable<SteamGame> games)
    {
        var totals = new Dictionary<int, int>();
        foreach (var g in games)
            if (g.Minutes > 0) totals[g.Id] = g.Minutes;
        if (totals.Count == 0) return;  // Steam unreadable right now: keep what there is
        lock (Lock)
        {
            var days = Days();
            days[Key(DateTime.Today)] = totals;
            foreach (var old in days.Keys.Where(k => string.CompareOrdinal(k, Key(DateTime.Today.AddDays(-15))) < 0).ToList())
                days.Remove(old);
            try
            {
                Directory.CreateDirectory(Settings.Folder);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(days));
            }
            catch (Exception)
            {
            }
        }
    }

    /// <summary>
    /// Minutes played per game over the last [Days] days (7 once there is a week of record; 0 days
    /// = no record yet).
    /// </summary>
    public sealed record Week(int Days, Dictionary<int, int> Minutes)
    {
        public int Total => Minutes.Values.Sum();
    }

    public static Week ThisWeek()
    {
        lock (Lock)
        {
            var days = Days();
            if (!days.TryGetValue(Key(DateTime.Today), out var today)) return new Week(0, []);
            // The day that ended a week ago, else the oldest day before today.
            var weekAgo = Key(DateTime.Today.AddDays(-7));
            var baseKey = days.ContainsKey(weekAgo) ? weekAgo
                : days.Keys.Where(k => string.CompareOrdinal(k, weekAgo) > 0 && string.CompareOrdinal(k, Key(DateTime.Today)) < 0)
                      .OrderBy(k => k, StringComparer.Ordinal).FirstOrDefault();
            if (baseKey is null) return new Week(0, []);
            var baseline = days[baseKey];
            var span = (DateTime.Today - DateTime.ParseExact(baseKey, "yyyy-MM-dd", null)).Days;
            var minutes = new Dictionary<int, int>();
            foreach (var (id, total) in today)
                if (baseline.TryGetValue(id, out var before) && total > before) minutes[id] = total - before;
                else if (!baseline.ContainsKey(id) && baseline.Count > 0 && total > 0 && total <= span * 24 * 60)
                    minutes[id] = total;  // a game first played since then (all its time is new)
            return new Week(span, minutes);
        }
    }
}
