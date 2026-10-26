using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Jinky;

/// <summary>
/// "Suspender o PC" / "Desligar o PC" from a paired TV (the engine checks the TV's key first).
/// Sleep is Windows' normal sleep; shutting down is a normal shutdown, so open programs may still
/// ask to save their work.
/// </summary>
public static class Power
{
    [DllImport("powrprof.dll", SetLastError = true)]
    static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

    public static bool Run(string action)
    {
        try
        {
            if (action == "sleep") return SetSuspendState(false, false, false);
            if (action == "shutdown")
            {
                using var p = Process.Start(new ProcessStartInfo("shutdown.exe", "/s /t 0") { CreateNoWindow = true, UseShellExecute = false });
                return p is not null;
            }
        }
        catch (Exception)
        {
        }
        return false;
    }
}
