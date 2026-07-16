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

class LlamaRuntimeVersionHealthCheckTest {

    @Test
    void runsVersionCommandAndAcceptsSuccessfulOutput() throws Exception {
        requireUnix();
        Path directory = Files.createTempDirectory("kortty-llama-health-");
        Path executable = script(directory, "#!/bin/sh\n[ \"$1\" = \"--version\" ] || exit 9\necho 'llama.cpp b10025'\n");
        LlamaRuntimeInstallation installation = installation(directory, executable);

        assertThat(new LlamaRuntimeVersionHealthCheck(Duration.ofSeconds(2)).isHealthy(installation)).isTrue();
    }

    @Test
    void killsVersionCommandAfterTimeout() throws Exception {
        requireUnix();
        Path directory = Files.createTempDirectory("kortty-llama-health-timeout-");
        Path executable = script(directory, "#!/bin/sh\nsleep 5\necho late\n");
        LlamaRuntimeInstallation installation = installation(directory, executable);
        long started = System.nanoTime();

        assertThat(new LlamaRuntimeVersionHealthCheck(Duration.ofMillis(100)).isHealthy(installation)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
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
