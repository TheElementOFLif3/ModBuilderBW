package com.ewalab.modbillder;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

public final class ModBuilderService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public Path ensureWorkspaceDir() throws IOException {
        Path desktop = Paths.get(System.getProperty("user.home"), "Desktop");
        Path workspace = desktop.resolve("Mod Builder");
        Files.createDirectories(workspace);
        return workspace;
    }

    public boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public List<Path> detectWindowsWotRoots() {
        if (!isWindows()) {
            return List.of();
        }

        List<Path> found = new ArrayList<>();
        for (String drive : List.of("C", "D", "E", "F")) {
            Path gamesDir = Paths.get(drive + ":\\Games");
            if (!Files.isDirectory(gamesDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(gamesDir)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("world_of_tanks"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .forEach(found::add);
            } catch (IOException ignored) {
                // Skip unreadable drive/folder.
            }
        }
        return found;
    }

    public Optional<Path> detectWindowsWotRootForRegion(Region region) {
        List<Path> roots = detectWindowsWotRoots();
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        if (region == Region.EU || region == Region.NA || region == Region.RU) {
            String exact = "world_of_tanks_" + region.name().toLowerCase(Locale.ROOT);
            Optional<Path> regionMatch = roots.stream()
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(exact))
                    .findFirst();
            if (regionMatch.isPresent()) {
                return regionMatch;
            }
        }
        return Optional.of(roots.get(0));
    }

    public String recommendedInstallPath(Region region, String gameRootRaw, String modsFolderRaw, String versionRaw) {
        String modsFolder = sanitizeSegment(modsFolderRaw, "mods");
        String version = sanitizeSegment(versionRaw, "2.1.1.2");
        String gameRoot = normalizeRootPath(blankToNull(gameRootRaw));

        if (region == Region.NONE) {
            return gameRoot == null ? "<select install folder>" : gameRoot;
        }

        if (gameRoot == null) {
            if (region == Region.CUSTOM) {
                return "<select game root>/" + modsFolder + "/" + version;
            }
            gameRoot = defaultWindowsRoot(region);
        }

        return joinPathForDisplay(gameRoot, modsFolder, version);
    }

    public String previewInstallerFileName(String installerNameRaw, Region region, String versionRaw) {
        String version = sanitizeSegment(versionRaw, "2.1.1.2");
        Region effectiveRegion = region == null ? Region.EU : region;
        String fileStem = sanitizeInstallerFileStem(installerNameRaw,
                "WoT_ModInstaller_" + effectiveRegion.name() + "_" + safeToken(version));
        return fileStem + ".exe";
    }

    public String previewSetupWindowTitle(String setupWindowTitleRaw, Region region, String versionRaw) {
        String version = sanitizeSegment(versionRaw, "2.1.1.2");
        Region effectiveRegion = region == null ? Region.EU : region;
        return normalizeInstallerDisplayName(setupWindowTitleRaw,
                "WoT Mod Installer " + effectiveRegion.name() + " " + version);
    }

    public BufferedImage readInstallerIconPreview(Path sourceIconPath) throws IOException {
        if (sourceIconPath == null) {
            return null;
        }
        Path source = sourceIconPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            return null;
        }
        return loadInstallerIconImage(source);
    }

    public BuildResult build(BuildRequest request, Consumer<String> log) throws IOException {
        List<Path> sources = request.sourcePaths() == null ? List.of() : request.sourcePaths().stream()
                .filter(p -> p != null && Files.exists(p))
                .map(Path::toAbsolutePath)
                .toList();
        if (sources.isEmpty()) {
            throw new IOException("No source mods selected.");
        }

        Path outputDir = request.outputDirectory() == null ? ensureWorkspaceDir() : request.outputDirectory().toAbsolutePath();
        Files.createDirectories(outputDir);

        String modsFolder = sanitizeSegment(request.modsFolderName(), "mods");
        String version = sanitizeSegment(request.versionFolder(), "2.1.1.2");
        Region region = request.region() == null ? Region.CUSTOM : request.region();
        String regionSlug = region.name().toLowerCase(Locale.ROOT);
        String stamp = LocalDateTime.now().format(STAMP);

        String folderName = "mod_build_" + regionSlug + "_" + safeToken(version) + "_" + stamp;
        Path buildFolder = resolveUniquePath(outputDir.resolve(folderName));
        Files.createDirectories(buildFolder);

        Path payloadDir = buildFolder.resolve(modsFolder).resolve(version);
        Files.createDirectories(payloadDir);

        log.accept("Build folder created: " + buildFolder);

        int copiedItems = 0;
        for (Path source : sources) {
            Path target = resolveUniquePath(payloadDir.resolve(source.getFileName().toString()));
            if (Files.isDirectory(source)) {
                copyDirectory(source, target);
                log.accept("Copied folder: " + source + " -> " + target);
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                log.accept("Copied file: " + source + " -> " + target);
            }
            copiedItems++;
        }

        String previewPath = recommendedInstallPath(region, request.gameRoot(), modsFolder, version);
        writeInstallInfo(buildFolder, previewPath, request, copiedItems, modsFolder, version);

        Path zipPath = null;
        if (request.createZip()) {
            zipPath = resolveUniquePath(outputDir.resolve(buildFolder.getFileName().toString() + ".zip"));
            zipDirectory(buildFolder, zipPath);
            log.accept("ZIP package created: " + zipPath);
        }

        Path installerExePath = null;
        if (request.createInstallerExe()) {
            if (previewPath.contains("<select")) {
                throw new IOException("Set Game root / install folder before exporting Windows EXE installer.");
            }
            installerExePath = buildWindowsInstallerExe(payloadDir, outputDir, region.name(), version, previewPath, request.installerName(), request.setupWindowTitle(), request.installerIconPath(), log);
            log.accept("Windows EXE installer created: " + installerExePath);
        }

        return new BuildResult(buildFolder, zipPath, installerExePath, previewPath, copiedItems);
    }

    private Path buildWindowsInstallerExe(Path payloadDir,
                                          Path outputDir,
                                          String region,
                                          String version,
                                          String recommendedInstallPath,
                                          String installerNameRaw,
                                          String setupWindowTitleRaw,
                                          Path installerIconPath,
                                          Consumer<String> log) throws IOException {
        String safeVersion = safeToken(version);
        String displayName = normalizeInstallerDisplayName(setupWindowTitleRaw, "WoT Mod Installer " + region + " " + version);
        String installerName = sanitizeInstallerFileStem(installerNameRaw, "WoT_ModInstaller_" + region + "_" + safeVersion);

        if (isWindows()) {
            Optional<String> makensisCommand = resolveMakensisCommand();
            if (makensisCommand.isPresent()) {
                log.accept("Building Windows EXE using local makensis (NSIS): " + makensisCommand.get());
                return buildWindowsInstallerExeLocalNsis(payloadDir, outputDir, installerName, displayName, recommendedInstallPath, installerIconPath, makensisCommand.get(), log);
            }
            throw new IOException("makensis (NSIS) not found on Windows. Reinstall Mod Builder BW (bundled NSIS) or install NSIS manually.");
        }

        assertDockerReady();
        log.accept("Building Windows EXE using Docker NSIS cross-build...");
        return buildWindowsInstallerExeDockerNsis(payloadDir, outputDir, installerName, displayName, recommendedInstallPath, installerIconPath, log);
    }

    private Path buildWindowsInstallerExeLocalNsis(Path payloadDir,
                                                   Path outputDir,
                                                   String installerName,
                                                   String displayName,
                                                   String recommendedInstallPath,
                                                   Path installerIconPath,
                                                   String makensisCommand,
                                                   Consumer<String> log) throws IOException {
        Path tmpDir = Files.createTempDirectory("mod_builder_nsis_");
        try {
            Path workPayload = tmpDir.resolve("payload");
            copyDirectory(payloadDir, workPayload);

            Path outFile = resolveUniquePath(outputDir.resolve(installerName + ".exe"));
            boolean includeInstallerIcon = prepareInstallerIcon(installerIconPath, tmpDir.resolve("installer_icon.ico"));
            Path scriptPath = tmpDir.resolve("installer.nsi");
            Files.writeString(scriptPath, buildNsisScriptText(
                    displayName,
                    outFile.toAbsolutePath().toString(),
                    recommendedInstallPath,
                    true,
                    includeInstallerIcon
            ), StandardCharsets.UTF_8);

            runCommand(List.of(makensisCommand, scriptPath.toString()), tmpDir, log);
            return outFile;
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private Path buildWindowsInstallerExeDockerNsis(Path payloadDir,
                                                    Path outputDir,
                                                    String installerName,
                                                    String displayName,
                                                    String recommendedInstallPath,
                                                    Path installerIconPath,
                                                    Consumer<String> log) throws IOException {
        Path tmpDir = Files.createTempDirectory("mod_builder_nsis_");
        try {
            Path workPayload = tmpDir.resolve("payload");
            copyDirectory(payloadDir, workPayload);

            Path outFile = resolveUniquePath(outputDir.resolve(installerName + ".exe"));
            Files.createDirectories(outputDir);
            boolean includeInstallerIcon = prepareInstallerIcon(installerIconPath, tmpDir.resolve("installer_icon.ico"));
            Path scriptPath = tmpDir.resolve("installer.nsi");
            Files.writeString(scriptPath, buildNsisScriptText(
                    displayName,
                    "/out/" + outFile.getFileName(),
                    recommendedInstallPath,
                    false,
                    includeInstallerIcon
            ), StandardCharsets.UTF_8);

            runCommand(List.of(
                    "docker", "run", "--rm",
                    "-v", tmpDir.toAbsolutePath() + ":/work",
                    "-v", outputDir.toAbsolutePath() + ":/out",
                    "ubuntu:24.04",
                    "sh", "-lc",
                    "apt-get update -qq && apt-get install -y -qq nsis >/dev/null && cd /work && makensis installer.nsi"
            ), null, log);

            return outFile;
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private String buildNsisScriptText(String displayName,
                                       String outFilePath,
                                       String recommendedInstallPath,
                                       boolean localOutFile,
                                       boolean includeInstallerIcon) {
        String outFileClause = localOutFile
                ? escapeNsisString(outFilePath)
                : outFilePath.replace('\\', '/');
        String iconClause = includeInstallerIcon
                ? "!define MUI_ICON \"installer_icon.ico\"\n"
                + "!define MUI_UNICON \"installer_icon.ico\"\n"
                + "Icon \"installer_icon.ico\"\n"
                : "";

        return "!include \"MUI2.nsh\"\n"
                + iconClause
                + "Name \"" + escapeNsisString(displayName) + "\"\n"
                + "OutFile \"" + outFileClause + "\"\n"
                + "InstallDir \"" + escapeNsisString(recommendedInstallPath) + "\"\n"
                + "ManifestDPIAware true\n"
                + "RequestExecutionLevel user\n"
                + "!define MUI_ABORTWARNING\n"
                + "!insertmacro MUI_PAGE_WELCOME\n"
                + "!insertmacro MUI_PAGE_DIRECTORY\n"
                + "!insertmacro MUI_PAGE_INSTFILES\n"
                + "!insertmacro MUI_PAGE_FINISH\n"
                + "!insertmacro MUI_LANGUAGE \"English\"\n"
                + "Section \"Install Mods\"\n"
                + "  SetOutPath \"$INSTDIR\"\n"
                + "  File /r \"payload\\*\"\n"
                + "SectionEnd\n";
    }

    private void assertDockerReady() throws IOException {
        try {
            runCommand(List.of("docker", "info"), null, null);
        } catch (IOException e) {
            throw new IOException("Docker is required for Windows EXE cross-build on this OS. Start Docker/OrbStack and try again.", e);
        }
    }

    private Optional<String> resolveMakensisCommand() {
        for (Path candidate : bundledMakensisCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().toString());
            }
        }

        for (String candidate : List.of(
                "C:\\Program Files (x86)\\NSIS\\makensis.exe",
                "C:\\Program Files\\NSIS\\makensis.exe"
        )) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toAbsolutePath().toString());
            }
        }

        if (isCommandAvailable(List.of("makensis", "/VERSION"))) {
            return Optional.of("makensis");
        }
        return Optional.empty();
    }

    private List<Path> bundledMakensisCandidates() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        return List.of(
                cwd.resolve("tools").resolve("nsis").resolve("makensis.exe"),
                cwd.resolve("NSIS").resolve("makensis.exe"),
                cwd.resolve("nsis").resolve("makensis.exe")
        );
    }

    private boolean isCommandAvailable(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Drain output.
                }
            }
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String escapeNsisString(String value) {
        return value.replace("$", "$$");
    }

    private void runCommand(List<String> command, Path workingDir, Consumer<String> log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    if (log != null) {
                        log.accept(line);
                    }
                    output.append(line).append('\n');
                }
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + String.join(" ", command), e);
        }

        if (exitCode != 0) {
            String msg = output.isEmpty() ? "" : (" Output: " + output.toString().trim());
            throw new IOException("Command failed (exit " + exitCode + "): " + String.join(" ", command) + msg);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.forEach(path -> {
                Path relative = sourceDir.relativize(path);
                Path target = targetDir.resolve(relative.toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        Path base = sourceDir.getParent();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile), StandardCharsets.UTF_8);
             Stream<Path> walk = Files.walk(sourceDir)) {
            walk.sorted().forEach(path -> {
                try {
                    String entryName = base.relativize(path).toString().replace('\\', '/');
                    if (entryName.isEmpty()) {
                        return;
                    }
                    if (Files.isDirectory(path)) {
                        if (!entryName.endsWith("/")) {
                            entryName += "/";
                        }
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    } else {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void writeInstallInfo(Path buildFolder,
                                  String previewPath,
                                  BuildRequest request,
                                  int copiedItems,
                                  String modsFolder,
                                  String version) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Mod Builder BW\n");
        sb.append("Generated: ").append(LocalDateTime.now()).append("\n");
        sb.append("Region: ").append(request.region()).append("\n");
        sb.append("Game root: ").append(request.gameRoot() == null ? "" : request.gameRoot()).append("\n");
        sb.append("Mods folder: ").append(modsFolder).append("\n");
        sb.append("Version folder: ").append(version).append("\n");
        sb.append("Installer name: ").append(blankToNull(request.installerName()) == null ? "<default>" : request.installerName().trim()).append("\n");
        sb.append("Installer icon: ").append(request.installerIconPath() == null ? "" : request.installerIconPath()).append("\n");
        sb.append("Recommended install path: ").append(previewPath).append("\n");
        sb.append("Source items copied: ").append(copiedItems).append("\n");
        sb.append("Windows EXE export requested: ").append(request.createInstallerExe()).append("\n");
        sb.append("\nContents are built under: ").append(modsFolder).append('/').append(version).append("\n");
        Files.writeString(buildFolder.resolve("INSTALL_INFO.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private String defaultWindowsRoot(Region region) {
        return switch (region) {
            case EU -> "C:\\Games\\World_of_Tanks_EU";
            case NA -> "C:\\Games\\World_of_Tanks_NA";
            case RU -> "C:\\Games\\World_of_Tanks_RU";
            case CUSTOM, NONE -> "C:\\Games\\World_of_Tanks";
        };
    }

    private String joinPathForDisplay(String root, String modsFolder, String version) {
        String normalizedRoot = normalizeRootPath(root);
        boolean windowsStyle = normalizedRoot.contains("\\") || normalizedRoot.matches("^[A-Za-z]:.*");
        String sep = windowsStyle ? "\\" : "/";
        while (normalizedRoot.endsWith(sep)) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        return normalizedRoot + sep + modsFolder + sep + version;
    }

    private String normalizeRootPath(String root) {
        String cleaned = blankToNull(root);
        if (cleaned == null) {
            return null;
        }

        boolean windowsStyle = cleaned.contains("\\") || cleaned.matches("^[A-Za-z]:.*");
        String normalized = cleaned;
        if (windowsStyle) {
            normalized = normalized.replace('/', '\\');
            normalized = collapseRepeatedSeparators(normalized, '\\', normalized.startsWith("\\\\") ? 2 : 0);
            while (normalized.length() > 3 && normalized.endsWith("\\")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }

        normalized = normalized.replace('\\', '/');
        normalized = collapseRepeatedSeparators(normalized, '/', normalized.startsWith("/") ? 1 : 0);
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String collapseRepeatedSeparators(String value, char separator, int keepLeadingSeparators) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            char ch = value.charAt(index);
            if (ch != '/' && ch != '\\') {
                break;
            }
            index++;
        }

        for (int i = 0; i < keepLeadingSeparators && i < index; i++) {
            out.append(separator);
        }

        boolean previousWasSeparator = false;
        for (int i = index; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean isSeparator = ch == '/' || ch == '\\';
            if (isSeparator) {
                if (!previousWasSeparator) {
                    out.append(separator);
                    previousWasSeparator = true;
                }
                continue;
            }
            out.append(ch);
            previousWasSeparator = false;
        }
        return out.toString();
    }

    private String normalizeInstallerDisplayName(String raw, String fallback) {
        String value = blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            value = value.substring(0, value.length() - 4).trim();
        }
        return value.isBlank() ? fallback : value;
    }

    private String sanitizeInstallerFileStem(String raw, String fallback) {
        String value = blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            value = value.substring(0, value.length() - 4).trim();
        }
        String cleaned = value.replaceAll("[\\/:*?\"<>|]+", "_").replaceAll("\\s+", "_");
        cleaned = safeToken(cleaned);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private boolean prepareInstallerIcon(Path sourceIconPath, Path targetIcoPath) throws IOException {
        if (sourceIconPath == null) {
            return false;
        }
        Path source = sourceIconPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Installer icon file not found: " + source);
        }
        Files.createDirectories(targetIcoPath.getParent());
        if (source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ico")) {
            Files.copy(source, targetIcoPath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }

        BufferedImage image = loadInstallerIconImage(source);
        if (image == null) {
            throw new IOException("Unsupported installer icon format: " + source);
        }
        writeIcoFile(image, targetIcoPath);
        return true;
    }

    private BufferedImage loadInstallerIconImage(Path source) throws IOException {
        BufferedImage image = ImageIO.read(source.toFile());
        if (image != null) {
            return image;
        }
        if (isMac()) {
            Path tempPng = Files.createTempFile("mod_builder_installer_icon_", ".png");
            try {
                runCommand(List.of("sips", "-s", "format", "png", source.toString(), "--out", tempPng.toString()), null, null);
                return ImageIO.read(tempPng.toFile());
            } finally {
                Files.deleteIfExists(tempPng);
            }
        }
        return null;
    }

    private void writeIcoFile(BufferedImage source, Path target) throws IOException {
        BufferedImage image = resizeImage(source, 256);
        ByteArrayOutputStream pngBuffer = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", pngBuffer)) {
            throw new IOException("Failed to encode installer icon PNG payload.");
        }
        byte[] pngBytes = pngBuffer.toByteArray();
        ByteArrayOutputStream ico = new ByteArrayOutputStream();
        writeLeShort(ico, 0);
        writeLeShort(ico, 1);
        writeLeShort(ico, 1);
        ico.write(0);
        ico.write(0);
        ico.write(0);
        ico.write(0);
        writeLeShort(ico, 1);
        writeLeShort(ico, 32);
        writeLeInt(ico, pngBytes.length);
        writeLeInt(ico, 22);
        ico.write(pngBytes);
        Files.write(target, ico.toByteArray());
    }

    private BufferedImage resizeImage(BufferedImage source, int size) {
        int imageType = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resized = new BufferedImage(size, size, imageType);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    private void writeLeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private void writeLeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private Path resolveUniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }

        Path parent = path.getParent();
        String fileName = path.getFileName().toString();
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        boolean looksFile = dot > 0 && !Files.isDirectory(path);
        if (looksFile) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }

        for (int i = 2; i < 10_000; i++) {
            String candidateName = base + " (" + i + ")" + ext;
            Path candidate = parent.resolve(candidateName);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return path;
    }

    private String sanitizeSegment(String raw, String fallback) {
        String value = blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim().replace('\\', '_').replace('/', '_').replace(':', '_');
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private String safeToken(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
