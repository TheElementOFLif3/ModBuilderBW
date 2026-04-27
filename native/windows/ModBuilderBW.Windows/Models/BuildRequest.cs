namespace ModBuilderBW.Windows.Models;

public enum Region
{
    EU,
    NA,
    RU,
    CUSTOM,
    NONE
}

public static class RegionExtensions
{
    public static string DisplayName(this Region region) => region switch
    {
        Region.NONE => "Auto Detection",
        _ => region.ToString()
    };

    public static string InstallerToken(this Region region) => region switch
    {
        Region.NONE => "AUTO",
        _ => region.ToString()
    };
}

public sealed class BuildRequest
{
    public required List<string> Sources { get; set; }
    public required string OutputDirectory { get; set; }
    public required Region Region { get; set; }
    public string GameRoot { get; set; } = string.Empty;
    public required string ModsFolderName { get; set; }
    public required string VersionFolder { get; set; }
    public string InstallerName { get; set; } = string.Empty;
    public string SetupWindowTitle { get; set; } = string.Empty;
    public string InstallerIconPath { get; set; } = string.Empty;
    public bool CreateZip { get; set; }
    public bool CreateInstallerMsi { get; set; }
}
