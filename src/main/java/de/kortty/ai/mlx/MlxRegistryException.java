package de.kortty.ai.mlx;

/** Actionable failure while reading or writing the local MLX model registry. */
public final class MlxRegistryException extends RuntimeException {

    public MlxRegistryException(String message) {
        super(message);
    }

    public MlxRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
