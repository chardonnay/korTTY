package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.core.ScrollbackSnapshotCodec;
import de.kortty.core.SessionScrollbackStore;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Keeps the saved output of every terminal pane for Settings › Window › Session Restore › "Also
 * restore the output", off by default.
 *
 * <ul>
 *   <li><b>When.</b> Every {@value #INTERVAL_SECONDS} s, and once more when korTTY quits, the panes
 *       that received output since their file was written are written again
 *       ({@link SessionScrollbackRecorder}); a pane without new output is not touched.</li>
 *   <li><b>How.</b> The panes are listed on the JavaFX thread; their rows are read
 *       ({@link PaneTailReader}, bounded, under the pane's buffer lock), encrypted and written on one
 *       background thread ({@link SessionScrollbackStore}).</li>
 *   <li><b>Only with the key.</b> Without the master-password key — no master password, or the vault
 *       is locked — nothing is written, and a restore shows nothing.</li>
 *   <li><b>Cleaned up.</b> Files no saved session names any more are deleted: at startup, after each
 *       round, and every file when the setting is turned off.</li>
 * </ul>
 */
final class SessionScrollbackCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SessionScrollbackCoordinator.class);

    /** How often the changed panes are written. */
    static final long INTERVAL_SECONDS = 30;

    /** How long quitting waits for the last round. */
    static final long EXIT_WAIT_MILLIS = 2_000;

    /** What the running korTTY tells the coordinator; every supplier runs on the JavaFX thread. */
    record Environment(BooleanSupplier enabled, IntSupplier linesPerPane, Supplier<SecretKey> key,
                       BooleanSupplier writesAllowed, Supplier<List<TerminalView>> openViews,
                       Supplier<Set<String>> savedRefs, Runnable sessionChanged, Consumer<Runnable> fxPost) {
        Environment {
            Objects.requireNonNull(enabled, "enabled");
            Objects.requireNonNull(linesPerPane, "linesPerPane");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(writesAllowed, "writesAllowed");
            Objects.requireNonNull(openViews, "openViews");
            Objects.requireNonNull(savedRefs, "savedRefs");
            Objects.requireNonNull(sessionChanged, "sessionChanged");
            Objects.requireNonNull(fxPost, "fxPost");
        }
    }

    private final SessionScrollbackStore store;
    private final Environment environment;
    private final SessionScrollbackRecorder<SithTermFxWidget> recorder = new SessionScrollbackRecorder<>();
    private final ScheduledExecutorService worker;
    /** The files the sessions on disk at startup name: kept until the next start. */
    private final Set<String> startupRefs;
    /** The purges the recorder has seen; FX thread. */
    private long seenPurges = SessionScrollbackStore.purgeCount();
    private volatile boolean stopped;

    SessionScrollbackCoordinator(SessionScrollbackStore store, Environment environment, Set<String> startupRefs) {
        this.store = Objects.requireNonNull(store, "store");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.startupRefs = Set.copyOf(startupRefs);
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-session-scrollback");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the rounds and cleans up what the last run left: with the setting off every file goes,
     * otherwise every file the saved sessions no longer name. FX thread.
     */
    void start() {
        boolean enabled = environment.enabled().getAsBoolean();
        submit(() -> {
            if (!enabled) {
                store.purge();
            } else {
                store.retainOnly(startupRefs);
            }
        });
        worker.scheduleWithFixedDelay(() -> environment.fxPost().accept(this::writeChangedPanes),
            INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Whether panes' output is saved now: the setting is on and the vault is open. FX thread. */
    boolean isActive() {
        return !stopped && environment.enabled().getAsBoolean() && environment.key().get() != null;
    }

    /** The file name the session snapshot names for {@code pane}, or null. FX thread. */
    @Nullable String refOf(TerminalView view, @Nullable SithTermFxWidget pane) {
        if (pane == null || !isActive()) {
            return null;
        }
        return recorder.refOf(pane, view.lastPaneOutputNanos(pane));
    }

    /**
     * Reads a file of saved output with the current key, for a restore; empty without the key or the
     * setting. The returned loader runs off the JavaFX thread. FX thread.
     */
    java.util.function.@Nullable Function<String, Optional<ScrollbackSnapshotCodec.Decoded>> loader() {
        if (!environment.enabled().getAsBoolean()) {
            return null;
        }
        SecretKey key = environment.key().get();
        if (key == null) {
            return null;
        }
        return ref -> store.read(ref, key);
    }

    /** The setting changed: turned off, every file is deleted and no pane is kept. FX thread. */
    void settingChanged(boolean enabled) {
        if (!enabled) {
            recorder.clear();
            submit(store::purge);
        } else {
            environment.fxPost().accept(this::writeChangedPanes);
        }
    }

    /** Writes the changed panes one last time and waits for it, bounded. FX thread. */
    void flushForExit() {
        Future<?> round = writeChangedPanes();
        stopped = true;
        if (round != null) {
            try {
                round.get(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warn("The saved terminal output was not written before quitting: {}", e.toString());
            }
        }
    }

    /** One round: queue the changed panes' files and the clean-up. FX thread. */
    @Nullable Future<?> writeChangedPanes() {
        if (stopped || !environment.enabled().getAsBoolean() || !environment.writesAllowed().getAsBoolean()) {
            return null;
        }
        SecretKey key = environment.key().get();
        if (key == null) {
            // The vault is locked (or there is no master password): nothing is written unencrypted.
            return null;
        }
        long purges = SessionScrollbackStore.purgeCount();
        if (purges != seenPurges) {
            // The master password changed or a backup was restored: every file is gone.
            seenPurges = purges;
            recorder.filesPurged();
        }
        int lines = ScrollbackSnapshotCodec.clampLines(environment.linesPerPane().getAsInt());
        Map<SithTermFxWidget, TerminalView> owners = new IdentityHashMap<>();
        for (TerminalView view : environment.openViews().get()) {
            for (SithTermFxWidget pane : view.getOpenPanes()) {
                owners.put(pane, view);
            }
        }
        boolean named = false;
        for (Map.Entry<SithTermFxWidget, TerminalView> open : owners.entrySet()) {
            if (!recorder.hasRef(open.getKey())
                && recorder.refOf(open.getKey(), open.getValue().lastPaneOutputNanos(open.getKey())) != null) {
                named = true;
            }
        }
        if (named) {
            // A pane got its first output since the last capture: the session snapshot names its file.
            environment.sessionChanged().run();
        }
        List<SessionScrollbackRecorder.Job<SithTermFxWidget>> jobs = recorder.changedPanes(owners.keySet(),
            pane -> owners.get(pane).lastPaneOutputNanos(pane),
            pane -> () -> owners.get(pane).readPaneTail(pane, lines));
        Set<String> keep = new HashSet<>(startupRefs);
        keep.addAll(environment.savedRefs().get());
        return submit(() -> {
            long now = System.currentTimeMillis();
            for (SessionScrollbackRecorder.Job<SithTermFxWidget> job : jobs) {
                try {
                    List<String> rows = job.rows().get();
                    if (rows == null) {
                        // A full-screen program is showing: the file keeps the output before it.
                        continue;
                    }
                    if (store.write(job.ref(), rows, now, lines, key)) {
                        recorder.written(job);
                    }
                } catch (Exception e) {
                    logger.warn("Could not save a pane's output: {}", e.toString());
                }
            }
            keep.addAll(recorder.refs());
            store.retainOnly(keep);
        });
    }

    private @Nullable Future<?> submit(Runnable task) {
        try {
            return worker.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
