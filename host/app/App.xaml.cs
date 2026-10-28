using System.IO;
using System.Windows;
using Forms = System.Windows.Forms;

namespace Jinky;

/// <summary>
/// One Jinky per user: it owns the engine and the tray icon. Closing the window keeps Jinky running
/// in the tray; "Sair" stops the engine cleanly (display and sound restored) and exits.
/// Starting Jinky again just brings the existing window forward.
/// </summary>
public partial class App : Application
{
    const string InstanceName = "Jinky.SingleInstance";
    Mutex? _instance;
    EventWaitHandle? _showSignal, _quitSignal;
    Forms.NotifyIcon? _tray;
    MainWindow? _window;
    Engine? _engine;
    TvService? _tvService;
    Settings _settings = new();
    MainViewModel? _vm;
    bool _trayHintShown;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _instance = new Mutex(true, InstanceName, out var first);
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Show");
        _quitSignal = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Quit");
        if (!first)
        {
            // "Jinky.exe --quit" asks the running Jinky to exit cleanly (installer, updates, scripts);
            // any other launch brings its window forward.
            (e.Args.Contains("--quit") ? _quitSignal : _showSignal).Set();
            Shutdown();
            return;
        }
        if (e.Args.Contains("--quit"))
        {
            Shutdown();
            return;
        }
        new Thread(() =>
        {
            if (_quitSignal.WaitOne()) Dispatcher.Invoke(Quit);
        }) { IsBackground = true }.Start();
        // Wake up and show the window when another launch signals us.
        new Thread(() =>
        {
            while (_showSignal.WaitOne()) Dispatcher.Invoke(ShowWindow);
        }) { IsBackground = true }.Start();

        _settings = Settings.Load();
        _vm = new MainViewModel(_settings, OnSettingsChanged, cmd => _engine?.Send(cmd));
        _window = new MainWindow(_vm);
        // Console mode turns the PC into a console: don't leave Jinky's window on the monitor.
        _vm.SessionStarted += () =>
        {
            if (_settings.ConsoleMode) _window?.Hide();
        };
        // The game the TV asked for starts once the engine says so (after Big Picture is up).
        _vm.ReadyToLaunch += () => _tvService?.OnSessionStarted();
        // A TV asking to pair needs the user's eyes on the code: bring the window forward.
        _vm.PairingRequested += () =>
        {
            ShowWindow();
            _tray?.ShowBalloonTip(5000, "Nova TV quer se conectar", $"{_vm.Pairing?.Name} · código {_vm.PairingCode}", Forms.ToolTipIcon.None);
        };
        _window.Closing += (_, args) =>
        {
            // The window's X hides to the tray; only "Sair" quits.
            if (_quitting) return;
            args.Cancel = true;
            _window.Hide();
            if (!_trayHintShown)
            {
                _tray?.ShowBalloonTip(3000, "Jinky continua ligado", "Ele fica aqui perto do relógio, pronto para a TV.", Forms.ToolTipIcon.None);
                _trayHintShown = true;
            }
        };

        CreateTray();

        TvService.AllowTailscale = _settings.RemotePlay;
        _tvService = new TvService();
        _vm.GamePausing = () =>
        {
            if (_tvService.CurrentGameId != 0) return (_tvService.CurrentGameId, _tvService.CurrentGameName);
            // Started from Big Picture, not from the TV's library: ask Steam which game it is.
            var id = SteamLibrary.RunningGame();
            return (id, SteamLibrary.Games().FirstOrDefault(g => g.Id == id)?.Name ?? "O jogo");
        };
        _tvService.DiagnosticsReceived += (tv, path) => Dispatcher.Invoke(() => _vm.OnTvDiagnostics(tv, path));
        _tvService.GameLaunched += name => Dispatcher.Invoke(() => _vm.OnGameLaunched(name));
        _tvService.Notice += text => Dispatcher.Invoke(() => _vm.OnNotice(text));
        GamePause.ResumedOnPc += name => Dispatcher.Invoke(() => _vm.OnNotice($"{name} continuou aqui no PC"));
        GamePause.Start();
        _vm.CheckWakeFromTv();
        _vm.CheckVirtualScreen();
        _tvService.Streaming = () => Dispatcher.Invoke(() => _vm.IsStreaming);
        _tvService.LaunchReady = () => Dispatcher.Invoke(() => _vm.LaunchReady);
        _tvService.SteamApiKey = () => _settings.SteamApiKey;
        _vm.CheckSteamKey();

