package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaRuntimeVersionHealthCheckTest {

    @Test
    void runsVersionCommandAndAcceptsSuccessfulOutput() throws Exception {
        requireUnix();
        Path directory = Files.createTempDirectory("kortty-llama-health-");
        Path executable = script(directory, "#!/bin/sh\n[ \"$1\" = \"--version\" ] || exit 9\necho 'llama.cpp b10025'\n");
        LlamaRuntimeInstallation installation = installation(directory, executable);

        // Generous budget on purpose: this case is about the success path, not the deadline. Two
        // seconds to spawn a process and read its output was thin on a loaded machine, and the
        // test failed intermittently in full-suite runs while passing alone.
        // killsVersionCommandAfterTimeout keeps the short timeout that covers the deadline.
        assertThat(new LlamaRuntimeVersionHealthCheck(Duration.ofSeconds(30)).isHealthy(installation)).isTrue();
    }

    @Test
    void killsVersionCommandAfterTimeout() throws Exception {
        requireUnix();
        Path directory = Files.createTempDirectory("kortty-llama-health-timeout-");
        Path executable = script(directory, "#!/bin/sh\nsleep 5\necho late\n");
        LlamaRuntimeInstallation installation = installation(directory, executable);
        long started = System.nanoTime();

        LlamaRuntimeHealthCheckFailedException failure = expectThrows(
            LlamaRuntimeHealthCheckFailedException.class,
            () -> new LlamaRuntimeVersionHealthCheck(Duration.ofMillis(100)).isHealthy(installation));
        assertThat(failure).hasMessageThat().contains("did not finish within 100 ms");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void reportsExitCodeAndOutputOfAFailedVersionCommand() throws Exception {
        requireUnix();
        Path directory = Files.createTempDirectory("kortty-llama-health-exit-");
        Path executable = script(directory, "#!/bin/sh\necho 'error while loading shared libraries: libssl.so.3'\nexit 127\n");
        LlamaRuntimeInstallation installation = installation(directory, executable);

        LlamaRuntimeHealthCheckFailedException failure = expectThrows(
            LlamaRuntimeHealthCheckFailedException.class,
            () -> new LlamaRuntimeVersionHealthCheck(Duration.ofSeconds(30)).isHealthy(installation));

        assertThat(failure).hasMessageThat().contains("exited with code 127 (0x0000007F)");
        assertThat(failure).hasMessageThat().contains("libssl.so.3");
    }

    @Test
    void reportsAnExecutableThatCannotBeStarted() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-health-missing-");
        LlamaRuntimeInstallation installation = installation(directory, directory.resolve("llama-server-missing"));

        LlamaRuntimeHealthCheckFailedException failure = expectThrows(
            LlamaRuntimeHealthCheckFailedException.class,
            () -> new LlamaRuntimeVersionHealthCheck(Duration.ofSeconds(30)).isHealthy(installation));

        assertThat(failure).hasMessageThat().startsWith("llama-server could not be started");
    }

    @Test
    void namesTheWindowsLoaderStatusForAMissingDll() {
        // The exit code a Windows package without its OpenSSL DLLs ended with on user machines.
        assertThat(LlamaRuntimeVersionHealthCheck.describeExit(0xC0000135))
            .isEqualTo("llama-server --version exited with code -1073741515 (0xC0000135): "
                + "a DLL it needs is missing (STATUS_DLL_NOT_FOUND).");
        assertThat(LlamaRuntimeVersionHealthCheck.describeExit(3))
            .isEqualTo("llama-server --version exited with code 3 (0x00000003).");
    }

    private static Path script(Path directory, String content) throws Exception {
        Path executable = Files.writeString(directory.resolve("llama-server"), content);
        try {
            Files.setPosixFilePermissions(executable, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            executable.toFile().setExecutable(true, true);
        }
        return executable;
    }

    private static LlamaRuntimeInstallation installation(Path directory, Path executable) {
        LlamaRuntimePackageDescriptor descriptor = new LlamaRuntimePackageDescriptor(
            "llama-b10025-kortty1",
            "b10025",
            LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1,
            "2.5.2",
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            LlamaBackend.CPU,
            1,
            "0".repeat(64),
            URI.create("https://example.test/runtime.zip"),
            executable.getFileName().toString(),
            false);
        return new LlamaRuntimeInstallation(descriptor, directory, executable);
    }

    private static void requireUnix() {
        if (LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS) {
            throw new SkipException("Shell-script health-check fixture is Unix-only.");
        }
    }
}
