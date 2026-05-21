package de.kortty.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public record PlatformProfile(
    OperatingSystem operatingSystem,
    String architecture,
    String linuxId,
    Set<String> linuxIdLike
) {

    public PlatformProfile {
        operatingSystem = operatingSystem != null ? operatingSystem : OperatingSystem.OTHER;
        architecture = canonicalArchitecture(architecture);
        linuxId = normalizeToken(linuxId);
        linuxIdLike = linuxIdLike == null ? Set.of() : Set.copyOf(normalizeTokens(linuxIdLike));
    }

    public static PlatformProfile current() {
        OperatingSystem operatingSystem = detectOperatingSystem(System.getProperty("os.name", ""));
        String architecture = canonicalArchitecture(System.getProperty("os.arch", ""));
        String linuxId = null;
        Set<String> linuxIdLike = Set.of();
        if (operatingSystem == OperatingSystem.LINUX) {
            OsRelease osRelease = readOsRelease(Path.of("/etc/os-release"));
            linuxId = osRelease.id();
            linuxIdLike = osRelease.idLike();
        }
        return new PlatformProfile(operatingSystem, architecture, linuxId, linuxIdLike);
    }

    public boolean linuxMatches(String... tokens) {
        Set<String> actual = new HashSet<>();
        if (linuxId != null && !linuxId.isBlank()) {
            actual.add(linuxId);
        }
        actual.addAll(linuxIdLike);
        for (String token : tokens) {
            if (actual.contains(normalizeToken(token))) {
                return true;
            }
        }
        return false;
    }

    public Set<String> architectureTokens() {
        return switch (architecture) {
            case "x86_64" -> Set.of("x86_64", "amd64", "x64");
            case "aarch64" -> Set.of("aarch64", "arm64");
            default -> Set.of(architecture);
        };
    }

    static OperatingSystem detectOperatingSystem(String osName) {
        String normalized = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
        if (normalized.contains("win")) {
            return OperatingSystem.WINDOWS;
        }
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return OperatingSystem.MACOS;
        }
        if (normalized.contains("linux")) {
            return OperatingSystem.LINUX;
        }
        return OperatingSystem.OTHER;
    }

    static String canonicalArchitecture(String architecture) {
        String normalized = architecture != null ? architecture.toLowerCase(Locale.ROOT).trim() : "";
        return switch (normalized) {
            case "x86_64", "amd64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> normalized.isBlank() ? "unknown" : normalized;
        };
    }

    static OsRelease readOsRelease(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new OsRelease(null, Set.of());
        }
        try {
            String id = null;
            Set<String> idLike = new HashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ID=")) {
                    id = normalizeOsReleaseValue(trimmed.substring(3));
                } else if (trimmed.startsWith("ID_LIKE=")) {
                    String value = normalizeOsReleaseValue(trimmed.substring(8));
                    if (value != null) {
                        for (String token : value.split("\\s+")) {
                            String normalized = normalizeToken(token);
                            if (!normalized.isBlank()) {
                                idLike.add(normalized);
                            }
                        }
                    }
                }
            }
            return new OsRelease(id, idLike);
        } catch (IOException e) {
            return new OsRelease(null, Set.of());
        }
    }

    private static String normalizeOsReleaseValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
            && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return normalizeToken(trimmed);
    }

    private static Set<String> normalizeTokens(Set<String> tokens) {
        Set<String> normalized = new HashSet<>();
        for (String token : tokens) {
            String value = normalizeToken(token);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static String normalizeToken(String token) {
        return token != null ? token.trim().toLowerCase(Locale.ROOT) : "";
    }

    record OsRelease(String id, Set<String> idLike) {
        OsRelease {
            id = normalizeToken(id);
            idLike = idLike == null ? Set.of() : Set.copyOf(idLike);
        }
    }
}
