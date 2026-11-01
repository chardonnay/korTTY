package de.kortty.memory;

/** A write exceeds a size limit: the hard file cap, the entry length or the soft cap of a korTTY append. */
public final class MemoryLimitException extends MemoryWriteException {

    public MemoryLimitException(String message) {
        super(message);
    }
}
