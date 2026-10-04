package de.kortty.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Actions that wait for an event that happens once, such as a tab's first successful connect, and
 * run exactly once when it does. A reconnect fires the event again and runs nothing: an action added
 * here is never repeated. An action added after the event runs right away through the late runner
 * (on the JavaFX thread, the next pulse), so it cannot miss the event by arriving late.
 *
 * <p>Not thread-safe: confined to one thread, the JavaFX thread in korTTY. Free of JavaFX itself, so
 * it is unit-tested with a plain late runner.
 */
final class OneShotRunnables {

    private static final Logger logger = LoggerFactory.getLogger(OneShotRunnables.class);

    private final Consumer<Runnable> lateRunner;
    private final List<Runnable> pending = new ArrayList<>();
    private boolean fired;

    /** @param lateRunner runs an action added after the event, such as {@code Platform::runLater} */
    OneShotRunnables(Consumer<Runnable> lateRunner) {
        this.lateRunner = Objects.requireNonNull(lateRunner, "lateRunner");
    }

    /** Runs {@code action} when the event happens, or through the late runner if it has happened. */
    void add(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (fired) {
            lateRunner.accept(action);
        } else {
            pending.add(action);
        }
    }

    /**
     * The event happened: runs every waiting action once, in the order they were added. A failing
     * action is logged and does not keep the others from running.
     *
     * @return true the first time, false when the event had happened before and nothing ran
     */
    boolean fire() {
        if (fired) {
            return false;
        }
        fired = true;
        List<Runnable> actions = new ArrayList<>(pending);
        pending.clear();
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                logger.warn("An action waiting for the first connect failed: {}", e.toString());
            }
        }
        return true;
    }

    /** Drops the waiting actions without running them, as closing the tab does. */
    void clear() {
        pending.clear();
    }

    /** Whether the event has happened. */
    boolean hasFired() {
        return fired;
    }

    /** The number of actions waiting; for tests. */
    int pendingCount() {
        return pending.size();
    }
}
