using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Jinky;

/// <summary>
/// One setting, Windows 11 style: icon, title, one short line and the control on the right
/// (a switch, a button, a field). Clicking anywhere on the card flips its switch.
/// </summary>
public class SettingCard : ContentControl
{
    public static readonly DependencyProperty HeaderProperty =
        DependencyProperty.Register(nameof(Header), typeof(string), typeof(SettingCard), new PropertyMetadata(""));
    public static readonly DependencyProperty DescriptionProperty =
        DependencyProperty.Register(nameof(Description), typeof(string), typeof(SettingCard), new PropertyMetadata(""));
    public static readonly DependencyProperty StatusProperty =
        DependencyProperty.Register(nameof(Status), typeof(string), typeof(SettingCard), new PropertyMetadata(""));
    public static readonly DependencyProperty IconProperty =
        DependencyProperty.Register(nameof(Icon), typeof(Geometry), typeof(SettingCard), new PropertyMetadata(null));

    public string Header { get => (string)GetValue(HeaderProperty); set => SetValue(HeaderProperty, value); }
    public string Description { get => (string)GetValue(DescriptionProperty); set => SetValue(DescriptionProperty, value); }
    /// <summary>A live line under the description (what the option is doing now); hidden when empty.</summary>
    public string Status { get => (string)GetValue(StatusProperty); set => SetValue(StatusProperty, value); }
    public Geometry? Icon { get => (Geometry?)GetValue(IconProperty); set => SetValue(IconProperty, value); }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        if (e.Handled || Content is not ToggleButton { IsEnabled: true } toggle) return;
        toggle.IsChecked = toggle.IsChecked != true;
        e.Handled = true;
    }
}

/// <summary>Collapsed when the text is empty.</summary>
public sealed class NonEmptyToVisibility : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is string { Length: > 0 } ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>Visible when false.</summary>
public sealed class NotVisibility : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is true ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>Small motion helpers: pages and options slide in instead of popping.</summary>
public static class Motion
{
    static readonly IEasingFunction Ease = new CubicEase { EasingMode = EasingMode.EaseOut };

    /// <summary>Off when Windows' "animation effects" are off (Acessibilidade › Efeitos visuais).</summary>
    public static bool Enabled { get; set; } = SystemParameters.ClientAreaAnimation;

    /// <summary>Ui.Reveal="True": the element fades and slides in each time it becomes visible.</summary>
    public static readonly DependencyProperty RevealProperty =
        DependencyProperty.RegisterAttached("Reveal", typeof(bool), typeof(Motion), new PropertyMetadata(false, (d, e) =>
        {
            if (d is not UIElement el) return;
            if ((bool)e.NewValue) el.IsVisibleChanged += OnVisible;
            else el.IsVisibleChanged -= OnVisible;
        }));

    public static bool GetReveal(DependencyObject d) => (bool)d.GetValue(RevealProperty);
    public static void SetReveal(DependencyObject d, bool value) => d.SetValue(RevealProperty, value);

    static void OnVisible(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (sender is UIElement el && (bool)e.NewValue) SlideIn(el, 0, 10);
    }

    /// <summary>Fade + rise of [el], starting after [delayMs] (hidden until then).</summary>
    public static void SlideIn(UIElement el, int delayMs, double rise = 14, int durationMs = 300)
    {
        if (!Enabled) return;
        if (el.RenderTransform is not TranslateTransform move)
            el.RenderTransform = move = new TranslateTransform();
        el.BeginAnimation(UIElement.OpacityProperty, Frames(0, 1, delayMs, durationMs));
        move.BeginAnimation(TranslateTransform.YProperty, Frames(rise, 0, delayMs, durationMs));
    }

    static DoubleAnimationUsingKeyFrames Frames(double from, double to, int delayMs, int durationMs)
    {
        var begin = TimeSpan.FromMilliseconds(delayMs);
        // FillBehavior.Stop hands the property back to its own value (the shown, resting one).
        return new DoubleAnimationUsingKeyFrames
        {
            KeyFrames =
            {
                new DiscreteDoubleKeyFrame(from, KeyTime.FromTimeSpan(TimeSpan.Zero)),
                new DiscreteDoubleKeyFrame(from, KeyTime.FromTimeSpan(begin)),
                new EasingDoubleKeyFrame(to, KeyTime.FromTimeSpan(begin + TimeSpan.FromMilliseconds(durationMs)), Ease),
            },
            FillBehavior = FillBehavior.Stop,
        };
    }
}

