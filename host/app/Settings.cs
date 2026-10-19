using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace Jinky;

/// <summary>User preferences, stored in %LOCALAPPDATA%\Jinky\settings.json.</summary>
public sealed class Settings
{
    /// <summary>Big Picture + 60 Hz + sound on the TV while a TV is connected.</summary>
    public bool ConsoleMode { get; set; } = true;

    /// <summary>When console mode is off: still send the sound only to the TV.</summary>
    public bool SoundOnlyOnTv { get; set; } = true;

    public static string Folder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Jinky");

    static string FilePath => Path.Combine(Folder, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch (Exception)
        {
            // A corrupt file must never stop the app from starting; fall back to defaults.
        }
        return new Settings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Folder);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    /// <summary>Engine arguments for these preferences.</summary>
    public string EngineArguments()
    {
        var args = "--events --stdin-control";
        if (ConsoleMode) args += " --console";
        else if (SoundOnlyOnTv) args += " --mute-pc";
        return args;
    }
}

/// <summary>"Start with Windows": a value under HKCU\...\Run (no admin rights needed).</summary>
public static class StartWithWindows
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string Name = "Jinky";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
            key.SetValue(Name, $"\"{Environment.ProcessPath}\" --tray");
        else
            key.DeleteValue(Name, throwOnMissingValue: false);
    }
}
