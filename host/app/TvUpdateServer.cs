using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Jinky;

/// <summary>
/// Serves the TV app that ships with this Jinky version to TVs on the home network, so TVs update
/// from the PC (no store, no internet). GET /tv/version -> "versionCode versionName";
/// GET /tv/jinky.apk -> the APK. Android only installs it over the existing app if it carries the
/// same signature, so a tampered file is refused by the TV itself.
/// </summary>
public sealed class TvUpdateServer : IDisposable
{
    public const int Port = 47101;
    readonly TcpListener _listener = new(IPAddress.Any, Port);
    readonly CancellationTokenSource _stop = new();

    public static string ApkPath => Path.Combine(AppContext.BaseDirectory, "tv", "jinky-tv.apk");
    public static bool Available => File.Exists(ApkPath);

    /// <summary>Same formula as the TV build: "0.3.0" -> 300.</summary>
    public static int VersionCode
    {
        get
        {
            var v = typeof(TvUpdateServer).Assembly.GetName().Version ?? new Version(0, 0, 0);
            return v.Major * 10000 + v.Minor * 100 + Math.Max(v.Build, 0);
        }
    }

    public bool Start()
    {
        if (!Available) return false;
        try
        {
            _listener.Start();
        }
        catch (SocketException)
        {
            return false;  // port busy: TVs just won't see an update offer
        }
        _ = Task.Run(AcceptLoop);
        return true;
    }

    async Task AcceptLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token);
            }
            catch (Exception)
            {
                return;
            }
            _ = Task.Run(() => Handle(client));
        }
    }

    static bool IsHomeNetwork(IPAddress a)
    {
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        var b = a.GetAddressBytes();
        return b.Length == 4 && (b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
                                 (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) || b[0] == 127);
    }

    static async Task Handle(TcpClient client)
    {
        using (client)
        {
            try
            {
                if (client.Client.RemoteEndPoint is not IPEndPoint remote || !IsHomeNetwork(remote.Address)) return;
                client.ReceiveTimeout = client.SendTimeout = 15000;
                var stream = client.GetStream();
                using var reader = new StreamReader(stream, Encoding.ASCII, false, 1024, leaveOpen: true);
                var request = await reader.ReadLineAsync() ?? "";
                while (!string.IsNullOrEmpty(await reader.ReadLineAsync())) { }  // skip headers

                var path = request.Split(' ') is { Length: >= 2 } parts && parts[0] == "GET" ? parts[1] : "";
                if (path == "/tv/version")
                {
                    var body = Encoding.ASCII.GetBytes($"{VersionCode} {typeof(TvUpdateServer).Assembly.GetName().Version?.ToString(3)}");
                    await Respond(stream, "200 OK", "text/plain", body.Length);
                    await stream.WriteAsync(body);
                }
                else if (path == "/tv/jinky.apk" && Available)
                {
                    await using var file = File.OpenRead(ApkPath);
                    await Respond(stream, "200 OK", "application/vnd.android.package-archive", file.Length);
                    await file.CopyToAsync(stream);
                }
                else
                {
                    await Respond(stream, "404 Not Found", "text/plain", 0);
                }
            }
            catch (Exception)
            {
                // A TV that went away mid-download; nothing to do.
            }
        }
    }

    static Task Respond(Stream s, string status, string type, long length)
    {
        var head = $"HTTP/1.1 {status}\r\nContent-Type: {type}\r\nContent-Length: {length}\r\nConnection: close\r\n\r\n";
        return s.WriteAsync(Encoding.ASCII.GetBytes(head)).AsTask();
    }

    public void Dispose()
    {
        _stop.Cancel();
        _listener.Stop();
    }
}
