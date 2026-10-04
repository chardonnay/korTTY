package de.kortty.ui;

import de.kortty.core.SessionWorkingDirectory;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The working directory each local-shell pane of a tab is saved with in the session snapshot.
 *
 * <p>The session snapshot is captured on the JavaFX thread, which must never wait for the operating
 * system: the shell's live directory is read with {@code lsof} on macOS. So the capture takes the
 * connector's cached directory ({@code getCurrentWorkingDirectory()}, never blocking) and remembers it.
 * When there is none yet, or a submitted {@code cd} hid it, the capture saves the last directory it
 * knew and asks for a live read on a background {@link Scheduler}; a read that finds a new directory
 * tells {@code onChanged}, so the snapshot is written again with it. After a {@code cd} the read waits
 * {@value #CHANGE_SETTLE_MILLIS} ms, so the shell has run the command before it is asked.
 *
 * <p>At most one read per pane is pending at a time. Free of JavaFX, so it is unit-tested with a
 * scheduler that runs on demand.
 *
 * @param <P> the pane
 */
final class PaneWorkingDirectoryTracker<P> {

    /** How long after a submitted {@code cd} the live directory is read. */
    static final long CHANGE_SETTLE_MILLIS = 1500;

    /** Runs a task on a background thread after a delay. */
    @FunctionalInterface
    interface Scheduler {
        void schedule(Runnable task, long delayMillis);
    }

    private final Scheduler scheduler;
    private final Runnable onChanged;
    private final Map<P, String> known = new ConcurrentHashMap<>();
    private final Set<P> pendingReads = ConcurrentHashMap.newKeySet();

    /**
     * @param scheduler runs the live reads, off the JavaFX thread
     * @param onChanged told, on the scheduler's thread, when a live read found a directory other than
     *     the one remembered for the pane
     */
    PaneWorkingDirectoryTracker(Scheduler scheduler, Runnable onChanged) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    /**
     * The directory to save for {@code pane} now; never blocks.
     *
     * @param cached   the shell's trusted cached directory, or null when it has none or a {@code cd}
     *                 is unresolved
     * @param liveRead reads the shell's directory from the operating system (blocking), or null
     * @return the cached directory, else the last one known for the pane, else null
     */
    @Nullable String snapshotDirectory(P pane, @Nullable String cached, Supplier<String> liveRead) {
        String current = SessionWorkingDirectory.forSnapshot(cached);
        if (current != null) {
            known.put(pane, current);
            return current;
        }
        requestRead(pane, liveRead, 0);
        return known.get(pane);
    }

    /** A submitted line may have changed {@code pane}'s directory: read it once the shell ran it. */
    void directoryMayHaveChanged(P pane, Supplier<String> liveRead) {
        requestRead(pane, liveRead, CHANGE_SETTLE_MILLIS);
    }

    /** The directory last known for {@code pane}, or null. */
    @Nullable String lastKnown(P pane) {
        return known.get(pane);
    }

    /** Forgets {@code pane}: it closed, or a new shell started in it. */
    void forget(P pane) {
        known.remove(pane);
    }

    /** Forgets every pane: the tab closed. */
    void clear() {
        known.clear();
    }

    private void requestRead(P pane, Supplier<String> liveRead, long delayMillis) {
        Objects.requireNonNull(liveRead, "liveRead");
        if (!pendingReads.add(pane)) {
            return;
        }
        try {
            scheduler.schedule(() -> readNow(pane, liveRead), delayMillis);
        } catch (RuntimeException e) {
            // A scheduler that shut down reads nothing; the cached directory still counts.
            pendingReads.remove(pane);
        }
    }

    private void readNow(P pane, Supplier<String> liveRead) {
        // Off the list first: a cd submitted while this read runs asks for another one.
        pendingReads.remove(pane);
        String live;
        try {
            live = SessionWorkingDirectory.forSnapshot(liveRead.get());
        } catch (RuntimeException e) {
            return;
        }
        if (live == null) {
            // No live read here (Windows, Flatpak) or the shell is gone: keep what is known.
            return;
        }
        String previous = known.put(pane, live);
        if (!live.equals(previous)) {
            onChanged.run();
        }
    }
}
