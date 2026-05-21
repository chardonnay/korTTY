package de.kortty.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class DownloadDirectoryResolver {

    private DownloadDirectoryResolver() {}

    public static Path resolveDefaultDownloadsDirectory() {
        Path home = Path.of(System.getProperty("user.home"));
        return resolve(home, System.getenv(), System.getProperty("os.name", ""));
    }

    static Path resolve(Path home, Map<String, String> environment, String osName) {
        if (home == null) {
            return Path.of("Downloads").toAbsolutePath();
        }
        String normalizedOs = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
        if (normalizedOs.contains("linux")) {
            Path xdgDownloads = resolveLinuxXdgDownloadsDirectory(home, environment);
            if (xdgDownloads != null) {
                return xdgDownloads;
            }
        }
        return home.resolve("Downloads");
    }

    private static Path resolveLinuxXdgDownloadsDirectory(Path home, Map<String, String> environment) {
        String envValue = environment != null ? environment.get("XDG_DOWNLOAD_DIR") : null;
        Path fromEnvironment = resolveXdgPath(home, envValue);
        if (fromEnvironment != null) {
            return fromEnvironment;
        }
        Path userDirs = home.resolve(".config").resolve("user-dirs.dirs");
        if (!Files.isRegularFile(userDirs)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(userDirs, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("XDG_DOWNLOAD_DIR=")) {
                    continue;
                }
                return resolveXdgPath(home, trimmed.substring("XDG_DOWNLOAD_DIR=".length()));
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static Path resolveXdgPath(Path home, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 2
            && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.replace("$HOME", home.toString());
        Path path = Path.of(normalized);
        return path.isAbsolute() ? path : home.resolve(path).normalize();
    }
}
