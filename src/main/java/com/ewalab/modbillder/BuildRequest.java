package com.ewalab.modbillder;

import java.nio.file.Path;
import java.util.List;

public record BuildRequest(
        List<Path> sourcePaths,
        Path outputDirectory,
        Region region,
        String gameRoot,
        String modsFolderName,
        String versionFolder,
        String installerName,
        String setupWindowTitle,
        Path installerIconPath,
        boolean createZip,
        boolean createInstallerExe
) {
}
