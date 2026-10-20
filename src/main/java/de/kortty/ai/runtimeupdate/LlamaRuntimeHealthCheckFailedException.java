package de.kortty.ai.runtimeupdate;

/**
 * A runtime health check that can say why a package is unhealthy throws this instead of returning
 * {@code false}. The installer treats it exactly like {@code false} — the package is removed and the
 * activation rolls back — but keeps the reason so the log and the AI Manager can show it.
 */
public final class LlamaRuntimeHealthCheckFailedException extends Exception {

    public LlamaRuntimeHealthCheckFailedException(String message) {
        super(message);
    }

    public LlamaRuntimeHealthCheckFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
