using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ModBuilderBW.Windows.Models;

namespace ModBuilderBW.Windows.Services;

public sealed class ModBuilderService
{
    public string EnsureWorkspaceDir()
    {
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var workspace = Path.Combine(desktop, "Mod Builder");
        Directory.CreateDirectory(workspace);
        return workspace;
    }

    public string RecommendedInstallPath(Region region, string gameRootRaw, string modsFolderRaw, string versionRaw)
    {
        var modsFolder = SanitizeSegment(modsFolderRaw, "mods");
        var version = SanitizeSegment(versionRaw, "2.2.0.2");
        var gameRoot = NormalizeRootPath(BlankToNull(gameRootRaw));

        if (region == Region.NONE)
        {
            return gameRoot ?? "Auto-detect installed WoT client in installer";
        }

        if (!string.IsNullOrWhiteSpace(gameRoot))
        {
            return JoinPathForDisplay(gameRoot!, modsFolder, version);
        }

        if (region == Region.CUSTOM)
        {
            return $"<select game root>/{modsFolder}/{version}";
        }

        return JoinPathForDisplay(DefaultWindowsRoot(region), modsFolder, version);
    }

    public string PreviewInstallerFileName(string installerNameRaw, Region region, string versionRaw)
    {
        var version = SanitizeSegment(versionRaw, "2.2.0.2");
        var fallback = $"WoT_ModInstaller_{region.InstallerToken()}_{SafeToken(version)}";
        return SanitizeInstallerFileStem(installerNameRaw, fallback) + ".msi";
    }

    public string PreviewSetupWindowTitle(string setupWindowTitleRaw, Region region, string versionRaw)
    {
        var version = SanitizeSegment(versionRaw, "2.2.0.2");
        return NormalizeInstallerDisplayName(setupWindowTitleRaw, $"WoT Mod Installer {region.DisplayName()} {version}");
    }

    private string? ResolvedInstallerDefaultPath(Region region, string gameRootRaw, string modsFolderRaw, string versionRaw)
    {
        var modsFolder = SanitizeSegment(modsFolderRaw, "mods");
        var version = SanitizeSegment(versionRaw, "2.2.0.2");
        var gameRoot = NormalizeRootPath(BlankToNull(gameRootRaw));

        if (region == Region.NONE)
        {
            if (!string.IsNullOrWhiteSpace(gameRoot))
            {
                return gameRoot;
            }
            return JoinPathForDisplay(DefaultWindowsRoot(Region.CUSTOM), modsFolder, version);
        }

        if (!string.IsNullOrWhiteSpace(gameRoot))
        {
            return JoinPathForDisplay(gameRoot!, modsFolder, version);
        }

        if (region == Region.CUSTOM)
        {
            return null;
        }

        return JoinPathForDisplay(DefaultWindowsRoot(region), modsFolder, version);
    }

    public string? DetectWindowsWotRootForRegion(Region region)
    {
        var roots = DetectWindowsWotRoots();
        if (roots.Count == 0)
        {
            return null;
        }

        if (region is Region.EU or Region.NA or Region.RU)
        {
            var exact = $"world_of_tanks_{region.ToString().ToLowerInvariant()}";
            var regionMatch = roots.FirstOrDefault(path => Path.GetFileName(path).Equals(exact, StringComparison.OrdinalIgnoreCase));
            if (regionMatch is not null)
            {
                return regionMatch;
            }
        }

        return roots[0];
    }

    public List<string> DetectWindowsWotRoots()
    {
        var found = new List<string>();
        foreach (var drive in new[] { "C", "D", "E", "F" })
        {
            var gamesDir = $@"{drive}:\Games";
            if (!Directory.Exists(gamesDir))
            {
                continue;
            }

            foreach (var dir in Directory.GetDirectories(gamesDir))
            {
                if (Path.GetFileName(dir).StartsWith("world_of_tanks", StringComparison.OrdinalIgnoreCase))
                {
                    found.Add(dir);
                }
            }
        }

        found.Sort(StringComparer.OrdinalIgnoreCase);
        return found;
    }

