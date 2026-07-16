package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Bounded local smoke test used before an installed runtime becomes active. */
public final class LlamaRuntimeVersionHealthCheck implements LlamaRuntimePackageInstaller.RuntimeHealthCheck {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_OUTPUT_BYTES = 1024 * 1024;

    private final Duration timeout;

    public LlamaRuntimeVersionHealthCheck() {
        this(DEFAULT_TIMEOUT);
    }

    LlamaRuntimeVersionHealthCheck(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Runtime health-check timeout must be positive.");
        }
    }

    @Override
    public boolean isHealthy(LlamaRuntimeInstallation installation) throws Exception {
        Objects.requireNonNull(installation, "installation");
        Path output = Files.createTempFile("kortty-llama-version-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(installation.executable().toString(), "--version");
            builder.directory(installation.directory().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.min(2_000L, timeout.toMillis()), TimeUnit.MILLISECONDS);
                return false;
            }
            if (process.exitValue() != 0 || Files.size(output) == 0 || Files.size(output) > MAX_OUTPUT_BYTES) {
                return false;
            }
            String version = Files.readString(output, StandardCharsets.UTF_8).trim();
            return !version.isBlank();
        } catch (IOException e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(output);
        }
    }
}
