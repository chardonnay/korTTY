package de.kortty.rag;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Recursive WatchService bridge with one debounced callback per AUTOMATIC source. */
public final class RagSourceWatchService implements AutoCloseable {
    public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(3);
    public static final int DEFAULT_MAX_WATCH_DIRECTORIES = 8_192;

    private final WatchService watcher;
    private final ScheduledExecutorService scheduler;
    private final Consumer<RagSource> onChanged;
    private final BiConsumer<RagSource, String> onMonitoringLimited;
    private final RagSourceFormatRegistry formats = new RagSourceFormatRegistry();
    private final long debounceMillis;
    private final int maxWatchDirectories;
    private final Map<String, RagSource> sources = new HashMap<>();
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final Map<Path, WatchKey> keysByDirectory = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new HashMap<>();
    private final Set<String> limitedSources = new HashSet<>();
    private final Thread watchThread;
    private volatile boolean closed;

    public RagSourceWatchService(Consumer<RagSource> onChanged) throws IOException {
        this(DEFAULT_DEBOUNCE, DEFAULT_MAX_WATCH_DIRECTORIES, onChanged, null);
    }

    public RagSourceWatchService(
        Consumer<RagSource> onChanged,
        BiConsumer<RagSource, String> onMonitoringLimited
    ) throws IOException {
        this(DEFAULT_DEBOUNCE, DEFAULT_MAX_WATCH_DIRECTORIES, onChanged, onMonitoringLimited);
    }

    public RagSourceWatchService(Duration debounce, Consumer<RagSource> onChanged) throws IOException {
        this(debounce, DEFAULT_MAX_WATCH_DIRECTORIES, onChanged, null);
    }

    RagSourceWatchService(
        Duration debounce,
        int maxWatchDirectories,
        Consumer<RagSource> onChanged
    ) throws IOException {
        this(debounce, maxWatchDirectories, onChanged, null);
    }

