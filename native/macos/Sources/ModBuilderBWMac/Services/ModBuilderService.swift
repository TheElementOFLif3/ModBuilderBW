import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

final class ModBuilderService {
    private lazy var stampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter
    }()

    func ensureWorkspaceDir() throws -> URL {
        let desktop = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Desktop", isDirectory: true)
        let workspace = desktop.appendingPathComponent("Mod Builder", isDirectory: true)
        try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
        return workspace
    }

    func recommendedInstallPath(region: Region, gameRootRaw: String, modsFolderRaw: String, versionRaw: String) -> String {
        let modsFolder = sanitizeSegment(modsFolderRaw, fallback: "mods")
        let version = sanitizeSegment(versionRaw, fallback: "2.2.0.2")
        let gameRoot = normalizeRootPath(blankToNil(gameRootRaw))

        if region == .NONE {
            return gameRoot ?? "Auto-detect installed WoT client in installer"
        }

        if let gameRoot {
            return joinPathForDisplay(root: gameRoot, modsFolder: modsFolder, version: version)
        }

        if region == .CUSTOM {
            return "<select game root>/\(modsFolder)/\(version)"
        }

        return joinPathForDisplay(root: defaultWindowsRoot(for: region), modsFolder: modsFolder, version: version)
    }

    func previewInstallerFileName(installerNameRaw: String, region: Region, versionRaw: String) -> String {
        let version = sanitizeSegment(versionRaw, fallback: "2.2.0.2")
        let fallback = "WoT_ModInstaller_\(region.installerToken)_\(safeToken(version))"
        return sanitizeInstallerFileStem(installerNameRaw, fallback: fallback) + ".msi"
    }

    func previewSetupWindowTitle(setupWindowTitleRaw: String, region: Region, versionRaw: String) -> String {
        let version = sanitizeSegment(versionRaw, fallback: "2.2.0.2")
        let fallback = "WoT Mod Installer \(region.displayName) \(version)"
        return normalizeInstallerDisplayName(setupWindowTitleRaw, fallback: fallback)
    }

    private func resolvedInstallerDefaultPath(region: Region, gameRootRaw: String, modsFolderRaw: String, versionRaw: String) -> String? {
        let modsFolder = sanitizeSegment(modsFolderRaw, fallback: "mods")
        let version = sanitizeSegment(versionRaw, fallback: "2.2.0.2")
        let gameRoot = normalizeRootPath(blankToNil(gameRootRaw))

        if region == .NONE {
            if let gameRoot {
                return gameRoot
            }
            return joinPathForDisplay(root: defaultWindowsRoot(for: .CUSTOM), modsFolder: modsFolder, version: version)
        }

        if let gameRoot {
            return joinPathForDisplay(root: gameRoot, modsFolder: modsFolder, version: version)
        }

        if region == .CUSTOM {
            return nil
        }

        return joinPathForDisplay(root: defaultWindowsRoot(for: region), modsFolder: modsFolder, version: version)
    }

    func build(request: BuildRequest, log: @escaping (String) -> Void) throws -> BuildResult {
        let sources = request.sources
            .map { URL(fileURLWithPath: $0).standardizedFileURL }
            .filter { FileManager.default.fileExists(atPath: $0.path) }

        guard !sources.isEmpty else {
            throw BuildServiceError("No source mods selected.")
        }

        let outputDir: URL
        if let raw = blankToNil(request.outputDirectory) {
            outputDir = URL(fileURLWithPath: raw).standardizedFileURL
            try FileManager.default.createDirectory(at: outputDir, withIntermediateDirectories: true)
        } else {
            outputDir = try ensureWorkspaceDir()
        }

        let modsFolder = sanitizeSegment(request.modsFolderName, fallback: "mods")
        let version = sanitizeSegment(request.versionFolder, fallback: "2.2.0.2")
        let stamp = stampFormatter.string(from: Date())
        let folderName = "mod_build_\(request.region.rawValue.lowercased())_\(safeToken(version))_\(stamp)"
        let buildFolder = resolveUniqueURL(outputDir.appendingPathComponent(folderName, isDirectory: true))
        try FileManager.default.createDirectory(at: buildFolder, withIntermediateDirectories: true)

        let payloadDir = buildFolder.appendingPathComponent(modsFolder, isDirectory: true).appendingPathComponent(version, isDirectory: true)
        try FileManager.default.createDirectory(at: payloadDir, withIntermediateDirectories: true)
        log("Build folder created: \(buildFolder.path)")

        var copiedItems = 0
        for source in sources {
            let target = resolveUniqueURL(payloadDir.appendingPathComponent(source.lastPathComponent, isDirectory: isDirectory(source)))
            try copyItem(at: source, to: target)
            log("Copied: \(source.path) -> \(target.path)")
            copiedItems += 1
        }

        let previewPath = recommendedInstallPath(region: request.region, gameRootRaw: request.gameRoot, modsFolderRaw: modsFolder, versionRaw: version)
        let installerDefaultPath = resolvedInstallerDefaultPath(region: request.region, gameRootRaw: request.gameRoot, modsFolderRaw: modsFolder, versionRaw: version)
        try writeInstallInfo(buildFolder: buildFolder, previewPath: previewPath, request: request, copiedItems: copiedItems, modsFolder: modsFolder, version: version)

        var zipURL: URL?
        if request.createZip {
            zipURL = resolveUniqueURL(outputDir.appendingPathComponent(folderName).appendingPathExtension("zip"))
            try zipDirectory(sourceDir: buildFolder, zipFile: zipURL!)
            log("ZIP package created: \(zipURL!.path)")
        }

        var installerURL: URL?
        if request.createInstallerMsi {
            let defaultInstallPath = installerDefaultPath
                ?? joinPathForDisplay(root: defaultWindowsRoot(for: .CUSTOM), modsFolder: modsFolder, version: version)
            installerURL = try buildWindowsInstallerMsi(
                payloadDir: payloadDir,
                outputDir: outputDir,
                region: request.region.rawValue,
                modsFolder: modsFolder,
                version: version,
                recommendedInstallPath: defaultInstallPath,
                installerNameRaw: request.installerName,
                setupWindowTitleRaw: request.setupWindowTitle,
                installerIconPath: request.installerIconPath,
                log: log
            )
            if let installerURL {
                log("Windows MSI installer created: \(installerURL.path)")
            }
        }

        return BuildResult(buildFolder: buildFolder, zipPath: zipURL, installerMsiPath: installerURL, previewPath: previewPath, copiedItems: copiedItems)
    }

    private func buildWindowsInstallerMsi(payloadDir: URL,
                                          outputDir: URL,
                                          region: String,
                                          modsFolder: String,
                                          version: String,
                                          recommendedInstallPath: String,
                                          installerNameRaw: String,
                                          setupWindowTitleRaw: String,
                                          installerIconPath: String,
                                          log: @escaping (String) -> Void) throws -> URL {
        let safeVersion = safeToken(version)
        let displayName = normalizeInstallerDisplayName(setupWindowTitleRaw, fallback: "WoT Mod Installer \(region) \(version)")
        let installerName = sanitizeInstallerFileStem(installerNameRaw, fallback: "WoT_ModInstaller_\(region)_\(safeVersion)")

        guard !isWindows else {
            throw BuildServiceError("Windows local MSI build is not implemented in the macOS Swift app. Use Docker cross-build from macOS.")
        }

        try assertDockerReady()

        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("mod_builder_msi_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let workPayload = tempDir.appendingPathComponent("payload", isDirectory: true)
        try copyItem(at: payloadDir, to: workPayload)

        let innerExe = tempDir.appendingPathComponent("inner_installer").appendingPathExtension("exe")
        let iconTarget = tempDir.appendingPathComponent("installer_icon.ico")
        let includeInstallerIcon = try prepareInstallerIcon(sourcePath: installerIconPath, targetIcoPath: iconTarget)
        let nsisScriptPath = tempDir.appendingPathComponent("installer.nsi")
        try buildNsisScript(
            displayName: displayName,
            outFilePath: "/work/\(innerExe.lastPathComponent)",
            recommendedInstallPath: recommendedInstallPath,
            autoDetectCandidates: buildAutoDetectCandidates(region: region, modsFolder: modsFolder, version: version),
            includeInstallerIcon: includeInstallerIcon,
            writeTo: nsisScriptPath
        )

        log("Building wrapped Windows MSI using Docker (NSIS payload + wixl wrapper)...")
        try runCommand([
            "docker", "run", "--rm",
            "-v", "\(tempDir.path):/work",
            "ubuntu:24.04",
            "sh", "-lc",
            "apt-get update -qq && apt-get install -y -qq nsis >/dev/null && cd /work && makensis installer.nsi"
        ], workingDirectory: nil, log: log)

        let msiOutput = resolveUniqueURL(outputDir.appendingPathComponent(installerName).appendingPathExtension("msi"))
        let wrapperScriptPath = tempDir.appendingPathComponent("wrapper.wxs")
        try buildWixlWrapperScript(
            displayName: displayName,
            productVersion: msiProductVersion(from: version),
            upgradeCode: UUID().uuidString.uppercased(),
            innerInstallerFileName: innerExe.lastPathComponent,
            includeInstallerIcon: includeInstallerIcon,
            writeTo: wrapperScriptPath
        )

        try runCommand([
            "docker", "run", "--rm",
            "-v", "\(tempDir.path):/work",
            "-v", "\(outputDir.path):/out",
            "debian:bookworm",
            "sh", "-lc",
            "apt-get update -qq >/dev/null && apt-get install -y -qq wixl >/dev/null && cd /work && wixl -o /out/\(msiOutput.lastPathComponent) wrapper.wxs"
        ], workingDirectory: nil, log: log)

        return msiOutput
    }

    private func buildWixlWrapperScript(displayName: String,
                                        productVersion: String,
                                        upgradeCode: String,
                                        innerInstallerFileName: String,
                                        includeInstallerIcon: Bool,
                                        writeTo scriptPath: URL) throws {
        let iconBlock = includeInstallerIcon
            ? """
              <Icon Id="InstallerIcon" SourceFile="installer_icon.ico" />
              <Property Id="ARPPRODUCTICON" Value="InstallerIcon" />
              """
            : ""
        let text = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
          <Product Id="*" Name="\(escapeWixString(displayName))" Language="1033" Version="\(productVersion)" Manufacturer="Blackwot" UpgradeCode="\(upgradeCode)">
            <Package InstallerVersion="500" Compressed="yes" InstallScope="perUser" />
            <MediaTemplate EmbedCab="yes" />
            <Property Id="ARPSYSTEMCOMPONENT" Value="1" />
            <Property Id="ARPNOMODIFY" Value="1" />
            <Property Id="ARPNOREPAIR" Value="1" />
            \(iconBlock)
            <Directory Id="TARGETDIR" Name="SourceDir">
              <Directory Id="TempFolder">
                <Directory Id="INSTALLFOLDER" Name="MBWTemp">
                  <Component Id="cmp_inner" Guid="\(UUID().uuidString.uppercased())">
                    <File Id="fil_inner" Source="\(escapeWixString(innerInstallerFileName))" KeyPath="yes" />
                  </Component>
                </Directory>
              </Directory>
            </Directory>
            <CustomAction Id="LaunchWrappedInstaller" FileKey="fil_inner" ExeCommand="" Execute="immediate" Return="check" Impersonate="yes" />
            <InstallExecuteSequence>
              <Custom Action="LaunchWrappedInstaller" After="InstallFiles">NOT REMOVE</Custom>
            </InstallExecuteSequence>
            <Feature Id="Complete" Title="\(escapeWixString(displayName))" Level="1">
              <ComponentRef Id="cmp_inner" />
            </Feature>
          </Product>
        </Wix>
        """
        try text.write(to: scriptPath, atomically: true, encoding: .utf8)
    }

    private func buildNsisScript(displayName: String,
                                 outFilePath: String,
                                 recommendedInstallPath: String,
                                 autoDetectCandidates: [(probePath: String, installPath: String)],
                                 includeInstallerIcon: Bool,
                                 writeTo scriptPath: URL) throws {
        let iconClause: String
        if includeInstallerIcon {
            iconClause = "!define MUI_ICON \"installer_icon.ico\"\n!define MUI_UNICON \"installer_icon.ico\"\nIcon \"installer_icon.ico\"\nUninstallIcon \"installer_icon.ico\"\n"
        } else {
            iconClause = ""
        }

        let customPageIncludes = autoDetectCandidates.isEmpty
            ? ""
            : "!include \"nsDialogs.nsh\"\n!include \"WinMessages.nsh\"\n"
        let customPageDeclaration = autoDetectCandidates.isEmpty
            ? ""
            : "Page custom ClientDetectPageCreate ClientDetectPageLeave\n"
        let customPageFunctions = buildClientDetectNsisFunctions(autoDetectCandidates)

        let text = """
        !include "MUI2.nsh"
        \(customPageIncludes)\(iconClause)Name "\(escapeNsisString(displayName))"
        OutFile "\(outFilePath.replacingOccurrences(of: "\\", with: "/"))"
        InstallDir "\(escapeNsisString(recommendedInstallPath))"
        ManifestDPIAware true
        RequestExecutionLevel user
        CRCCheck off
        !define MUI_ABORTWARNING
        !insertmacro MUI_PAGE_WELCOME
        \(customPageDeclaration)!insertmacro MUI_PAGE_DIRECTORY
        !insertmacro MUI_PAGE_INSTFILES
        !insertmacro MUI_PAGE_FINISH
        !insertmacro MUI_LANGUAGE "English"
        Section "Install Mods"
          SetOutPath "$INSTDIR"
          File /r "payload\\*"
        SectionEnd

        \(customPageFunctions)
        """
        try text.write(to: scriptPath, atomically: true, encoding: .utf8)
    }

    private func buildClientDetectNsisFunctions(_ candidates: [(probePath: String, installPath: String)]) -> String {
        guard !candidates.isEmpty else {
            return ""
        }

        var lines: [String] = [
            "Var ClientDetectDialog",
            "Var ClientCombo",
            "Var ClientMessage",
            "Var DetectedClientCount",
            "",
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
            "",
            "Function ClientDetectPageLeave",
            "  Call UpdateInstallDirFromDetectedClient",
            "FunctionEnd",
            "",
            "Function UpdateInstallDirFromDetectedClient",
            "  StrCmp $DetectedClientCount 0 detected_client_done",
            "  ${NSD_GetText} $ClientCombo $INSTDIR",
            "  detected_client_done:",
            "FunctionEnd",
            "",
            "Function PopulateDetectedClients"
        ]

        for (index, candidate) in candidates.enumerated() {
            let label = "detect_next_\(index)"
            let probe = escapeNsisString(candidate.probePath)
            let install = escapeNsisString(candidate.installPath)
            lines.append("  IfFileExists \"\(probe)\\*.*\" 0 \(label)")
            lines.append("    ${NSD_CB_AddString} $ClientCombo \"\(install)\"")
            lines.append("    IntOp $DetectedClientCount $DetectedClientCount + 1")
            lines.append("  \(label):")
        }

        lines.append("FunctionEnd")
        return lines.joined(separator: "\n")
    }

    private func buildAutoDetectCandidates(region: String, modsFolder: String, version: String) -> [(probePath: String, installPath: String)] {
        let preferredRootNames = orderedWorldOfTanksRootNames(preferredRegion: region)
        let drives = ["C", "D", "E", "F"]
        return preferredRootNames.flatMap { rootName in
            drives.map { drive in
                let probeRoot = "\(drive):\\Games\\\(rootName)"
                let installPath = joinPathForDisplay(root: probeRoot, modsFolder: modsFolder, version: version)
                return (probePath: probeRoot, installPath: installPath)
            }
        }
    }

    private func orderedWorldOfTanksRootNames(preferredRegion: String) -> [String] {
        let canonical = ["World_of_Tanks_EU", "World_of_Tanks_NA", "World_of_Tanks_RU", "World_of_Tanks"]
        let preferred = "World_of_Tanks_\(preferredRegion.uppercased())"
        var ordered: [String] = []
        if canonical.contains(preferred) {
            ordered.append(preferred)
        }
        for item in canonical where !ordered.contains(item) {
            ordered.append(item)
        }
        return ordered
    }

    private func assertDockerReady() throws {
        do {
            try runCommand(["docker", "info"], workingDirectory: nil, log: nil)
        } catch {
            throw BuildServiceError("""
            Docker is not available for the macOS app.
            Start OrbStack or Docker Desktop and make sure the Docker CLI is installed.
            \(error.localizedDescription)
            """)
        }
    }

    private func runCommand(_ command: [String], workingDirectory: URL?, log: ((String) -> Void)?) throws {
        guard let commandName = command.first else {
            throw BuildServiceError("Empty command.")
        }

        guard let executablePath = resolveExecutable(named: commandName) else {
            if commandName == "docker" {
                throw BuildServiceError("""
                Docker CLI not found.
                Checked common locations including /usr/local/bin/docker, /opt/homebrew/bin/docker and ~/.orbstack/bin/docker.
                Install Docker Desktop or OrbStack, then try again.
                """)
            }
            throw BuildServiceError("Command not found: \(commandName)")
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: executablePath)
        process.arguments = Array(command.dropFirst())
        process.environment = resolvedEnvironment()
        if let workingDirectory {
            process.currentDirectoryURL = workingDirectory
        }

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        try process.run()
        let data = try pipe.fileHandleForReading.readToEnd() ?? Data()
        process.waitUntilExit()

        let output = String(decoding: data, as: UTF8.self)
        for rawLine in output.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if !line.isEmpty {
                log?(line)
            }
        }

        if process.terminationStatus != 0 {
            let message = "Command failed (exit \(process.terminationStatus)): \(command.joined(separator: " "))\n\(output.trimmingCharacters(in: .whitespacesAndNewlines))"
            throw BuildServiceError(message)
        }
    }

    private func resolveExecutable(named command: String) -> String? {
        if command.contains("/") {
            return FileManager.default.isExecutableFile(atPath: command) ? command : nil
        }

        for candidate in preferredExecutableCandidates(named: command) {
            let expanded = NSString(string: candidate).expandingTildeInPath
            if FileManager.default.isExecutableFile(atPath: expanded) {
                return expanded
            }
        }

        let envPath = ProcessInfo.processInfo.environment["PATH"] ?? ""
        for base in envPath.split(separator: ":").map(String.init).filter({ !$0.isEmpty }) {
            let candidate = URL(fileURLWithPath: base, isDirectory: true).appendingPathComponent(command).path
            if FileManager.default.isExecutableFile(atPath: candidate) {
                return candidate
            }
        }

        return nil
    }

    private func preferredExecutableCandidates(named command: String) -> [String] {
        switch command {
        case "docker":
            return [
                "/usr/local/bin/docker",
                "/opt/homebrew/bin/docker",
                "~/.orbstack/bin/docker",
                "/Applications/OrbStack.app/Contents/MacOS/xbin/docker",
                "/Applications/OrbStack.app/Contents/MacOS/bin/docker",
                "/usr/bin/docker"
            ]
        case "ditto":
            return ["/usr/bin/ditto"]
        case "sh":
            return ["/bin/sh"]
        default:
            return [
                "/usr/local/bin/\(command)",
                "/opt/homebrew/bin/\(command)",
                "/usr/bin/\(command)",
                "/bin/\(command)",
                "/usr/sbin/\(command)",
                "/sbin/\(command)"
            ]
        }
    }

    private func resolvedEnvironment() -> [String: String] {
        var environment = ProcessInfo.processInfo.environment
        let existingParts = (environment["PATH"] ?? "")
            .split(separator: ":")
            .map(String.init)

        let preferredParts = [
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "\(FileManager.default.homeDirectoryForCurrentUser.path)/.orbstack/bin",
            "/Applications/OrbStack.app/Contents/MacOS/xbin",
            "/Applications/OrbStack.app/Contents/MacOS/bin",
            "/usr/bin",
            "/bin",
            "/usr/sbin",
            "/sbin"
        ]

        var merged: [String] = []
        for item in preferredParts + existingParts where !item.isEmpty {
            if !merged.contains(item) {
                merged.append(item)
            }
        }
        environment["PATH"] = merged.joined(separator: ":")
        return environment
    }

    private func copyItem(at source: URL, to target: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.copyItem(at: source, to: target)
    }

    private func zipDirectory(sourceDir: URL, zipFile: URL) throws {
        try runCommand([
            "ditto", "-c", "-k", "--sequesterRsrc", "--keepParent",
            sourceDir.path,
            zipFile.path
        ], workingDirectory: sourceDir.deletingLastPathComponent(), log: nil)
    }

    private func writeInstallInfo(buildFolder: URL,
                                  previewPath: String,
                                  request: BuildRequest,
                                  copiedItems: Int,
                                  modsFolder: String,
                                  version: String) throws {
        let text = """
        Mod Builder BW
        Generated: \(Date())
        Region: \(request.region.displayName)
        Game root: \(request.gameRoot)
        Mods folder: \(modsFolder)
        Version folder: \(version)
        Installer name: \(blankToNil(request.installerName) ?? "<default>")
        Setup window title: \(blankToNil(request.setupWindowTitle) ?? "<default>")
        Installer icon: \(request.installerIconPath)
        Recommended install path: \(previewPath)
        Source items copied: \(copiedItems)
        Windows MSI export requested: \(request.createInstallerMsi)

        Contents are built under: \(modsFolder)/\(version)
        """
        try text.write(to: buildFolder.appendingPathComponent("INSTALL_INFO.txt"), atomically: true, encoding: .utf8)
    }

    private func prepareInstallerIcon(sourcePath: String, targetIcoPath: URL) throws -> Bool {
        guard let raw = blankToNil(sourcePath) else {
            return false
        }
        let sourceURL = URL(fileURLWithPath: raw).standardizedFileURL
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw BuildServiceError("Installer icon file not found: \(sourceURL.path)")
        }

        if sourceURL.pathExtension.lowercased() == "ico" {
            try FileManager.default.copyItem(at: sourceURL, to: targetIcoPath)
            return true
        }

        let cgImage = try loadCGImage(from: sourceURL)
        let icoData = try makeIcoData(from: cgImage, sizes: [16, 24, 32, 48, 64, 128, 256])
        try icoData.write(to: targetIcoPath, options: [.atomic])
        return true
    }

    private func loadCGImage(from url: URL) throws -> CGImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw BuildServiceError("Unsupported installer icon format: \(url.path)")
        }
        return image
    }

    private func resizeImage(_ image: CGImage, size: Int) throws -> CGImage {
        guard let colorSpace = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpace(name: CGColorSpace.genericRGBLinear) else {
            throw BuildServiceError("Unable to create color space for installer icon.")
        }
        guard let context = CGContext(
            data: nil,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            throw BuildServiceError("Unable to create graphics context for installer icon.")
        }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: size, height: size))
        guard let resized = context.makeImage() else {
            throw BuildServiceError("Unable to render installer icon.")
        }
        return resized
    }

    private func makeIcoData(from image: CGImage, sizes: [Int]) throws -> Data {
        let uniqueSizes = Array(Set(sizes)).sorted()
        let frames: [(size: Int, data: Data)] = try uniqueSizes.map { size in
            let frameData = try buildClassicIconFrame(from: image, size: size)
            return (size: size, data: frameData)
        }

        var data = Data()
        data.appendLEUInt16(0)
        data.appendLEUInt16(1)
        data.appendLEUInt16(UInt16(frames.count))

        var offset = 6 + (16 * frames.count)
        for frame in frames {
            let sizeByte = UInt8(frame.size >= 256 ? 0 : frame.size)
            data.append(sizeByte)
            data.append(sizeByte)
            data.append(0)
            data.append(0)
            data.appendLEUInt16(1)
            data.appendLEUInt16(32)
            data.appendLEUInt32(UInt32(frame.data.count))
            data.appendLEUInt32(UInt32(offset))
            offset += frame.data.count
        }

        for frame in frames {
            data.append(frame.data)
        }
        return data
    }

    private func buildClassicIconFrame(from image: CGImage, size: Int) throws -> Data {
        let bytesPerRow = size * 4
        let pixelData = try renderIconBitmapData(from: image, size: size, bytesPerRow: bytesPerRow)
        let maskRowBytes = ((size + 31) / 32) * 4
        let maskData = Data(repeating: 0, count: maskRowBytes * size)

        var frame = Data()
        frame.appendLEUInt32(40)
        frame.appendLEUInt32(UInt32(bitPattern: Int32(size)))
        frame.appendLEUInt32(UInt32(bitPattern: Int32(size * 2)))
        frame.appendLEUInt16(1)
        frame.appendLEUInt16(32)
        frame.appendLEUInt32(0)
        frame.appendLEUInt32(UInt32(bytesPerRow * size))
        frame.appendLEUInt32(0)
        frame.appendLEUInt32(0)
        frame.appendLEUInt32(0)
        frame.appendLEUInt32(0)

        for row in stride(from: size - 1, through: 0, by: -1) {
            let start = row * bytesPerRow
            frame.append(pixelData[start..<(start + bytesPerRow)])
        }
        frame.append(maskData)
        return frame
    }

    private func renderIconBitmapData(from image: CGImage, size: Int, bytesPerRow: Int) throws -> Data {
        guard let colorSpace = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpace(name: CGColorSpace.genericRGBLinear) else {
            throw BuildServiceError("Unable to create color space for installer icon.")
        }

        var data = Data(count: bytesPerRow * size)
        let rendered = data.withUnsafeMutableBytes { rawBuffer -> Bool in
            guard let baseAddress = rawBuffer.baseAddress else {
                return false
            }
            let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
            guard let context = CGContext(
                data: baseAddress,
                width: size,
                height: size,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: bitmapInfo
            ) else {
                return false
            }
            context.clear(CGRect(x: 0, y: 0, width: size, height: size))
            context.interpolationQuality = .high

            let scale = min(CGFloat(size) / CGFloat(image.width), CGFloat(size) / CGFloat(image.height))
            let drawWidth = max(1, CGFloat(image.width) * scale)
            let drawHeight = max(1, CGFloat(image.height) * scale)
            let drawX = (CGFloat(size) - drawWidth) / 2
            let drawY = (CGFloat(size) - drawHeight) / 2
            context.draw(image, in: CGRect(x: drawX, y: drawY, width: drawWidth, height: drawHeight))
            return true
        }

        if !rendered {
            throw BuildServiceError("Unable to render installer icon bitmap data.")
        }
        return data
    }

    private func isDirectory(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
        return isDir.boolValue
    }

    private var isWindows: Bool {
        ProcessInfo.processInfo.operatingSystemVersionString.lowercased().contains("windows")
    }

    private func resolveUniqueURL(_ url: URL) -> URL {
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path) else {
            return url
        }
        let ext = url.pathExtension
        let base = url.deletingPathExtension().lastPathComponent
        let parent = url.deletingLastPathComponent()
        for index in 2...9_999 {
            let name = ext.isEmpty ? "\(base) (\(index))" : "\(base) (\(index)).\(ext)"
            let candidate = parent.appendingPathComponent(name, isDirectory: ext.isEmpty)
            if !fm.fileExists(atPath: candidate.path) {
                return candidate
            }
        }
        return url
    }

    private func defaultWindowsRoot(for region: Region) -> String {
        switch region {
        case .EU:
            return "C:\\Games\\World_of_Tanks_EU"
        case .NA:
            return "C:\\Games\\World_of_Tanks_NA"
        case .RU:
            return "C:\\Games\\World_of_Tanks_RU"
        case .CUSTOM, .NONE:
            return "C:\\Games\\World_of_Tanks"
        }
    }

    private func joinPathForDisplay(root: String, modsFolder: String, version: String) -> String {
        var normalizedRoot = normalizeRootPath(root) ?? root
        let windowsStyle = normalizedRoot.contains("\\") || normalizedRoot.range(of: #"^[A-Za-z]:.*"#, options: .regularExpression) != nil
        let sep = windowsStyle ? "\\" : "/"
        while normalizedRoot.hasSuffix(sep) && normalizedRoot.count > (windowsStyle ? 3 : 1) {
            normalizedRoot.removeLast()
        }
        return normalizedRoot + sep + modsFolder + sep + version
    }

    private func normalizeRootPath(_ root: String?) -> String? {
        guard let cleaned = blankToNil(root) else {
            return nil
        }
        let windowsStyle = cleaned.contains("\\") || cleaned.range(of: #"^[A-Za-z]:.*"#, options: .regularExpression) != nil
        if windowsStyle {
            var normalized = cleaned.replacingOccurrences(of: "/", with: "\\")
            normalized = collapseRepeatedSeparators(in: normalized, separator: "\\", keepLeadingSeparators: normalized.hasPrefix("\\\\") ? 2 : 0)
            while normalized.count > 3 && normalized.hasSuffix("\\") {
                normalized.removeLast()
            }
            return normalized
        }

        var normalized = cleaned.replacingOccurrences(of: "\\", with: "/")
        normalized = collapseRepeatedSeparators(in: normalized, separator: "/", keepLeadingSeparators: normalized.hasPrefix("/") ? 1 : 0)
        while normalized.count > 1 && normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        return normalized
    }

    private func collapseRepeatedSeparators(in value: String, separator: String, keepLeadingSeparators: Int) -> String {
        let chars = Array(value)
        var index = 0
        while index < chars.count && (chars[index] == "/" || chars[index] == "\\") {
            index += 1
        }

        var result = String(repeating: separator, count: min(keepLeadingSeparators, index))
        var previousWasSeparator = false
        for char in chars[index...] {
            let isSeparator = char == "/" || char == "\\"
            if isSeparator {
                if !previousWasSeparator {
                    result += separator
                    previousWasSeparator = true
                }
            } else {
                result.append(char)
                previousWasSeparator = false
            }
        }
        return result
    }

    private func normalizeInstallerDisplayName(_ raw: String, fallback: String) -> String {
        guard var value = blankToNil(raw) else {
            return fallback
        }
        let lowercased = value.lowercased()
        if lowercased.hasSuffix(".exe") || lowercased.hasSuffix(".msi") {
            value = String(value.dropLast(4)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return value.isEmpty ? fallback : value
    }

    private func sanitizeInstallerFileStem(_ raw: String, fallback: String) -> String {
        guard var value = blankToNil(raw) else {
            return fallback
        }
        let lowercased = value.lowercased()
        if lowercased.hasSuffix(".exe") || lowercased.hasSuffix(".msi") {
            value = String(value.dropLast(4)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        value = value.replacingOccurrences(of: #"[\\/:*?"<>|]+"#, with: "_", options: .regularExpression)
        value = value.replacingOccurrences(of: #"\s+"#, with: "_", options: .regularExpression)
        value = safeToken(value)
        return value.isEmpty ? fallback : value
    }

    private func sanitizeSegment(_ raw: String, fallback: String) -> String {
        guard let value = blankToNil(raw) else {
            return fallback
        }
        let cleaned = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return cleaned.isEmpty ? fallback : cleaned
    }

    private func safeToken(_ value: String) -> String {
        value.replacingOccurrences(of: #"[^A-Za-z0-9._-]"#, with: "_", options: .regularExpression)
    }

    private func blankToNil(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    private func escapeNsisString(_ value: String) -> String {
        value
            .replacingOccurrences(of: "$", with: "$$")
            .replacingOccurrences(of: "\"", with: "$\\\"")
    }

    private func escapeWixString(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    private func msiProductVersion(from rawVersion: String) -> String {
        let numbers = rawVersion
            .split(whereSeparator: { !$0.isNumber })
            .compactMap { Int($0) }
        let major = min(max(numbers.first ?? 1, 0), 255)
        let minor = min(max(numbers.dropFirst().first ?? 0, 0), 255)
        let build = min(max(numbers.dropFirst(2).first ?? 0, 0), 65_535)
        return "\(major).\(minor).\(build)"
    }
}

private struct BuildServiceError: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? { message }
}

private extension Data {
    mutating func appendLEUInt16(_ value: UInt16) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
    }

    mutating func appendLEUInt32(_ value: UInt32) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 24) & 0xff))
    }
}
