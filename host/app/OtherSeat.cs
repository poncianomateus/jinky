using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Jinky;

/// <summary>
/// Another Jinky of this PC (another Windows user / ASTER workplace), as the "Outros lugares deste
/// PC" list shows it: what it is doing, a TV asking to pair with it, and its name and console mode,
/// changed from here (handy for a workplace without a screen, like a "TV" one).
/// </summary>
public sealed class OtherSeat(string user) : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public string User { get; } = user;

    string _name = "", _status = "", _version = "";
    bool _console, _streaming;
    SeatLink.PairingAsk? _pairing;

    public string Name { get => _name; private set { if (_name == value) return; _name = value; Notify(); Notify(nameof(Title)); } }
    public string Title => _name.Length > 0 ? _name : "Jinky de " + User;
    public string Status { get => _status; private set { if (_status == value) return; _status = value; Notify(); Notify(nameof(Detail)); } }
    public string Detail => $"Usuário do Windows: {User} · {_status}" + (_version.Length > 0 ? $" · v{_version}" : "");
    public bool Streaming { get => _streaming; private set { if (_streaming == value) return; _streaming = value; Notify(); } }

    /// <summary>The switch: asks that Jinky to turn console mode on or off.</summary>
    public bool ConsoleMode
    {
        get => _console;
        set
        {
            if (_console == value) return;
            _console = value;
            Notify();
            SeatLink.Send(User, new SeatLink.Command("console", value ? "1" : "0"));
        }
    }

    public SeatLink.PairingAsk? Pairing { get => _pairing; private set { _pairing = value; Notify(); Notify(nameof(HasPairing)); Notify(nameof(PairingText)); Notify(nameof(PairingCode)); } }
    public bool HasPairing => _pairing is not null;
    public string PairingText => _pairing is null ? "" : $"{_pairing.Name} quer se conectar a {Title}";
    public string PairingCode => _pairing is null || _pairing.Code.Length < 6 ? _pairing?.Code ?? "" : $"{_pairing.Code[..3]} {_pairing.Code[3..]}";

    /// <summary>The name being typed (sent with "Salvar").</summary>
    public string NewName { get; set; } = "";

    public void Update(SeatLink.SeatInfo info)
    {
        if (Name != info.Name && (NewName.Length == 0 || NewName == Name)) NewName = info.Name;
        Name = info.Name;
        Status = info.Status;
        Streaming = info.Streaming;
        _version = info.Version;
        // The switch follows that Jinky, without sending it back.
        if (_console != info.ConsoleMode) { _console = info.ConsoleMode; Notify(nameof(ConsoleMode)); }
        if (_pairing?.Id != info.Pairing?.Id) Pairing = info.Pairing;
    }

    public void SaveName() => SeatLink.Send(User, new SeatLink.Command("name", NewName.Trim()));

    public void Accept()
    {
        if (_pairing is { } p) SeatLink.Send(User, new SeatLink.Command("pair-accept", p.Id));
        Pairing = null;
    }

    public void Reject()
    {
        if (_pairing is { } p) SeatLink.Send(User, new SeatLink.Command("pair-reject", p.Id));
        Pairing = null;
    }

    void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
