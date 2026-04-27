namespace ModBuilderBW.Windows.Models;

public sealed class BuildResult
{
    public required string BuildFolder { get; set; }
    public string? ZipPath { get; set; }
    public string? InstallerMsiPath { get; set; }
    public required string PreviewPath { get; set; }
    public int CopiedItems { get; set; }
}
