using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace Jinky;

public partial class MainWindow : Window
{
    public MainWindow(MainViewModel vm)
    {
        InitializeComponent();
        DataContext = vm;
        SourceInitialized += (_, _) => UseDarkTitleBar();
    }

    /// <summary>Dark, Jinky-coloured title bar on Windows 11 (falls back silently elsewhere).</summary>
    void UseDarkTitleBar()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        int on = 1;
        DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int));
        int caption = 0x000D0907;  // #07090D as COLORREF (0x00BBGGRR)
        DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, ref caption, sizeof(int));
    }

    MainViewModel Vm => (MainViewModel)DataContext;

    void AcceptPairing_Click(object sender, RoutedEventArgs e) => Vm.AcceptPairing();

    async void Update_Click(object sender, RoutedEventArgs e) => await Vm.InstallUpdateAsync();
    void RejectPairing_Click(object sender, RoutedEventArgs e) => Vm.RejectPairing();

    void Unpair_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string id } &&
            MessageBox.Show(this, "Essa TV vai precisar parear de novo para jogar. Remover?", "Jinky",
                            MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
            Vm.Unpair(id);
    }

    void OpenLogs_Click(object sender, RoutedEventArgs e)
    {
        var folder = Path.Combine(Settings.Folder, "logs");
        Directory.CreateDirectory(folder);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{folder}\"") { UseShellExecute = true });
    }

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