        _engine = new Engine();
        _engine.EventReceived += ev => Dispatcher.Invoke(() => { _vm.OnEngineEvent(ev); UpdateTray(); });
        _engine.Failed += msg => Dispatcher.Invoke(() => { _vm.OnEngineFailed(msg); UpdateTray(); });
        _engine.Exited += () => Dispatcher.Invoke(() => { _vm.OnEngineExited(); UpdateTray(); });
        // Another Jinky took this one's port meanwhile: pick a free pair and start again.
        _engine.PortBusy += () => Task.Delay(3000).ContinueWith(_ => Dispatcher.Invoke(() =>
        {
            if (_quitting || !_onScreen) return;
            _tvService?.Stop();
            UsePorts();
            _tvService?.Start();
            RunEngine(e => e.Start(_settings.EngineArguments()));
        }));
        _onScreen = OnScreen();
        if (_onScreen)
        {
            UsePorts();
            _tvService.Start();
            _engine.Start(_settings.EngineArguments());
        }
        else
        {
            _vm.OnAway();
        }
        // Two Windows users signed in at once ("switch user"): only the one on the screen can
        // stream (Windows doesn't capture a disconnected session, and both Jinkys want the same
        // ports). The one left behind steps aside and the one in front takes over, both ways.
        // With ASTER both are on screen: the first to start keeps the ports (see port_busy).
        var sessionTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        sessionTimer.Tick += (_, _) => CheckSession();
        sessionTimer.Start();

