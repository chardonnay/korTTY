package de.kortty.memory;

/**
 * A write to the user's memory was refused because the enterprise policy makes it read-only or disabled.
 * The file is left unchanged.
 */
public final class MemoryReadOnlyException extends MemoryWriteException {

    public MemoryReadOnlyException(String message) {
        super(message);
    }
}
