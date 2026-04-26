using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ModBuilderBW.Windows.Models;

namespace ModBuilderBW.Windows.Services;

public sealed class BuildRequestViewModel : INotifyPropertyChanged
{
    private readonly ModBuilderService _service = new();
    private readonly SettingsStore _settingsStore = new();
    private string _outputDirectory;
    private Region _region = Region.EU;
    private string _gameRoot = string.Empty;
    private string _modsFolderName = "mods";
    private string _versionFolder = "2.2.0.2";
    private string _installerName = string.Empty;
    private string _setupWindowTitle = string.Empty;
    private string _installerIconPath = string.Empty;
    private bool _createZip = true;
    private bool _createInstallerExe = true;
    private string _logText = string.Empty;
    private bool _isBuilding;
    private ImageSource? _installerIconPreview;
    private BuildResult? _lastBuildResult;

    public BuildRequestViewModel()
    {
        _outputDirectory = _service.EnsureWorkspaceDir();
        Sources.CollectionChanged += SourcesOnCollectionChanged;
        LoadSettings();
        ApplyBundledInstallerIconIfNeeded();
        RefreshIconPreview();
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public ObservableCollection<SourceEntry> Sources { get; } = [];

    public string OutputDirectory
    {
        get => _outputDirectory;
        set => SetField(ref _outputDirectory, value, refreshPreview: false, nameof(CanBuild));
    }

    public Region Region
    {
        get => _region;
        set
        {
            if (SetField(ref _region, value, refreshPreview: true, nameof(RecommendedInstallPath)))
            {
                var detected = _service.DetectWindowsWotRootForRegion(value);
                if (!string.IsNullOrWhiteSpace(detected) && string.IsNullOrWhiteSpace(GameRoot))
                {
                    GameRoot = detected;
                }
            }
        }
    }

    public string GameRoot
    {
        get => _gameRoot;
        set => SetField(ref _gameRoot, value, refreshPreview: true, nameof(RecommendedInstallPath));
    }

    public string ModsFolderName
    {
        get => _modsFolderName;
        set => SetField(ref _modsFolderName, value, refreshPreview: true, nameof(RecommendedInstallPath));
    }

    public string VersionFolder
    {
        get => _versionFolder;
        set => SetField(ref _versionFolder, value, refreshPreview: true, nameof(RecommendedInstallPath), nameof(PreviewInstallerFileName), nameof(PreviewSetupWindowTitle));
    }

    public string InstallerName
    {
        get => _installerName;
        set => SetField(ref _installerName, value, refreshPreview: true, nameof(PreviewInstallerFileName));
    }

    public string SetupWindowTitle
    {
        get => _setupWindowTitle;
        set => SetField(ref _setupWindowTitle, value, refreshPreview: true, nameof(PreviewSetupWindowTitle));
    }

    public string InstallerIconPath
    {
        get => _installerIconPath;
        set
        {
            if (SetField(ref _installerIconPath, value, refreshPreview: false))
            {
                RefreshIconPreview();
            }
        }
    }

    public bool CreateZip
    {
        get => _createZip;
        set => SetField(ref _createZip, value, refreshPreview: false);
    }

    public bool CreateInstallerExe
    {
        get => _createInstallerExe;
        set => SetField(ref _createInstallerExe, value, refreshPreview: false);
    }

    public string LogText
    {
        get => _logText;
        set => SetField(ref _logText, value, refreshPreview: false);
    }

    public bool IsBuilding
    {
        get => _isBuilding;
        set => SetField(ref _isBuilding, value, refreshPreview: false, nameof(CanBuild));
    }

    public ImageSource? InstallerIconPreview
    {
        get => _installerIconPreview;
        private set => SetField(ref _installerIconPreview, value, refreshPreview: false);
    }

    public BuildResult? LastBuildResult
    {
        get => _lastBuildResult;
        private set => SetField(ref _lastBuildResult, value, refreshPreview: false);
    }

    public string SourceCountText => $"Total: {Sources.Count} | Included: {Sources.Count(x => x.Included)}";
    public string RecommendedInstallPath => _service.RecommendedInstallPath(Region, GameRoot, ModsFolderName, VersionFolder);
    public string PreviewInstallerFileName => _service.PreviewInstallerFileName(InstallerName, Region, VersionFolder);
    public string PreviewSetupWindowTitle => _service.PreviewSetupWindowTitle(SetupWindowTitle, Region, VersionFolder);
    public bool CanBuild => !IsBuilding && Sources.Any(x => x.Included);

    public void AddPaths(IEnumerable<string> paths)
    {
        var existing = Sources.Select(x => System.IO.Path.GetFullPath(x.Path)).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in paths.Where(path => !string.IsNullOrWhiteSpace(path)))
        {
            var fullPath = System.IO.Path.GetFullPath(raw);
            if (existing.Contains(fullPath) || (!File.Exists(fullPath) && !Directory.Exists(fullPath)))
            {
                continue;
            }

            Sources.Add(new SourceEntry { Path = fullPath, Included = true });
            existing.Add(fullPath);
        }
    }

