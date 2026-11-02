package de.kortty.memory;

import java.io.IOException;

/** Base of the refusals {@link MemoryStore#write} and its helpers raise. The file is left unchanged. */
public class MemoryWriteException extends IOException {

    public MemoryWriteException(String message) {
        super(message);
    }
}
