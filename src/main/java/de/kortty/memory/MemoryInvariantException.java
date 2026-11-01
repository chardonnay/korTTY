package de.kortty.memory;

/** A korTTY write would change text the user wrote, or it has no consent from the user. */
public final class MemoryInvariantException extends MemoryWriteException {

    public MemoryInvariantException(String message) {
        super(message);
    }
}