    public BuildResult Build(BuildRequest request, Action<string> log)
    {
        var sources = request.Sources
            .Where(path => !string.IsNullOrWhiteSpace(path) && (File.Exists(path) || Directory.Exists(path)))
            .Select(Path.GetFullPath)
            .ToList();

        if (sources.Count == 0)
        {
            throw new InvalidOperationException("No source mods selected.");
        }

        var outputDir = string.IsNullOrWhiteSpace(request.OutputDirectory)
            ? EnsureWorkspaceDir()
            : Path.GetFullPath(request.OutputDirectory);
        Directory.CreateDirectory(outputDir);

        var modsFolder = SanitizeSegment(request.ModsFolderName, "mods");
        var version = SanitizeSegment(request.VersionFolder, "2.2.0.2");
        var stamp = DateTime.Now.ToString("yyyyMMdd_HHmmss");
        var folderName = $"mod_build_{request.Region.ToString().ToLowerInvariant()}_{SafeToken(version)}_{stamp}";
        var buildFolder = ResolveUniquePath(Path.Combine(outputDir, folderName), isDirectory: true);
        Directory.CreateDirectory(buildFolder);

        var payloadDir = Path.Combine(buildFolder, modsFolder, version);
        Directory.CreateDirectory(payloadDir);
        log($"Build folder created: {buildFolder}");

        var copiedItems = 0;
        foreach (var source in sources)
        {
            var target = ResolveUniquePath(Path.Combine(payloadDir, Path.GetFileName(source)), isDirectory: Directory.Exists(source));
            CopyItem(source, target);
            log($"Copied: {source} -> {target}");
            copiedItems++;
        }

        var previewPath = RecommendedInstallPath(request.Region, request.GameRoot, modsFolder, version);
        var installerDefaultPath = ResolvedInstallerDefaultPath(request.Region, request.GameRoot, modsFolder, version);
        WriteInstallInfo(buildFolder, previewPath, request, copiedItems, modsFolder, version);

        string? zipPath = null;
        if (request.CreateZip)
        {
            zipPath = ResolveUniquePath(Path.Combine(outputDir, folderName + ".zip"), isDirectory: false);
            ZipDirectoryWithRoot(buildFolder, zipPath);
            log($"ZIP package created: {zipPath}");
        }

        string? installerMsiPath = null;
        if (request.CreateInstallerMsi)
        {
            var defaultInstallPath = installerDefaultPath ?? JoinPathForDisplay(DefaultWindowsRoot(Region.CUSTOM), modsFolder, version);
            installerMsiPath = BuildWindowsInstallerMsi(payloadDir, outputDir, request.Region.ToString(), modsFolder, version, defaultInstallPath, request.InstallerName, request.SetupWindowTitle, request.InstallerIconPath, log);
            log($"Windows MSI installer created: {installerMsiPath}");
        }

        return new BuildResult
        {
            BuildFolder = buildFolder,
            ZipPath = zipPath,
            InstallerMsiPath = installerMsiPath,
            PreviewPath = previewPath,
            CopiedItems = copiedItems
        };
    }

