package de.kortty.core.remote.edit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Notices when an external editor saved one of the files being edited.
 *
 * <p>Polls each tracked file every {@link #POLL_INTERVAL} for its size, modification time and file
 * key (the inode on POSIX systems, so a save that writes a new file and renames it over the old one
 * is seen even when size and time stay the same). A change must then hold still for
 * {@link #DEBOUNCE} before the file is hashed; only a SHA-256 that differs from the last one known
 * reports a save. So a burst of writes is one save, a {@code touch} is none, and the swap and
 * backup files editors write next to the file ({@code .swp}, {@code ~}, {@code .#name}, vim's
 * {@code 4913}) are never looked at, because only the tracked file itself is.
 *
 * <p>The JDK {@code WatchService} is not used: on macOS it polls anyway, and it misses rename-saves
 * inconsistently. Listeners run on the watcher's thread and must hand longer work elsewhere.
 */
public final class RemoteEditWatcher implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RemoteEditWatcher.class);

    /** How often each tracked file is looked at. */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    /** How long a change must hold still before it counts as a save. */
    public static final Duration DEBOUNCE = Duration.ofMillis(700);

    /** Told about a save; runs on the watcher's thread. */
    @FunctionalInterface
    public interface Listener {
        void onSaved(Path file, String sha256);
    }

    private record Signature(long size, long mtimeMillis, Object fileKey) {
    }

    private static final class Tracked {
        final Listener listener;
        Signature lastSeen;
        long changedAt = -1;
        String hash;

        Tracked(Listener listener, Signature lastSeen, String hash) {
            this.listener = listener;
            this.lastSeen = lastSeen;
            this.hash = hash;
        }
    }

    private final LongSupplier clockMillis;
    private final Map<Path, Tracked> tracked = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    /** A watcher on the system clock; {@link #start()} begins the polling. */
    public RemoteEditWatcher() {
        this(System::currentTimeMillis);
    }

    /** A watcher on {@code clockMillis}; tests call {@link #poll()} themselves. */
    public RemoteEditWatcher(LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Starts polling on a daemon thread; idempotent. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-remote-edit-watch");
            thread.setDaemon(true);
            return thread;
        });
        long period = POLL_INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(this::pollQuietly, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Watches {@code file}, whose content now has the SHA-256 {@code sha256}; a save with other
     * content calls {@code listener}.
     */
    public void track(Path file, String sha256, Listener listener) {
        Objects.requireNonNull(listener, "listener");
        tracked.put(file, new Tracked(listener, signature(file), sha256));
    }

    /** Stops watching {@code file}. */
    public void untrack(Path file) {
        tracked.remove(file);
    }

    /** Whether {@code file} is watched. */
    public boolean isTracked(Path file) {
        return tracked.containsKey(file);
    }

    /** Records {@code sha256} as the known content, for example after a forced upload. */
    public void updateHash(Path file, String sha256) {
        Tracked entry = tracked.get(file);
        if (entry != null) {
            synchronized (entry) {
                entry.hash = sha256;
            }
        }
    }

    /** One pass over every tracked file. */
    public void poll() {
        long now = clockMillis.getAsLong();
        for (Map.Entry<Path, Tracked> entry : tracked.entrySet()) {
            check(entry.getKey(), entry.getValue(), now);
        }
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (RuntimeException e) {
            logger.warn("Checking the edited files failed", e);
        }
    }

    private void check(Path file, Tracked entry, long now) {
        String savedHash = null;
        synchronized (entry) {
            Signature current = signature(file);
            if (current == null) {
                // In the middle of a rename-save, or deleted: wait for the file to come back.
                return;
            }
            if (!current.equals(entry.lastSeen)) {
                entry.lastSeen = current;
                entry.changedAt = now;
                return;
            }
            if (entry.changedAt < 0 || now - entry.changedAt < DEBOUNCE.toMillis()) {
                return;
            }
            entry.changedAt = -1;
            String hash;
            try {
                hash = RemoteEditHashes.sha256(file);
            } catch (IOException e) {
                logger.debug("Could not read the edited file {}: {}", file, e.toString());
                return;
            }
            if (!hash.equals(entry.hash)) {
                entry.hash = hash;
                savedHash = hash;
            }
        }
        if (savedHash != null && tracked.get(file) == entry) {
            entry.listener.onSaved(file, savedHash);
        }
    }

    private static Signature signature(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return null;
            }
            return new Signature(attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey());
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            logger.debug("Could not look at the edited file {}: {}", file, e.toString());
            return null;
        }
    }

    /** Stops polling and forgets every file. */
    @Override
    public synchronized void close() {
        tracked.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
