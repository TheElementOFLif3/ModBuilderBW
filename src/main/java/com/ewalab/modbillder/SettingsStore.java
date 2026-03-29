package com.ewalab.modbillder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class SettingsStore {
    private final Path settingsFile;

    public SettingsStore(Path workspaceDir) {
        this.settingsFile = workspaceDir.resolve("mod_builder_java21.settings.properties");
    }

    public Path getSettingsFile() {
        return settingsFile;
    }

    public SettingsData load() {
        if (!Files.isRegularFile(settingsFile)) {
            return null;
        }

        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            return null;
        }

        String language = p.getProperty("language", "EN");
        String region = p.getProperty("region", Region.EU.name());
        String gameRoot = p.getProperty("gameRoot", "");
        String modsFolder = p.getProperty("modsFolder", "mods");
        String version = p.getProperty("version", "2.1.1.2");
        String outputDir = p.getProperty("outputDir", "");
        String installerName = p.getProperty("installerName", "");
        String setupWindowTitle = p.getProperty("setupWindowTitle", "");
        String installerIconPath = p.getProperty("installerIconPath", "");
        boolean createZip = Boolean.parseBoolean(p.getProperty("createZip", "true"));
        boolean createInstallerExe = Boolean.parseBoolean(p.getProperty("createInstallerExe", "false"));

        int count = parseInt(p.getProperty("sources.count"), 0);
        List<SourceEntry> sources = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = p.getProperty("sources." + i + ".path");
            if (path == null || path.isBlank()) {
                continue;
            }
            boolean included = Boolean.parseBoolean(p.getProperty("sources." + i + ".included", "true"));
            try {
                Path sourcePath = Path.of(path).toAbsolutePath().normalize();
                if (Files.exists(sourcePath)) {
                    sources.add(new SourceEntry(sourcePath, included));
                }
            } catch (Exception ignored) {
                // Ignore invalid stored path.
            }
        }

        return new SettingsData(language, region, gameRoot, modsFolder, version, outputDir, installerName, setupWindowTitle, installerIconPath, createZip, createInstallerExe, sources);
    }

    public void save(SettingsData data) throws IOException {
        Files.createDirectories(settingsFile.getParent());

        Properties p = new Properties();
        p.setProperty("language", nullToEmpty(data.language()));
        p.setProperty("region", data.region() == null ? Region.EU.name() : data.region());
        p.setProperty("gameRoot", nullToEmpty(data.gameRoot()));
        p.setProperty("modsFolder", nullToEmpty(data.modsFolder()));
        p.setProperty("version", nullToEmpty(data.version()));
        p.setProperty("outputDir", nullToEmpty(data.outputDir()));
        p.setProperty("installerName", nullToEmpty(data.installerName()));
        p.setProperty("setupWindowTitle", nullToEmpty(data.setupWindowTitle()));
        p.setProperty("installerIconPath", nullToEmpty(data.installerIconPath()));
        p.setProperty("createZip", Boolean.toString(data.createZip()));
        p.setProperty("createInstallerExe", Boolean.toString(data.createInstallerExe()));

        List<SourceEntry> sources = data.sources() == null ? List.of() : data.sources();
        p.setProperty("sources.count", Integer.toString(sources.size()));
        for (int i = 0; i < sources.size(); i++) {
            SourceEntry s = sources.get(i);
            p.setProperty("sources." + i + ".path", s.getPath().toString());
            p.setProperty("sources." + i + ".included", Boolean.toString(s.isIncluded()));
        }

        try (Writer writer = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
            p.store(writer, "Mod Builder BW settings");
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record SettingsData(
            String language,
            String region,
            String gameRoot,
            String modsFolder,
            String version,
            String outputDir,
            String installerName,
            String setupWindowTitle,
            String installerIconPath,
            boolean createZip,
            boolean createInstallerExe,
            List<SourceEntry> sources
    ) {
    }
}
