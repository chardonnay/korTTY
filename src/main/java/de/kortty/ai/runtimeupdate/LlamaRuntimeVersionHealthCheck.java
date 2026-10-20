package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded local smoke test used before an installed runtime becomes active. Every rejection throws
 * {@link LlamaRuntimeHealthCheckFailedException} with the reason — exit code, the Windows loader
 * status it stands for, and the start of the output — and logs it, because a rolled-back install
 * otherwise leaves the user with nothing to go on.
 */
public final class LlamaRuntimeVersionHealthCheck implements LlamaRuntimePackageInstaller.RuntimeHealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(LlamaRuntimeVersionHealthCheck.class);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_DETAIL_OUTPUT_CHARS = 300;

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
            try {
                process = builder.start();
            } catch (IOException e) {
                throw fail(installation, "llama-server could not be started: " + e.getMessage(), e);
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.min(2_000L, timeout.toMillis()), TimeUnit.MILLISECONDS);
                throw fail(installation, "llama-server --version did not finish within "
                    + timeout.toMillis() + " ms.", null);
            }
            long size = Files.size(output);
            if (size > MAX_OUTPUT_BYTES) {
                throw fail(installation, "llama-server --version wrote more than "
                    + MAX_OUTPUT_BYTES + " bytes.", null);
            }
            String version = Files.readString(output, StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw fail(installation, describeExit(exitCode) + outputExcerpt(version), null);
            }
            if (version.isBlank()) {
                throw fail(installation, "llama-server --version printed nothing.", null);
            }
            return true;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(output);
        }
    }

    /** Exit-code text, naming the Windows loader statuses a broken package typically ends with. */
    static String describeExit(int exitCode) {
        String text = "llama-server --version exited with code " + exitCode
            + String.format(Locale.ROOT, " (0x%08X)", exitCode);
        String meaning = switch (exitCode) {
            case 0xC0000135 -> "a DLL it needs is missing (STATUS_DLL_NOT_FOUND)";
            case 0xC0000139 -> "a DLL it loaded lacks a required function (STATUS_ENTRYPOINT_NOT_FOUND)";
            case 0xC000007B -> "a DLL it loaded has the wrong architecture (STATUS_INVALID_IMAGE_FORMAT)";
            case 0xC0000142 -> "a DLL failed to initialize (STATUS_DLL_INIT_FAILED)";
            case 0xC000001D -> "the CPU does not support its instruction set (STATUS_ILLEGAL_INSTRUCTION)";
            default -> null;
        };
        return meaning != null ? text + ": " + meaning + "." : text + ".";
    }

    private static String outputExcerpt(String output) {
        if (output.isBlank()) {
            return "";
        }
        String flat = output.replaceAll("\\s+", " ");
        return " Output: " + (flat.length() > MAX_DETAIL_OUTPUT_CHARS
            ? flat.substring(0, MAX_DETAIL_OUTPUT_CHARS) + "…"
            : flat);
    }

    private static LlamaRuntimeHealthCheckFailedException fail(
        LlamaRuntimeInstallation installation,
        String reason,
        Throwable cause
    ) {
        logger.warn("llama.cpp runtime {} failed its health check ({}): {}",
            installation.descriptor().runtimeId(), installation.executable(), reason);
        return new LlamaRuntimeHealthCheckFailedException(reason, cause);
    }
}
