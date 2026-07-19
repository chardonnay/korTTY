package de.kortty.ai.llama;

/** Actionable failure while starting or communicating with an embedded llama.cpp runtime. */
public final class LlamaRuntimeException extends RuntimeException {

    public LlamaRuntimeException(String message) {
        super(message);
    }

    public LlamaRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
