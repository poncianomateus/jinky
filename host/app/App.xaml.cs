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
            _tvService?.OnSessionStarted();
        };
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

        _tvService = new TvService();
        _tvService.DiagnosticsReceived += (tv, path) => Dispatcher.Invoke(() => _vm.OnTvDiagnostics(tv, path));
        _tvService.GameLaunched += name => Dispatcher.Invoke(() => _vm.OnGameLaunched(name));
        _tvService.Streaming = () => Dispatcher.Invoke(() => _vm.IsStreaming);
        _tvService.Start();

        _engine = new Engine();
        _engine.EventReceived += ev => Dispatcher.Invoke(() => { _vm.OnEngineEvent(ev); UpdateTray(); });
        _engine.Failed += msg => Dispatcher.Invoke(() => { _vm.OnEngineFailed(msg); UpdateTray(); });
        _engine.Exited += () => Dispatcher.Invoke(() => { _vm.OnEngineExited(); UpdateTray(); });
        _engine.Start(_settings.EngineArguments());

        // Updates: check shortly after start, then every 6 hours. Never interrupts a game: the user
        // clicks "Atualizar" when it suits them.
        _ = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(20));
            while (true)
            {
                var update = await Updater.CheckAsync();
                Dispatcher.Invoke(() =>
                {
                    var isNew = update is not null && _vm.UpdateButtonText != $"Atualizar para {update.Version}";
                    _vm.SetUpdate(update);
                    if (isNew)
                        _tray?.ShowBalloonTip(5000, "Atualização do Jinky disponível",
                                              $"Versão {update!.Version}. Abra o Jinky e clique em Atualizar.", Forms.ToolTipIcon.None);
                });
                await Task.Delay(TimeSpan.FromHours(6));
            }
        });

        if (!e.Args.Contains("--tray")) ShowWindow();
    }

    bool _quitting;

    void OnSettingsChanged()
    {
        _settings.Save();
        // The engine reads its options at start; restarting it takes about a second.
        Task.Run(() => _engine?.Restart(_settings.EngineArguments()));
    }

    void CreateTray()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "jinky.ico");
        _tray = new Forms.NotifyIcon
        {
            Icon = File.Exists(iconPath) ? new System.Drawing.Icon(iconPath) : System.Drawing.SystemIcons.Application,
            Text = "Jinky",
            Visible = true,
            ContextMenuStrip = new Forms.ContextMenuStrip(),
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
        _tvService?.Dispose();
        _engine?.Dispose();
        _tray?.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }
}
