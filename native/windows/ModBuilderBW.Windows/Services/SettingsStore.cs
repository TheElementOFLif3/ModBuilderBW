using System.IO;
using System.Text.Json;
using ModBuilderBW.Windows.Models;

namespace ModBuilderBW.Windows.Services;

public sealed class PersistedSettings
{
    public List<SourceEntry> Sources { get; set; } = [];
    public string OutputDirectory { get; set; } = string.Empty;
    public Region Region { get; set; } = Region.EU;
    public string GameRoot { get; set; } = string.Empty;
    public string ModsFolderName { get; set; } = "mods";
    public string VersionFolder { get; set; } = "2.2.0.2";
    public string InstallerName { get; set; } = string.Empty;
    public string SetupWindowTitle { get; set; } = string.Empty;
    public string InstallerIconPath { get; set; } = string.Empty;
    public bool CreateZip { get; set; } = true;
    public bool CreateInstallerExe { get; set; } = true;
}

public sealed class SettingsStore
{
    private readonly string _filePath;
    private readonly JsonSerializerOptions _jsonOptions = new()
    {
        WriteIndented = true
    };

    public SettingsStore()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Mod Builder BW");
        _filePath = Path.Combine(dir, "settings.json");
    }

    public PersistedSettings? Load()
    {
        if (!File.Exists(_filePath))
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<PersistedSettings>(File.ReadAllText(_filePath), _jsonOptions);
        }
        catch
        {
            return null;
        }
    }

    public void Save(PersistedSettings settings)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_filePath)!);
            File.WriteAllText(_filePath, JsonSerializer.Serialize(settings, _jsonOptions));
        }
        catch
        {
            // Non-fatal.
        }
    }
}
