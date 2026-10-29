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

    /// <summary>
    /// Dark title bar; on Windows 11 (22H2 and later) the window's back is Mica, the translucent
    /// material of Windows' own apps (the desktop's colours show faintly through the menu).
    /// Elsewhere: Jinky's solid dark colours.
    /// </summary>
    void UseDarkTitleBar()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        int on = 1;
        DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int));
        if (Environment.OSVersion.Version.Build >= 22621 && SystemParameters.HighContrast == false)
        {
            try
            {
                if (HwndSource.FromHwnd(hwnd) is { CompositionTarget: { } target }) target.BackgroundColor = System.Windows.Media.Colors.Transparent;
                var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
                DwmExtendFrameIntoClientArea(hwnd, ref margins);
                int mica = 2;  // DWMSBT_MAINWINDOW
                if (DwmSetWindowAttribute(hwnd, 38 /* DWMWA_SYSTEMBACKDROP_TYPE */, ref mica, sizeof(int)) == 0)
                {
                    Background = System.Windows.Media.Brushes.Transparent;
                    Side.Background = Frozen(0xB8, 0x0A, 0x0D, 0x14);
                    PageArea.Background = Frozen(0xEB, 0x07, 0x09, 0x0D);
                    return;
                }
            }
            catch (Exception)
            {
            }
        }
        int caption = 0x000D0907;  // #07090D as COLORREF (0x00BBGGRR)
        DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, ref caption, sizeof(int));
    }

    static System.Windows.Media.SolidColorBrush Frozen(byte a, byte r, byte g, byte b)
    {
        var brush = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromArgb(a, r, g, b));
        brush.Freeze();
        return brush;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct Margins { public int Left, Right, Top, Bottom; }

    [DllImport("dwmapi.dll")]
    static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins margins);

    MainViewModel Vm => (MainViewModel)DataContext;

    /// <summary>Opens a page of the left menu: its cards come in one after the other.</summary>
    void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (Pages is null || sender is not RadioButton { Content: string title } nav) return;
        var page = nav == NavLibrary ? PageLibrary : nav == NavGames ? PageGames : nav == NavTvs ? PageTvs : nav == NavSystem ? PageSystem : PageHome;
        if (page == PageLibrary) Vm.LoadLibrary();
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

    void EndSession_Click(object sender, RoutedEventArgs e) => Vm.EndTvSession();
    void HideChart_Click(object sender, RoutedEventArgs e) => Vm.SessionChart = false;
    void TestConnection_Click(object sender, RoutedEventArgs e) => Vm.TestConnection();

    void LibraryFilter_Checked(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string filter } && DataContext is MainViewModel vm) vm.LibraryPage.Filter = filter;
    }

    void ChangeCover_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: LibraryItem item }) return;
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = $"Capa de {item.Name}",
            Filter = "Imagens (PNG, JPG)|*.png;*.jpg;*.jpeg",
        };
        if (dialog.ShowDialog(this) != true) return;
        if (item.SetCover(dialog.FileName) is { } error)
            MessageBox.Show(this, "Não deu para usar essa imagem: " + error, "Jinky", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    void ResetCover_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: LibraryItem item }) item.ResetCover();
    }

    void GuideSkip_Click(object sender, RoutedEventArgs e) => Vm.FinishGuide();

    void GuideNext_Click(object sender, RoutedEventArgs e)
    {
        if (Vm.GuideStep >= 3) Vm.FinishGuide();
        else Vm.GuideStep++;
    }

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
