using System.IO;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Jinky;

/// <summary>
/// A game's store page in short, for the TV's game page: description, genres, who made it, when,
/// screenshots and trailers (in Portuguese), from Steam's public store API. Cached on disk for a
/// week so pages open instantly and the store isn't asked over and over.
/// </summary>
public static partial class SteamStore
{
    public sealed record Media(string Thumb, string Full);
    public sealed record Trailer(string Name, string Thumb, string Hls);
    public sealed record Details(string Description, string[] Genres, string Developer, string Released,
                                 int Metacritic, Media[] Screenshots, Trailer[] Trailers);

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };
    static string CacheDir => Path.Combine(Settings.Folder, "details");

    /// <summary>The store details of a Steam app, or null (non-Steam game, offline, not in the store).</summary>
    public static Details? Get(int id)
    {
        if (id <= 0) return null;
        var cache = Path.Combine(CacheDir, $"{id}.json");
        try
        {
            if (File.Exists(cache) && DateTime.UtcNow - File.GetLastWriteTimeUtc(cache) < TimeSpan.FromDays(7))
                return JsonSerializer.Deserialize<Details>(File.ReadAllText(cache));
            var json = Http.GetStringAsync($"https://store.steampowered.com/api/appdetails?appids={id}&l=brazilian&cc=br")
                .GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            var entry = doc.RootElement.GetProperty(id.ToString());
            if (!entry.GetProperty("success").GetBoolean()) return null;
            var d = entry.GetProperty("data");
            var details = new Details(
                Clean(Str(d, "short_description")),
                d.TryGetProperty("genres", out var g) ? g.EnumerateArray().Select(x => Str(x, "description")).Take(4).ToArray() : [],
                d.TryGetProperty("developers", out var dev) ? string.Join(", ", dev.EnumerateArray().Select(x => x.GetString()).Take(2)) : "",
                d.TryGetProperty("release_date", out var r) ? Str(r, "date") : "",
                d.TryGetProperty("metacritic", out var m) && m.TryGetProperty("score", out var sc) ? sc.GetInt32() : 0,
                d.TryGetProperty("screenshots", out var ss)
                    ? ss.EnumerateArray().Take(12).Select(x => new Media(Str(x, "path_thumbnail"), Str(x, "path_full"))).ToArray()
                    : [],
                d.TryGetProperty("movies", out var mv)
                    ? mv.EnumerateArray().Where(x => Str(x, "hls_h264") != "").Take(4)
                        .Select(x => new Trailer(Str(x, "name"), Str(x, "thumbnail"), Str(x, "hls_h264"))).ToArray()
                    : []);
            Directory.CreateDirectory(CacheDir);
            File.WriteAllText(cache, JsonSerializer.Serialize(details));
            return details;
        }
        catch (Exception)
        {
            // Offline or the store refused (rate limit): use an old copy if there is one.
            try { return File.Exists(cache) ? JsonSerializer.Deserialize<Details>(File.ReadAllText(cache)) : null; }
            catch (Exception) { return null; }
        }
    }

    static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    /// <summary>Store text comes with HTML entities and the odd tag.</summary>
    static string Clean(string html) => WebUtility.HtmlDecode(Tags().Replace(html, "")).Trim();

    [GeneratedRegex("<[^>]+>")]
    private static partial Regex Tags();
}
