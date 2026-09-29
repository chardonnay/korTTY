package de.kortty.core;

import java.util.concurrent.CancellationException;

/**
 * The AI request was stopped by the user (see {@link AiCancellation}). It is a
 * {@link CancellationException}, so it is never mistaken for a provider failure: callers report it
 * as "stopped", never as an error, and never retry it.
 */
public final class AiCancelledException extends CancellationException {

    public AiCancelledException(String message, Throwable cause) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
