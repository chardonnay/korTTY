package de.kortty.codingagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads the process title a script host gave itself. A Node.js CLI that sets {@code process.title}
 * (MiniMax Code renames itself to {@code minimax-code}) overwrites its own argument area, after which
 * {@link ProcessHandle.Info} on macOS reports no arguments and no command line at all — only the
 * {@code node} binary. The title is what {@code ps} shows instead, so this asks {@code ps} (macOS and
 * other Unixes) or reads {@code /proc/<pid>/cmdline} (Linux). Windows keeps the arguments intact and
 * is never asked.
 *
 * <p>The answer is cached per PID and start instant, because the coding-agent monitor polls every
 * pane repeatedly and a {@code ps} fork per poll would be wasteful; a recycled PID has a different
 * start instant and is read afresh. Never throws.
 */
final class ProcessTitles {

    private static final Logger logger = LoggerFactory.getLogger(ProcessTitles.class);

    private static final Path PS = Path.of("/bin/ps");
    private static final long PS_TIMEOUT_MILLIS = 1_000;
    /** Upper bound for the cache; past it the cache is simply cleared (titles are cheap to re-read). */
    private static final int MAX_CACHED = 256;

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private record Key(long pid, Instant startedAt) {
    }

    private static final Map<Key, Optional<String>> CACHE = new ConcurrentHashMap<>();

    private ProcessTitles() {
    }

    /** The first word of the OS-reported title of {@code handle}, or empty when unknown; never throws. */
    static Optional<String> titleOf(ProcessHandle handle) {
        if (handle == null || WINDOWS) {
            return Optional.empty();
        }
        try {
            Key key = new Key(handle.pid(), handle.info().startInstant().orElse(null));
            Optional<String> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            Optional<String> title = read(handle.pid());
            if (CACHE.size() >= MAX_CACHED) {
                CACHE.clear();
            }
            CACHE.put(key, title);
            return title;
        } catch (RuntimeException e) {
            logger.debug("Cannot read the title of process {}: {}", handle.pid(), e.toString());
            return Optional.empty();
        }
    }

    private static Optional<String> read(long pid) {
        Path cmdline = Path.of("/proc", Long.toString(pid), "cmdline");
        if (Files.isReadable(cmdline)) {
            try {
                return firstWord(new String(Files.readAllBytes(cmdline), StandardCharsets.UTF_8).replace('\0', ' '));
            } catch (IOException e) {
                logger.debug("Cannot read {}: {}", cmdline, e.toString());
                return Optional.empty();
            }
        }
        if (!Files.isExecutable(PS)) {
            return Optional.empty();
        }
        Process ps = null;
        try {
            ps = new ProcessBuilder(PS.toString(), "-o", "command=", "-p", Long.toString(pid))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            // One short line: it fits the pipe buffer, so waiting before reading cannot deadlock.
            if (!ps.waitFor(PS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
            try (InputStream in = ps.getInputStream()) {
                return firstWord(new String(in.readNBytes(4096), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            logger.debug("Cannot run ps for pid {}: {}", pid, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (ps != null && ps.isAlive()) {
                ps.destroyForcibly();
            }
        }
    }

    private static Optional<String> firstWord(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        int space = 0;
        while (space < trimmed.length() && !Character.isWhitespace(trimmed.charAt(space))) {
            space++;
        }
        return Optional.of(trimmed.substring(0, space));
    }
}
