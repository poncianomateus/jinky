namespace Jinky;

/// <summary>
/// "Avisos do PC na TV": things worth knowing on the couch — a download or update finished, a
/// friend came online, a paused game was closed to free memory. The TV asks for the ones after the
/// last it saw (GET /notices?after=) and shows them in the corner, like achievements.
/// </summary>
public static class Notices
{
    public sealed record Notice(long Id, long At, string Kind, string Title, string Text, int GameId, string Icon);

    static readonly object Lock = new();
    static readonly List<Notice> Recent = [];
    static long _next = 1;

    /// <summary>
    /// kind: "download" (finished), "downloadStart", "update" (finished), "friend", "paused".
    /// [gameId]: the game it is about (its cover shows), 0 for none; [icon]: a picture URL instead
    /// (a friend's avatar).
    /// </summary>
    public static void Post(string kind, string title, string text, int gameId = 0, string icon = "")
    {
        lock (Lock)
        {
            Recent.Add(new Notice(_next++, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), kind, title, text, gameId, icon));
            if (Recent.Count > 50) Recent.RemoveAt(0);
        }
    }

    /// <summary>The notices after [after] (none for after &lt; 0: a TV just learning where it starts) and the last id.</summary>
    public static (long Last, List<Notice> List) After(long after)
    {
        lock (Lock)
        {
            var last = _next - 1;
            if (after < 0) return (last, []);
            // Only fresh ones: a TV that comes back hours later doesn't get a stale pile.
            var fresh = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 10 * 60_000;
            return (last, Recent.Where(n => n.Id > after && n.At >= fresh).ToList());
        }
    }
}
