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