    public void RemoveSources(IEnumerable<SourceEntry> selected)
    {
        foreach (var entry in selected.ToList())
        {
            Sources.Remove(entry);
        }
    }

    public void ClearSources() => Sources.Clear();

    public void ExcludeSources(IEnumerable<SourceEntry> selected)
    {
        foreach (var entry in selected)
        {
            entry.Included = false;
        }
        OnPropertyChanged(nameof(SourceCountText));
        OnPropertyChanged(nameof(CanBuild));
        SaveSettings();
    }

    public void IncludeSources(IEnumerable<SourceEntry> selected)
    {
        foreach (var entry in selected)
        {
            entry.Included = true;
        }
        OnPropertyChanged(nameof(SourceCountText));
        OnPropertyChanged(nameof(CanBuild));
        SaveSettings();
    }

    public void ClearInstallerIcon()
    {
        var changed = !string.IsNullOrWhiteSpace(_installerIconPath) || _installerIconPreview is not null;
        _installerIconPath = string.Empty;
        InstallerIconPreview = null;
        if (changed)
        {
            OnPropertyChanged(nameof(InstallerIconPath));
        }
        SaveSettings();
    }

    public void RevealOutputDirectory()
    {
        if (Directory.Exists(OutputDirectory))
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", $"\"{OutputDirectory}\"") { UseShellExecute = true });
        }
    }

    public void RevealLastBuildFolder()
    {
        if (LastBuildResult is not null && Directory.Exists(LastBuildResult.BuildFolder))
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", $"\"{LastBuildResult.BuildFolder}\"") { UseShellExecute = true });
        }
    }

    public async Task<BuildResult> BuildAsync()
    {
        var request = new BuildRequest
        {
            Sources = Sources.Where(x => x.Included).Select(x => x.Path).ToList(),
            OutputDirectory = OutputDirectory,
            Region = Region,
            GameRoot = GameRoot,
            ModsFolderName = ModsFolderName,
            VersionFolder = VersionFolder,
            InstallerName = InstallerName,
            SetupWindowTitle = SetupWindowTitle,
            InstallerIconPath = InstallerIconPath,
            CreateZip = CreateZip,
            CreateInstallerExe = CreateInstallerExe
        };

        if (request.Sources.Count == 0)
        {
            throw new InvalidOperationException("Add at least one mod before building.");
        }

        IsBuilding = true;
        LastBuildResult = null;
        LogText = string.Empty;
        AppendLog($"Source mod items: {request.Sources.Count}");
        foreach (var source in request.Sources)
        {
            AppendLog($"  - {source}");
        }
        AppendLog($"Recommended install path: {RecommendedInstallPath}");

        try
        {
            var result = await Task.Run(() => _service.Build(request, line => Application.Current.Dispatcher.Invoke(() => AppendLog(line))));
            LastBuildResult = result;
            AppendLog($"Done: {result.BuildFolder}");
            return result;
        }
        catch (Exception ex)
        {
            AppendLog($"ERROR: {ex.Message}");
            throw;
        }
        finally
        {
            IsBuilding = false;
        }
    }

    private void SourcesOnCollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        OnPropertyChanged(nameof(SourceCountText));
        OnPropertyChanged(nameof(CanBuild));
        SaveSettings();
    }

    private void LoadSettings()
    {
        var settings = _settingsStore.Load();
        if (settings is null)
        {
            return;
        }

        Sources.Clear();
        foreach (var entry in settings.Sources)
        {
            Sources.Add(entry);
        }

        _outputDirectory = settings.OutputDirectory;
        _region = settings.Region;
        _gameRoot = settings.GameRoot;
        _modsFolderName = settings.ModsFolderName;
        _versionFolder = settings.VersionFolder;
        _installerName = settings.InstallerName;
        _setupWindowTitle = settings.SetupWindowTitle;
        _installerIconPath = settings.InstallerIconPath;
        _createZip = settings.CreateZip;
        _createInstallerExe = settings.CreateInstallerExe;

        OnPropertyChanged(string.Empty);
    }

    private void ApplyBundledInstallerIconIfNeeded()
    {
        var bundledIconPath = GetBundledInstallerIconPath();
        if (string.IsNullOrWhiteSpace(bundledIconPath))
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(_installerIconPath) || !File.Exists(_installerIconPath))
        {
            _installerIconPath = bundledIconPath;
            OnPropertyChanged(nameof(InstallerIconPath));
        }
    }

    private void SaveSettings()
    {
        _settingsStore.Save(new PersistedSettings
        {
            Sources = Sources.ToList(),
            OutputDirectory = OutputDirectory,
            Region = Region,
            GameRoot = GameRoot,
            ModsFolderName = ModsFolderName,
            VersionFolder = VersionFolder,
            InstallerName = InstallerName,
            SetupWindowTitle = SetupWindowTitle,
            InstallerIconPath = InstallerIconPath,
            CreateZip = CreateZip,
            CreateInstallerExe = CreateInstallerExe
        });
    }

    private void RefreshIconPreview()
    {
        if (string.IsNullOrWhiteSpace(InstallerIconPath) || !File.Exists(InstallerIconPath))
        {
            InstallerIconPreview = null;
            SaveSettings();
            return;
        }

        try
        {
            using var stream = File.OpenRead(Path.GetFullPath(InstallerIconPath));
            var decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            var frame = decoder.Frames.OrderByDescending(x => x.PixelWidth * x.PixelHeight).FirstOrDefault();
            if (frame is null)
            {
                InstallerIconPreview = null;
            }
            else
            {
                var preview = RenderSquarePreview(frame, 96);
                preview.Freeze();
                InstallerIconPreview = preview;
            }
        }
        catch
        {
            InstallerIconPreview = null;
        }

        SaveSettings();
    }

    private static string? GetBundledInstallerIconPath()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Assets", "ModPackDefaultIcon.png");
        return File.Exists(path) ? path : null;
    }

    private static BitmapSource RenderSquarePreview(BitmapSource source, int size)
    {
        var scale = Math.Min((double)size / source.PixelWidth, (double)size / source.PixelHeight);
        var drawWidth = Math.Max(1.0, source.PixelWidth * scale);
        var drawHeight = Math.Max(1.0, source.PixelHeight * scale);
        var drawX = (size - drawWidth) / 2.0;
        var drawY = (size - drawHeight) / 2.0;

        var visual = new DrawingVisual();
        using (var context = visual.RenderOpen())
        {
            context.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, size, size));
            context.DrawImage(source, new Rect(drawX, drawY, drawWidth, drawHeight));
        }

        var bitmap = new RenderTargetBitmap(size, size, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        return bitmap;
    }

    private bool SetField<T>(ref T field, T value, bool refreshPreview, params string[] additionalProperties)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        OnPropertyChanged();
        foreach (var propertyName in additionalProperties)
        {
            OnPropertyChanged(propertyName);
        }
        if (refreshPreview)
        {
            OnPropertyChanged(nameof(RecommendedInstallPath));
            OnPropertyChanged(nameof(PreviewInstallerFileName));
            OnPropertyChanged(nameof(PreviewSetupWindowTitle));
        }
        SaveSettings();
        return true;
    }

    private void AppendLog(string line)
    {
        LogText = string.IsNullOrEmpty(LogText) ? line : LogText + Environment.NewLine + line;
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}
