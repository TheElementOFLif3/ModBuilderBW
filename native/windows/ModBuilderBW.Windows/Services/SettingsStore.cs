using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
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
    public bool CreateInstallerMsi { get; set; } = true;
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CreateInstallerExe { get; set; }
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
            var json = File.ReadAllText(_filePath);
            var settings = JsonSerializer.Deserialize<PersistedSettings>(json, _jsonOptions);
            if (settings is null)
            {
                return null;
            }

            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (!root.TryGetProperty(nameof(PersistedSettings.CreateInstallerMsi), out _) &&
                root.TryGetProperty(nameof(PersistedSettings.CreateInstallerExe), out var legacyValue) &&
                legacyValue.ValueKind is JsonValueKind.True or JsonValueKind.False)
            {
                settings.CreateInstallerMsi = legacyValue.GetBoolean();
            }

            settings.CreateInstallerExe = null;
            return settings;
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
