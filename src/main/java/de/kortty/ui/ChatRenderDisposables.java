package de.kortty.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks dispose callbacks for the WebView/Monaco nodes a chat tab renders, so a message
 * rebuild (font zoom) or tab close releases their native WebKit memory instead of orphaning
 * the engines. Also hands out epoch tokens that asynchronous render callbacks (PlantUML
 * subprocess, mermaid/MathJax poll chain) check before attaching a late WebView to a message
 * list that has since been rebuilt or closed.
 *
 * <p>Not thread-safe by design: all callers run on the JavaFX application thread, matching the
 * WebView requirement that engine loads/unloads happen there.</p>
 */
final class ChatRenderDisposables {

    private final List<Runnable> disposables = new ArrayList<>();
    private int epoch;
    private boolean closed;

    /**
     * Registers a dispose callback for a node rendered under the current epoch. After
     * {@link #close()}, late registrations are disposed immediately instead of leaking.
     */
    void register(Runnable disposable) {
        if (disposable == null) {
            return;
        }
        if (closed) {
            runQuietly(disposable);
            return;
        }
        disposables.add(disposable);
    }

    /** Token identifying the current render generation; pair with {@link #isLive(int)}. */
    int epoch() {
        return epoch;
    }

    /** Whether a callback holding the given token may still attach/render content. */
    boolean isLive(int token) {
        return !closed && token == epoch;
    }

    /** Disposes everything rendered so far and invalidates outstanding epoch tokens (rebuild). */
    void disposeAll() {
        epoch++;
        List<Runnable> pending = new ArrayList<>(disposables);
        disposables.clear();
        for (Runnable disposable : pending) {
            runQuietly(disposable);
        }
    }

    /** Disposes everything and refuses future registrations (tab closed). Idempotent. */
    void close() {
        closed = true;
        disposeAll();
    }

    boolean isClosed() {
        return closed;
    }

    int size() {
        return disposables.size();
    }

    private static void runQuietly(Runnable disposable) {
        try {
            disposable.run();
        } catch (RuntimeException ignored) {
            // One failing dispose (e.g. an engine already torn down) must not keep the
            // remaining WebViews alive.
        }
    }
}
