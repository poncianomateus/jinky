using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
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

    /// <summary>Opens a page of the left menu: its cards come in one after the other.</summary>
    void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (Pages is null || sender is not RadioButton { Content: string title } nav) return;
        var page = nav == NavGames ? PageGames : nav == NavTvs ? PageTvs : nav == NavSystem ? PageSystem : PageHome;
        foreach (UIElement p in Pages.Children) p.Visibility = p == page ? Visibility.Visible : Visibility.Collapsed;
        PageTitle.Text = title;
        Scroller.ScrollToTop();
        Motion.SlideIn(PageTitle, 0, 8, 260);
        var delay = 30;
        foreach (UIElement child in page.Children)
        {
            if (child.Visibility != Visibility.Visible) continue;
            Motion.SlideIn(child, delay, 16, 320);
            delay = Math.Min(delay + 28, 260);
        }
    }

    void GoTvs_Click(object sender, RoutedEventArgs e) => NavTvs.IsChecked = true;

    void AcceptPairing_Click(object sender, RoutedEventArgs e) => Vm.AcceptPairing();

    async void Update_Click(object sender, RoutedEventArgs e) => await Vm.InstallUpdateAsync();
    void CheckUpdates_Click(object sender, RoutedEventArgs e) => Vm.CheckForUpdatesNow();
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
