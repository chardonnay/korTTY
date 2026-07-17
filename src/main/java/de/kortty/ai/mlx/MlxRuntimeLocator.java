package de.kortty.ai.mlx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the active MLX runtime installation below {@code <llmDir>/mlx/runtime}.
 *
 * <p>The runtime root contains an {@code active} pointer file naming one immutable installation in
 * {@code packages/<id>/}. A valid installation ships the relocatable CPython at
 * {@code python/bin/python3} and korTTY's authenticated launcher {@code kortty_mlx_server.py}.
 * Every failure mode (missing pointer, unsafe id, escape from the packages directory, missing or
 * non-executable files) resolves to {@link Optional#empty()} so callers fail closed instead of
 * launching an unverified interpreter.
 */
public final class MlxRuntimeLocator {

    public static final String ACTIVE_POINTER_FILE = "active";
    public static final String PACKAGES_DIRECTORY = "packages";
    public static final String LAUNCHER_SCRIPT_NAME = "kortty_mlx_server.py";

    private static final long MAX_POINTER_BYTES = 1024;
    private static final Pattern SAFE_INSTALLATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path runtimeRoot;
    private final Path packagesDirectory;

    public MlxRuntimeLocator(Path runtimeRoot) {
        if (runtimeRoot == null) {
            throw new IllegalArgumentException("MLX runtime root must be configured.");
        }
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.packagesDirectory = this.runtimeRoot.resolve(PACKAGES_DIRECTORY);
    }

    /** Returns the locator for the standard layout below {@code <llmDir>/mlx/runtime}. */
    public static MlxRuntimeLocator inLlmDirectory(Path llmDirectory) {
        if (llmDirectory == null) {
            throw new IllegalArgumentException("MLX data directory must be configured.");
        }
        return new MlxRuntimeLocator(llmDirectory.resolve("mlx").resolve("runtime"));
    }

    public Path getRuntimeRoot() {
        return runtimeRoot;
    }

    /** Resolves and validates the active installation; anything invalid yields empty. */
    public Optional<MlxRuntimeInstallation> locateActive() {
        try {
            Path pointer = runtimeRoot.resolve(ACTIVE_POINTER_FILE);
            if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)
                || Files.size(pointer) > MAX_POINTER_BYTES) {
                return Optional.empty();
            }
            String installationId = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (!SAFE_INSTALLATION_ID.matcher(installationId).matches()) {
                return Optional.empty();
            }
            Path directory = packagesDirectory.resolve(installationId).normalize();
            if (!directory.startsWith(packagesDirectory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            // The interpreter check must follow links: a dev-built runtime package is a plain venv
            // whose bin/python3 is a symlink, and that symlinked path (not its target) is what a
            // launch command has to invoke for the package's site-packages to be visible.
            Path pythonExecutable = directory.resolve("python").resolve("bin").resolve("python3");
            if (!Files.isRegularFile(pythonExecutable) || !Files.isExecutable(pythonExecutable)) {
                return Optional.empty();
            }
            Path launcherScript = directory.resolve(LAUNCHER_SCRIPT_NAME);
            if (!Files.isRegularFile(launcherScript, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(new MlxRuntimeInstallation(installationId, directory, pythonExecutable, launcherScript));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    /** Validated active MLX runtime package on local disk. */
    public record MlxRuntimeInstallation(
        String id,
        Path directory,
        Path pythonExecutable,
        Path launcherScript
    ) {
    }
}
