package de.kortty.ai.mlx;

/** Actionable failure while starting or communicating with an embedded MLX runtime. */
public final class MlxRuntimeException extends RuntimeException {

    public MlxRuntimeException(String message) {
        super(message);
    }

    public MlxRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
