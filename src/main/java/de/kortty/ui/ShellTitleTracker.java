package de.kortty.ui;

import de.kortty.core.DisplayTextSanitizer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The title the programs in a tab's panes set with OSC 0 or OSC 2 (a shell typically puts
 * {@code user@host: directory} there), reduced to the one title the tab shows: the focused pane's.
 *
 * <p>The text comes from the remote side, so it is cleaned before anything keeps it: control and
 * bidi characters removed, trimmed and capped at {@link #MAX_LENGTH} characters (see
 * {@link #normalize}). Titles arrive on each pane's emulator thread; they are stored there and
 * handed to the tab on the FX thread through the executor given to the constructor
 * ({@code Platform::runLater} in the app). However many titles arrive, at most one hand-over is
 * queued at a time, so a program that rewrites its title in a loop (a spinner, for example) cannot
 * flood the FX thread. The listener is told only when the shown title changes.
 *
 * <p>While the setting is off ({@code enabled} false) titles are still stored, so switching it on
 * shows the current one at once, but nothing is queued and the shown title is {@code null}.
 *
 * <p>FX-free, so it can be tested without a toolkit; {@code P} is the pane type.
 */
final class ShellTitleTracker<P> {

    /** Longest title a program can give a tab, in characters. */
    static final int MAX_LENGTH = 80;

    /**
     * The window title a SithTermFX pane starts with. A program that saves the title and restores
     * it on exit (XTWINOPS 22/23, as vim does) hands it back when it never had another one, so it
     * means "no title", as in the coding-agent screen snapshot.
     */
    static final String PANE_DEFAULT_TITLE = "Terminal";

    /** How much of a title is looked at before cleaning, so a huge one costs no more than a long one. */
    private static final int RAW_SCAN_LIMIT = MAX_LENGTH * 8;

    private final Consumer<Runnable> fxExecutor;
    private final Supplier<P> focusedPane;
    private final BooleanSupplier enabled;
    private final Map<P, String> titles = new ConcurrentHashMap<>();
    private final AtomicBoolean handOverQueued = new AtomicBoolean();
    private volatile Consumer<String> listener;
    private volatile String shown;
    private volatile boolean disposed;

    /**
     * @param fxExecutor  runs a task on the FX thread later ({@code Platform::runLater})
     * @param focusedPane the pane whose title the tab shows; read on the FX thread
     * @param enabled     whether tabs show the title programs set (Window settings)
     */
    ShellTitleTracker(Consumer<Runnable> fxExecutor, Supplier<P> focusedPane, BooleanSupplier enabled) {
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "fxExecutor");
        this.focusedPane = Objects.requireNonNull(focusedPane, "focusedPane");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /**
     * A title as a tab may show it: controls and bidi controls removed, trimmed, capped at
     * {@link #MAX_LENGTH} characters. {@code null} when nothing visible is left, and for
     * {@link #PANE_DEFAULT_TITLE}.
     */
    static String normalize(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        String scanned = rawTitle;
        if (scanned.length() > RAW_SCAN_LIMIT) {
            int end = RAW_SCAN_LIMIT;
            if (Character.isHighSurrogate(scanned.charAt(end - 1))) {
                end--;
            }
            scanned = scanned.substring(0, end);
        }
        String clean = DisplayTextSanitizer.sanitize(scanned, MAX_LENGTH);
        return clean.isEmpty() || PANE_DEFAULT_TITLE.equals(clean) ? null : clean;
    }

    /** Sets who is told, on the FX thread, when the shown title changes ({@code null} for none). */
    void setListener(Consumer<String> listener) {
        this.listener = listener;
    }

    /**
     * A program in {@code pane} set its title. Any thread (the pane's emulator thread in the app).
     */
    void titleChanged(P pane, String rawTitle) {
        if (pane == null || disposed) {
            return;
        }
        String title = normalize(rawTitle);
        if (title == null) {
            titles.remove(pane);
        } else {
            titles.put(pane, title);
        }
        if (enabled.getAsBoolean()) {
            queueHandOver();
        }
    }

    /** {@code pane} starts a new session (a reconnect): its old title no longer applies. FX thread. */
    void paneReset(P pane) {
        if (pane != null && titles.remove(pane) != null) {
            publish();
        }
    }

    /** {@code pane} closed: its title goes, and the tab shows the pane that has the focus now. FX thread. */
    void paneClosed(P pane) {
        if (pane != null) {
            titles.remove(pane);
        }
        publish();
    }

    /**
     * Tells the listener the title the tab should show now, if it differs from the last one: after
     * the focus moved to another pane, or the setting changed. FX thread.
     */
    void publish() {
        if (disposed) {
            return;
        }
        String title = currentTitle();
        if (Objects.equals(title, shown)) {
            return;
        }
        shown = title;
        Consumer<String> target = listener;
        if (target != null) {
            target.accept(title);
        }
    }

    /** The title last handed to the listener, {@code null} for none. */
    String shownTitle() {
        return shown;
    }

    /** Stops for good: the tab closed. Titles that still arrive are ignored. */
    void dispose() {
        disposed = true;
        listener = null;
        titles.clear();
    }

    private String currentTitle() {
        if (!enabled.getAsBoolean()) {
            return null;
        }
        P pane = focusedPane.get();
        return pane != null ? titles.get(pane) : null;
    }

    private void queueHandOver() {
        if (handOverQueued.compareAndSet(false, true)) {
            fxExecutor.accept(() -> {
                // Cleared before reading, so a title that arrives while this runs queues the next hand-over.
                handOverQueued.set(false);
                publish();
            });
        }
    }
}
