using System.Globalization;
using System.IO;

namespace Jinky;

/// <summary>
/// Screenshots taken from the TV ("Capturar tela"): the engine saves them as
/// Pictures\Jinky\yyyy-MM-dd HH-mm-ss_APPID.jpg. The TV lists, shows and deletes them (deleted
/// files go to the Recycle Bin).
/// </summary>
public static class Captures
{
    public sealed record Capture(string Name, long Time, int GameId, long Bytes);

    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Jinky");

    /// <summary>Newest first.</summary>
    public static List<Capture> List()
    {
        if (!Directory.Exists(Folder)) return [];
        var list = new List<Capture>();
        foreach (var f in new DirectoryInfo(Folder).EnumerateFiles("*.jpg"))
        {
            var stem = Path.GetFileNameWithoutExtension(f.Name);
            var underscore = stem.LastIndexOf('_');
            var id = underscore > 0 && int.TryParse(stem[(underscore + 1)..], out var v) ? v : 0;
            var time = underscore > 0 && DateTime.TryParseExact(stem[..underscore], "yyyy-MM-dd HH-mm-ss", CultureInfo.InvariantCulture,
                                                                 DateTimeStyles.AssumeLocal, out var t)
                ? t : f.LastWriteTime;
            list.Add(new Capture(f.Name, new DateTimeOffset(time).ToUnixTimeSeconds(), id, f.Length));
        }
        return list.OrderByDescending(c => c.Time).ToList();
    }

    /// <summary>The file for [name], only if it is a capture in the folder (no paths from outside).</summary>
    public static string? PathOf(string? name)
    {
        if (string.IsNullOrEmpty(name) || name != Path.GetFileName(name) || !name.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase))
            return null;
        var path = Path.Combine(Folder, name);
        return File.Exists(path) ? path : null;
    }

    /// <summary>To the Recycle Bin (it can still be restored on the PC).</summary>
    public static bool Delete(string? name)
    {
        if (PathOf(name) is not { } path) return false;
        try
        {
            Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(path, Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs,
                                                               Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