    private string BuildWindowsInstallerMsi(string payloadDir,
                                            string outputDir,
                                            string region,
                                            string modsFolder,
                                            string version,
                                            string recommendedInstallPath,
                                            string installerNameRaw,
                                            string setupWindowTitleRaw,
                                            string installerIconPath,
                                            Action<string> log)
    {
        var safeVersion = SafeToken(version);
        var displayName = NormalizeInstallerDisplayName(setupWindowTitleRaw, $"WoT Mod Installer {region} {version}");
        var installerName = SanitizeInstallerFileStem(installerNameRaw, $"WoT_ModInstaller_{region}_{safeVersion}");

        var tempDir = Path.Combine(Path.GetTempPath(), "mod_builder_msi_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempDir);
        try
        {
            var payloadExePath = Path.Combine(tempDir, "inner_installer.exe");
            var iconTarget = Path.Combine(tempDir, "installer_icon.ico");
            var headerTarget = Path.Combine(tempDir, "installer_header.bmp");
            var (includeIcon, includeHeaderImage) = PrepareInstallerAssets(installerIconPath, iconTarget, headerTarget);

            BuildWindowsInstallerPayloadExe(payloadDir, payloadExePath, region, modsFolder, version, recommendedInstallPath, displayName, includeIcon, includeHeaderImage, log);

            var outputMsiPath = ResolveUniquePath(Path.Combine(outputDir, installerName + ".msi"), isDirectory: false);
            BuildWindowsInstallerWrapperMsi(payloadExePath, outputMsiPath, displayName, version, includeIcon ? iconTarget : null, log);
            return outputMsiPath;
        }
        finally
        {
            try
            {
                Directory.Delete(tempDir, true);
            }
            catch
            {
                // Ignore cleanup failures.
            }
        }
    }

    private void BuildWindowsInstallerPayloadExe(string payloadDir,
                                                 string outputExePath,
                                                 string region,
                                                 string modsFolder,
                                                 string version,
                                                 string recommendedInstallPath,
                                                 string displayName,
                                                 bool includeInstallerIcon,
                                                 bool includeHeaderImage,
                                                 Action<string> log)
    {
        var makensis = ResolveMakensisCommand();
        if (string.IsNullOrWhiteSpace(makensis))
        {
            throw new InvalidOperationException("makensis (NSIS) not found on Windows. Install NSIS or bundle makensis with the app.");
        }

        var tempDir = Path.GetDirectoryName(outputExePath)!;
        var workPayload = Path.Combine(tempDir, "payload");
        CopyItem(payloadDir, workPayload);

        var scriptPath = Path.Combine(tempDir, "installer.nsi");
        File.WriteAllText(scriptPath, BuildNsisScriptText(displayName, outputExePath, recommendedInstallPath, BuildAutoDetectCandidates(region, modsFolder, version), includeInstallerIcon, includeHeaderImage), Encoding.UTF8);

        log($"Building wrapped installer payload using local makensis: {makensis}");
        RunCommand(makensis, [scriptPath], tempDir, log);
    }

    private void BuildWindowsInstallerWrapperMsi(string payloadExePath,
                                                 string outputMsiPath,
                                                 string displayName,
                                                 string version,
                                                 string? installerIconIcoPath,
                                                 Action<string> log)
    {
        var dotnet = ResolveDotnetCommand();
        if (string.IsNullOrWhiteSpace(dotnet))
        {
            throw new InvalidOperationException(".NET SDK not found on Windows. Install .NET 10 SDK or newer to export Windows MSI mod-pack installers.");
        }

        var tempDir = Path.Combine(Path.GetTempPath(), "mod_builder_wix_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempDir);
        try
        {
            File.Copy(payloadExePath, Path.Combine(tempDir, "inner_installer.exe"), true);
            if (!string.IsNullOrWhiteSpace(installerIconIcoPath))
            {
                File.Copy(installerIconIcoPath, Path.Combine(tempDir, "installer_icon.ico"), true);
            }

            var wixSourcePath = Path.Combine(tempDir, "wrapper.wxs");
            var wixProjectPath = Path.Combine(tempDir, "wrapper.wixproj");
            File.WriteAllText(wixSourcePath, BuildWixWrapperSourceText(displayName, MsiProductVersion(version), Guid.NewGuid().ToString().ToUpperInvariant(), !string.IsNullOrWhiteSpace(installerIconIcoPath)), Encoding.UTF8);
            File.WriteAllText(wixProjectPath, BuildWixWrapperProjectText(!string.IsNullOrWhiteSpace(installerIconIcoPath)), Encoding.UTF8);

            var objDir = Path.Combine(tempDir, "obj");
            var binDir = Path.Combine(tempDir, "bin");
            log("Building Windows MSI wrapper using local WiX...");
            RunCommand(dotnet, [
                "build",
                wixProjectPath,
                "-c",
                "Release",
                $"-p:BaseIntermediateOutputPath={objDir}{Path.DirectorySeparatorChar}",
                $"-p:OutputPath={binDir}{Path.DirectorySeparatorChar}"
            ], tempDir, log);

            var builtMsi = Directory.GetFiles(binDir, "*.msi", SearchOption.AllDirectories)
                .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault();
            if (string.IsNullOrWhiteSpace(builtMsi))
            {
                throw new InvalidOperationException("WiX build completed but no MSI was produced.");
            }

            File.Copy(builtMsi, outputMsiPath, true);
        }
        finally
        {
            try
            {
                Directory.Delete(tempDir, true);
            }
            catch
            {
                // Ignore cleanup failures.
            }
        }
    }

    private string BuildNsisScriptText(string displayName,
                                       string outFilePath,
                                       string recommendedInstallPath,
                                       IReadOnlyList<(string ProbePath, string InstallPath)> autoDetectCandidates,
                                       bool includeInstallerIcon,
                                       bool includeHeaderImage)
    {
        var iconClause = includeInstallerIcon
            ? "!define MUI_ICON \"installer_icon.ico\"\n!define MUI_UNICON \"installer_icon.ico\"\nIcon \"installer_icon.ico\"\nUninstallIcon \"installer_icon.ico\"\n"
            : string.Empty;
        var headerClause = includeHeaderImage
            ? "!define MUI_HEADERIMAGE\n!define MUI_HEADERIMAGE_RIGHT\n!define MUI_HEADERIMAGE_BITMAP \"installer_header.bmp\"\n"
            : string.Empty;
        var customPageIncludes = autoDetectCandidates.Count == 0
            ? string.Empty
            : "!include \"nsDialogs.nsh\"\n!include \"WinMessages.nsh\"\n";
        var customPageDeclaration = autoDetectCandidates.Count == 0
            ? string.Empty
            : "Page custom ClientDetectPageCreate ClientDetectPageLeave\n";
        var customPageFunctions = BuildClientDetectNsisFunctions(autoDetectCandidates);

        return $"!include \"MUI2.nsh\"\n" +
               customPageIncludes +
               iconClause +
               headerClause +
               $"Name \"{EscapeNsisString(displayName)}\"\n" +
               $"OutFile \"{EscapeNsisString(outFilePath)}\"\n" +
               $"InstallDir \"{EscapeNsisString(recommendedInstallPath)}\"\n" +
               "ManifestDPIAware true\n" +
               "RequestExecutionLevel user\nCRCCheck off\n" +
               "!define MUI_ABORTWARNING\n" +
               "!insertmacro MUI_PAGE_WELCOME\n" +
               customPageDeclaration +
               "!insertmacro MUI_PAGE_DIRECTORY\n" +
               "!insertmacro MUI_PAGE_INSTFILES\n" +
               "!insertmacro MUI_PAGE_FINISH\n" +
               "!insertmacro MUI_LANGUAGE \"English\"\n" +
               "Section \"Install Mods\"\n" +
               "  SetOutPath \"$INSTDIR\"\n" +
               "  File /r \"payload\\*\"\n" +
               "SectionEnd\n\n" +
               customPageFunctions + "\n";
    }

    private string BuildClientDetectNsisFunctions(IReadOnlyList<(string ProbePath, string InstallPath)> candidates)
    {
        if (candidates.Count == 0)
        {
            return string.Empty;
        }

        var lines = new List<string>
        {
            "Var ClientDetectDialog",
            "Var ClientCombo",
            "Var ClientMessage",
            "Var DetectedClientCount",
            string.Empty,
            "Function ClientDetectPageCreate",
            "  nsDialogs::Create 1018",
            "  Pop $ClientDetectDialog",
            "  StrCmp $ClientDetectDialog error 0 +2",
            "    Abort",
            "  ${NSD_CreateLabel} 0 0 100% 20u \"Detected World of Tanks clients. Select where to install the mods.\"",
            "  Pop $ClientMessage",
            "  ${NSD_CreateDropList} 0 22u 100% 80u \"\"",
            "  Pop $ClientCombo",
            "  StrCpy $DetectedClientCount 0",
            "  Call PopulateDetectedClients",
            "  StrCmp $DetectedClientCount 0 no_clients detected_clients",
            "  no_clients:",
            "    ${NSD_SetText} $ClientMessage \"No World of Tanks clients were auto-detected on drives C, D, E or F. Click Next to choose the folder manually.\"",
            "    EnableWindow $ClientCombo 0",
            "    Goto detect_done",
            "  detected_clients:",
            "    ${NSD_SetText} $ClientMessage \"Detected World of Tanks clients. Select the target client, then continue.\"",
            "    SendMessage $ClientCombo ${CB_SETCURSEL} 0 0",
            "    Call UpdateInstallDirFromDetectedClient",
            "  detect_done:",
            "  nsDialogs::Show",
            "FunctionEnd",
            string.Empty,
            "Function ClientDetectPageLeave",
            "  Call UpdateInstallDirFromDetectedClient",
            "FunctionEnd",
            string.Empty,
            "Function UpdateInstallDirFromDetectedClient",
            "  StrCmp $DetectedClientCount 0 detected_client_done",
            "  ${NSD_GetText} $ClientCombo $INSTDIR",
            "  detected_client_done:",
            "FunctionEnd",
            string.Empty,
            "Function PopulateDetectedClients"
        };

        for (var index = 0; index < candidates.Count; index++)
        {
            var label = $"detect_next_{index}";
            var probe = EscapeNsisString(candidates[index].ProbePath);
            var install = EscapeNsisString(candidates[index].InstallPath);
            lines.Add($"  IfFileExists \"{probe}\\*.*\" 0 {label}");
            lines.Add($"    ${{NSD_CB_AddString}} $ClientCombo \"{install}\"");
            lines.Add("    IntOp $DetectedClientCount $DetectedClientCount + 1");
            lines.Add($"  {label}:");
        }

        lines.Add("FunctionEnd");
        return string.Join("\n", lines);
    }

    private List<(string ProbePath, string InstallPath)> BuildAutoDetectCandidates(string region, string modsFolder, string version)
    {
        var roots = OrderedWorldOfTanksRootNames(region);
        var candidates = new List<(string ProbePath, string InstallPath)>();
        foreach (var rootName in roots)
        {
            foreach (var drive in new[] { "C", "D", "E", "F" })
            {
                var probeRoot = $@"{drive}:\Games\{rootName}";
                var installPath = JoinPathForDisplay(probeRoot, modsFolder, version);
                candidates.Add((probeRoot, installPath));
            }
        }
        return candidates;
    }

    private List<string> OrderedWorldOfTanksRootNames(string preferredRegion)
    {
        var canonical = new List<string> { "World_of_Tanks_EU", "World_of_Tanks_NA", "World_of_Tanks_RU", "World_of_Tanks" };
        var preferred = $"World_of_Tanks_{preferredRegion.ToUpperInvariant()}";
        var ordered = new List<string>();
        if (canonical.Contains(preferred, StringComparer.OrdinalIgnoreCase))
        {
            ordered.Add(preferred);
        }
        foreach (var item in canonical)
        {
            if (!ordered.Contains(item, StringComparer.OrdinalIgnoreCase))
            {
                ordered.Add(item);
            }
        }
        return ordered;
    }

    private string BuildWixWrapperSourceText(string displayName, string productVersion, string upgradeCode, bool includeInstallerIcon)
    {
        var iconBlock = includeInstallerIcon
            ? "    <Icon Id=\"AppIcon\" SourceFile=\"installer_icon.ico\" />\n    <Property Id=\"ARPPRODUCTICON\" Value=\"AppIcon\" />\n"
            : string.Empty;
        var componentGuid = StableGuid("WrappedInstallerComponent_" + upgradeCode);
        var manufacturer = EscapeXml("Blackwot");

        return
            "<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\">\n" +
            "  <Package\n" +
            $"      Name=\"{EscapeXml(displayName)}\"\n" +
            $"      Manufacturer=\"{manufacturer}\"\n" +
            $"      Version=\"{productVersion}\"\n" +
            $"      UpgradeCode=\"{{{upgradeCode}}}\"\n" +
            "      Language=\"1033\"\n" +
            "      Scope=\"perUser\"\n" +
            "      InstallerVersion=\"500\"\n" +
            "      Compressed=\"yes\">\n" +
            $"    <SummaryInformation Description=\"{EscapeXml(displayName)}\" Manufacturer=\"{manufacturer}\" />\n" +
            iconBlock +
            "    <Property Id=\"ARPSYSTEMCOMPONENT\" Value=\"1\" />\n" +
            "    <Property Id=\"ARPNOMODIFY\" Value=\"1\" />\n" +
            "    <Property Id=\"ARPNOREPAIR\" Value=\"1\" />\n" +
            "    <MediaTemplate EmbedCab=\"yes\" CompressionLevel=\"high\" />\n\n" +
            "    <StandardDirectory Id=\"TempFolder\">\n" +
            "      <Directory Id=\"INSTALLFOLDER\" Name=\"MBWTemp\">\n" +
            $"        <Component Id=\"WrappedInstallerComponent\" Guid=\"{{{componentGuid}}}\">\n" +
            "          <File Id=\"WrappedInstallerFile\" Source=\"inner_installer.exe\" KeyPath=\"yes\" />\n" +
            "        </Component>\n" +
            "      </Directory>\n" +
            "    </StandardDirectory>\n\n" +
            "    <CustomAction Id=\"LaunchWrappedInstaller\" FileRef=\"WrappedInstallerFile\" ExeCommand=\"\" Execute=\"immediate\" Return=\"check\" Impersonate=\"yes\" />\n\n" +
            "    <InstallExecuteSequence>\n" +
            "      <Custom Action=\"LaunchWrappedInstaller\" After=\"InstallFiles\" Condition=\"NOT REMOVE\" />\n" +
            "    </InstallExecuteSequence>\n\n" +
            $"    <Feature Id=\"MainFeature\" Title=\"{EscapeXml(displayName)}\" Level=\"1\">\n" +
            "      <ComponentRef Id=\"WrappedInstallerComponent\" />\n" +
            "    </Feature>\n" +
            "  </Package>\n" +
            "</Wix>\n";
    }

    private string BuildWixWrapperProjectText(bool includeInstallerIcon)
    {
        var itemGroup = includeInstallerIcon
            ? """
  <ItemGroup>
    <None Include="installer_icon.ico" />
  </ItemGroup>
"""
            : string.Empty;

        return $"""
<Project Sdk="WixToolset.Sdk/7.0.0">
  <PropertyGroup>
    <AcceptEula>wix7</AcceptEula>
    <OutputType>Package</OutputType>
    <TargetName>wrapped-mod-installer</TargetName>
    <InstallerPlatform>x64</InstallerPlatform>
    <SuppressValidation>true</SuppressValidation>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="wrapper.wxs" />
  </ItemGroup>
{itemGroup}</Project>
""";
    }

    private string? ResolveMakensisCommand()
    {
        var baseDir = AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(baseDir, "tools", "nsis", "makensis.exe"),
            Path.Combine(baseDir, "NSIS", "makensis.exe"),
            Path.Combine(baseDir, "nsis", "makensis.exe"),
            @"C:\Program Files (x86)\NSIS\makensis.exe",
            @"C:\Program Files\NSIS\makensis.exe"
        };

        foreach (var candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return IsCommandAvailable("makensis", "/VERSION") ? "makensis" : null;
    }

    private string? ResolveDotnetCommand()
    {
        var candidates = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "dotnet", "dotnet.exe"),
            @"C:\Program Files\dotnet\dotnet.exe",
            @"C:\Program Files (x86)\dotnet\dotnet.exe"
        };

        foreach (var candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return IsCommandAvailable("dotnet", "--info") ? "dotnet" : null;
    }

    private (bool IncludeIcon, bool IncludeHeaderImage) PrepareInstallerAssets(string sourcePath, string targetIcoPath, string targetHeaderBmpPath)
    {
        if (string.IsNullOrWhiteSpace(sourcePath))
        {
            return (false, false);
        }

        var fullPath = Path.GetFullPath(sourcePath);
        if (!File.Exists(fullPath))
        {
            throw new FileNotFoundException("Installer icon file not found.", fullPath);
        }

        var sourceBitmap = LoadBitmapSource(fullPath);
        if (string.Equals(Path.GetExtension(fullPath), ".ico", StringComparison.OrdinalIgnoreCase))
        {
            File.Copy(fullPath, targetIcoPath, true);
        }
        else
        {
            File.WriteAllBytes(targetIcoPath, BuildMultiSizeIco(sourceBitmap, [16, 24, 32, 48, 64, 128, 256]));
        }

        var headerBitmap = RenderBitmapToCanvas(sourceBitmap, 150, 57, Brushes.White);
        WriteBitmapAsBmp(headerBitmap, targetHeaderBmpPath);
        return (true, true);
    }

    private BitmapSource LoadBitmapSource(string fullPath)
    {
        using var stream = File.OpenRead(fullPath);
        var decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        var frame = decoder.Frames.OrderByDescending(x => x.PixelWidth * x.PixelHeight).FirstOrDefault();
        if (frame is null)
        {
            throw new InvalidOperationException($"Unsupported installer icon file: {fullPath}");
        }

        frame.Freeze();
        return frame;
    }

    private void WriteBitmapAsBmp(BitmapSource bitmap, string targetBmpPath)
    {
        var encoder = new BmpBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = File.Create(targetBmpPath);
        encoder.Save(stream);
    }

    private byte[] BuildMultiSizeIco(BitmapSource source, IReadOnlyList<int> sizes)
    {
        var frames = sizes
            .Distinct()
            .OrderBy(x => x)
            .Select(size =>
            {
                var rendered = RenderBitmapToCanvas(source, size, size, Brushes.Transparent);
                return (Size: size, Data: BuildClassicIconFrame(rendered, size));
            })
            .ToList();

        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);
        writer.Write((ushort)0);
        writer.Write((ushort)1);
        writer.Write((ushort)frames.Count);

        var offset = 6 + (16 * frames.Count);
        foreach (var frame in frames)
        {
            var sizeByte = (byte)(frame.Size >= 256 ? 0 : frame.Size);
            writer.Write(sizeByte);
            writer.Write(sizeByte);
            writer.Write((byte)0);
            writer.Write((byte)0);
            writer.Write((ushort)1);
            writer.Write((ushort)32);
            writer.Write(frame.Data.Length);
            writer.Write(offset);
            offset += frame.Data.Length;
        }

        foreach (var frame in frames)
        {
            writer.Write(frame.Data);
        }

        return stream.ToArray();
    }

