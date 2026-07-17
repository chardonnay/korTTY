package de.kortty.ai.mlx;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** One immutable macOS/arm64 MLX runtime artifact from mlx-runtime-index-v1.json. */
public record MlxRuntimePackageDescriptor(
    int schemaVersion,
    String runtimeId,
    String installationId,
    String platform,
    String architecture,
    String backend,
    String minimumOsVersion,
    String mlxLmVersion,
    String pythonVersion,
    String sourceCommit,
    String executablePath,
    String launcherPath,
    long sizeBytes,
    String sha256,
    String requirementsLockSha256,
    URI downloadUri
) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String SAFE_IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";
    private static final String VERSION_TOKEN = "[0-9A-Za-z][0-9A-Za-z._+-]{0,63}";

    public MlxRuntimePackageDescriptor {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported MLX runtime package schema: " + schemaVersion);
        }
        if (runtimeId == null || !runtimeId.matches(SAFE_IDENTIFIER)) {
            throw new IllegalArgumentException("Invalid MLX runtime id.");
        }
        if (installationId == null || !installationId.matches(SAFE_IDENTIFIER)) {
            throw new IllegalArgumentException("Invalid MLX runtime installation id.");
        }
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("MLX runtime platform is required.");
        }
        platform = platform.trim().toLowerCase(Locale.ROOT);
        if (architecture == null || architecture.isBlank()) {
            throw new IllegalArgumentException("MLX runtime architecture is required.");
        }
        architecture = normalizeArchitecture(architecture);
        if (backend == null || !"MLX".equalsIgnoreCase(backend.trim())) {
            throw new IllegalArgumentException("MLX runtime artifact must use the MLX backend.");
        }
        backend = "MLX";
        minimumOsVersion = requireVersionToken(minimumOsVersion, "minimumOsVersion");
        mlxLmVersion = requireVersionToken(mlxLmVersion, "mlxLmVersion");
        pythonVersion = requireVersionToken(pythonVersion, "pythonVersion");
        if (sourceCommit == null || !sourceCommit.matches("(?i)[0-9a-f]{7,64}")) {
            throw new IllegalArgumentException("MLX runtime source commit is invalid.");
        }
        sourceCommit = sourceCommit.toLowerCase(Locale.ROOT);
        executablePath = validateRelativePath(executablePath, "executablePath");
        launcherPath = validateRelativePath(launcherPath, "launcherPath");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("MLX runtime package size must be positive.");
        }
        sha256 = requireSha256(sha256, "sha256");
        requirementsLockSha256 = requireSha256(requirementsLockSha256, "requirementsLockSha256");
        if (downloadUri == null || !("https".equalsIgnoreCase(downloadUri.getScheme())
            || "http".equalsIgnoreCase(downloadUri.getScheme()) && isLoopback(downloadUri.getHost()))) {
            throw new IllegalArgumentException("MLX runtime package URL must use HTTPS.");
        }
    }

    /** True when this entry targets the only platform MLX exists on: macOS arm64. */
    public boolean matchesCurrentPlatform() {
        return "macos".equals(platform) && "aarch64".equals(architecture);
    }

    public Path resolveExecutable(Path installationDirectory) {
        return resolveInside(installationDirectory, executablePath);
    }

    public Path resolveLauncher(Path installationDirectory) {
        return resolveInside(installationDirectory, launcherPath);
    }

    private static Path resolveInside(Path installationDirectory, String relative) {
        Path root = installationDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalStateException("MLX runtime path escapes its installation directory.");
        }
        return resolved;
    }

    private static String normalizeArchitecture(String architecture) {
        return switch (architecture.trim().toLowerCase(Locale.ROOT)) {
            case "arm64", "aarch64" -> "aarch64";
            default -> architecture.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String requireVersionToken(String value, String name) {
        if (value == null || !value.trim().matches(VERSION_TOKEN)) {
            throw new IllegalArgumentException("MLX runtime " + name + " is invalid.");
        }
        return value.trim();
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("MLX runtime " + name + " is invalid.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String validateRelativePath(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MLX runtime " + name + " is required.");
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")
            || normalized.endsWith("/..") || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("MLX runtime " + name + " must be a safe relative path.");
        }
        return normalized;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
