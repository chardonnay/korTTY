package de.kortty.ui;

import de.kortty.core.SessionSnapshotStore;
import de.kortty.model.Project;
import de.kortty.model.SessionSnapshot;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps the session snapshot ({@link SessionSnapshotStore}) up to date while korTTY runs, so File ›
 * Restore Previous Session can bring the windows and tabs back after a restart or a crash.
 *
 * <ul>
 *   <li><b>When.</b> Every change to the windows and tabs marks the session as changed
 *       ({@link #markDirty}): a tab opened, closed, moved or renamed, a split, a window opened,
 *       closed, moved or resized, the dashboard, the restore bar and the Recently Closed list. The
 *       snapshot is written {@value #DEBOUNCE_MILLIS} ms after the last change, and while changes
 *       keep coming at the latest {@value #PERIODIC_MILLIS} ms after the first one. No timer runs
 *       while nothing changes.</li>
 *   <li><b>How.</b> The capture runs on the JavaFX thread and takes no screen text; the file is
 *       written on a single background thread, in the order of the captures.</li>
 *   <li><b>What.</b> {@link SessionAutosaveRule} decides: nothing after the snapshot korTTY quits
 *       with, nothing while a restore is opening tabs, and never an empty capture over a snapshot
 *       with tabs.</li>
 *   <li><b>Quitting.</b> {@link #saveAndSeal} writes the snapshot korTTY quits with, marked as a
 *       clean exit, waits up to {@value #QUIT_SAVE_TIMEOUT_MILLIS} ms for it and seals: Quit calls it
 *       after every window agreed and before the first window closes, and closing the last window
 *       on Windows and Linux before its tabs close. {@link #sealOnShutdown} covers every other way
 *       out at the end of the shutdown.</li>
 * </ul>
 *
 * <p>Use it on the JavaFX thread, except {@link #sealOnShutdown}. Toolkit-free itself: the timers
 * come in through {@link Timers}, so the tests drive it without a toolkit.
 */
public final class SessionAutosaveCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SessionAutosaveCoordinator.class);

    /** Quiet time after the last change before the snapshot is written. */
    static final long DEBOUNCE_MILLIS = 2_000;
    /** Longest time a change waits while further changes keep the quiet time from ending. */
    static final long PERIODIC_MILLIS = 30_000;
    /** How long quitting waits for the snapshot it quits with. */
    static final long QUIT_SAVE_TIMEOUT_MILLIS = 2_000;

    /** What one capture of the open session holds. */
    record Capture(Project project, List<SessionSnapshot.ClosedEntry> recentlyClosed) {
        Capture {
            Objects.requireNonNull(project, "project");
            recentlyClosed = List.copyOf(recentlyClosed);
        }
    }

    /** The coordinator's timer: a quiet time that calls back {@link #saveIfDirty} when it ends. */
    interface Timers {
        /** No timer: the tests call {@link #saveIfDirty} themselves. */
        Timers NONE = new Timers() {
            @Override
            public void restartDebounce() {
            }

            @Override
            public void runSoon() {
            }

            @Override
            public void stop() {
            }
        };

        /** Starts the quiet time anew. */
        void restartDebounce();

        /** Calls back right after the current event, without waiting for the quiet time. */
        void runSoon();

        /** Stops the timer for good. */
        void stop();
    }

    /** What the running korTTY tells the coordinator. */
    record Environment(Supplier<Capture> capture, BooleanSupplier writesAllowed, BooleanSupplier onFxThread,
                       LongSupplier clock, String appVersion) {
        Environment {
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(writesAllowed, "writesAllowed");
            Objects.requireNonNull(onFxThread, "onFxThread");
            Objects.requireNonNull(clock, "clock");
        }
    }

    private final SessionSnapshotStore store;
    private final Environment environment;
    private final ExecutorService writer;
    private final Timers timers;

    /** Changed since the last write; FX thread. */
    private boolean dirty;
    /** When the first change since the last write came, by {@link Environment#clock()}; FX thread. */
    private long dirtySince;
    /** The last write failed: try again on the next timer, even without a change; any thread. */
    private volatile boolean retry;
    /** Open restores (project or session); FX thread. */
    private int restoring;
    /** Written the snapshot korTTY quits with: no more writes. */
    private volatile boolean sealed;
    /** File › Restore Previous Session ran in this run. */
    private boolean previousRestored;
    /** The snapshot on disk as far as this process knows: the last one it wrote, or the one it kept at startup. */
    private volatile @Nullable SessionSnapshot saved;

    /**
     * @param startup what {@link SessionSnapshotStore#startUp} found
     * @param timers  makes the timers for a callback that runs {@link #saveIfDirty}
     */
    SessionAutosaveCoordinator(SessionSnapshotStore store, SessionSnapshotStore.StartupState startup,
                               Environment environment, ExecutorService writer, Function<Runnable, Timers> timers) {
        this.store = Objects.requireNonNull(store, "store");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.saved = startup != null ? startup.keptLast() : null;
        this.timers = Objects.requireNonNull(timers.apply(this::saveIfDirty), "timers");
    }

    /** The windows or tabs changed: the snapshot is written after the quiet time. FX thread. */
    void markDirty() {
        if (sealed) {
            return;
        }
        long now = environment.clock().getAsLong();
        if (!dirty) {
            dirty = true;
            dirtySince = now;
        }
        if (now - dirtySince >= PERIODIC_MILLIS) {
            // Changes keep coming: do not let the quiet time put the save off any longer.
            timers.runSoon();
        } else {
            timers.restartDebounce();
        }
    }

    boolean isDirty() {
        return dirty;
    }

    boolean isSealed() {
        return sealed;
    }

    /** A project or session restore starts opening tabs: captures wait until {@link #endRestore}. FX thread. */
    void beginRestore() {
        restoring++;
    }

    /** The restore opened what it opens right away; a pending change is written after the quiet time. FX thread. */
    void endRestore() {
        if (restoring > 0) {
            restoring--;
        }
        if (restoring == 0 && dirty) {
            timers.restartDebounce();
        }
    }

    /** Writes the snapshot when something changed since the last write. FX thread. */
    void saveIfDirty() {
        if (!dirty && !retry) {
            return;
        }
        save(false);
    }

    /**
     * Writes the snapshot korTTY quits with, as a clean exit, waits for it at most
     * {@value #QUIT_SAVE_TIMEOUT_MILLIS} ms, and seals: nothing is written afterwards, so the windows
     * that close while korTTY quits cannot shrink it. A no-op once sealed. FX thread.
     *
     * @return whether the snapshot was written in time
     */
    boolean saveAndSeal() {
        if (sealed) {
            return false;
        }
        Future<?> write = save(true);
        sealed = true;
        dirty = false;
        timers.stop();
        return await(write, QUIT_SAVE_TIMEOUT_MILLIS);
    }

    /**
     * The last step of korTTY's shutdown, on any thread: when nothing sealed the snapshot yet (a quit
     * that did not go through Quit or the last window, for example the force quit while scheduled
     * jobs drain), writes it as {@link #saveAndSeal} does on the JavaFX thread, and elsewhere marks
     * the snapshot on disk as a clean exit. Then waits for the writes, bounded, and releases the store.
     */
    public void sealOnShutdown() {
        try {
            if (!sealed) {
                if (environment.onFxThread().getAsBoolean()) {
                    saveAndSeal();
                } else {
                    sealed = true;
                    timers.stop();
                    SessionSnapshot onDisk = saved;
                    if (onDisk != null && !onDisk.isCleanExit() && writable()) {
                        submit(onDisk.withCleanExit(true));
                    }
                }
            }
            awaitWrites(QUIT_SAVE_TIMEOUT_MILLIS);
        } finally {
            writer.shutdown();
            store.close();
        }
    }

    /** Whether File › Restore Previous Session has something to open in this run. */
    boolean canRestorePrevious() {
        return !previousRestored && store.isPreviousAvailable();
    }

    /** The previous session, cleaned; empty when there is none or it could not be read. */
    Optional<SessionSnapshot> loadPrevious() {
        return store.loadPrevious();
    }

    /** File › Restore Previous Session opened the previous session: it is not offered again in this run. */
    void markPreviousRestored() {
        previousRestored = true;
    }

    /** Waits until every write queued so far is on disk, at most {@code millis} ms. */
    boolean awaitWrites(long millis) {
        try {
            return await(writer.submit(() -> { }), millis);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // The writer was shut down: it still finishes what was queued.
            try {
                return writer.awaitTermination(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean writable() {
        return environment.writesAllowed().getAsBoolean() && store.canWrite();
    }

    /** Captures, decides and queues the write; returns the queued write or {@code null}. FX thread. */
    private @Nullable Future<?> save(boolean cleanExit) {
        if (sealed) {
            return null;
        }
        if (!writable()) {
            dirty = false;
            retry = false;
            return null;
        }
        Capture capture;
        try {
            capture = environment.capture().get();
        } catch (RuntimeException e) {
            // Saving the session is a convenience; it must never break what the user is doing.
            logger.warn("Could not capture the session", e);
            return null;
        }
        SessionSnapshot onDisk = saved;
        SessionAutosaveRule.Decision decision = SessionAutosaveRule.decide(new SessionAutosaveRule.State(
            false, true, restoring > 0,
            SessionSnapshotStore.restorableTabs(capture.project()),
            SessionSnapshotStore.restorableTabs(onDisk)));
        Project windows;
        switch (decision) {
            case DROP -> {
                dirty = false;
                return null;
            }
            case LATER -> {
                if (!cleanExit) {
                    return null;
                }
                // Quitting in the middle of a restore: keep the session on disk, marked as a clean exit.
                windows = onDisk != null ? onDisk.getProject() : capture.project();
            }
            case KEEP_SAVED_WINDOWS -> windows = onDisk != null ? onDisk.getProject() : capture.project();
            default -> windows = capture.project();
        }
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setAppVersion(environment.appVersion());
        snapshot.setSavedAtMillis(environment.clock().getAsLong());
        snapshot.setCleanExit(cleanExit);
        snapshot.setProject(windows);
        snapshot.setRecentlyClosed(new java.util.ArrayList<>(capture.recentlyClosed()));
        dirty = false;
        retry = false;
        saved = snapshot;
        return submit(snapshot);
    }

    private Future<?> submit(SessionSnapshot snapshot) {
        try {
            return writer.submit(() -> {
                try {
                    store.write(snapshot);
                } catch (IOException | RuntimeException e) {
                    retry = true;
                    logger.warn("Could not save the session snapshot: {}", e.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("The session snapshot writer has stopped");
            return CompletableFuture.completedFuture(null);
        }
    }

    private static boolean await(@Nullable Future<?> write, long millis) {
        if (write == null) {
            return false;
        }
        try {
            write.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            logger.warn("The session snapshot was not written within {} ms", millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }
}
