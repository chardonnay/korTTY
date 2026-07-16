package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** One immutable platform/backend artifact from runtime-index-v1.json. */
public record LlamaRuntimePackageDescriptor(
    String runtimeId,
    String llamaTag,
    String commit,
    int apiContractVersion,
    String minimumKorttyVersion,
    LlamaRuntimePlatform platform,
    String architecture,
    LlamaBackend backend,
    long size,
    String sha256,
    URI downloadUri,
    String entrypoint,
    boolean revoked
) {

    public LlamaRuntimePackageDescriptor {
        if (runtimeId == null || !runtimeId.matches("llama-b[0-9]+-kortty[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid immutable llama.cpp runtime id.");
        }
        if (llamaTag == null || !llamaTag.matches("b[0-9]+")) {
            throw new IllegalArgumentException("Invalid llama.cpp release tag.");
        }
        if (commit == null || !commit.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Runtime commit must be a full SHA-1.");
        }
        commit = commit.toLowerCase(Locale.ROOT);
        if (apiContractVersion < 1) {
            throw new IllegalArgumentException("Runtime API contract version must be positive.");
        }
        if (minimumKorttyVersion == null || minimumKorttyVersion.isBlank()) {
            throw new IllegalArgumentException("Minimum korTTY version is required.");
        }
        if (platform == null) {
            throw new IllegalArgumentException("Runtime platform is required.");
        }
        architecture = normalizeArchitecture(architecture);
        if (backend == null || backend == LlamaBackend.AUTO) {
            throw new IllegalArgumentException("Runtime artifact must name a concrete backend.");
        }
        if (backend == LlamaBackend.METAL && platform != LlamaRuntimePlatform.MACOS) {
            throw new IllegalArgumentException("Metal runtime artifacts are valid only on macOS.");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Runtime package size must be positive.");
        }
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Runtime package SHA-256 is invalid.");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (downloadUri == null || !("https".equalsIgnoreCase(downloadUri.getScheme())
            || "http".equalsIgnoreCase(downloadUri.getScheme()) && isLoopback(downloadUri.getHost()))) {
            throw new IllegalArgumentException("Runtime package URL must use HTTPS.");
        }
        entrypoint = validateEntrypoint(entrypoint);
    }

    public String installationId() {
        return runtimeId + "-" + platform.manifestValue() + "-" + architecture + "-"
            + backend.name().toLowerCase(Locale.ROOT);
    }

    public Path resolveEntrypoint(Path installationDirectory) {
        Path root = installationDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(entrypoint.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Runtime entrypoint escapes installation directory.");
        }
        return resolved;
    }

    public static String normalizeArchitecture(String architecture) {
        if (architecture == null) {
            throw new IllegalArgumentException("Runtime architecture is required.");
        }
        return switch (architecture.trim().toLowerCase(Locale.ROOT)) {
            case "amd64", "x64", "x86-64", "x86_64" -> "x86_64";
            case "arm64", "aarch64" -> "aarch64";
            default -> throw new IllegalArgumentException("Unsupported runtime architecture: " + architecture);
        };
    }

    public static String currentArchitecture() {
        return normalizeArchitecture(System.getProperty("os.arch", ""));
    }

    private static String validateEntrypoint(String entrypoint) {
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("Runtime entrypoint is required.");
        }
        String normalized = entrypoint.replace('\\', '/');
        Path path = Path.of(normalized);
        if (path.isAbsolute() || normalized.startsWith("/") || normalized.contains("../")
            || normalized.equals("..") || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Runtime entrypoint must be a safe relative path.");
        }
        return normalized;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
