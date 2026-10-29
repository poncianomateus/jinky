using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Jinky;

/// <summary>One game in the PC's "Biblioteca" page.</summary>
public sealed class LibraryItem : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public int Id { get; }
    public string Name { get; }
    public string Meta { get; }
    public bool Installed { get; }
    public string Initial => Name.Length > 0 ? Name[..1].ToUpperInvariant() : "?";
    readonly string? _steamCover;

    public LibraryItem(SteamGame g)
    {
        Id = g.Id;
        Name = g.Name;
        Installed = g.State == GameState.Installed;
        _steamCover = g.CoverPath;
        var from = g.Platform switch
        {
            "ps2" => "PlayStation 2",
            "epic" => "Epic Games",
            "gog" => "GOG",
            "xbox" => "Xbox",
            _ => g.Id < 0 ? "Atalho na Steam" : "Steam",
        };
        var hours = g.Minutes >= 60 ? $" · {g.Minutes / 60} h jogadas" : g.Minutes > 0 ? $" · {g.Minutes} min jogados" : "";
        Meta = from + (g.State == GameState.Installed ? "" : g.State == GameState.Downloading ? " · baixando" : " · não instalado") + hours;
        _hidden = LibraryPrefs.IsHidden(g.Id);
        _favorite = LibraryPrefs.IsFavorite(g.Id);
        _hasOwnCover = LibraryPrefs.CustomCover(g.Id) is not null;
    }

    ImageSource? _cover;
    public ImageSource? Cover { get => _cover; private set { _cover = value; Notify(); Notify(nameof(NoCover)); } }
    public bool NoCover => _cover is null;

    /// <summary>Reads the cover small (off the UI thread; the image is frozen to cross threads).</summary>
    public void LoadCover()
    {
        var path = LibraryPrefs.CustomCover(Id) ?? _steamCover;
        ImageSource? image = null;
        if (path is not null && File.Exists(path))
        {
            try
            {
                var b = new BitmapImage();
                b.BeginInit();
                b.UriSource = new Uri(path);
                b.DecodePixelWidth = 84;
                b.CacheOption = BitmapCacheOption.OnLoad;
                b.CreateOptions = BitmapCreateOptions.IgnoreImageCache;
                b.EndInit();
                b.Freeze();
                image = b;
            }
            catch (Exception)
            {
            }
        }
        System.Windows.Application.Current?.Dispatcher.Invoke(() => Cover = image);
    }

    bool _hidden, _favorite, _hasOwnCover;

    public bool Hidden
    {
        get => _hidden;
        set
        {
            if (_hidden == value) return;
            _hidden = value;
            LibraryPrefs.SetHidden(Id, value);
            if (value) { _favorite = false; Notify(nameof(Favorite)); }
            Notify();
            Notify(nameof(ShownOnTv));
        }
    }

    /// <summary>The eye switch: on = the TV shows it.</summary>
    public bool ShownOnTv { get => !Hidden; set => Hidden = !value; }

    public bool Favorite
    {
        get => _favorite;
        set
        {
            if (_favorite == value) return;
            _favorite = value;
            LibraryPrefs.SetFavorite(Id, value);
            if (value && _hidden) { _hidden = false; Notify(nameof(Hidden)); Notify(nameof(ShownOnTv)); }
            Notify();
        }
    }

    public bool HasOwnCover { get => _hasOwnCover; private set { _hasOwnCover = value; Notify(); } }

    public string? SetCover(string image)
    {
        var error = LibraryPrefs.SetCustomCover(Id, image);
        if (error is null)
        {
            HasOwnCover = true;
            Task.Run(LoadCover);
        }
        return error;
    }

    public void ResetCover()
    {
        LibraryPrefs.RemoveCustomCover(Id);
        HasOwnCover = false;
        Task.Run(LoadCover);
    }

    void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>The PC's "Biblioteca" page: every game the TV sees, with search and filters.</summary>
public sealed class LibraryView : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    static readonly CultureInfo PtBr = CultureInfo.GetCultureInfo("pt-BR");

    public ObservableCollection<LibraryItem> Items { get; } = new();
    public ICollectionView View { get; }

    public LibraryView()
    {
        View = CollectionViewSource.GetDefaultView(Items);
        View.Filter = o => o is LibraryItem i && Matches(i);
    }

    bool _loading;
    public bool Loading { get => _loading; private set { _loading = value; Notify(); Notify(nameof(CountText)); } }
    DateTime _loaded;

    string _search = "";
    public string Search { get => _search; set { _search = value; Notify(); Refresh(); } }

    /// <summary>"installed" (default), "favorites", "hidden" or "all".</summary>
    string _filter = "installed";
    public string Filter { get => _filter; set { _filter = value; Notify(); Refresh(); } }

    public string CountText
    {
        get
        {
            if (Loading && Items.Count == 0) return "Lendo a biblioteca…";
            var shown = View.Cast<object>().Count();
            var hidden = Items.Count(i => i.Hidden);
            return $"{shown} de {Items.Count} jogos" + (hidden > 0 ? $" · {hidden} escondido(s) da TV" : "");
        }
    }

    bool Matches(LibraryItem i)
    {
        if (_search.Length > 0 && !Normal(i.Name).Contains(Normal(_search))) return false;
        return _filter switch
        {
            "favorites" => i.Favorite,
            "hidden" => i.Hidden,
            "all" => true,
            _ => i.Installed || i.Favorite || i.Hidden,
        };
    }

    static string Normal(string s) =>
        new string(s.Normalize(System.Text.NormalizationForm.FormD)
                    .Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark).ToArray())
            .ToLower(PtBr);

    void Refresh()
    {
        View.Refresh();
        Notify(nameof(CountText));
    }

    /// <summary>Reads the games (off the UI thread), at most once every 30 seconds.</summary>
    public void Load(string? apiKey)
    {
        if (Loading || DateTime.UtcNow - _loaded < TimeSpan.FromSeconds(30)) return;
        Loading = true;
        Task.Run(() =>
        {
            List<SteamGame> games;
            try { games = Library.Games(apiKey); }
            catch (Exception) { games = []; }
            var items = games.OrderBy(g => g.Name, StringComparer.Create(PtBr, true)).Select(g => new LibraryItem(g)).ToList();
            System.Windows.Application.Current?.Dispatcher.Invoke(() =>
            {
                Items.Clear();
                foreach (var i in items) Items.Add(i);
                _loaded = DateTime.UtcNow;
                Loading = false;
                Refresh();
            });
            // Covers after the list: a few at a time, small.
            Parallel.ForEach(items, new ParallelOptions { MaxDegreeOfParallelism = 3 }, i => i.LoadCover());
        });
    }

    void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