    private RagSourceWatchService(
        Duration debounce,
        int maxWatchDirectories,
        Consumer<RagSource> onChanged,
        BiConsumer<RagSource, String> onMonitoringLimited
    ) throws IOException {
        if (debounce == null || debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
        if (maxWatchDirectories < 1) {
            throw new IllegalArgumentException("maxWatchDirectories must be positive");
        }
        this.watcher = FileSystems.getDefault().newWatchService();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-rag-watch-debounce");
            thread.setDaemon(true);
            return thread;
        });
        this.onChanged = onChanged != null ? onChanged : ignored -> { };
        this.onMonitoringLimited = onMonitoringLimited != null
            ? onMonitoringLimited : (ignored, message) -> { };
        this.debounceMillis = debounce.toMillis();
        this.maxWatchDirectories = maxWatchDirectories;
        this.watchThread = new Thread(this::watchLoop, "kortty-rag-watch");
        this.watchThread.setDaemon(true);
        this.watchThread.start();
    }

    public synchronized void watch(RagSource source) throws IOException {
        unwatch(source.id());
        if (!source.enabled() || source.syncMode() != RagSyncMode.AUTOMATIC) {
            return;
        }
        sources.put(source.id(), source);
        Path root = source.type() == RagSourceType.FILE ? source.path().getParent() : source.path();
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (source.type() == RagSourceType.FILE) {
            register(root);
        } else {
            registerRecursively(source, root);
        }
    }

    public synchronized void unwatch(String sourceId) {
        sources.remove(sourceId);
        ScheduledFuture<?> future = pending.remove(sourceId);
        if (future != null) {
            future.cancel(false);
        }
        limitedSources.remove(sourceId);
        // Keys are intentionally shared and kept until close; callbacks filter against current sources.
    }

    public synchronized Set<String> watchedSourceIds() {
        return Set.copyOf(sources.keySet());
    }

    private void registerRecursively(RagSource source, Path root) throws IOException {
        RagSourceScanner.PathFilter sourceFilter = new RagSourceScanner.PathFilter(
            source.includePatterns(), source.excludePatterns());
        RagSourceScanner.PathFilter gitIgnoreFilter = new RagSourceScanner.PathFilter(
            List.of(), RagSourceScanner.loadGitIgnorePatterns(source));
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {

                if (closed) {
                    return FileVisitResult.TERMINATE;
                }
                if (!directory.equals(source.path())) {
                    if (!source.recursive()
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(directory)
                        || isExcludedDirectory(directory)
                        || isHiddenDirectory(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = source.path().relativize(directory);
                    if (!sourceFilter.acceptDirectory(relative)
                        || !gitIgnoreFilter.acceptDirectory(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                register(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (keysByDirectory.containsKey(normalized)) {
            return;
        }
        if (keysByDirectory.size() >= maxWatchDirectories) {
            throw new IOException("Automatic monitoring reached the safe limit of "
                + maxWatchDirectories + " directories");
        }
        WatchKey key = normalized.register(watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.OVERFLOW);
        keysByDirectory.put(normalized, key);
        directories.put(key, normalized);
    }

    private void watchLoop() {
        while (!closed) {
            try {
                WatchKey key = watcher.take();
                Path directory;
                synchronized (this) {
                    directory = directories.get(key);
                }
                if (directory != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = event.kind() == StandardWatchEventKinds.OVERFLOW
                            ? directory : directory.resolve((Path) event.context()).toAbsolutePath().normalize();
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(changed) && !isExcludedDirectory(changed)
                            && !isHiddenDirectory(changed)) {
                            registerNewDirectoryForCoveredSources(changed);
                        }
                        scheduleAffected(changed, event.kind() == StandardWatchEventKinds.OVERFLOW);
                    }
                }
                if (!key.reset()) {
                    synchronized (this) {
                        Path removed = directories.remove(key);
                        if (removed != null) {
                            keysByDirectory.remove(removed);
                        }
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // A startup reconciliation scan catches events a platform watcher could not register.
            }
        }
    }

    private synchronized void registerNewDirectoryForCoveredSources(Path directory) {
        for (RagSource source : sources.values()) {
            if (source.type() != RagSourceType.DIRECTORY || !source.recursive()
                || !directory.startsWith(source.path()) || !isRelevantChange(source, directory)) {
                continue;
            }
            try {
                registerRecursively(source, directory);
            } catch (IOException | SecurityException error) {
                notifyMonitoringLimited(source, error);
            }
        }
    }

    private void notifyMonitoringLimited(RagSource source, Exception error) {
        if (!limitedSources.add(source.id())) {
            return;
        }
        String detail = error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName() : error.getMessage();
        try {
            onMonitoringLimited.accept(source,
                "Automatic file watching is limited; manual refresh remains available (" + detail + ")");
        } catch (RuntimeException ignored) {
            // Monitoring diagnostics must not terminate the watch thread.
        }
    }

    private synchronized void scheduleAffected(Path changed, boolean overflow) {
        for (RagSource source : sources.values()) {
            boolean affected = source.type() == RagSourceType.FILE
                ? changed.equals(source.path())
                    || (overflow && changed.equals(source.path().getParent()))
                : changed.startsWith(source.path());
            if (!affected || !isRelevantChange(source, changed)) {
                continue;
            }
            ScheduledFuture<?> previous = pending.remove(source.id());
            if (previous != null) {
                previous.cancel(false);
            }
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                synchronized (RagSourceWatchService.this) {
                    pending.remove(source.id());
                    if (!sources.containsKey(source.id())) {
                        return;
                    }
                }
                onChanged.accept(source);
            }, debounceMillis, TimeUnit.MILLISECONDS);
            pending.put(source.id(), future);
        }
    }

    private boolean isRelevantChange(RagSource source, Path changed) {
        if (source.type() == RagSourceType.FILE) {
            return true;
        }
        Path relative;
        try {
            relative = source.path().relativize(changed);
        } catch (IllegalArgumentException error) {
            return false;
        }
        for (Path segment : relative) {
            String value = segment.toString();
            if (value.startsWith(".") || RagSourceScanner.STANDARD_EXCLUDED_DIRECTORIES
                .contains(value.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        RagSourceScanner.PathFilter filter = new RagSourceScanner.PathFilter(
            source.includePatterns(), source.excludePatterns());
        if (Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
            return filter.acceptDirectory(relative);
        }
        return formats.isAllowed(changed) && filter.accept(relative);
    }

    private static boolean isExcludedDirectory(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String value = name.toString();
        return value.startsWith(".")
            || RagSourceScanner.STANDARD_EXCLUDED_DIRECTORIES.contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isHiddenDirectory(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException | SecurityException error) {
            // A directory whose visibility cannot be inspected is not safe to descend into.
            return true;
        }
    }

    synchronized boolean isDirectoryWatched(Path directory) {
        return keysByDirectory.containsKey(directory.toAbsolutePath().normalize());
    }

    synchronized int watchedDirectoryCount() {
        return keysByDirectory.size();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        watchThread.interrupt();
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        scheduler.shutdownNow();
        watcher.close();
        sources.clear();
        limitedSources.clear();
        directories.clear();
        keysByDirectory.clear();
    }
}
