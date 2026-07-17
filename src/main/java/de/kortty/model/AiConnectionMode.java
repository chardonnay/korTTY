package de.kortty.model;

/**
 * Selects how an AI profile connects to the configured provider.
 */
public enum AiConnectionMode {
    HTTP_API,
    LOCAL_CLI,
    EMBEDDED_LLAMA_CPP,
    /** Embedded mlx-lm sidecar for MLX safetensors models; Apple Silicon (macOS 14+) only. */
    EMBEDDED_MLX;

    /**
     * True for modes that run a bundled local model in-process (llama.cpp GGUF or MLX) and share
     * the {@code embeddedModelId} profile field instead of an API URL/key.
     */
    public boolean isEmbedded() {
        return this == EMBEDDED_LLAMA_CPP || this == EMBEDDED_MLX;
    }
}
