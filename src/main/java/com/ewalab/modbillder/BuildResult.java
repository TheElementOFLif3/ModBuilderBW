package com.ewalab.modbillder;

import java.nio.file.Path;

public record BuildResult(
        Path buildFolder,
        Path zipFile,
        Path installerExeFile,
        String recommendedInstallPath,
        int copiedItems
) {
}