/// <summary>
/// The live session chart: latency (blue line) and video rate (green area) over the last
/// minutes. Drawn directly (one geometry per line), redrawn only when a point arrives.
/// </summary>
public sealed class SessionChart : FrameworkElement
{
    public static readonly DependencyProperty SourceProperty =
        DependencyProperty.Register(nameof(Source), typeof(SessionSamples), typeof(SessionChart),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender, (d, e) =>
            {
                var chart = (SessionChart)d;
                if (e.OldValue is SessionSamples old) old.Changed -= chart.OnChanged;
                if (e.NewValue is SessionSamples now) now.Changed += chart.OnChanged;
            }));

    public SessionSamples? Source { get => (SessionSamples?)GetValue(SourceProperty); set => SetValue(SourceProperty, value); }

    static readonly Brush Blue = Freeze(new SolidColorBrush(Color.FromRgb(0x4F, 0x8B, 0xFF)));
    static readonly Brush Green = Freeze(new SolidColorBrush(Color.FromRgb(0x3D, 0xDC, 0x97)));
    static readonly Brush GreenFill = Freeze(new LinearGradientBrush(Color.FromArgb(0x55, 0x3D, 0xDC, 0x97), Color.FromArgb(0x00, 0x3D, 0xDC, 0x97), 90));
    static readonly Pen BluePen = Freeze(new Pen(Blue, 2) { LineJoin = PenLineJoin.Round });
    static readonly Pen GreenPen = Freeze(new Pen(Green, 1.5) { LineJoin = PenLineJoin.Round });
    static readonly Pen GridPen = Freeze(new Pen(new SolidColorBrush(Color.FromArgb(0x1A, 0xFF, 0xFF, 0xFF)), 1));
    static readonly Brush Label = Freeze(new SolidColorBrush(Color.FromRgb(0xA6, 0xAE, 0xC2)));
    static readonly Typeface Font = new(new FontFamily(new Uri("pack://application:,,,/"), "./Assets/Fonts/#JetBrains Mono"),
                                        FontStyles.Normal, FontWeights.Medium, FontStretches.Normal);

    static T Freeze<T>(T f) where T : Freezable { f.Freeze(); return f; }

    void OnChanged()
    {
        if (Dispatcher.CheckAccess()) InvalidateVisual();
        else Dispatcher.BeginInvoke(InvalidateVisual);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w < 40 || h < 30) return;
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));
        const double top = 22;
        var plot = h - top - 4;
        for (var i = 0; i <= 2; i++)
        {
            var y = top + plot * i / 2;
            dc.DrawLine(GridPen, new Point(0, y), new Point(w, y));
        }
        if (Source is not { } s) return;
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var step = w / (SessionSamples.Capacity - 1);

        // Video rate: area, scaled to the highest point (at least 20 Mbit/s).
        var mbps = s.Mbps.ToArray();
        if (mbps.Length > 1)
        {
            var max = Math.Max(20, mbps.Max() * 1.15);
            var start = w - (mbps.Length - 1) * step;
            var area = new StreamGeometry();
            using (var g = area.Open())
            {
                g.BeginFigure(new Point(start, top + plot), true, true);
                for (var i = 0; i < mbps.Length; i++) g.LineTo(new Point(start + i * step, top + plot - plot * mbps[i] / max), true, true);
                g.LineTo(new Point(w, top + plot), false, false);
            }
            area.Freeze();
            dc.DrawGeometry(GreenFill, null, area);
            var line = new StreamGeometry();
            using (var g = line.Open())
            {
                g.BeginFigure(new Point(start, top + plot - plot * mbps[0] / max), false, false);
                for (var i = 1; i < mbps.Length; i++) g.LineTo(new Point(start + i * step, top + plot - plot * mbps[i] / max), true, true);
            }
            line.Freeze();
            dc.DrawGeometry(null, GreenPen, line);
        }

        // Latency: line, scaled to at least 150 ms.
        var lat = s.Latency.ToArray();
        if (lat.Length > 1)
        {
            var max = Math.Max(150, lat.Max() * 1.15);
            var start = w - (lat.Length - 1) * step;
            var line = new StreamGeometry();
            using (var g = line.Open())
            {
                g.BeginFigure(new Point(start, top + plot - plot * lat[0] / max), false, false);
                for (var i = 1; i < lat.Length; i++) g.LineTo(new Point(start + i * step, top + plot - plot * lat[i] / max), true, true);
            }
            line.Freeze();
            dc.DrawGeometry(null, BluePen, line);
        }

        // Legend: the latest values.
        var x = 0.0;
        void Legend(Brush color, string text)
        {
            dc.DrawRoundedRectangle(color, null, new Rect(x, 6, 10, 3), 1.5, 1.5);
            var t = new FormattedText(text, System.Globalization.CultureInfo.CurrentCulture, FlowDirection.LeftToRight, Font, 11, Label, dpi);
            dc.DrawText(t, new Point(x + 15, 0));
            x += 15 + t.Width + 22;
        }
        var pt = System.Globalization.CultureInfo.GetCultureInfo("pt-BR");
        Legend(Blue, "Latência " + (lat.Length > 0 ? lat[^1].ToString("0", pt) + " ms" : "—"));
        Legend(Green, "Vídeo " + (mbps.Length > 0 ? mbps[^1].ToString("0.0", pt) + " Mbit/s" : "—"));
    }
}
