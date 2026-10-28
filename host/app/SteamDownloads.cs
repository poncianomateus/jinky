using System.Text.Json;

namespace Jinky;

/// <summary>
/// Steam's downloads, live: the game downloading now (percent, speed, peak, disk, time left,
/// paused) and the queue, read from the Steam client itself (its download manager, the same data
/// its Downloads page shows) through the developer interface "Ler direto da Steam" opens. The
/// library manifests only get written now and then, so this is what makes the TV's progress move
/// in real time. Speeds are sampled here every second, for the TV's speed graph.
/// </summary>
public static class SteamDownloads
{
    public sealed record Queued(int Id, string Name, int Percent, bool Paused);

    public sealed record Live(int Id, string Name, string State, int Percent, long Speed, long Peak, long Disk,
                              int EtaSeconds, bool Paused, bool Install, List<Queued> Queue, List<long> History);

    // Read-only: the download overview, the queue, and who is signed in (two Windows users can each
    // run a Steam; only this user's counts).
    const string Query = """
        JSON.stringify((() => {
          const d = downloadsStore, o = d.LocalDownloadOverview || {};
          const name = id => ((appStore.GetAppOverviewByAppID(id) || {}).display_name || '');
          return {
            user: (typeof App !== 'undefined' && App.m_CurrentUser && App.m_CurrentUser.strSteamID) || '',
            id: o.update_appid || 0, name: name(o.update_appid), state: o.update_state || '',
            pct: o.overall_percent_complete || 0, speed: o.update_network_bytes_per_second || 0,
            peak: o.update_peak_network_bytes_per_second || 0, disk: o.update_disc_bytes_per_second || 0,
            eta: o.overall_estimated_time_remaining_sec || 0, paused: !!o.paused, install: !!o.update_is_install,
            queue: (d.QueuedTransfers || []).map(t => ({ id: t.appid, name: name(t.appid),
              pct: t.overall_percent_complete || 0, paused: !!t.paused, active: !!t.active }))
          };
        })())
        """;

    static readonly object Lock = new();
    static Live? _last;
    static DateTime _at;
    static readonly List<long> History = [];  // bytes per second, one a second, the last two minutes
    static int _historyApp;
    static DateTime _sampledAt;

    /// <summary>The live state (at most a second old); null when Steam can't be read this way.</summary>
    public static Live? Now()
    {
        lock (Lock)
            if (DateTime.UtcNow - _at < TimeSpan.FromMilliseconds(800)) return _last;
        var json = SteamClient.Run(Query);
        Live? live = null;
        if (json is not null)
        {
            try
            {
                using var doc = JsonDocument.Parse(json);
                var r = doc.RootElement;
                var user = r.GetProperty("user").GetString() ?? "";
                var steam = SteamLibrary.SteamPath();
                var mine = user.Length == 0 || steam is null || SteamLibrary.SteamId(steam) is not { } me || me == user;
                if (mine)
                {
                    var queue = r.GetProperty("queue").EnumerateArray()
                        .Select(q => new Queued(q.GetProperty("id").GetInt32(), q.GetProperty("name").GetString() ?? "",
                                                (int)q.GetProperty("pct").GetDouble(), q.GetProperty("paused").GetBoolean()))
                        .Where(q => SteamLibrary.IsGame(q.Id, q.Name) && q.Name.Length > 0)
                        .ToList();
                    var id = r.GetProperty("id").GetInt32();
                    var name = r.GetProperty("name").GetString() ?? "";
                    if (!SteamLibrary.IsGame(id, name)) id = 0;  // a Steam tool updating: nothing to show
                    lock (Lock)
                    {
                        var speed = (long)r.GetProperty("speed").GetDouble();
                        if (id != _historyApp)
                        {
                            History.Clear();
                            _historyApp = id;
                        }
                        if (id != 0 && DateTime.UtcNow - _sampledAt >= TimeSpan.FromMilliseconds(900))
                        {
                            History.Add(speed);
                            if (History.Count > 120) History.RemoveAt(0);
                            _sampledAt = DateTime.UtcNow;
                        }
                        live = new Live(id, name, r.GetProperty("state").GetString() ?? "",
                                        (int)r.GetProperty("pct").GetDouble(), speed, (long)r.GetProperty("peak").GetDouble(),
                                        (long)r.GetProperty("disk").GetDouble(), (int)r.GetProperty("eta").GetDouble(),
                                        r.GetProperty("paused").GetBoolean(), r.GetProperty("install").GetBoolean(),
                                        queue, [.. History]);
                    }
                }
            }
            catch (Exception)
            {
                live = null;  // Steam changed its page: the TV falls back to the manifests
            }
        }
        lock (Lock)
        {
            _last = live;
            _at = DateTime.UtcNow;
        }
        return live;
    }
}
