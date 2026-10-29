using System.IO;
using System.Text.Json;

namespace Jinky;

/// <summary>
/// The player's choices about the TV's library, made in the PC app's "Biblioteca" page: games
/// hidden from the TV, favourites (pinned on the TV's home) and covers of their own.
/// %LOCALAPPDATA%\Jinky\library-prefs.json; covers in art\custom\(id).png|jpg.
/// </summary>
public static class LibraryPrefs
{
    sealed class Data
    {
        public HashSet<int> Hidden { get; set; } = [];
        public HashSet<int> Favorites { get; set; } = [];
    }

    static readonly object Lock = new();
    static Data? _data;
    static string FilePath => Path.Combine(Settings.Folder, "library-prefs.json");
    static string CoverFolder => Path.Combine(Settings.Folder, "art", "custom");

    /// <summary>Raised (on any thread) when something changed: the TV's library follows.</summary>
    public static event Action? Changed;

    static Data Load()
    {
        if (_data is not null) return _data;
        try { _data = File.Exists(FilePath) ? JsonSerializer.Deserialize<Data>(File.ReadAllText(FilePath)) : null; }
        catch (Exception) { }
        return _data ??= new Data();
    }

    static void Save()
    {
        try
        {
            Directory.CreateDirectory(Settings.Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(_data));
        }
        catch (Exception)
        {
        }
        Changed?.Invoke();
    }

    public static bool IsHidden(int id)
    {
        lock (Lock) return Load().Hidden.Contains(id);
    }

    public static bool IsFavorite(int id)
    {
        lock (Lock) return Load().Favorites.Contains(id);
    }

    public static void SetHidden(int id, bool hidden)
    {
        lock (Lock)
        {
            var d = Load();
            if (hidden ? !d.Hidden.Add(id) : !d.Hidden.Remove(id)) return;
            if (hidden) d.Favorites.Remove(id);
            Save();
        }
    }

    public static void SetFavorite(int id, bool favorite)
    {
        lock (Lock)
        {
            var d = Load();
            if (favorite ? !d.Favorites.Add(id) : !d.Favorites.Remove(id)) return;
            if (favorite) d.Hidden.Remove(id);
            Save();
        }
    }

    /// <summary>The player's own cover for game [id], if any.</summary>
    public static string? CustomCover(int id)
    {
        foreach (var ext in new[] { ".png", ".jpg" })
        {
            var path = Path.Combine(CoverFolder, id + ext);
            if (File.Exists(path)) return path;
        }
        return null;
    }

    /// <summary>Copies [image] as game [id]'s cover (PNG or JPEG). Null on success, else why not.</summary>
    public static string? SetCustomCover(int id, string image)
    {
        try
        {
            var ext = Path.GetExtension(image).ToLowerInvariant() switch
            {
                ".png" => ".png",
                ".jpg" or ".jpeg" => ".jpg",
                _ => null,
            };
            if (ext is null) return "use uma imagem PNG ou JPG";
            if (new FileInfo(image).Length > 20 * 1024 * 1024) return "a imagem passa de 20 MB";
            Directory.CreateDirectory(CoverFolder);
            RemoveCustomCover(id, notify: false);
            File.Copy(image, Path.Combine(CoverFolder, id + ext), overwrite: true);
            Changed?.Invoke();
            return null;
        }
        catch (Exception e)
        {
            return e.Message;
        }
    }

    public static void RemoveCustomCover(int id, bool notify = true)
    {
        foreach (var ext in new[] { ".png", ".jpg" })
        {
            try { File.Delete(Path.Combine(CoverFolder, id + ext)); } catch (Exception) { }
        }
        if (notify) Changed?.Invoke();
    }
}
