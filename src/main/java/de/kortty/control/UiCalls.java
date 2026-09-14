package de.kortty.control;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the per-operation budget to one {@link UiDispatcher} hop and converts a timeout into
 * {@link ControlErrorCode#TIMEOUT} with {@code data.stage:"ui"}.
 *
 * <p>This is what keeps a modal dialog the API did not open — QuickConnect, a password prompt, a
 * keyboard-interactive auth stage — from piling up parked worker threads: {@code Platform.runLater}
 * does not drain while a nested event loop is up, so every UI-touching verb degrades to a timeout and
 * releases its thread. The task itself is deliberately <strong>not</strong> cancelled; it is left to
 * finish harmlessly once the toolkit drains again.
 *
 * <p>Pure, any thread — but never the JavaFX application thread, because it blocks.
 */
public final class UiCalls {

    private static final Logger LOG = LoggerFactory.getLogger(UiCalls.class);

    private UiCalls() {
    }

    /**
     * Runs {@code task} on the UI thread and waits at most {@code budgetMillis} for its result.
     *
     * <p>A task that must fail with a {@link ControlApiException} throws it wrapped in an unchecked
     * exception (a {@link CompletionException} is the idiom); the original is unwrapped here and
     * rethrown unchanged, so a verb's precise error code survives the hop.
     *
     * @throws ControlApiException {@link ControlErrorCode#TIMEOUT} when the budget expires,
     *     {@link ControlErrorCode#UI_UNAVAILABLE} when there is no toolkit to dispatch to, whatever
     *     the task wrapped, or {@link ControlErrorCode#INTERNAL_ERROR} for anything else
     */
    public static <T> T await(UiDispatcher ui, long budgetMillis, Supplier<T> task)
            throws ControlApiException {
        if (ui == null) {
            throw new ControlApiException(ControlErrorCode.UI_UNAVAILABLE, "No user interface is available");
        }
        long budget = Math.max(1L, budgetMillis);
        CompletableFuture<T> future;
        try {
            future = ui.submit(task);
        } catch (IllegalStateException e) {
            throw new ControlApiException(ControlErrorCode.UI_UNAVAILABLE,
                "The korTTY user interface is not available", e);
        }
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ControlApiException(ControlErrorCode.TIMEOUT,
                "The user interface did not answer within " + budget + " ms",
                Map.of("stage", "ui", "budget_ms", budget));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlApiException(ControlErrorCode.TIMEOUT,
                "The request was interrupted while waiting for the user interface",
                Map.of("stage", "ui"));
        } catch (ExecutionException e) {
            throw translate(e.getCause());
        }
    }

    private static ControlApiException translate(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ControlApiException controlApiException) {
                return controlApiException;
            }
            if (current instanceof de.kortty.codingagent.CodingAgentActionException agentException) {
                return ControlApiException.from(agentException);
            }
            if (current instanceof IllegalStateException) {
                return new ControlApiException(ControlErrorCode.UI_UNAVAILABLE,
                    "The korTTY user interface is not available", current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        LOG.error("control-api UI hop failed", cause);
        return new ControlApiException(ControlErrorCode.INTERNAL_ERROR,
            "The user interface call failed; see the korTTY log", cause);
    }
}
