package de.kortty.memory;

/** The file changed since it was read, for example after an external edit. Nothing was overwritten. */
public final class MemoryConflictException extends MemoryWriteException {

    public MemoryConflictException(MemoryScope scope) {
        super("The memory file for " + scope.key() + " changed on disk. Reload it before saving.");
    }
}
