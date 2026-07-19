package de.kortty.rag;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight cancellation contract shared by scanning, embedding and index replacement. */
@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("RAG operation cancelled");
        }
    }

    static Source source() {
        return new Source();
    }

    /** Mutable cancellation owner; callers pass {@link #token()} to background work. */
    final class Source {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public CancellationToken token() {
            return cancelled::get;
        }

        public void cancel() {
            cancelled.set(true);
        }
    }
}
