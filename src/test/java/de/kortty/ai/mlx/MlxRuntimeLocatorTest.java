package de.kortty.ai.mlx;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class MlxRuntimeLocatorTest {

    @Test
    void resolvesAValidActiveInstallation() throws Exception {
        Path runtimeRoot = Files.createTempDirectory("kortty-mlx-locator-");
        Path packageDirectory = createPackage(runtimeRoot, "mlx-0.31.3-kortty1-macos-aarch64");
        Files.writeString(
            runtimeRoot.resolve("active"),
            "mlx-0.31.3-kortty1-macos-aarch64" + System.lineSeparator());

        MlxRuntimeLocator.MlxRuntimeInstallation installation =
            new MlxRuntimeLocator(runtimeRoot).locateActive().orElseThrow();

        assertThat(installation.id()).isEqualTo("mlx-0.31.3-kortty1-macos-aarch64");
        assertThat(installation.directory()).isEqualTo(packageDirectory.toAbsolutePath().normalize());
        assertThat(installation.pythonExecutable())
            .isEqualTo(packageDirectory.resolve("python").resolve("bin").resolve("python3"));
        assertThat(installation.launcherScript()).isEqualTo(packageDirectory.resolve("kortty_mlx_server.py"));
    }

    @Test
    void missingRootPointerOrPackageDirectoryYieldsEmpty() throws Exception {
        Path runtimeRoot = Files.createTempDirectory("kortty-mlx-locator-");

        assertThat(new MlxRuntimeLocator(runtimeRoot.resolve("does-not-exist")).locateActive()).isEmpty();
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();

        Files.writeString(runtimeRoot.resolve("active"), "mlx-0.31.3-kortty1-missing");
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();
    }

    @Test
    void rejectsUnsafeOrOversizedActivePointers() throws Exception {
        Path runtimeRoot = Files.createTempDirectory("kortty-mlx-locator-");
        createPackage(runtimeRoot, "valid");
        Path pointer = runtimeRoot.resolve("active");

        Files.writeString(pointer, "../valid");
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();

        Files.writeString(pointer, "");
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();

        Files.writeString(pointer, "x".repeat(2048));
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();

        Files.writeString(pointer, "valid");
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isPresent();
    }

    @Test
    void rejectsPackagesWithoutUsableInterpreterOrLauncher() throws Exception {
        Path runtimeRoot = Files.createTempDirectory("kortty-mlx-locator-");
        Files.writeString(runtimeRoot.resolve("active"), "incomplete");
        Path packageDirectory = Files.createDirectories(
            runtimeRoot.resolve("packages").resolve("incomplete"));

        // No interpreter at all.
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();

        // Interpreter present but not executable. Skipped on Windows/NTFS: there is no POSIX
        // executable bit to revoke via File.setExecutable(false) (it returns false there instead of
        // taking effect), and MLX is Apple-Silicon-only so this case never occurs on that OS anyway.
        Path python = Files.createDirectories(packageDirectory.resolve("python").resolve("bin"))
            .resolve("python3");
        Files.writeString(python, "interpreter");
        if (!isWindows()) {
            assertThat(python.toFile().setExecutable(false)).isTrue();
            assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();
            assertThat(python.toFile().setExecutable(true)).isTrue();
        }

        // Executable interpreter but no launcher script.
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isEmpty();

        // Complete package resolves.
        Files.writeString(packageDirectory.resolve("kortty_mlx_server.py"), "# launcher");
        assertThat(new MlxRuntimeLocator(runtimeRoot).locateActive()).isPresent();
    }

    @Test
    void standardLayoutResolvesBelowTheLlmDirectory() throws Exception {
        Path llmDirectory = Files.createTempDirectory("kortty-mlx-llm-");

        MlxRuntimeLocator locator = MlxRuntimeLocator.inLlmDirectory(llmDirectory);

        assertThat(locator.getRuntimeRoot())
            .isEqualTo(llmDirectory.toAbsolutePath().normalize().resolve("mlx").resolve("runtime"));
        assertThat(locator.locateActive()).isEmpty();
    }

    private static Path createPackage(Path runtimeRoot, String installationId) throws Exception {
        Path packageDirectory = Files.createDirectories(
            runtimeRoot.resolve("packages").resolve(installationId));
        Path python = Files.createDirectories(packageDirectory.resolve("python").resolve("bin"))
            .resolve("python3");
        Files.writeString(python, "interpreter");
        assertThat(python.toFile().setExecutable(true)).isTrue();
        Files.writeString(packageDirectory.resolve("kortty_mlx_server.py"), "# launcher");
        return packageDirectory;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