        // Updates: check shortly after start, then every 6 hours. Never interrupts a game: the user
        // clicks "Atualizar" when it suits them.
        // Turning test versions on or off checks again at once.
        var recheck = new SemaphoreSlim(0);
        _vm.UpdateChannelChanged += () => recheck.Release();
        _ = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(20));
            while (true)
            {
                var update = await Updater.CheckAsync(_settings.WantsTestUpdates);
                Dispatcher.Invoke(() =>
                {
                    var isNew = update is not null && _vm.OfferedVersion != update.Version;
                    _vm.SetUpdate(update);
                    if (isNew)
                        _tray?.ShowBalloonTip(5000, "Atualização do Jinky disponível",
                                              $"Versão {update!.Version}{(update.Test ? " (teste)" : "")}. Abra o Jinky e clique em Atualizar.",
                                              Forms.ToolTipIcon.None);
                });
                await recheck.WaitAsync(TimeSpan.FromHours(6));
            }
        });

        if (!e.Args.Contains("--tray")) ShowWindow();
    }

    bool _quitting;

    void OnSettingsChanged()
    {
        _settings.Save();
        // The engine reads its options at start; restarting it takes about a second. While
        // another user has the screen it is off, and starts with the new options on return.
        if (_onScreen) RunEngine(e => e.Restart(_settings.EngineArguments()));
    }

    bool _onScreen = true;
    Task _engineWork = Task.CompletedTask;

    // Engine start/stop take up to a few seconds: one at a time, in order, off the UI thread.
    void RunEngine(Action<Engine> work)
    {
        var engine = _engine;
        if (engine is null) return;
        lock (this) _engineWork = _engineWork.ContinueWith(_ => work(engine), TaskScheduler.Default);
    }

    /// <summary>Takes a free pair of ports (another Jinky on this PC may have the usual ones).</summary>
    void UsePorts()
    {
        var slot = Ports.Pick();
        _vm?.OnPortsChanged(slot);
    }

    /// <summary>
    /// Whether this Windows user's session is on a screen: active, not disconnected. After "switch
    /// user" the one left behind is disconnected; with ASTER every workplace's session is active.
    /// </summary>
    static bool OnScreen()
    {
        if (!WTSQuerySessionInformation(IntPtr.Zero, -1 /* this session */, 8 /* WTSConnectState */, out var buffer, out _))
            return true;
        var state = System.Runtime.InteropServices.Marshal.ReadInt32(buffer);
        WTSFreeMemory(buffer);
        return state == 0;  // WTSActive
    }

    void CheckSession()
    {
        if (_quitting || _engine is null || _tvService is null || _vm is null) return;
        var onScreen = OnScreen();
        if (onScreen == _onScreen)
        {
            // Back on screen but the other user's Jinky hadn't freed the port yet: retry.
            if (onScreen && !_tvService.Running) _tvService.Start();
            return;
        }
        _onScreen = onScreen;
        if (!onScreen)
        {
            _tvService.Stop();
            RunEngine(e => e.Stop());
            _vm.OnAway();
        }
        else
        {
            _vm.OnBack();
            UsePorts();
            _tvService.Start();
            RunEngine(e => e.Start(_settings.EngineArguments()));
        }
        UpdateTray();
    }

    [System.Runtime.InteropServices.DllImport("wtsapi32.dll", SetLastError = true)]
    static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);

    [System.Runtime.InteropServices.DllImport("wtsapi32.dll")]
    static extern void WTSFreeMemory(IntPtr memory);

    void CreateTray()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "jinky.ico");
        _tray = new Forms.NotifyIcon
        {
            Icon = File.Exists(iconPath) ? new System.Drawing.Icon(iconPath, Forms.SystemInformation.SmallIconSize) : System.Drawing.SystemIcons.Application,
            Text = "Jinky",
            Visible = true,
            ContextMenuStrip = new Forms.ContextMenuStrip { Renderer = new TrayMenuRenderer(), ShowImageMargin = false },
        };
        _tray.ContextMenuStrip.Items.Add("Abrir o Jinky", null, (_, _) => ShowWindow());
        _tray.ContextMenuStrip.Items.Add(new Forms.ToolStripSeparator());
        _tray.ContextMenuStrip.Items.Add("Sair", null, (_, _) => Quit());
        _tray.MouseClick += (_, e) => { if (e.Button == Forms.MouseButtons.Left) ShowWindow(); };
    }

    void UpdateTray()
    {
        if (_tray is null || _vm is null) return;
        var text = $"Jinky — {_vm.PillText}";
        _tray.Text = text.Length > 63 ? text[..63] : text;
    }

    void ShowWindow()
    {
        if (_window is null) return;
        _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
    }

    void Quit()
    {
        _quitting = true;
        if (_tray is not null) _tray.Visible = false;
        _window?.Close();
        // Stop the engine before exiting so console mode is undone (display Hz, sound).
        _engine?.Dispose();
        _tray?.Dispose();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        // A frozen game must never outlive Jinky frozen: it continues on the PC.
        GamePause.ResumeAllSilently();
        _tvService?.Dispose();
        _engine?.Dispose();
        _tray?.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }
}

/// <summary>The tray menu in the app's dark colours instead of the light Windows Forms default.</summary>
sealed class TrayMenuRenderer() : Forms.ToolStripProfessionalRenderer(new Colors())
{
    static readonly System.Drawing.Color Text = System.Drawing.Color.FromArgb(0xF5, 0xF7, 0xFC);

    protected override void OnRenderItemText(Forms.ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = Text;
        base.OnRenderItemText(e);
    }

    sealed class Colors : Forms.ProfessionalColorTable
    {
        static readonly System.Drawing.Color Surface = System.Drawing.Color.FromArgb(0x10, 0x14, 0x1F);
        static readonly System.Drawing.Color Raised = System.Drawing.Color.FromArgb(0x23, 0x28, 0x3A);
        public override System.Drawing.Color ToolStripDropDownBackground => Surface;
        public override System.Drawing.Color MenuBorder => Raised;
        public override System.Drawing.Color MenuItemBorder => Raised;
        public override System.Drawing.Color MenuItemSelected => Raised;
        public override System.Drawing.Color MenuItemSelectedGradientBegin => Raised;
        public override System.Drawing.Color MenuItemSelectedGradientEnd => Raised;
        public override System.Drawing.Color SeparatorDark => Raised;
        public override System.Drawing.Color SeparatorLight => Surface;
        public override System.Drawing.Color ImageMarginGradientBegin => Surface;
        public override System.Drawing.Color ImageMarginGradientMiddle => Surface;
        public override System.Drawing.Color ImageMarginGradientEnd => Surface;
    }
}
