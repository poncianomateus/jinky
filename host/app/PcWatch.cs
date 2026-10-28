namespace Jinky;

/// <summary>
/// Watches the PC for things the TV should hear about (see <see cref="Notices"/>): Steam downloads
/// and updates starting and finishing (with their speed, for "faltam 12 min"), and friends coming
/// online or starting a game. Also keeps the daily play-time record (<see cref="PlaytimeHistory"/>).
/// </summary>
public static class PcWatch
{
    static Timer? _timer;
    static int _busy;
    static Func<string?>? _apiKey;
    static readonly object Lock = new();

    static Dictionary<int, SteamLibrary.AppState>? _apps;
    static readonly Dictionary<int, (DateTime At, long Done)> _window = [];
    static readonly Dictionary<int, double> _speed = [];  // bytes per second
    static Dictionary<string, (int State, string Game)>? _friends;
    static DateTime _friendsAt, _playtimeAt;

    public static void Start(Func<string?> apiKey)
    {
        _apiKey = apiKey;
        _timer ??= new Timer(_ => Tick(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(10));
    }

    /// <summary>Seconds left for a game downloading or updating; -1 when unknown (just started, paused).</summary>
    public static int EtaSeconds(int id)
    {
        lock (Lock)
        {
            if (_apps is null || !_apps.TryGetValue(id, out var a) || !_speed.TryGetValue(id, out var speed) || speed < 1024) return -1;
            var left = Math.Max(0, a.Total - a.Done);
            return (int)Math.Min(int.MaxValue, left / speed);
        }
    }

    /// <summary>An installed game whose update is downloading: its progress (0..1); null when not updating.</summary>
    public static double? UpdateProgress(int id)
    {
        lock (Lock)
            return _apps is not null && _apps.TryGetValue(id, out var a) && a.Updating
                ? (a.Total > 0 ? Math.Clamp((double)a.Done / a.Total, 0, 1) : 0)
                : null;
    }

    static void Tick()
    {
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        try
        {
            WatchDownloads();
            WatchFriends();
            // The play-time record: the /library requests keep it fresh, this covers days without TV.
            if (DateTime.UtcNow - _playtimeAt > TimeSpan.FromMinutes(30))
            {
                _playtimeAt = DateTime.UtcNow;
                PlaytimeHistory.Record(SteamLibrary.Games(_apiKey?.Invoke()));
                EnsurePs2QuickResume();
            }
        }
        catch (Exception)
        {
        }
        finally
        {
            Interlocked.Exchange(ref _busy, 0);
        }
    }

    /// <summary>
    /// PS2 games' Quick Resume needs PINE on in PCSX2 (its local control channel): turned on while
    /// PCSX2 is closed, for every PCSX2 the Steam shortcuts start.
    /// </summary>
    public static void EnsurePs2QuickResume()
    {
        try
        {
            var steam = SteamLibrary.SteamPath();
            var me = steam is null ? null : SteamLibrary.SteamId(steam);
            if (steam is null || me is null) return;
            foreach (var exe in SteamShortcuts.Read(steam, me).Select(s => s.Exe).Where(Pcsx2.IsPcsx2).Distinct(StringComparer.OrdinalIgnoreCase))
                if (Pcsx2.EnsurePine(exe)) Ps2QuickResumeTurnedOn?.Invoke();
        }
        catch (Exception)
        {
        }
    }

    /// <summary>PINE was just turned on in PCSX2's settings (for the PC app's activity list).</summary>
    public static event Action? Ps2QuickResumeTurnedOn;

    static void WatchDownloads()
    {
        var now = SteamLibrary.AppStates().GroupBy(a => a.Id).ToDictionary(g => g.Key, g => g.First());
        Dictionary<int, SteamLibrary.AppState>? before;
        lock (Lock)
        {
            before = _apps;
            var t = DateTime.UtcNow;
            foreach (var a in now.Values)
            {
                var active = (!a.Installed || a.Updating) && a.Total > 0;
                if (!active)
                {
                    _window.Remove(a.Id);
                    _speed.Remove(a.Id);
                    continue;
                }
                if (!_window.TryGetValue(a.Id, out var w) || a.Done < w.Done)
                {
                    _window[a.Id] = (t, a.Done);
                    continue;
                }
                var secs = (t - w.At).TotalSeconds;
                if (secs < 10) continue;
                var speed = (a.Done - w.Done) / secs;
                _speed[a.Id] = _speed.TryGetValue(a.Id, out var old) ? old * 0.5 + speed * 0.5 : speed;
                if (secs >= 60) _window[a.Id] = (t, a.Done);  // the last minute or so counts
            }
            _apps = now;
        }
        if (before is null) return;  // the first look only learns what is there
        foreach (var a in now.Values)
        {
            if (!before.TryGetValue(a.Id, out var b))
            {
                if (!a.Installed) Notices.Post("downloadStart", a.Name, "Começou a baixar no PC", a.Id);
                continue;
            }
            if (!b.Installed && a.Installed) Notices.Post("download", a.Name, "Download concluído · pronto para jogar", a.Id);
            else if (b.Updating && a.Installed && !a.Updating) Notices.Post("update", a.Name, "Atualização concluída", a.Id);
        }
    }

    static void WatchFriends()
    {
        if (DateTime.UtcNow - _friendsAt < TimeSpan.FromSeconds(60)) return;
        _friendsAt = DateTime.UtcNow;
        var key = _apiKey?.Invoke();
        if (string.IsNullOrWhiteSpace(key)) return;
        var list = SteamProfile.AllFriends(key);
        if (list is null) return;
        var before = _friends;
        _friends = list.GroupBy(f => f.Id).ToDictionary(g => g.Key, g => (g.First().State, g.First().Game));
        if (before is null) return;
        var posted = 0;
        foreach (var f in list)
        {
            if (posted >= 3) break;  // a burst (Steam came back): a few, not a flood
            if (!before.TryGetValue(f.Id, out var b)) continue;
            if (f.Game.Length > 0 && f.Game != b.Game)
            {
                Notices.Post("friend", f.Name, $"Começou a jogar {f.Game}", f.GameId, f.Avatar);
                posted++;
            }
            else if (b.State == 0 && f.State > 0)
            {
                Notices.Post("friend", f.Name, "Ficou online", 0, f.Avatar);
                posted++;
            }
        }
    }
}
