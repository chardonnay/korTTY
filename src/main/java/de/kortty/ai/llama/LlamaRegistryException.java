package de.kortty.ai.llama;

/** Indicates that the local llama model registry could not be read or written safely. */
public final class LlamaRegistryException extends RuntimeException {

    public LlamaRegistryException(String message) {
        super(message);
    }

    public LlamaRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