    private byte[] BuildClassicIconFrame(BitmapSource bitmap, int size)
    {
        BitmapSource bgraBitmap = bitmap.Format == PixelFormats.Bgra32
            ? bitmap
            : new FormatConvertedBitmap(bitmap, PixelFormats.Bgra32, null, 0);

        if (bgraBitmap.CanFreeze && !bgraBitmap.IsFrozen)
        {
            bgraBitmap.Freeze();
        }

        var stride = size * 4;
        var pixelBytes = new byte[stride * size];
        bgraBitmap.CopyPixels(pixelBytes, stride, 0);

        var maskStride = ((size + 31) / 32) * 4;
        var maskBytes = new byte[maskStride * size];

        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);
        writer.Write(40);
        writer.Write(size);
        writer.Write(size * 2);
        writer.Write((ushort)1);
        writer.Write((ushort)32);
        writer.Write(0);
        writer.Write(stride * size);
        writer.Write(0);
        writer.Write(0);
        writer.Write(0);
        writer.Write(0);

        for (var y = size - 1; y >= 0; y--)
        {
            writer.Write(pixelBytes, y * stride, stride);
        }

        writer.Write(maskBytes);
        return stream.ToArray();
    }

    private BitmapSource RenderBitmapToCanvas(BitmapSource source, int canvasWidth, int canvasHeight, Brush background)
    {
        var scale = Math.Min((double)canvasWidth / source.PixelWidth, (double)canvasHeight / source.PixelHeight);
        var drawWidth = Math.Max(1.0, source.PixelWidth * scale);
        var drawHeight = Math.Max(1.0, source.PixelHeight * scale);
        var drawX = (canvasWidth - drawWidth) / 2.0;
        var drawY = (canvasHeight - drawHeight) / 2.0;

        var visual = new DrawingVisual();
        using (var context = visual.RenderOpen())
        {
            context.DrawRectangle(background, null, new Rect(0, 0, canvasWidth, canvasHeight));
            context.DrawImage(source, new Rect(drawX, drawY, drawWidth, drawHeight));
        }

        var bitmap = new RenderTargetBitmap(canvasWidth, canvasHeight, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;
    }
    private void RunCommand(string fileName, IEnumerable<string> arguments, string? workingDirectory, Action<string>? log)
    {
        var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = fileName,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WorkingDirectory = workingDirectory ?? Environment.CurrentDirectory
            }
        };

        foreach (var arg in arguments)
        {
            process.StartInfo.ArgumentList.Add(arg);
        }

        var output = new StringBuilder();
        process.OutputDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                output.AppendLine(e.Data);
                log?.Invoke(e.Data);
            }
        };
        process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                output.AppendLine(e.Data);
                log?.Invoke(e.Data);
            }
        };

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        process.WaitForExit();

        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException($"Command failed (exit {process.ExitCode}): {fileName} {string.Join(' ', arguments)}\n{output.ToString().Trim()}");
        }
    }

    private bool IsCommandAvailable(string fileName, params string[] arguments)
    {
        try
        {
            var process = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = fileName,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                }
            };
            foreach (var arg in arguments)
            {
                process.StartInfo.ArgumentList.Add(arg);
            }
            process.Start();
            process.WaitForExit();
            return process.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }

    private void CopyItem(string source, string target)
    {
        if (Directory.Exists(source))
        {
            CopyDirectory(source, target);
            return;
        }

        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        File.Copy(source, target, true);
    }

    private void CopyDirectory(string sourceDir, string targetDir)
    {
        Directory.CreateDirectory(targetDir);
        foreach (var directory in Directory.GetDirectories(sourceDir, "*", SearchOption.AllDirectories))
        {
            Directory.CreateDirectory(directory.Replace(sourceDir, targetDir));
        }

        foreach (var file in Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories))
        {
            var targetFile = file.Replace(sourceDir, targetDir);
            Directory.CreateDirectory(Path.GetDirectoryName(targetFile)!);
            File.Copy(file, targetFile, true);
        }
    }

    private void ZipDirectoryWithRoot(string sourceDir, string zipFile)
    {
        if (File.Exists(zipFile))
        {
            File.Delete(zipFile);
        }

        using var archive = ZipFile.Open(zipFile, ZipArchiveMode.Create);
        var baseDir = Directory.GetParent(sourceDir)!.FullName;

        foreach (var directory in Directory.GetDirectories(sourceDir, "*", SearchOption.AllDirectories))
        {
            var entryName = Path.GetRelativePath(baseDir, directory).Replace('\\', '/') + "/";
            archive.CreateEntry(entryName);
        }

        foreach (var file in Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories))
        {
            var entryName = Path.GetRelativePath(baseDir, file).Replace('\\', '/');
            archive.CreateEntryFromFile(file, entryName, CompressionLevel.Optimal);
        }
    }

    private void WriteInstallInfo(string buildFolder, string previewPath, BuildRequest request, int copiedItems, string modsFolder, string version)
    {
        var text = $"""
Mod Builder BW
Generated: {DateTime.Now}
Region: {request.Region.DisplayName()}
Game root: {request.GameRoot}
Mods folder: {modsFolder}
Version folder: {version}
Installer name: {(string.IsNullOrWhiteSpace(request.InstallerName) ? "<default>" : request.InstallerName.Trim())}
Setup window title: {(string.IsNullOrWhiteSpace(request.SetupWindowTitle) ? "<default>" : request.SetupWindowTitle.Trim())}
Installer icon: {request.InstallerIconPath}
Recommended install path: {previewPath}
Source items copied: {copiedItems}
        Windows MSI export requested: {request.CreateInstallerMsi}

Contents are built under: {modsFolder}/{version}
""";
        File.WriteAllText(Path.Combine(buildFolder, "INSTALL_INFO.txt"), text, Encoding.UTF8);
    }

    private string DefaultWindowsRoot(Region region) => region switch
    {
        Region.EU => @"C:\Games\World_of_Tanks_EU",
        Region.NA => @"C:\Games\World_of_Tanks_NA",
        Region.RU => @"C:\Games\World_of_Tanks_RU",
        _ => @"C:\Games\World_of_Tanks"
    };

    private string JoinPathForDisplay(string root, string modsFolder, string version)
    {
        var normalizedRoot = NormalizeRootPath(root) ?? root;
        var windowsStyle = normalizedRoot.Contains('\\') || Regex.IsMatch(normalizedRoot, "^[A-Za-z]:.*");
        var sep = windowsStyle ? "\\" : "/";
        while (normalizedRoot.Length > (windowsStyle ? 3 : 1) && normalizedRoot.EndsWith(sep, StringComparison.Ordinal))
        {
            normalizedRoot = normalizedRoot[..^1];
        }
        return normalizedRoot + sep + modsFolder + sep + version;
    }

    private string? NormalizeRootPath(string? root)
    {
        var cleaned = BlankToNull(root);
        if (cleaned is null)
        {
            return null;
        }

        var windowsStyle = cleaned.Contains('\\') || Regex.IsMatch(cleaned, "^[A-Za-z]:.*");
        if (windowsStyle)
        {
            var normalized = cleaned.Replace('/', '\\');
            normalized = CollapseRepeatedSeparators(normalized, '\\', normalized.StartsWith("\\\\", StringComparison.Ordinal) ? 2 : 0);
            while (normalized.Length > 3 && normalized.EndsWith("\\", StringComparison.Ordinal))
            {
                normalized = normalized[..^1];
            }
            return normalized;
        }

        var unixNormalized = cleaned.Replace('\\', '/');
        unixNormalized = CollapseRepeatedSeparators(unixNormalized, '/', unixNormalized.StartsWith("/", StringComparison.Ordinal) ? 1 : 0);
        while (unixNormalized.Length > 1 && unixNormalized.EndsWith("/", StringComparison.Ordinal))
        {
            unixNormalized = unixNormalized[..^1];
        }
        return unixNormalized;
    }

    private string CollapseRepeatedSeparators(string value, char separator, int keepLeadingSeparators)
    {
        var index = 0;
        while (index < value.Length && (value[index] == '/' || value[index] == '\\'))
        {
            index++;
        }

        var builder = new StringBuilder();
        builder.Append(separator, Math.Min(keepLeadingSeparators, index));

        var previousWasSeparator = false;
        for (var i = index; i < value.Length; i++)
        {
            var ch = value[i];
            var isSeparator = ch is '/' or '\\';
            if (isSeparator)
            {
                if (!previousWasSeparator)
                {
                    builder.Append(separator);
                    previousWasSeparator = true;
                }
                continue;
            }

            builder.Append(ch);
            previousWasSeparator = false;
        }

        return builder.ToString();
    }

    private string NormalizeInstallerDisplayName(string raw, string fallback)
    {
        var value = BlankToNull(raw);
        if (value is null)
        {
            return fallback;
        }

        if (value.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) || value.EndsWith(".msi", StringComparison.OrdinalIgnoreCase))
        {
            value = value[..^4].Trim();
        }

        return string.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private string SanitizeInstallerFileStem(string raw, string fallback)
    {
        var value = BlankToNull(raw);
        if (value is null)
        {
            return fallback;
        }

        if (value.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) || value.EndsWith(".msi", StringComparison.OrdinalIgnoreCase))
        {
            value = value[..^4].Trim();
        }

        value = Regex.Replace(value, "[\\\\/:*?\"<>|]+", "_");
        value = Regex.Replace(value, "\\s+", "_");
        value = SafeToken(value);
        return string.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private string SanitizeSegment(string raw, string fallback)
    {
        var value = BlankToNull(raw);
        if (value is null)
        {
            return fallback;
        }

        var cleaned = value.Trim()
            .Replace('\\', '_')
            .Replace('/', '_')
            .Replace(':', '_');
        return string.IsNullOrWhiteSpace(cleaned) ? fallback : cleaned;
    }

    private string SafeToken(string value) => Regex.Replace(value, "[^A-Za-z0-9._-]", "_");

    private string? BlankToNull(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        return value.Trim();
    }

    private string EscapeNsisString(string value) => value.Replace("$", "$$").Replace("\"", "$\\\"");

    private string EscapeXml(string value)
        => value
            .Replace("&", "&amp;", StringComparison.Ordinal)
            .Replace("\"", "&quot;", StringComparison.Ordinal)
            .Replace("<", "&lt;", StringComparison.Ordinal)
            .Replace(">", "&gt;", StringComparison.Ordinal);

    private string MsiProductVersion(string rawVersion)
    {
        var parts = Regex.Matches(rawVersion, @"\d+")
            .Select(match => int.TryParse(match.Value, out var value) ? value : 0)
            .ToList();
        var major = Math.Clamp(parts.ElementAtOrDefault(0), 0, 255);
        var minor = Math.Clamp(parts.ElementAtOrDefault(1), 0, 255);
        var build = Math.Clamp(parts.ElementAtOrDefault(2), 0, 65_535);
        return $"{major}.{minor}.{build}";
    }

    private string StableGuid(string name)
    {
        var bytes = Encoding.UTF8.GetBytes(name);
        var hash = System.Security.Cryptography.MD5.HashData(bytes);
        hash[6] = (byte)((hash[6] & 0x0F) | 0x30);
        hash[8] = (byte)((hash[8] & 0x3F) | 0x80);
        var guidBytes = new byte[16];
        Array.Copy(hash, guidBytes, 16);
        return new Guid(guidBytes).ToString().ToUpperInvariant();
    }

    private string ResolveUniquePath(string path, bool isDirectory)
    {
        if (!File.Exists(path) && !Directory.Exists(path))
        {
            return path;
        }

        var directory = Path.GetDirectoryName(path)!;
        var extension = isDirectory ? string.Empty : Path.GetExtension(path);
        var baseName = isDirectory ? Path.GetFileName(path) : Path.GetFileNameWithoutExtension(path);
        for (var index = 2; index < 10_000; index++)
        {
            var candidate = Path.Combine(directory, isDirectory
                ? $"{baseName} ({index})"
                : $"{baseName} ({index}){extension}");
            if (!File.Exists(candidate) && !Directory.Exists(candidate))
            {
                return candidate;
            }
        }

        return path;
    }
}
