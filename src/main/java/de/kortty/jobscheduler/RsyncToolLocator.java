package de.kortty.jobscheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

class RsyncToolLocator {

    String resolveRsync(String configuredPath) throws JobBlockedException {
        return resolve(configuredPath, "rsync");
    }

    String resolveSsh() throws JobBlockedException {
        return resolve(null, "ssh");
    }

    private String resolve(String configuredPath, String executableName) throws JobBlockedException {
        String configured = configuredPath != null ? configuredPath.trim() : "";
        if (!configured.isEmpty()) {
            Path configuredFile = Path.of(configured);
            if (configuredFile.isAbsolute() || configured.contains("/") || configured.contains("\\")) {
                if (isRunnable(configuredFile)) {
                    return configuredFile.toAbsolutePath().toString();
                }
                throw new JobBlockedException(executableName + " executable is not runnable: " + configured);
            }
            return searchPath(configured)
                .stream()
                .findFirst()
                .orElseThrow(() -> new JobBlockedException(executableName + " executable was not found in PATH: " + configured));
        }
        return searchPath(executableName)
            .stream()
            .findFirst()
            .orElseThrow(() -> new JobBlockedException(executableName + " executable was not found in PATH."));
    }

    private List<String> searchPath(String executableName) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            for (String candidateName : candidateNames(executableName)) {
                Path candidate = Path.of(directory, candidateName);
                if (isRunnable(candidate)) {
                    results.add(candidate.toAbsolutePath().toString());
                }
            }
        }
        return results;
    }

    private List<String> candidateNames(String executableName) {
        if (!isWindows() || executableName.contains(".")) {
            return List.of(executableName);
        }
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            return List.of(executableName, executableName + ".exe");
        }
        return Arrays.stream(pathext.split(";"))
            .filter(ext -> ext != null && !ext.isBlank())
            .map(ext -> executableName + ext.toLowerCase(Locale.ROOT))
            .toList();
    }

    private boolean isRunnable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
