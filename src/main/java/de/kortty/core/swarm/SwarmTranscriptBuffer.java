package de.kortty.core.swarm;

/**
 * Bounded transcript accumulator for one swarm agent. Keeps the tail of the live agent output for
 * the expandable detail view: once {@code capChars} is exceeded the front is trimmed back to
 * {@code trimToChars} (amortized, not per append) and snapshots carry a leading ellipsis line.
 * Appends may arrive from worker threads while snapshots are taken on the FX thread, hence the
 * synchronization.
 */
public final class SwarmTranscriptBuffer {

    private final int capChars;
    private final int trimToChars;
    private final StringBuilder buffer = new StringBuilder();
    private boolean trimmed;

    public SwarmTranscriptBuffer(int capChars, int trimToChars) {
        if (capChars <= 0 || trimToChars <= 0 || trimToChars > capChars) {
            throw new IllegalArgumentException("Require 0 < trimToChars <= capChars");
        }
        this.capChars = capChars;
        this.trimToChars = trimToChars;
    }

    /** Appends a chunk; returns {@code true} when a front-trim happened (view needs a full reset). */
    public synchronized boolean append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        buffer.append(chunk);
        if (buffer.length() > capChars) {
            buffer.delete(0, buffer.length() - trimToChars);
            trimmed = true;
            return true;
        }
        return false;
    }

    /** Current content; prefixed with an ellipsis line once the front has been trimmed. */
    public synchronized String snapshot() {
        return trimmed ? "…\n" + buffer : buffer.toString();
    }
}
