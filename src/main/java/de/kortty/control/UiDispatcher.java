package de.kortty.control;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Marshals work onto the JavaFX application thread. This is the <strong>only</strong> route from
 * {@code de.kortty.control} to the UI: no class in this package may call
 * {@code javafx.application.Platform} directly, so {@code Platform.runLater} appears exactly once, in
 * {@code de.kortty.ui.ControlApiUiBridge}.
 *
 * <p>Implementations are called from any thread. Every call site goes through
 * {@link UiCalls#await(UiDispatcher, long, Supplier)}, which applies the per-operation budget.
 */
public interface UiDispatcher {

    /**
     * Runs {@code task} on the UI thread.
     *
     * <p>The returned future is completed on the UI thread. The inline fast path — already on the UI
     * thread, so run now — is expected of real implementations. A task that must fail with a
     * {@link ControlApiException} throws it wrapped in an unchecked exception;
     * {@link UiCalls#await(UiDispatcher, long, Supplier)} unwraps it again.
     *
     * @throws IllegalStateException when there is no toolkit to dispatch to; {@code UiCalls} turns
     *     that into {@link ControlErrorCode#UI_UNAVAILABLE}
     */
    <T> CompletableFuture<T> submit(Supplier<T> task);

    /** Whether the calling thread is the UI thread. */
    boolean isUiThread();

    /**
     * Runs every task inline on the calling thread; what unit tests inject so they never start a
     * toolkit.
     */
    UiDispatcher DIRECT = new UiDispatcher() {

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public boolean isUiThread() {
            return true;
        }
    };
}
