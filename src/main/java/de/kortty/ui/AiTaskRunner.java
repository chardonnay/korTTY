package de.kortty.ui;

import de.kortty.core.AiCancellation;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;

/**
 * Starts an AI {@link Task} on its own daemon thread with an {@link AiCancellation} handle bound to
 * it. {@code task.cancel()} then does more than interrupt: the handle closes the in-flight HTTP
 * stream or kills the CLI process tree, and every later AI call of the same run is refused — so a
 * Stop takes effect at once, and whatever the provider still returns is discarded (a cancelled
 * {@link Task} never reports success).
 */
final class AiTaskRunner {

    private AiTaskRunner() {
    }

    static Thread start(Task<?> task, String threadName) {
        AiCancellation.Handle handle = bindCancellation(task);
        Thread thread = new Thread(() -> AiCancellation.runBound(handle, task), threadName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** A handle that is cancelled as soon as {@code task} is. */
    static AiCancellation.Handle bindCancellation(Task<?> task) {
        AiCancellation.Handle handle = AiCancellation.newHandle();
        task.stateProperty().addListener((observable, previous, state) -> {
            if (state == Worker.State.CANCELLED) {
                handle.cancel();
            }
        });
        if (task.isCancelled()) {
            handle.cancel();
        }
        return handle;
    }
}
