package de.kortty.ai.huggingface;

/** Distribution format of a Hugging Face model repository korTTY can run locally. */
public enum HuggingFaceModelFormat {
    /** Single-file GGUF weights served by the embedded llama.cpp runtime. */
    GGUF,
    /** Apple-MLX safetensors directory served by the embedded mlx-lm runtime (Apple Silicon). */
    MLX
}
