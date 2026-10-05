package de.kortty.isolation.sandbox;

import de.kortty.platform.FlatpakSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which operating-system sandbox korTTY can use on this computer, and whether it really works.
 *
 * <p>A sandbox counts as available only after a self-test: a secret written into korTTY's configuration
 * folder must be unreadable from inside it, while a harmless command must still run. The result is
 * cached for the run of the app, so the test costs two short-lived processes once. A shield on a tab
 * therefore shows a sandbox that was seen to hold, never one that was merely asked for.
 */
public final class SandboxSupport {

    private static final Logger logger = LoggerFactory.getLogger(SandboxSupport.class);

    /** Why no sandbox can be used; {@link #AVAILABLE} when one can. */
    public enum Status {
        AVAILABLE,
        /** The operating system has no backend korTTY supports yet (Windows). */
        UNSUPPORTED_OS,
        /** korTTY runs in Flatpak, which is a sandbox itself and does not let it nest another one. */
        FLATPAK,
        /** The tool is missing ({@code bwrap} on Linux). */
        NOT_INSTALLED,
        /** The tool exists, but the self-test failed: the secret was readable, or nothing could start. */
        SELF_TEST_FAILED
    }

    /**
     * The result of checking for a sandbox.
     *
     * @param status    whether one can be used, or why not
     * @param backendId the backend's {@link SandboxBackend#id()}, or null when there is none
     * @param detail    a technical detail for the log and the tooltip, or null
     */
    public record Availability(Status status, String backendId, String detail) {

        public boolean available() {
            return status == Status.AVAILABLE;
        }
    }

    /** The name of the folder in korTTY's configuration folder the self-test writes its secret to. */
    static final String PROBE_FOLDER = "isolation";

    private static final AtomicReference<Availability> cached = new AtomicReference<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SandboxSupport() {
    }

    /** The backend for this operating system, or null when there is none. */
    public static SandboxBackend backend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MacSandboxBackend();
        }
        if (os.contains("linux")) {
            return new BubblewrapSandboxBackend();
        }
        return null;
    }

    /**
     * Whether a sandbox can be used, self-testing it the first time. Starts processes on the first call;
     * call it off the JavaFX thread.
     */
    public static Availability availability() {
        Availability known = cached.get();
        if (known != null) {
            return known;
        }
        Availability checked = check(backend(), de.kortty.KorTTYApplication.getConfigDirectory());
        cached.compareAndSet(null, checked);
        logger.info("Session sandbox: {} ({}){}", checked.status(), checked.backendId(),
            checked.detail() != null ? " - " + checked.detail() : "");
        return cached.get();
    }

    /** The cached result, or null while the self-test has not run. Safe on the JavaFX thread. */
    public static Availability cachedAvailability() {
        return cached.get();
    }

    /** Forgets the cached result; for tests. */
    static void resetForTests() {
        cached.set(null);
    }

    /** The check behind {@link #availability()} with its parts given; package-private for tests. */
    static Availability check(SandboxBackend backend, Path configDirectory) {
        if (FlatpakSupport.isRunningInFlatpak()) {
            return new Availability(Status.FLATPAK, null, null);
        }
        if (backend == null) {
            return new Availability(Status.UNSUPPORTED_OS, null, System.getProperty("os.name"));
        }
        if (!backend.installed()) {
            return new Availability(Status.NOT_INSTALLED, backend.id(), null);
        }
        Path probeDir = configDirectory.resolve(PROBE_FOLDER);
        Path secretFile = probeDir.resolve("sandbox-probe-" + randomHex(8));
        String secret = randomHex(16);
        try {
            Files.createDirectories(probeDir);
            Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
            SandboxSpec spec = new SandboxSpec(List.of(configDirectory), List.of(), true, List.of());
            ProbeResult ran = run(backend.wrap(List.of("/bin/sh", "-c", "echo sandbox-ok"), spec));
            if (ran.exitCode() != 0 || !ran.output().contains("sandbox-ok")) {
                return new Availability(Status.SELF_TEST_FAILED, backend.id(),
                    "command did not run (exit " + ran.exitCode() + ")");
            }
            ProbeResult read = run(backend.wrap(List.of("/bin/cat", secretFile.toString()), spec));
            if (read.output().contains(secret)) {
                return new Availability(Status.SELF_TEST_FAILED, backend.id(), "configuration folder was readable");
            }
            return new Availability(Status.AVAILABLE, backend.id(), null);
        } catch (IOException | RuntimeException e) {
            return new Availability(Status.SELF_TEST_FAILED, backend.id(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Availability(Status.SELF_TEST_FAILED, backend.id(), "interrupted");
        } finally {
            try {
                Files.deleteIfExists(secretFile);
            } catch (IOException ignored) {
                // A leftover random file in korTTY's own folder does no harm.
            }
        }
    }

    private record ProbeResult(int exitCode, String output) {
    }

    private static ProbeResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        byte[] out;
        try (InputStream in = process.getInputStream()) {
            out = in.readNBytes(64 * 1024);
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new ProbeResult(-1, "");
        }
        return new ProbeResult(process.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    /**
     * The paths every sandboxed session is kept away from: korTTY's configuration folder (connections,
     * credentials, {@code master.key}, logs, journals), the SSH and GnuPG keys and, on macOS, the keychains.
     */
    public static List<Path> sensitivePaths(Path configDirectory) {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> paths = new ArrayList<>();
        paths.add(configDirectory);
        paths.add(home.resolve(".ssh"));
        paths.add(home.resolve(".gnupg"));
        paths.add(home.resolve("Library").resolve("Keychains"));
        return paths;
    }

    /**
     * A new, empty, owner-only folder for one sandboxed session to write to: its shell history, temporary
     * files. Deleted with the session by {@link #deleteSessionDirectory}.
     */
    public static Path createSessionDirectory() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try {
            return Files.createTempDirectory(tmp, "kortty-session-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory(tmp, "kortty-session-");
        }
    }

    /** Deletes {@code directory} and everything in it; quietly, since it lives in the temp folder anyway. */
    public static void deleteSessionDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: the temp folder is cleaned by the system as well.
                }
            });
        } catch (IOException e) {
            logger.debug("Could not delete session folder {}: {}", directory, e.getMessage());
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
