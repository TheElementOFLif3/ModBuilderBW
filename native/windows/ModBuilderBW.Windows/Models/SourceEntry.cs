using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;

namespace ModBuilderBW.Windows.Models;

public sealed class SourceEntry : INotifyPropertyChanged
{
    private string _path = string.Empty;
    private bool _included = true;

    public event PropertyChangedEventHandler? PropertyChanged;

    public required string Path
    {
        get => _path;
        set
        {
            if (_path == value)
            {
                return;
            }

            _path = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(Name));
            OnPropertyChanged(nameof(DisplayLine));
        }
    }

    public bool Included
    {
        get => _included;
        set
        {
            if (_included == value)
            {
                return;
            }

            _included = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(DisplayLine));
        }
    }

    public string Name => System.IO.Path.GetFileName(Path);
    public string DisplayLine => $"[{(Included ? "ON" : "OFF")}] {Path}";

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}
